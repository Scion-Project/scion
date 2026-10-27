/*
 * SCGPU, the app's side: the guest's GPU in this process. gfxstream's host renderer
 * (libgfxstream_backend.so, built for Android by hackios-xnu-kernel tools/gfxstream/mk-host-android.sh)
 * is driven by the guest's virtio-gpu commands, which come through a mailbox in a region of guest
 * RAM the app lends, as it lends SCFB's; the guest's display frames are shown in a window of the
 * app's (gfxstream's subwindow), from the GPU straight to the screen. crosvm's own virtio-gpu could
 * draw with the same renderer but can't show a sideloaded app anything, so every frame came back
 * into the guest and out again through SCFB (17-40 ms a frame of readback on this phone).
 *
 * The region and its protocol: hackios-xnu-kernel xnu/files/bsd/sys/hakios.scgpu.h (copied below).
 * One command at a time. This thread spins a little after each, then sleeps on the doorbell: a
 * virtio-input device of the app's ("scion-scgpu") whose socket crosvm writes the guest's status
 * events into; it rings the guest back the same way (an event into the socket: an interrupt there),
 * each side only when the other has said it sleeps. Without the doorbell it polls.
 */
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/uio.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define TAG "scgpu"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define ERR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---- hakios.scgpu.h ---------------------------------------------------------------------- */

#define SCGPU_MAGIC             0x50474353u
#define SCGPU_VERSION           1u
#define SCGPU_CMD_OFF           0x1000u
#define SCGPU_CMD_MAX           (0x10000u - SCGPU_CMD_OFF)
#define SCGPU_RESP_OFF          0x10000u
#define SCGPU_RESP_MAX          0x1000u
#define SCGPU_POOL_OFF          0x20000u
#define SCGPU_F_HOST_READY      (1u << 0)
#define SCGPU_G_PRESENTING      (1u << 0)
#define SCGPU_CMD_TRANSFER      0x0f00u

struct scgpu_header {
	uint32_t magic, version;
	uint64_t size;
	uint32_t flags;
	uint32_t features_lo, features_hi;
	uint32_t num_capsets;
	uint32_t req_seq, req_len, resp_max, resp_seq, resp_len;
	int32_t status;
	uint64_t commands, presents, host_busy_ns;
	uint32_t guest_flags;
	uint32_t host_sleeping, guest_waiting;
	uint32_t reserved[13];
};

struct scgpu_transfer {
	uint32_t type, flags;
	uint64_t fence_id;
	uint32_t ctx_id;
	uint8_t ring_idx, padding[3];
	uint32_t resource_id, to_host, x, y, w, h;
	uint64_t offset, staging, staging_len;
};

/* ---- virtio-gpu (virtio 1.2, 5.7.6) ------------------------------------------------------- */

enum {
	CMD_GET_DISPLAY_INFO = 0x0100, CMD_RESOURCE_CREATE_2D, CMD_RESOURCE_UNREF, CMD_SET_SCANOUT,
	CMD_RESOURCE_FLUSH, CMD_TRANSFER_TO_HOST_2D, CMD_RESOURCE_ATTACH_BACKING, CMD_RESOURCE_DETACH_BACKING,
	CMD_GET_CAPSET_INFO, CMD_GET_CAPSET,
	CMD_CTX_CREATE = 0x0200, CMD_CTX_DESTROY, CMD_CTX_ATTACH_RESOURCE, CMD_CTX_DETACH_RESOURCE,
	CMD_RESOURCE_CREATE_3D, CMD_TRANSFER_TO_HOST_3D, CMD_TRANSFER_FROM_HOST_3D, CMD_SUBMIT_3D,
	RESP_OK_NODATA = 0x1100, RESP_OK_DISPLAY_INFO, RESP_OK_CAPSET_INFO, RESP_OK_CAPSET,
	RESP_ERR_UNSPEC = 0x1200,
};
#define F_VIRGL                 0
#define F_CONTEXT_INIT          4
#define CAPSET_GFXSTREAM_GLES   8
#define PIPE_TEXTURE_2D         2       /* struct resource_create_3d's target (gallium's) */

