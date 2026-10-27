/*
 * SCSND, the app's side: iOS's sound, played through AAudio. The guest's audio server writes what it
 * has mixed for its speaker to /dev/hakios-audio, and the kernel's sink puts it into a ring in a region
 * of guest RAM the app lends, as it lends SCFB's, SCCAM's and SCGPU's (AVF's own virtio-snd failed to
 * start two times in three on the Pixel and took the VM with it).
 *
 * The region and its protocol: hackios-xnu-kernel xnu/files/bsd/sys/hakios.scsnd.h (copied below).
 * A thread watches the ring: when samples come it opens an AAudio stream, whose callback takes them
 * out (at most a little behind: if more has built up, it skips ahead), scaled by iOS's volume; after
 * two seconds without any it closes the stream again, so a silent iOS keeps no audio path awake.
 */
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <errno.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

#define TAG "scsnd"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define ERR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---- hakios.scsnd.h ---------------------------------------------------------------------- */

#define SCSND_MAGIC     0x4e534353u
#define SCSND_VERSION   1u

struct scsnd_header {
	uint32_t magic, version;
	uint32_t ring_off, ring_bytes;
	uint32_t rate, channels;
	uint32_t head;
	uint32_t tail;
	uint32_t below, muted;
	uint32_t guest_ready;
	uint32_t dropped;
	uint32_t skipped;
	uint32_t underruns;
	uint32_t reserved[18];
};

_Static_assert(sizeof(struct scsnd_header) == 128, "scsnd_header");

/* ---- the player ------------------------------------------------------------------------------- */

#define RATE            48000
#define CHANNELS        2
#define FRAME           4                       /* bytes: 16-bit, left then right */
#define RING_OFF        4096u
#define RING_BYTES      (256u << 10)            /* 1.4 s; the callback keeps it near empty */
#define BEHIND_MAX_MS   120u                    /* more than this waiting: skip to... */
#define BEHIND_TO_MS    40u                     /* ... this */
#define IDLE_NS         2000000000ull           /* no samples this long: the stream is closed */

static struct {
	uint8_t *base;
	uint64_t size;
	volatile struct scsnd_header *h;
	uint8_t *ring;
	pthread_t thread;
	int running;
	volatile int stop;
	AAudioStream *stream;
	volatile int broken;                    /* the stream's error callback: closed and opened again on the next sound */
	volatile uint64_t last_ns;              /* when the callback last had samples */
	uint32_t vol_below, vol_muted;          /* the volume the gain is for */
	int32_t gain;                           /* Q15 */
	uint64_t played, starts;
	uint64_t run_played, run_underruns, run_start_ns;
} P;

static uint64_t
now_ns(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t)ts.tv_sec * 1000000000u + (uint64_t)ts.tv_nsec;
}

/* iOS's volume (eighths of a dB under the loudest, or muted) as a gain. */
static void
volume(void)
{
	uint32_t below = P.h->below, muted = P.h->muted;
	if (below == P.vol_below && muted == P.vol_muted && P.gain >= 0) {
		return;
	}
	P.vol_below = below;
	P.vol_muted = muted;
	P.gain = muted ? 0 : (int32_t)lrintf(32768.0f * powf(10.0f, -(float)below / 8.0f / 20.0f));
	if (P.gain > 32768) {
		P.gain = 32768;
	}
}

static aaudio_data_callback_result_t
take(AAudioStream *stream, void *user, void *audio, int32_t frames)
{
	volatile struct scsnd_header *h = P.h;
	uint32_t head = __atomic_load_n((uint32_t *)(uintptr_t)&h->head, __ATOMIC_ACQUIRE), tail = h->tail;
	uint32_t have = head - tail, want = (uint32_t)frames * FRAME;
	int16_t *out = audio;

	(void)stream;
	(void)user;
	if (have > RING_BYTES) {
		tail = head;                    /* (not a ring any more: start over at its head) */
		have = 0;
	}
	if (have > RATE * FRAME / 1000 * BEHIND_MAX_MS) {
		uint32_t skip = (have - RATE * FRAME / 1000 * BEHIND_TO_MS) & ~(FRAME - 1u);
		tail += skip;
		have -= skip;
		h->skipped += skip / FRAME;
	}
	uint32_t n = have < want ? have : want;
	uint32_t at = tail & (RING_BYTES - 1), first = n < RING_BYTES - at ? n : RING_BYTES - at;
	memcpy(out, P.ring + at, first);
	memcpy((uint8_t *)out + first, P.ring, n - first);
	memset((uint8_t *)out + n, 0, want - n);
	__atomic_store_n((uint32_t *)(uintptr_t)&h->tail, tail + n, __ATOMIC_RELEASE);
	volume();
	if (P.gain != 32768) {
		for (uint32_t i = 0; i < n / 2; i++) {
			out[i] = (int16_t)(((int32_t)out[i] * P.gain) >> 15);
		}
	}
	if (n > 0) {
		P.last_ns = now_ns();
		P.played += n / FRAME;
		P.run_played += n / FRAME;
	}
	if (n < want && n > 0) {
		h->underruns++;
		P.run_underruns++;
	}
	return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void
stream_error(AAudioStream *stream, void *user, aaudio_result_t error)
{
	(void)stream;
	(void)user;
	ERR("SCSND: the stream: %s", AAudio_convertResultToText(error));
	P.broken = 1;           /* (it can't be closed from here: the thread does) */
}

static int
open_stream(void)
{
	AAudioStreamBuilder *b;
	aaudio_result_t rc = AAudio_createStreamBuilder(&b);

	if (rc != AAUDIO_OK) {
		ERR("SCSND: no stream builder: %s", AAudio_convertResultToText(rc));
		return -1;
	}
	AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
	AAudioStreamBuilder_setSampleRate(b, RATE);
	AAudioStreamBuilder_setChannelCount(b, CHANNELS);
	AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
	AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
	AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MOVIE);
	AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);  /* (iOS buffers already; video stays in step) */
	AAudioStreamBuilder_setDataCallback(b, take, NULL);
	AAudioStreamBuilder_setErrorCallback(b, stream_error, NULL);
	rc = AAudioStreamBuilder_openStream(b, &P.stream);
	AAudioStreamBuilder_delete(b);
	if (rc != AAUDIO_OK) {
		ERR("SCSND: no stream: %s", AAudio_convertResultToText(rc));
		P.stream = NULL;
		return -1;
	}
	P.broken = 0;
	P.last_ns = now_ns();
	P.run_played = P.run_underruns = 0;
	P.run_start_ns = P.last_ns;
	rc = AAudioStream_requestStart(P.stream);
	if (rc != AAUDIO_OK) {
		ERR("SCSND: the stream didn't start: %s", AAudio_convertResultToText(rc));
		AAudioStream_close(P.stream);
		P.stream = NULL;
		return -1;
	}
	P.starts++;
	LOG("SCSND: playing: %d Hz, %d frames a burst, %d buffered", AAudioStream_getSampleRate(P.stream),
	    AAudioStream_getFramesPerBurst(P.stream), AAudioStream_getBufferSizeInFrames(P.stream));
	return 0;
}

