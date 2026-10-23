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
// dirty[] is exact for every published frame, and an empty rectangle means nothing changed.
// Without it a zero rectangle means "unknown" (the whole frame).
#define FLAG_DAMAGE 4u
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
    uint64_t frames, copy_ns, copy_max_ns, waits, retries, torn, partial, bytes, unchanged;
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

// Copies rows [y0, y1) and columns [x0, x1) of the guest buffer into the window buffer.
static void copy_rect(uint8_t *dst, size_t dstride, const uint8_t *src, size_t sstride, uint32_t x0, uint32_t y0,
                      uint32_t x1, uint32_t y1) {
    size_t row_bytes = (size_t)(x1 - x0) * 4;
    dst += (size_t)y0 * dstride + (size_t)x0 * 4;
    src += (size_t)y0 * sstride + (size_t)x0 * 4;
    if (dstride == sstride && row_bytes == sstride) {
        memcpy(dst, src, sstride * (y1 - y0));
        return;
    }
    for (uint32_t y = y0; y < y1; y++, dst += dstride, src += sstride) memcpy(dst, src, row_bytes);
}

// How long to sleep between looks at frame_seq: 250 us while frames are coming (well under a
// 120 Hz refresh), backing off to 2 ms after a second without one, so an idle guest costs
// almost nothing.
static long idle_nap(uint64_t since_frame_ns) {
    if (since_frame_ns < 100000000ull) return 250000;
    if (since_frame_ns < 1000000000ull) return 1000000;
    return 2000000;
}