struct hdr { uint32_t type, flags; uint64_t fence_id; uint32_t ctx_id; uint8_t ring_idx, padding[3]; };
struct box { uint32_t x, y, z, w, h, d; };
struct get_capset_info { struct hdr hdr; uint32_t capset_index, padding; };
struct resp_capset_info { struct hdr hdr; uint32_t capset_id, capset_max_version, capset_max_size, padding; };
struct get_capset { struct hdr hdr; uint32_t capset_id, capset_version; };
struct ctx_create { struct hdr hdr; uint32_t nlen, context_init; char debug_name[64]; };
struct ctx_resource { struct hdr hdr; uint32_t resource_id, padding; };
struct resource_create_3d {
	struct hdr hdr;
	uint32_t resource_id, target, format, bind, width, height, depth, array_size, last_level, nr_samples, flags,
	    padding;
};
struct mem_entry { uint64_t addr; uint32_t length, padding; };
struct attach_backing { struct hdr hdr; uint32_t resource_id, nr_entries; };   /* entries follow */
struct resource_id_cmd { struct hdr hdr; uint32_t resource_id, padding; };    /* UNREF, DETACH_BACKING */
struct transfer_3d {
	struct hdr hdr;
	struct box box;
	uint64_t offset;
	uint32_t resource_id, level, stride, layer_stride;
};
struct resource_flush { struct hdr hdr; uint32_t x, y, width, height, resource_id, padding; };

/* ---- gfxstream's stream_renderer (host/include/gfxstream/virtio-gpu-gfxstream-renderer.h) -- */

struct sr_param { uint64_t key, value; };
struct sr_create {
	uint32_t handle, target, format, bind, width, height, depth, array_size, last_level, nr_samples, flags;
};
struct sr_fence { uint32_t flags; uint64_t fence_id; uint32_t ctx_id; uint8_t ring_idx; };
#define SR_PARAM_USER_DATA      1
#define SR_PARAM_RENDERER_FLAGS 2
#define SR_PARAM_FENCE_CALLBACK 3
#define SR_PARAM_WIN0_WIDTH     4
#define SR_PARAM_WIN0_HEIGHT    5
#define SR_FLAGS_USE_EGL        (1u << 0)
#define SR_FLAGS_SURFACELESS    (1u << 3)
#define SR_FLAGS_USE_GLES       (1u << 4)

static struct {
	int (*init)(struct sr_param *, uint64_t);
	void (*teardown)(void);
	int (*context_create)(uint32_t, uint32_t, const char *, uint32_t);
	void (*context_destroy)(uint32_t);
	int (*resource_create)(struct sr_create *, struct iovec *, uint32_t);
	void (*resource_unref)(uint32_t);
	int (*attach_iov)(int, struct iovec *, int);
	void (*detach_iov)(int, struct iovec **, int *);
	void (*ctx_attach)(int, int);
	void (*ctx_detach)(int, int);
	int (*write)(uint32_t, uint32_t, int, uint32_t, uint32_t, struct box *, uint64_t, struct iovec *, unsigned);
	int (*read)(uint32_t, uint32_t, uint32_t, uint32_t, uint32_t, struct box *, uint64_t, struct iovec *, int);
	void (*get_cap_set)(uint32_t, uint32_t *, uint32_t *);
	void (*fill_caps)(uint32_t, uint32_t, void *);
	void (*flush)(uint32_t);
	void (*setup_window)(void *, int32_t, int32_t, int32_t, int32_t, int32_t, int32_t);
} sr;

/* ---- the device ----------------------------------------------------------------------------- */

static struct {
	uint8_t *base;                  /* the region, mapped here */
	uint64_t guest, size;           /* ... where the guest has it, and how big */
	volatile struct scgpu_header *h;
	int fb_w, fb_h;
	pthread_t thread;
	int running, renderer_up;
	volatile int stop;
	pthread_mutex_t window_lock;
	ANativeWindow *window;
	uint64_t commands, errors, busy_ns;
	uint32_t said_unknown;
	int bell;                       /* the doorbell's socket, or -1: polling */
	uint64_t rings_in, rings_out, sleeps;
} S = { .window_lock = PTHREAD_MUTEX_INITIALIZER, .bell = -1 };

#define SPIN_NS                 200000u         /* after a command, look for the next this long, then sleep */

static uint64_t
now_ns(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t)ts.tv_sec * 1000000000u + (uint64_t)ts.tv_nsec;
}