static void
close_stream(const char *why)
{
	if (P.stream == NULL) {
		return;
	}
	AAudioStream_requestStop(P.stream);
	AAudioStream_close(P.stream);
	P.stream = NULL;
	LOG("SCSND: stopped (%s) after %.1f s: %.1f s played, %llu underruns; in all %u frames skipped, %u dropped", why,
	    (now_ns() - P.run_start_ns) / 1e9, P.run_played / (double)RATE, (unsigned long long)P.run_underruns,
	    P.h->skipped, P.h->dropped);
}

static void *
run(void *arg)
{
	uint64_t retry_at = 0;

	(void)arg;
	while (!P.stop) {
		uint64_t now = now_ns();
		uint32_t head = __atomic_load_n((uint32_t *)(uintptr_t)&P.h->head, __ATOMIC_ACQUIRE);
		if (P.stream != NULL && P.broken) {
			close_stream("the stream broke");
		} else if (P.stream != NULL && now - P.last_ns > IDLE_NS) {
			close_stream("silence");
		} else if (P.stream == NULL && head != P.h->tail && now >= retry_at) {
			if (open_stream() != 0) {
				retry_at = now + 1000000000ull;
			}
		}
		struct timespec nap = { 0, 10000000 };
		nanosleep(&nap, NULL);
	}
	close_stream("the VM stopped");
	return NULL;
}

/* ---- JNI ---------------------------------------------------------------------------------- */

/* The header written and the player's thread up: before the VM starts. 0, or a negative reason. */
JNIEXPORT jint JNICALL
Java_io_github_dylanneve1_iosonpixel_avf_ScsndNative_start(JNIEnv *env, jclass cls, jobject region)
{
	(void)cls;
	if (P.running) {
		return -16;
	}
	P.base = (*env)->GetDirectBufferAddress(env, region);
	P.size = (uint64_t)(*env)->GetDirectBufferCapacity(env, region);
	if (P.base == NULL || P.size < RING_OFF + RING_BYTES) {
		return -3;
	}
	memset(P.base, 0, RING_OFF);
	P.h = (volatile struct scsnd_header *)P.base;
	P.ring = P.base + RING_OFF;
	P.h->ring_off = RING_OFF;
	P.h->ring_bytes = RING_BYTES;
	P.h->rate = RATE;
	P.h->channels = CHANNELS;
	P.h->version = SCSND_VERSION;
	__atomic_store_n((uint32_t *)(uintptr_t)&P.h->magic, SCSND_MAGIC, __ATOMIC_RELEASE);
	P.gain = -1;
	P.played = P.starts = 0;
	P.stop = 0;
	P.broken = 0;
	P.stream = NULL;
	if (pthread_create(&P.thread, NULL, run, NULL) != 0) {
		return -5;
	}
	pthread_setname_np(P.thread, "scsnd");
	P.running = 1;
	LOG("SCSND: a ring of %u ms in a %llu KiB region", RING_BYTES / FRAME * 1000 / RATE, (unsigned long long)(P.size >> 10));
	return 0;
}

JNIEXPORT void JNICALL
Java_io_github_dylanneve1_iosonpixel_avf_ScsndNative_stop(JNIEnv *env, jclass cls)
{
	(void)env;
	(void)cls;
	if (!P.running) {
		return;
	}
	P.stop = 1;
	pthread_join(P.thread, NULL);
	P.running = 0;
}

/* Frames played, streams started, frames skipped, frames the guest dropped, underruns, whether the guest's sink is up. */
JNIEXPORT jlongArray JNICALL
Java_io_github_dylanneve1_iosonpixel_avf_ScsndNative_stats(JNIEnv *env, jclass cls)
{
	(void)cls;
	jlong v[6] = { 0 };
	if (P.h != NULL) {
		v[0] = (jlong)P.played;
		v[1] = (jlong)P.starts;
		v[2] = P.h->skipped;
		v[3] = P.h->dropped;
		v[4] = P.h->underruns;
		v[5] = P.h->guest_ready;
	}
	jlongArray a = (*env)->NewLongArray(env, 6);
	if (a != NULL) {
		(*env)->SetLongArrayRegion(env, a, 0, 6, v);
	}
	return a;
}