// Returns 0 once stopped, or a negative reason:
//  -1 no window, -2 a header field points outside the region, -3 the buffer queue refused
//  to lock, -4 the buffer queue refused the guest's size/format, -5 a pixel format we don't take.
//
// Damage: a frame that directly follows the one shown last (frame_seq = last + 1) is copied
// only inside the guest's dirty rectangle; ANativeWindow_lock gets that rectangle, and the
// buffer queue copies the rest back from the previous buffer (and may widen the rectangle,
// which we then honour). Anything else (the first frame, a size change, skipped frames, a POLL
// refresh, a bad rectangle, a frame torn by the guest) is copied whole. With FLAG_DAMAGE an
// empty rectangle means the frame changed nothing, and it isn't copied at all.
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
    uint64_t last = 0, last_copy = 0, last_frame_ns = now_ns();
    uint32_t gw = 0, gh = 0, gfmt = 0;
    int full_next = 1, redo = 0;
    int rc = 0;
    while (!__atomic_load_n(&ctl->stop, __ATOMIC_ACQUIRE)) {
        if (h->magic != SCFB_MAGIC || !(h->flags & FLAG_READY)) {
            nap(2000000);
            continue;
        }
        uint64_t seq = __atomic_load_n(&h->frame_seq, __ATOMIC_ACQUIRE);
        uint64_t now = now_ns();
        // POLL: the guest draws without publishing; copy at ~60 Hz anyway.
        int poll_due = (h->flags & FLAG_POLL) && now - last_copy >= 16666666ull;
        if (seq == last && !poll_due && !redo) {
            nap(idle_nap(now - last_frame_ns));
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
            full_next = 1;
            LOGI("guest framebuffer %ux%u stride %u format 0x%x buffers %u", w, ht, stride, fmt, h->nbufs);
            (*env)->CallVoidMethod(env, sink, on_size, (jint)w, (jint)ht);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }
        // The seqlock of SCFB.md. Two buffers: the guest flips and won't draw into the one we
        // read. One buffer drawn in place: wait while the guest is drawing (host_reading stays
        // set meanwhile, so a guest drawing back to back lets us in after its current frame);
        // after 10 ms, take it anyway. Waiting before the lock lets the damage be read first.
        __atomic_store_n(&h->host_reading, seq ? seq : 1, __ATOMIC_RELEASE);
        int inplace = h->nbufs < 2;
        for (int tries = 0; inplace && tries < 40; tries++) {
            __atomic_thread_fence(__ATOMIC_SEQ_CST);
            if (!h->drawing) break;
            ctl->waits++;
            nap(250000);
        }
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        seq = __atomic_load_n(&h->frame_seq, __ATOMIC_ACQUIRE);
        uint32_t front = h->front & 1;
        uint32_t dx = h->dirty[0], dy = h->dirty[1], dw = h->dirty[2], dh = h->dirty[3];
        int follows = !full_next && seq == last + 1;
        if (follows && (h->flags & FLAG_DAMAGE) && (!dw || !dh)) {
            // A frame that changed nothing: the window already shows it.
            __atomic_store_n(&h->host_seq, seq, __ATOMIC_RELEASE);
            __atomic_store_n(&h->host_reading, 0, __ATOMIC_RELEASE);
            last = seq;
            last_frame_ns = now;
            ctl->unchanged++;
            continue;
        }
        int partial = follows && dw && dh && dx < gw && dy < gh && dw <= gw - dx && dh <= gh - dy;
        ARect r = {0, 0, (int32_t)gw, (int32_t)gh};
        if (partial) r = (ARect){(int32_t)dx, (int32_t)dy, (int32_t)(dx + dw), (int32_t)(dy + dh)};
        ANativeWindow_Buffer buf;
        if (ANativeWindow_lock(win, &buf, &r) != 0) {
            __atomic_store_n(&h->host_reading, 0, __ATOMIC_RELEASE);
            rc = -3;
            break;
        }
        uint32_t bw = gw < (uint32_t)buf.width ? gw : (uint32_t)buf.width;
        uint32_t bh = gh < (uint32_t)buf.height ? gh : (uint32_t)buf.height;
        // The queue says what it needs redrawn (at least what we asked for); clamp it.
        uint32_t x0 = r.left < 0 ? 0 : (uint32_t)r.left, y0 = r.top < 0 ? 0 : (uint32_t)r.top;
        uint32_t x1 = r.right < 0 ? 0 : (uint32_t)r.right, y1 = r.bottom < 0 ? 0 : (uint32_t)r.bottom;
        if (x1 > bw) x1 = bw;
        if (y1 > bh) y1 = bh;
        if (x0 > x1) x0 = x1;
        if (y0 > y1) y0 = y1;
        uint64_t off = h->buf_offset[front];
        int bad = off < sizeof(struct scfb_hdr) || off > size || (uint64_t)stride * gh > size - off;
        uint64_t t0 = now_ns();
        if (!bad) copy_rect(buf.bits, (size_t)buf.stride * 4, base + off, stride, x0, y0, x1, y1);
        uint64_t t1 = now_ns();
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        // The guest started another frame during the copy (it gave up waiting for us): what we
        // copied may mix two frames, and the next frame's damage won't cover the difference.
        int torn = inplace && (h->drawing || h->frame_seq != seq);
        __atomic_store_n(&h->host_seq, seq, __ATOMIC_RELEASE);
        __atomic_store_n(&h->host_reading, 0, __ATOMIC_RELEASE);
        ANativeWindow_unlockAndPost(win);
        if (bad) {
            rc = -2;
            break;
        }
        if (torn) ctl->torn++;
        // Copy it again, whole, as soon as the guest is done (even if it publishes nothing new).
        full_next = redo = torn;
        h->host_ns = t1;
        last_copy = t1;
        ctl->bytes += (uint64_t)(x1 - x0) * 4 * (y1 - y0);
        if (seq == last) continue;  // a POLL refresh, not a new frame
        if (partial) ctl->partial++;
        last = seq;
        last_frame_ns = t1;
        ctl->frames++;
        ctl->copy_ns += t1 - t0;
        if (t1 - t0 > ctl->copy_max_ns) ctl->copy_max_ns = t1 - t0;
    }
    ANativeWindow_release(win);
    return rc;
}