/* The guest's physical address of n bytes in the pool, here; NULL if they are not all in the region. */
static void *
at(uint64_t pa, uint64_t n)
{
	if (pa < S.guest + SCGPU_POOL_OFF || n > S.size || pa - S.guest > S.size - n) {
		return NULL;
	}
	return S.base + (pa - S.guest);
}

/*
 * gfxstream keeps a linear copy of every resource, the size of its backing, and copies through it;
 * without a backing that copy is empty and every transfer fails ("mLinear is too small"). SCGPU's
 * colour buffers have no backing in the guest (their transfers bring their own iovecs), so each gets
 * placeholder iovecs of its picture's size, over the pool: never read or written through, only there
 * to size the copy.
 */
static int
attach_placeholder(uint32_t res, uint64_t bytes)
{
	uint8_t *pool = S.base + SCGPU_POOL_OFF;
	uint64_t pool_len = S.size - SCGPU_POOL_OFF;
	uint32_t n = (uint32_t)((bytes + pool_len - 1) / pool_len);
	struct iovec *iov = calloc(n ? n : 1, sizeof(*iov));
	int err;

	if (iov == NULL) {
		return -ENOMEM;
	}
	for (uint32_t i = 0; i < n; i++) {
		uint64_t left = bytes - (uint64_t)i * pool_len;
		iov[i].iov_base = pool;
		iov[i].iov_len = (size_t)(left < pool_len ? left : pool_len);
	}
	err = sr.attach_iov((int)res, iov, (int)n);
	free(iov);
	return err;
}

static void
fence_cb(void *user, struct sr_fence *f)
{
	(void)user;
	(void)f;
}

