/*
 * The VM's CPUs at the clock speed its frames need. crosvm's vCPU threads work in short bursts (a
 * frame of CoreAnimation's: a few milliseconds every 16), and Android's governor keeps the cores they
 * run on at a quarter of their speed for that: measured while swiping on the Pixel 10, the prime
 * core at 0.9 of its 3.8 GHz. So every frame of iOS took four times the CPU time it needs.
 *
 * The cure is Android's own, the one games use: a performance hint session (ADPF), told how long the
 * work takes against how long it may, which raises the threads' clamps and so the cores' clocks.
 * The session's threads may be any of this app's uid, and crosvm runs with it -- but SELinux keeps
 * its /proc from the app, so its thread IDs can't be read. They are found instead: IDs are handed
 * out in order, so the VM's threads lie between the newest of ours before the VM starts and the
 * newest once the guest is talking to SCGPU, and the hint service (which can see /proc) says of each
 * whether it is ours. All of crosvm's threads end up in the session, and a few of ours: no harm.
 *
 * While the guest draws (SCGPU sees its commands) the session is told every 16 ms that the work took
 * debug.scion.boost times as long as it may (default 2; read again every second), which brings the
 * clocks up; when it stops, nothing is said, and the session's boost lapses by itself.
 * debug.scion.boost=0: none of this.
 */
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#define TAG "scgpu"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define TARGET_NS       16666667        /* a frame at 60 a second */
#define PROBE_MAX       1500            /* thread IDs looked at, at most */
#define MAX_TIDS        96

uint64_t scgpu_activity(void);          /* scgpu.c: commands and stream records so far */
int scgpu_device_tid(void);             /* scgpu.c: the device thread (gfxstream's decoding) */

typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;

static struct {
	APerformanceHintManager *(*get_manager)(void);
	APerformanceHintSession *(*create)(APerformanceHintManager *, const int32_t *, size_t, int64_t);
	int (*report)(APerformanceHintSession *, int64_t);
	void (*close)(APerformanceHintSession *);
	int (*increase)(APerformanceHintSession *, bool, bool, const char *);   /* (Android 16) */
} H;

static struct {
	pthread_t thread;
	volatile int stop;
	int created;
	int floor;                      /* the newest thread ID before the VM */
} B;

static uint64_t
now_ns(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t)ts.tv_sec * 1000000000u + (uint64_t)ts.tv_nsec;
}

static void *
tid_of(void *arg)
{
	*(int *)arg = gettid();
	return NULL;
}

/* The newest thread ID there is: a thread's own, made for the purpose. */
static int
newest_tid(void)
{
	pthread_t t;
	int tid = 0;
	if (pthread_create(&t, NULL, tid_of, &tid) == 0) {
		pthread_join(t, NULL);
	}
	return tid;
}

static bool
load(void)
{
	void *lib = dlopen("libandroid.so", RTLD_NOW);
	if (lib == NULL) {
		return false;
	}
	H.get_manager = (APerformanceHintManager * (*)(void))dlsym(lib, "APerformanceHint_getManager");
	H.create = (APerformanceHintSession * (*)(APerformanceHintManager *, const int32_t *, size_t, int64_t))
	    dlsym(lib, "APerformanceHint_createSession");
	H.report = (int (*)(APerformanceHintSession *, int64_t))dlsym(lib, "APerformanceHint_reportActualWorkDuration");
	H.close = (void (*)(APerformanceHintSession *))dlsym(lib, "APerformanceHint_closeSession");
	H.increase = (int (*)(APerformanceHintSession *, bool, bool, const char *))
	    dlsym(lib, "APerformanceHint_notifyWorkloadIncrease");
	return H.get_manager && H.create && H.report && H.close;
}

/* debug.scion.boost: how many times the target the work is said to take (0: no boost) */
static int
factor(void)
{
	char v[PROP_VALUE_MAX] = "";
	if (__system_property_get("debug.scion.boost", v) <= 0) {
		return 2;
	}
	int f = atoi(v);
	return f < 0 ? 0 : f > 50 ? 50 : f;
}

static void
nap_ms(int ms)
{
	struct timespec ts = { ms / 1000, (long)(ms % 1000) * 1000000L };
	nanosleep(&ts, NULL);
}

static void *
run(void *arg)
{
	(void)arg;
	APerformanceHintManager *m = H.get_manager();
	APerformanceHintSession *s = NULL;
	int32_t tids[MAX_TIDS];
	size_t n = 0;

	/* until the guest talks to the GPU: crosvm and its vCPUs are there by then */
	while (!B.stop && scgpu_activity() == 0) {
		nap_ms(200);
	}
	nap_ms(500);
	if (B.stop || m == NULL) {
		goto out;
	}
	int ceiling = newest_tid();
	int from = B.floor + 1, to = ceiling - from > PROBE_MAX ? from + PROBE_MAX : ceiling;
	uint64_t t0 = now_ns();
	for (int tid = from; tid < to && n < MAX_TIDS && !B.stop; tid++) {
		int32_t one = tid;
		APerformanceHintSession *p = H.create(m, &one, 1, TARGET_NS);
		if (p != NULL) {
			H.close(p);
			tids[n++] = tid;
		}
	}
	int dev = scgpu_device_tid();
	if (dev > 0 && n < MAX_TIDS) {
		tids[n++] = dev;
	}
	s = n ? H.create(m, tids, n, TARGET_NS) : NULL;
	LOG("SCGPU: the VM's CPUs: %s (%zu threads of this uid among IDs %d-%d, %.0f ms to find)",
	    s ? "a performance hint session asks for their clocks while the guest draws" : "NO hint session", n, from, to,
	    (now_ns() - t0) / 1e6);
	if (s == NULL) {
		goto out;
	}
	uint64_t seen = scgpu_activity(), quiet_since = 0, boosts = 0, ticks = 0;
	bool active = false;
	int f = factor();
	while (!B.stop) {
		nap_ms(16);
		if ((++ticks & 63) == 0) {
			f = factor();
		}
		uint64_t a = scgpu_activity(), now = now_ns();
		if (a != seen) {
			seen = a;
			quiet_since = 0;
			if (!active && H.increase) {
				H.increase(s, true, false, "the guest draws");
			}
			active = true;
			if (f == 0) {
				continue;
			}
			if (H.report(s, f * (int64_t)TARGET_NS) != 0) {
				LOG("SCGPU: the hint session refused a report: closed");
				break;
			}
			boosts++;
		} else if (active) {
			if (quiet_since == 0) {
				quiet_since = now;
			} else if (now - quiet_since > 250000000u) {
				active = false;         /* (nothing more said: the boost lapses) */
			}
		}
	}
	LOG("SCGPU: the hint session: %llu reports while the guest drew", (unsigned long long)boosts);
out:
	if (s != NULL) {
		H.close(s);
	}
	return NULL;
}

/* Before the VM starts (scgpu.c's start). */
void
vmboost_start(void)
{
	char v[PROP_VALUE_MAX] = "";
	__system_property_get("debug.scion.boost", v);
	if (B.created || (v[0] != 0 && atoi(v) == 0) || !load()) {
		return;
	}
	B.floor = newest_tid();
	B.stop = 0;
	if (pthread_create(&B.thread, NULL, run, NULL) == 0) {
		pthread_setname_np(B.thread, "vmboost");
		B.created = 1;
	}
}

void
vmboost_stop(void)
{
	if (!B.created) {
		return;
	}
	B.stop = 1;
	pthread_join(B.thread, NULL);
	B.created = 0;
}
