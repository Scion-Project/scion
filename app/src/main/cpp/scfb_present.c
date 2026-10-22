// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Host side of the Scion shared framebuffer (SCFB, scion-avf display/SCFB.md) for the AVF
// display (avf/ScfbIo.kt): waits for the guest to publish a frame in the shared region and
// copies it into the display's Surface. The buffer queue takes the guest's own pixel layout
// (BGRA_8888 for XR24, what iOS draws; RGBX_8888 for XB24), so each row is a plain memcpy.
//
// Native only because the SDK's ImageWriter refuses HAL format 5 (BGRA_8888); see ScfbIo.
//
// The region is guest memory, so every header field is untrusted: sizes and offsets are
// checked against the region before anything is read.

#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

#define TAG "ScfbPresent"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define SCFB_MAGIC 0x42464353u
#define FOURCC_XB24 0x34324258u /* bytes R,G,B,X */
#define FOURCC_XR24 0x34325258u /* bytes B,G,R,X */
#define HAL_FORMAT_BGRA_8888 5
#define FLAG_READY 1u
#define FLAG_POLL 2u
#define MAX_DIM 8192u

struct scfb_hdr {
    uint32_t magic, version;
    uint32_t width, height;
    uint32_t stride, format;
    uint32_t buf_offset[2];
    uint32_t nbufs, flags;
    uint32_t front, drawing;
    uint64_t frame_seq;
    uint64_t input_seq;
    uint64_t guest_ns;
    uint32_t dirty[4];
    uint64_t host_seq;
    uint64_t host_reading;
    uint64_t host_ns;
};

// The control block Kotlin shares with the loop (a direct ByteBuffer), little-endian.
struct control {
    uint32_t stop, pad;
    uint64_t frames, copy_ns, copy_max_ns, waits, retries, torn;
};

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static void nap(long ns) {
    struct timespec ts = {0, ns};
    nanosleep(&ts, NULL);
}

static void copy_rows(uint8_t *dst, size_t dstride, const uint8_t *src, size_t sstride, size_t row_bytes,
                      uint32_t rows) {
    if (dstride == sstride && row_bytes == sstride) {
        memcpy(dst, src, sstride * rows);
        return;
    }
    for (uint32_t y = 0; y < rows; y++) memcpy(dst + y * dstride, src + y * sstride, row_bytes);
}

// Returns 0 once stopped, or a negative reason:
//  -1 no window, -2 a header field points outside the region, -3 the buffer queue refused
//  to lock, -4 the buffer queue refused the guest's size/format, -5 a pixel format we don't take.
JNIEXPORT jint JNICALL Java_io_github_dylanneve1_iosonpixel_avf_ScfbNative_presentLoop(
    JNIEnv *env, jclass cls, jobject surface, jobject region, jobject control, jobject sink) {
    (void)cls;
    uint8_t *base = (*env)->GetDirectBufferAddress(env, region);
    uint64_t size = (uint64_t)(*env)->GetDirectBufferCapacity(env, region);
    struct control *ctl = (*env)->GetDirectBufferAddress(env, control);
    if (!base || !ctl || size < 4096 || (uint64_t)(*env)->GetDirectBufferCapacity(env, control) < sizeof *ctl) return -1;
    jmethodID on_size = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, sink), "onSize", "(II)V");
    if (!on_size) return -1;
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (!win) return -1;

    volatile struct scfb_hdr *h = (volatile struct scfb_hdr *)base;
    uint64_t last = 0, last_copy = 0;
    uint32_t gw = 0, gh = 0, gfmt = 0;
    int rc = 0;
    while (!__atomic_load_n(&ctl->stop, __ATOMIC_ACQUIRE)) {
        if (h->magic != SCFB_MAGIC || !(h->flags & FLAG_READY)) {
            nap(2000000);
            continue;
        }
        uint64_t seq = __atomic_load_n(&h->frame_seq, __ATOMIC_ACQUIRE);
        // POLL: the guest draws without publishing; copy at ~60 Hz anyway.
        int poll_due = (h->flags & FLAG_POLL) && now_ns() - last_copy >= 16666666ull;
        if (seq == last && !poll_due) {
            nap(250000);
            continue;
        }
        uint32_t w = h->width, ht = h->height, stride = h->stride, fmt = h->format;
        if (!w || !ht || w > MAX_DIM || ht > MAX_DIM || stride < w * 4u) {
            rc = -2;
            break;
        }
        if (w != gw || ht != gh || fmt != gfmt) {
            int hal;
            if (fmt == FOURCC_XR24) hal = HAL_FORMAT_BGRA_8888;
            else if (fmt == FOURCC_XB24) hal = AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
            else {
                rc = -5;
                break;
            }
            if (ANativeWindow_setBuffersGeometry(win, (int32_t)w, (int32_t)ht, hal) != 0) {
                rc = -4;
                break;
            }
            gw = w, gh = ht, gfmt = fmt;
            LOGI("guest framebuffer %ux%u stride %u format 0x%x buffers %u", w, ht, stride, fmt, h->nbufs);
            (*env)->CallVoidMethod(env, sink, on_size, (jint)w, (jint)ht);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }
        ANativeWindow_Buffer buf;
        if (ANativeWindow_lock(win, &buf, NULL) != 0) {
            rc = -3;
            break;
        }
        uint32_t cw = gw < (uint32_t)buf.width ? gw : (uint32_t)buf.width;
        uint32_t ch = gh < (uint32_t)buf.height ? gh : (uint32_t)buf.height;
        uint64_t t0 = 0;
        int bad = 0;
        // The seqlock of SCFB.md. Two buffers: the guest flips and won't draw into the one we
        // read. One buffer drawn in place: wait while the guest is drawing (host_reading stays
        // set meanwhile, so a guest drawing back to back lets us in after its current frame),
        // copy, and copy again if it started during the copy; after 10 ms, show it anyway.
        __atomic_store_n(&h->host_reading, seq ? seq : 1, __ATOMIC_RELEASE);
        for (int tries = 0;; tries++) {
            __atomic_thread_fence(__ATOMIC_SEQ_CST);
            int inplace = h->nbufs < 2;
            if (inplace && h->drawing && tries < 40) {
                ctl->waits++;
                nap(250000);
                continue;
            }
            seq = __atomic_load_n(&h->frame_seq, __ATOMIC_ACQUIRE);
            uint64_t off = h->buf_offset[h->front & 1];
            if (off < sizeof(struct scfb_hdr) || off > size || (uint64_t)stride * gh > size - off) {
                bad = 1;
                break;
            }
            t0 = now_ns();
            copy_rows(buf.bits, (size_t)buf.stride * 4, base + off, stride, (size_t)cw * 4, ch);
            __atomic_thread_fence(__ATOMIC_SEQ_CST);
            if (!inplace || (!h->drawing && h->frame_seq == seq)) break;
            if (tries >= 40) {
                ctl->torn++;
                break;
            }
            ctl->retries++;
        }
        uint64_t t1 = now_ns();
        __atomic_store_n(&h->host_seq, seq, __ATOMIC_RELEASE);
        __atomic_store_n(&h->host_reading, 0, __ATOMIC_RELEASE);
        ANativeWindow_unlockAndPost(win);
        if (bad) {
            rc = -2;
            break;
        }
        h->host_ns = t1;
        last_copy = t1;
        if (seq == last) continue;  // a POLL refresh, not a new frame
        last = seq;
        ctl->frames++;
        ctl->copy_ns += t1 - t0;
        if (t1 - t0 > ctl->copy_max_ns) ctl->copy_max_ns = t1 - t0;
    }
    ANativeWindow_release(win);
    return rc;
}