/* One command from the mailbox; the reply goes where the guest takes it. Returns the reply's length. */
static uint32_t
handle(uint32_t len)
{
	const uint8_t *cmd = S.base + SCGPU_CMD_OFF;
	uint8_t *resp = S.base + SCGPU_RESP_OFF;
	struct hdr in, out;
	uint32_t resp_len = sizeof(out);
	int err = 0;

	if (len < sizeof(in) || len > SCGPU_CMD_MAX) {
		memset(&in, 0, sizeof(in));
		err = -EINVAL;
		goto reply;
	}
	memcpy(&in, cmd, sizeof(in));
#define NEED(type) do { if (len < sizeof(type)) { err = -EINVAL; goto reply; } } while (0)
	switch (in.type) {
	case CMD_GET_CAPSET_INFO: {
		struct get_capset_info c;
		struct resp_capset_info r;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		if (c.capset_index != 0) {
			err = -ENOENT;          /* one capset: gfxstream's GLES */
			break;
		}
		memset(&r, 0, sizeof(r));
		r.hdr.type = RESP_OK_CAPSET_INFO;
		r.capset_id = CAPSET_GFXSTREAM_GLES;
		sr.get_cap_set(CAPSET_GFXSTREAM_GLES, &r.capset_max_version, &r.capset_max_size);
		memcpy(resp, &r, sizeof(r));
		return sizeof(r);
	}
	case CMD_GET_CAPSET: {
		struct get_capset c;
		uint32_t version = 0, size = 0;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		sr.get_cap_set(c.capset_id, &version, &size);
		if (size == 0 || size > SCGPU_RESP_MAX - sizeof(struct hdr)) {
			err = -EINVAL;
			break;
		}
		memset(resp, 0, sizeof(struct hdr) + size);
		sr.fill_caps(c.capset_id, c.capset_version, resp + sizeof(struct hdr));
		((struct hdr *)resp)->type = RESP_OK_CAPSET;
		return (uint32_t)(sizeof(struct hdr) + size);
	}
	case CMD_CTX_CREATE: {
		struct ctx_create c;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		err = sr.context_create(in.ctx_id, c.nlen > 64 ? 64 : c.nlen, c.debug_name, c.context_init);
		break;
	}
	case CMD_CTX_DESTROY:
		sr.context_destroy(in.ctx_id);
		break;
	case CMD_RESOURCE_CREATE_3D: {
		struct resource_create_3d c;
		struct sr_create a;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		a = (struct sr_create){ c.resource_id, c.target, c.format, c.bind, c.width, c.height, c.depth,
			c.array_size, c.last_level, c.nr_samples, c.flags };
		err = sr.resource_create(&a, NULL, 0);
		if (!err && c.target == PIPE_TEXTURE_2D) {
			/* a colour buffer: 4 bytes a pixel (BGRA/RGBA, all the guest makes) */
			err = attach_placeholder(c.resource_id, (uint64_t)c.width * (c.height ? c.height : 1) * 4);
		}
		break;
	}
	case CMD_RESOURCE_ATTACH_BACKING: {
		struct attach_backing c;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		if (c.nr_entries == 0) {
			break;                  /* SCGPU's colour buffers: placeholders already (attach_placeholder) */
		}
		if (c.nr_entries > (len - sizeof(c)) / sizeof(struct mem_entry)) {
			err = -EINVAL;
			break;
		}
		struct iovec *iov = calloc(c.nr_entries, sizeof(*iov));
		if (iov == NULL) {
			err = -ENOMEM;
			break;
		}
		for (uint32_t i = 0; i < c.nr_entries && !err; i++) {
			struct mem_entry e;
			memcpy(&e, cmd + sizeof(c) + i * sizeof(e), sizeof(e));
			iov[i].iov_base = at(e.addr, e.length);
			iov[i].iov_len = e.length;
			if (iov[i].iov_base == NULL) {
				ERR("ATTACH_BACKING of resource %u: %#llx+%u is not in the region", c.resource_id,
				    (unsigned long long)e.addr, e.length);
				err = -EFAULT;
			}
		}
		if (!err) {
			err = sr.attach_iov((int)c.resource_id, iov, (int)c.nr_entries);
		}
		free(iov);
		break;
	}
	case CMD_RESOURCE_DETACH_BACKING: {
		struct resource_id_cmd c;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		sr.detach_iov((int)c.resource_id, NULL, NULL);
		break;
	}
	case CMD_CTX_ATTACH_RESOURCE:
	case CMD_CTX_DETACH_RESOURCE: {
		struct ctx_resource c;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		if (in.type == CMD_CTX_ATTACH_RESOURCE) {
			sr.ctx_attach((int)in.ctx_id, (int)c.resource_id);
		} else {
			sr.ctx_detach((int)in.ctx_id, (int)c.resource_id);
		}
		break;
	}
	case CMD_RESOURCE_UNREF: {
		struct resource_id_cmd c;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		sr.resource_unref(c.resource_id);
		break;
	}
	case CMD_TRANSFER_TO_HOST_3D:
	case CMD_TRANSFER_FROM_HOST_3D: {
		/* on the attached backing: the gfxstream pipe's buffer */
		struct transfer_3d c;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		if (in.type == CMD_TRANSFER_TO_HOST_3D) {
			err = sr.write(c.resource_id, in.ctx_id, (int)c.level, c.stride, c.layer_stride, &c.box, c.offset, NULL, 0);
		} else {
			err = sr.read(c.resource_id, in.ctx_id, c.level, c.stride, c.layer_stride, &c.box, c.offset, NULL, 0);
		}
		break;
	}
	case SCGPU_CMD_TRANSFER: {
		/*
		 * Through a staging buffer of the pool: gfxstream reads its iovecs as the resource's whole
		 * linear picture and copies the bytes from the box's first pixel to its last, so the first
		 * iovec stands for the bytes before the box (never touched) and the staging buffer follows.
		 */
		struct scgpu_transfer c;
		struct box b;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		void *stage = at(c.staging, c.staging_len);
		if (stage == NULL || c.w == 0 || c.h == 0) {
			err = -EFAULT;
			break;
		}
		struct iovec iov[2] = { { stage, (size_t)c.offset }, { stage, (size_t)c.staging_len } };
		b = (struct box){ c.x, c.y, 0, c.w, c.h, 1 };
		if (c.to_host) {
			err = sr.write(c.resource_id, c.ctx_id, 0, 0, 0, &b, c.offset, iov, 2);
		} else {
			err = sr.read(c.resource_id, c.ctx_id, 0, 0, 0, &b, c.offset, iov, 2);
		}
		break;
	}
	case CMD_RESOURCE_FLUSH: {
		struct resource_flush c;
		NEED(c);
		memcpy(&c, cmd, sizeof(c));
		sr.flush(c.resource_id);
		break;
	}
	case CMD_SET_SCANOUT:
		break;                          /* the window is the app's; a flush shows a resource in it */
	default:
		if (S.said_unknown++ < 8) {
			ERR("command %#x not handled", in.type);
		}
		err = -ENOSYS;
		break;
	}
#undef NEED
reply:
	memset(&out, 0, sizeof(out));
	out.type = err ? RESP_ERR_UNSPEC : RESP_OK_NODATA;
	out.flags = in.flags;
	out.fence_id = in.fence_id;
	out.ctx_id = in.ctx_id;
	if (err) {
		S.errors++;
		if (S.errors <= 16) {
			ERR("command %#x (ctx %u): %d", in.type, in.ctx_id, err);
		}
	}
	memcpy(resp, &out, sizeof(out));
	return resp_len;
}

