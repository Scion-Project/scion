// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Host side of the Scion camera region (SCCAM, hackios-xnu-kernel avf/CAMERA.md) for the AVF
// camera (avf/SccamCamera.kt): one Camera2 frame (YUV_420_888) into a slot of the shared region as
// NV12 (or NV21, when that is how the camera's planes lie), published like a seqlock: the slot's
// seq odd while it is written, then twice the frame's number, then latest and frame_seq.
//
// The region is guest memory: the guest may write anything into it, so the layout (header size,
// slot size and count) comes from the app, never from the header, and the only header fields read
// here (latest, guest_reading) are used only to pick a slot.

#include <jni.h>
#include <stdint.h>
#include <string.h>

#define SCCAM_MAGIC 0x4d434353u
#define FOURCC_NV12 0x3231564eu
#define FOURCC_NV21 0x3132564eu
#define OFF_FRAME_SEQ 0x30
#define OFF_LATEST 0x38
#define OFF_GUEST_READING 0x58
#define OFF_SLOTS 0x100
#define SLOT_DESC 64

struct slot {
    uint64_t seq, time_ns;
    uint32_t offset, bytes, width, height, format, stride_y, stride_uv, uv_offset, facing, orientation;
    uint32_t reserved[2];
};

// Returns the slot written (0..nslots-1), or: -1 bad arguments, -2 not a region of ours, -3 a plane
// smaller than its strides say, -4 too big for a slot.
JNIEXPORT jint JNICALL Java_io_github_dylanneve1_iosonpixel_avf_SccamNative_publish(
    JNIEnv *env, jclass cls, jobject region, jint header_bytes, jint slot_bytes, jint nslots, jlong frame,
    jobject ybuf, jint y_stride, jobject ubuf, jobject vbuf, jint uv_stride, jint uv_pixel_stride,
    jint width, jint height, jint facing, jint orientation, jlong time_ns) {
    (void)cls;
    uint8_t *base = (*env)->GetDirectBufferAddress(env, region);
    jlong cap = (*env)->GetDirectBufferCapacity(env, region);
    const uint8_t *y = (*env)->GetDirectBufferAddress(env, ybuf);
    const uint8_t *u = (*env)->GetDirectBufferAddress(env, ubuf);
    const uint8_t *v = (*env)->GetDirectBufferAddress(env, vbuf);
    jlong ycap = (*env)->GetDirectBufferCapacity(env, ybuf);
    jlong ucap = (*env)->GetDirectBufferCapacity(env, ubuf);
    jlong vcap = (*env)->GetDirectBufferCapacity(env, vbuf);

    if (!base || !y || !u || !v || width < 2 || height < 2 || (width & 1) || (height & 1) || nslots < 2 || nslots > 4 ||
        header_bytes < OFF_SLOTS + SLOT_DESC * nslots || slot_bytes <= 0 || y_stride < width || uv_stride <= 0 ||
        (uv_pixel_stride != 1 && uv_pixel_stride != 2) || frame <= 0)
        return -1;
    if (cap < (jlong)header_bytes + (jlong)slot_bytes * nslots || *(volatile uint32_t *)base != SCCAM_MAGIC)
        return -2;
    uint32_t w = (uint32_t)width, h = (uint32_t)height, ch = h / 2, cw = w / 2;
    uint64_t bytes = (uint64_t)w * h * 3 / 2;
    if (bytes > (uint64_t)slot_bytes)
        return -4;
    if ((uint64_t)ycap < (uint64_t)y_stride * (h - 1) + w)
        return -3;
    // (a semi-planar plane's last row ends at its last sample: one short of the stride's width)
    uint64_t uv_need = (uint64_t)uv_stride * (ch - 1) + (uint64_t)(cw - 1) * (uint32_t)uv_pixel_stride + 1;
    if ((uint64_t)ucap < uv_need || (uint64_t)vcap < uv_need)
        return -3;

    uint32_t latest = __atomic_load_n((uint32_t *)(base + OFF_LATEST), __ATOMIC_RELAXED);
    uint32_t reading = __atomic_load_n((uint32_t *)(base + OFF_GUEST_READING), __ATOMIC_ACQUIRE);
    int s = 0;
    while (s < nslots && ((uint32_t)s == latest || (uint32_t)s + 1 == reading))
        s++;
    if (s == nslots)
        s = 0;
    struct slot *d = (struct slot *)(base + OFF_SLOTS + SLOT_DESC * s);
    uint32_t off = (uint32_t)header_bytes + (uint32_t)slot_bytes * (uint32_t)s;
    uint8_t *out = base + off, *uvout = out + (size_t)w * h;

    __atomic_store_n(&d->seq, 2 * (uint64_t)frame - 1, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    for (uint32_t r = 0; r < h; r++)
        memcpy(out + (size_t)r * w, y + (size_t)r * (uint32_t)y_stride, w);
    uint32_t format = FOURCC_NV12;
    if (uv_pixel_stride == 2 && (v == u + 1 || u == v + 1)) {
        // semi-planar already: rows of interleaved chroma, NV12 (U first) or NV21 (V first)
        const uint8_t *first = v == u + 1 ? u : v, *second = v == u + 1 ? v : u;
        jlong first_cap = v == u + 1 ? ucap : vcap, second_cap = v == u + 1 ? vcap : ucap;
        format = v == u + 1 ? FOURCC_NV12 : FOURCC_NV21;
        for (uint32_t r = 0; r < ch; r++) {
            uint64_t at = (uint64_t)r * (uint32_t)uv_stride;
            uint64_t n = (uint64_t)first_cap - at < w ? (uint64_t)first_cap - at : w;
            memcpy(uvout + (size_t)r * w, first + at, n);
            if (n < w && (uint64_t)second_cap > at + w - 2)
                uvout[(size_t)r * w + w - 1] = second[at + w - 2];
        }
    } else {
        for (uint32_t r = 0; r < ch; r++) {
            const uint8_t *ur = u + (size_t)r * (uint32_t)uv_stride, *vr = v + (size_t)r * (uint32_t)uv_stride;
            uint8_t *o = uvout + (size_t)r * w;
            for (uint32_t i = 0; i < cw; i++) {
                o[2 * i] = ur[(size_t)i * (uint32_t)uv_pixel_stride];
                o[2 * i + 1] = vr[(size_t)i * (uint32_t)uv_pixel_stride];
            }
        }
    }
    d->time_ns = (uint64_t)time_ns;
    d->offset = off;
    d->bytes = (uint32_t)bytes;
    d->width = w;
    d->height = h;
    d->format = format;
    d->stride_y = w;
    d->stride_uv = w;
    d->uv_offset = w * h;
    d->facing = (uint32_t)facing;
    d->orientation = (uint32_t)orientation;
    __atomic_thread_fence(__ATOMIC_RELEASE);
    __atomic_store_n(&d->seq, 2 * (uint64_t)frame, __ATOMIC_RELAXED);
    __atomic_store_n((uint32_t *)(base + OFF_LATEST), (uint32_t)s, __ATOMIC_RELEASE);
    __atomic_store_n((uint64_t *)(base + OFF_FRAME_SEQ), (uint64_t)frame, __ATOMIC_RELEASE);
    return s;
}
