/*
 * aaudio_tone: Scion audio milestone 3, step 1 probe.
 * Opens an AAudio output stream the way the planned QEMU driver will
 * (S16 stereo 48 kHz, low latency, USAGE_GAME, data callback), plays a
 * 440 Hz tone for a few seconds and prints every API result code plus the
 * number of frames the callback produced. Run it as a plain exec'd native
 * process to check that AAudio works outside an app's Java process.
 *
 * Build: aarch64-linux-android31-clang -O2 -o aaudio_tone aaudio_tone.c -laaudio -lm
 * Usage: aaudio_tone [seconds]
 */
#include <aaudio/AAudio.h>
#include <math.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

static atomic_long frames_cb;
static atomic_int cb_calls;
static atomic_int err_code;
static double phase;

static aaudio_data_callback_result_t data_cb(AAudioStream *s, void *user,
                                             void *data, int32_t n)
{
    int16_t *out = data;
    int32_t ch = AAudioStream_getChannelCount(s);
    double rate = AAudioStream_getSampleRate(s);
    for (int32_t i = 0; i < n; i++) {
        int16_t v = (int16_t)(sin(phase) * 8000);
        phase += 2 * M_PI * 440.0 / rate;
        if (phase > 2 * M_PI) {
            phase -= 2 * M_PI;
        }
        for (int32_t c = 0; c < ch; c++) {
            *out++ = v;
        }
    }
    atomic_fetch_add(&frames_cb, n);
    atomic_fetch_add(&cb_calls, 1);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void error_cb(AAudioStream *s, void *user, aaudio_result_t e)
{
    atomic_store(&err_code, e);
}

#define CHECK(what, r) \
    printf("%-40s %d (%s)\n", what, (int)(r), AAudio_convertResultToText(r))

int main(int argc, char **argv)
{
    int secs = argc > 1 ? atoi(argv[1]) : 3;
    AAudioStreamBuilder *b = NULL;
    AAudioStream *s = NULL;
    aaudio_result_t r;

    printf("uid=%d pid=%d\n", (int)getuid(), (int)getpid());
    r = AAudio_createStreamBuilder(&b);
    CHECK("AAudio_createStreamBuilder", r);
    if (r != AAUDIO_OK) {
        return 1;
    }
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, 48000);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(b, data_cb, NULL);
    AAudioStreamBuilder_setErrorCallback(b, error_cb, NULL);

    r = AAudioStreamBuilder_openStream(b, &s);
    CHECK("AAudioStreamBuilder_openStream", r);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        return 2;
    }
    printf("stream: rate=%d ch=%d fmt=%d perf=%d share=%d burst=%d bufcap=%d\n",
           AAudioStream_getSampleRate(s), AAudioStream_getChannelCount(s),
           AAudioStream_getFormat(s), AAudioStream_getPerformanceMode(s),
           AAudioStream_getSharingMode(s), AAudioStream_getFramesPerBurst(s),
           AAudioStream_getBufferCapacityInFrames(s));

    r = AAudioStream_requestStart(s);
    CHECK("AAudioStream_requestStart", r);
    aaudio_stream_state_t next = AAUDIO_STREAM_STATE_UNINITIALIZED;
    r = AAudioStream_waitForStateChange(s, AAUDIO_STREAM_STATE_STARTING,
                                        &next, 2000000000LL);
    CHECK("waitForStateChange(STARTING)", r);
    printf("state after start: %s\n", AAudio_convertStreamStateToText(next));

    for (int i = 0; i < secs; i++) {
        sleep(1);
        printf("t=%ds frames_cb=%ld calls=%d framesWritten=%lld framesRead=%lld "
               "xruns=%d state=%s err=%d\n",
               i + 1, (long)atomic_load(&frames_cb), atomic_load(&cb_calls),
               (long long)AAudioStream_getFramesWritten(s),
               (long long)AAudioStream_getFramesRead(s),
               AAudioStream_getXRunCount(s),
               AAudio_convertStreamStateToText(AAudioStream_getState(s)),
               atomic_load(&err_code));
    }
    r = AAudioStream_requestStop(s);
    CHECK("AAudioStream_requestStop", r);
    r = AAudioStream_close(s);
    CHECK("AAudioStream_close", r);
    long f = atomic_load(&frames_cb);
    printf("RESULT %s frames=%ld\n", f > 0 ? "OK" : "NO_FRAMES", f);
    return f > 0 ? 0 : 3;
}