/* One line every 5 s while commands come: how busy the device is, and how much it slept. */
static void
report(uint64_t now)
{
	static uint64_t at, commands, busy, rings_in, rings_out, sleeps;

	if (at == 0) {
		at = now;
	}
	if (now - at < 5000000000u) {
		return;
	}
	double secs = (now - at) / 1e9;
	if (S.commands != commands) {
		LOG("SCGPU: %.0f commands/s, %.1f%% of the time in them; %.0f sleeps/s, rung %.0f/s, rang %.0f/s%s",
		    (S.commands - commands) / secs, (S.busy_ns - busy) / 1e7 / secs, (S.sleeps - sleeps) / secs,
		    (S.rings_in - rings_in) / secs, (S.rings_out - rings_out) / secs, S.bell < 0 ? " (polling)" : "");
	}
	at = now;
	commands = S.commands;
	busy = S.busy_ns;
	rings_in = S.rings_in;
	rings_out = S.rings_out;
	sleeps = S.sleeps;
}

static void *
serve(void *arg)
{
	(void)arg;
	volatile uint32_t *req = (volatile uint32_t *)&S.h->req_seq;
	uint32_t seen = __atomic_load_n((uint32_t *)(uintptr_t)req, __ATOMIC_ACQUIRE);
	uint64_t last = now_ns();

	while (!S.stop) {
		uint32_t seq = __atomic_load_n((uint32_t *)(uintptr_t)req, __ATOMIC_ACQUIRE);
		if (seq == seen) {
			uint64_t idle = now_ns() - last;
			if (idle < SPIN_NS) {
				__asm__ volatile("yield");
				continue;
			}
			if (S.bell >= 0) {
				/* asleep until the guest rings: say so, then look once more (its half is in scgpu_ctrl) */
				__atomic_store_n((uint32_t *)(uintptr_t)&S.h->host_sleeping, 1, __ATOMIC_RELAXED);
				__atomic_thread_fence(__ATOMIC_SEQ_CST);
				if (__atomic_load_n((uint32_t *)(uintptr_t)req, __ATOMIC_ACQUIRE) == seen) {
					char buf[256];
					ssize_t n = read(S.bell, buf, sizeof(buf));
					S.sleeps++;
					if (n > 0) {
						S.rings_in += (uint64_t)n / 8;
					} else if (n == 0 || errno != EINTR) {
						if (!S.stop) {
							ERR("the doorbell's socket: %s; polling from now on", n == 0 ? "closed" : strerror(errno));
						}
						S.bell = -1;
					}
				}
				__atomic_store_n((uint32_t *)(uintptr_t)&S.h->host_sleeping, 0, __ATOMIC_RELAXED);
				report(now_ns());
				continue;
			}
			struct timespec nap = { 0, idle < 200000000u ? 50000 : 1000000 };
			nanosleep(&nap, NULL);
			report(now_ns());
			continue;
		}
		seen = seq;
		uint64_t t0 = now_ns();
		uint32_t n = handle(S.h->req_len);
		S.h->resp_len = n;
		S.h->status = 0;
		last = now_ns();
		S.busy_ns += last - t0;
		S.commands++;
		S.h->commands = S.commands;
		S.h->host_busy_ns = S.busy_ns;
		__atomic_store_n((uint32_t *)(uintptr_t)&S.h->resp_seq, seq, __ATOMIC_RELEASE);
		/* the guest asleep on the doorbell is rung: an input event, an interrupt there */
		__atomic_thread_fence(__ATOMIC_SEQ_CST);
		if (S.bell >= 0 && __atomic_load_n((uint32_t *)(uintptr_t)&S.h->guest_waiting, __ATOMIC_RELAXED)) {
			static const uint16_t syn[4] = { 0, 0, 0, 0 };      /* EV_SYN SYN_REPORT */
			if (write(S.bell, syn, sizeof(syn)) == (ssize_t)sizeof(syn)) {
				S.rings_out++;
			}
		}
		report(last);
	}
	return NULL;
}

static int
load(void)
{
	void *lib = dlopen("libgfxstream_backend.so", RTLD_NOW | RTLD_LOCAL);
	if (lib == NULL) {
		ERR("libgfxstream_backend.so: %s", dlerror());
		return -1;
	}
#define SYM(field, name) if ((*(void **)&sr.field = dlsym(lib, name)) == NULL) { ERR("no %s", name); return -2; }
	SYM(init, "stream_renderer_init");
	SYM(teardown, "stream_renderer_teardown");
	SYM(context_create, "stream_renderer_context_create");
	SYM(context_destroy, "stream_renderer_context_destroy");
	SYM(resource_create, "stream_renderer_resource_create");
	SYM(resource_unref, "stream_renderer_resource_unref");
	SYM(attach_iov, "stream_renderer_resource_attach_iov");
	SYM(detach_iov, "stream_renderer_resource_detach_iov");
	SYM(ctx_attach, "stream_renderer_ctx_attach_resource");
	SYM(ctx_detach, "stream_renderer_ctx_detach_resource");
	SYM(write, "stream_renderer_transfer_write_iov");
	SYM(read, "stream_renderer_transfer_read_iov");
	SYM(get_cap_set, "stream_renderer_get_cap_set");
	SYM(fill_caps, "stream_renderer_fill_caps");
	SYM(flush, "stream_renderer_flush");
	SYM(setup_window, "gfxstream_backend_setup_window");
#undef SYM
	return 0;
}

/*
 * The renderer up and the region's header written, so the guest finds a device that answers
 * (flags HOST_READY): before the VM starts. 0, or a negative reason.
 */
JNIEXPORT jint JNICALL
Java_io_github_dylanneve1_iosonpixel_avf_ScgpuNative_start(JNIEnv *env, jclass cls, jobject region, jlong guest,
    jint fb_w, jint fb_h, jint bell_fd)
{
	(void)cls;
	if (S.running) {
		return -16;
	}
	S.base = (*env)->GetDirectBufferAddress(env, region);
	S.size = (uint64_t)(*env)->GetDirectBufferCapacity(env, region);
	S.guest = (uint64_t)guest;
	S.fb_w = fb_w;
	S.fb_h = fb_h;
	if (S.base == NULL || S.size < SCGPU_POOL_OFF + 0x10000) {
		return -3;
	}
	if (sr.init == NULL && load() != 0) {
		return -1;
	}
	if (!S.renderer_up) {
		struct sr_param params[] = {
			{ SR_PARAM_USER_DATA, 0 },
			{ SR_PARAM_RENDERER_FLAGS, SR_FLAGS_USE_EGL | SR_FLAGS_SURFACELESS | SR_FLAGS_USE_GLES },
			{ SR_PARAM_FENCE_CALLBACK, (uint64_t)(uintptr_t)fence_cb },
			{ SR_PARAM_WIN0_WIDTH, (uint64_t)fb_w },
			{ SR_PARAM_WIN0_HEIGHT, (uint64_t)fb_h },
		};
		int rc = sr.init(params, sizeof(params) / sizeof(params[0]));
		if (rc != 0) {
			ERR("stream_renderer_init: %d", rc);
			return -4;
		}
		S.renderer_up = 1;
	}
	S.h = (volatile struct scgpu_header *)S.base;
	memset(S.base, 0, SCGPU_POOL_OFF);
	S.h->magic = SCGPU_MAGIC;
	S.h->version = SCGPU_VERSION;
	S.h->size = S.size;
	S.h->features_lo = (1u << F_VIRGL) | (1u << F_CONTEXT_INIT);
	S.h->num_capsets = 1;
	S.commands = S.errors = S.busy_ns = 0;
	S.rings_in = S.rings_out = S.sleeps = 0;
	S.bell = bell_fd >= 0 ? dup(bell_fd) : -1;       /* (ours: the app closes its own) */
	S.stop = 0;
	if (pthread_create(&S.thread, NULL, serve, NULL) != 0) {
		return -5;
	}
	pthread_setname_np(S.thread, "scgpu");
	__atomic_store_n((uint32_t *)(uintptr_t)&S.h->flags, SCGPU_F_HOST_READY, __ATOMIC_RELEASE);
	S.running = 1;
	LOG("SCGPU: gfxstream up, %llu MiB region at guest %#llx, display %dx%d, %s", (unsigned long long)(S.size >> 20),
	    (unsigned long long)S.guest, fb_w, fb_h, S.bell >= 0 ? "doorbell" : "polling");
	return 0;
}

/* Whether the guest has begun to show frames through the host (it set SCGPU_G_PRESENTING). */
JNIEXPORT jboolean JNICALL
Java_io_github_dylanneve1_iosonpixel_avf_ScgpuNative_presenting(JNIEnv *env, jclass cls)
{
	(void)env;
	(void)cls;
	return S.running && (__atomic_load_n((uint32_t *)(uintptr_t)&S.h->guest_flags, __ATOMIC_ACQUIRE) &
	    SCGPU_G_PRESENTING) ? JNI_TRUE : JNI_FALSE;
}

/* The window the guest's frames are shown in (gfxstream's subwindow), w x h pixels; null: none. */
JNIEXPORT void JNICALL
Java_io_github_dylanneve1_iosonpixel_avf_ScgpuNative_setWindow(JNIEnv *env, jclass cls, jobject surface, jint w,
    jint h)
{
	(void)cls;
	if (!S.renderer_up) {
		return;
	}
	pthread_mutex_lock(&S.window_lock);
	ANativeWindow *old = S.window, *win = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
	/*
	 * The subwindow lets go of the old window before it is released (android-host.patch). Its last two
	 * numbers are the size the guest's frame is drawn at inside the window (the emulator's zoom), not
	 * the frame's own: the whole window. (With the guest's 1125x2436 in a 1080x2338 window the picture
	 * overflowed it, and the top -- the status bar -- and the right edge were cut off.) The SurfaceView
	 * has the guest's aspect ratio already (SurfaceScreen).
	 */
	sr.setup_window(win, 0, 0, w, h, w, h);
	S.window = win;
	if (old != NULL) {
		ANativeWindow_release(old);
	}
	pthread_mutex_unlock(&S.window_lock);
	LOG("SCGPU: window %s (%dx%d)", win ? "set" : "taken away", w, h);
}

JNIEXPORT void JNICALL
Java_io_github_dylanneve1_iosonpixel_avf_ScgpuNative_stop(JNIEnv *env, jclass cls)
{
	if (!S.running) {
		return;
	}
	Java_io_github_dylanneve1_iosonpixel_avf_ScgpuNative_setWindow(env, cls, NULL, 0, 0);
	S.stop = 1;
	if (S.bell >= 0) {
		shutdown(S.bell, SHUT_RDWR);    /* (the thread may be asleep in read on it) */
	}
	pthread_join(S.thread, NULL);
	if (S.bell >= 0) {
		close(S.bell);
		S.bell = -1;
	}
	S.running = 0;
	/* the renderer stays up for the next run in this process: gfxstream is set up once */
	LOG("SCGPU: stopped after %llu commands (%llu failed), %.1f s busy", (unsigned long long)S.commands,
	    (unsigned long long)S.errors, S.busy_ns / 1e9);
}

/* commands, failed commands, ns spent running them */
JNIEXPORT jlongArray JNICALL
Java_io_github_dylanneve1_iosonpixel_avf_ScgpuNative_stats(JNIEnv *env, jclass cls)
{
	(void)cls;
	jlong v[3] = { (jlong)S.commands, (jlong)S.errors, (jlong)S.busy_ns };
	jlongArray a = (*env)->NewLongArray(env, 3);
	if (a != NULL) {
		(*env)->SetLongArrayRegion(env, a, 0, 3, v);
	}
	return a;
}
