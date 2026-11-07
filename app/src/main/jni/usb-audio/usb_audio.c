/*
 * Userspace USB Audio Class isochronous engine for Artemis.
 *
 * Android's Java USB API cannot do isochronous transfers, so this library
 * drives them with raw usbdevfs ioctls on the fd of a UsbDeviceConnection.
 * Java claims the interfaces and selects alt settings; this only moves bytes.
 *
 * Threading model:
 *  - One reaper thread per handle blocks in USBDEVFS_REAPURB and resubmits
 *    completed URBs for playback, capture and feedback.
 *  - Playback ring: single producer (writePlayback) / single consumer (the
 *    thread filling playback URBs), lock-free via an atomic fill counter.
 *  - Capture ring: mutex + condvar; readCapture waits on the condvar.
 *  - Every JNI entry point registers in h->users; close() sets h->closing,
 *    wakes readers, waits for users == 0, then stops and frees.
 */
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

/* ---- Tunables (Task 10) ---- */
#define PLAY_URBS        4
#define PLAY_MS_PER_URB  2
#define CAP_URBS         4
#define CAP_MS_PER_URB   4
#define FB_URBS          2
#define PLAY_RING_MS     200
#define CAP_RING_MS      500
#define PREFILL_MS       10
#define DRIFT_HIGH_MS    30
#define DRIFT_TARGET_MS  20
#define FB_TOLERANCE_PCT 10
#define MAX_URBS         4

#define TAG "UsbAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif

enum { K_PLAY, K_CAP, K_FB };

struct handle;

struct stream {
    int kind;
    int endpoint;
    int nurbs;
    int npk;        /* packets per URB */
    int pkt_bytes;  /* max bytes per packet */
    struct usbdevfs_urb *urbs[MAX_URBS];
    atomic_int dead;      /* stop resubmitting this stream */
    atomic_int inflight;  /* this stream's URBs pending in the kernel */
};

struct handle {
    int fd;
    int high_speed;

    atomic_int users;
    atomic_int closing;
    atomic_int gone;
    atomic_int stopping;
    atomic_int in_flight;
    atomic_int err_logged;

    pthread_mutex_t ctl;      /* start calls, thread creation, reaper idle wait */
    pthread_cond_t ctl_cond;
    pthread_t thread;
    int thread_started;       /* under ctl */
    int reaper_done;          /* under ctl: the reaper thread has left its loop */
    int draining;             /* under ctl: a start call waits for a stream to drain */
    int play_started;         /* under ctl */
    int cap_started;          /* under ctl */
    atomic_int play_failed;   /* every playback URB fell out of rotation */
    atomic_int cap_failed;    /* every capture URB fell out of rotation */

    /* Playback (consumer state touched only by the URB-filling thread) */
    struct stream play, fb;
    atomic_int play_active;
    uint8_t *pring;
    uint32_t pcap, pw, pr;
    atomic_uint pfill;
    uint32_t pframe, prate, pppm, pmax;
    uint32_t nominal, acc;
    uint32_t prefill_b, high_b, target_b;
    int primed, dropping;
    uint32_t since_drop;      /* packets since the last drift drop, capped at pppm */
    atomic_uint fb_rate;      /* 16.16 frames per packet, 0 = none yet */
    int fb_shift, fb_shift_known;

    /* Capture */
    struct stream cap;
    atomic_int cap_active;
    pthread_mutex_t cm;
    pthread_cond_t cc;
    uint8_t *cring;
    uint32_t ccap, cr, cfill, cframe;

    /* drift_dropped: frames skipped by drift control; rejected: frames
     * writePlayback refused for lack of ring space. */
    atomic_int underruns, overruns, drift_dropped, rejected, errors;
};

/* ---- Handle lifetime ---- */

static struct handle *enter(jlong hh)
{
    struct handle *h = (struct handle *)(intptr_t)hh;
    if (!h) return NULL;
    atomic_fetch_add(&h->users, 1);
    if (atomic_load(&h->closing)) {
        atomic_fetch_sub(&h->users, 1);
        return NULL;
    }
    return h;
}

static void leave(struct handle *h)
{
    atomic_fetch_sub(&h->users, 1);
}

static void wake_readers(struct handle *h)
{
    pthread_mutex_lock(&h->cm);
    pthread_cond_broadcast(&h->cc);
    pthread_mutex_unlock(&h->cm);
}

static void set_gone(struct handle *h, const char *where, int err)
{
    if (atomic_exchange(&h->gone, 1) == 0)
        LOGW("device gone (%s: %s)", where, strerror(err));
    wake_readers(h);
}

static void note_error(struct handle *h, const char *where, int err)
{
    atomic_fetch_add(&h->errors, 1);
    if (atomic_exchange(&h->err_logged, 1) == 0)
        LOGE("first error: %s: %s", where, strerror(err));
}

static int is_gone_errno(int e)
{
    return e == ENODEV || e == ESHUTDOWN;
}

/* ---- URBs ---- */

static void free_stream(struct stream *s)
{
    for (int i = 0; i < s->nurbs; i++) {
        free(s->urbs[i]);
        s->urbs[i] = NULL;
    }
    s->nurbs = 0;
}

static int alloc_stream(struct stream *s, int kind, int ep, int nurbs, int npk, int pkt_bytes)
{
    size_t hdr = sizeof(struct usbdevfs_urb) +
                 (size_t)npk * sizeof(struct usbdevfs_iso_packet_desc);
    hdr = (hdr + 15) & ~(size_t)15;
    size_t data = (size_t)npk * (size_t)pkt_bytes;

    s->kind = kind;
    s->endpoint = ep;
    s->npk = npk;
    s->pkt_bytes = pkt_bytes;
    s->nurbs = 0;
    atomic_store(&s->dead, 0);
    atomic_store(&s->inflight, 0);
    for (int i = 0; i < nurbs; i++) {
        struct usbdevfs_urb *u = calloc(1, hdr + data);
        if (!u) {
            free_stream(s);
            return -ENOMEM;
        }
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char)ep;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->buffer = (uint8_t *)u + hdr;
        u->buffer_length = (int)data;
        u->number_of_packets = npk;
        u->usercontext = s;
        for (int p = 0; p < npk; p++)
            u->iso_frame_desc[p].length = (unsigned int)pkt_bytes;
        s->urbs[s->nurbs++] = u;
    }
    return 0;
}

static void reset_urb(struct usbdevfs_urb *u)
{
    u->status = 0;
    u->actual_length = 0;
    u->start_frame = 0;
    u->error_count = 0;
    for (int p = 0; p < u->number_of_packets; p++) {
        u->iso_frame_desc[p].actual_length = 0;
        u->iso_frame_desc[p].status = 0;
    }
}

/* Returns 0 or -errno. in_flight is raised before the ioctl so the reaper
 * can never see a completion for a URB it has not counted. Callers other
 * than the reaper thread must hold h->ctl (see reaper_main). The stream's
 * own count is kept the same way; the reaper lowers it after complete_urb. */
static int submit(struct handle *h, struct usbdevfs_urb *u)
{
    struct stream *s = u->usercontext;
    atomic_fetch_add(&h->in_flight, 1);
    atomic_fetch_add(&s->inflight, 1);
    if (ioctl(h->fd, USBDEVFS_SUBMITURB, u) == 0)
        return 0;
    int e = errno;
    atomic_fetch_sub(&s->inflight, 1);
    atomic_fetch_sub(&h->in_flight, 1);
    return -e;
}

static void discard_stream(struct handle *h, struct stream *s)
{
    /* EINVAL for URBs not currently in flight is expected and harmless. */
    for (int i = 0; i < s->nurbs; i++)
        ioctl(h->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
}

static void discard_all(struct handle *h)
{
    discard_stream(h, &h->play);
    discard_stream(h, &h->fb);
    discard_stream(h, &h->cap);
}

/* ---- Playback ring (SPSC) ---- */

static uint32_t ms_to_bytes(uint32_t ms, uint32_t rate, uint32_t frame)
{
    return (uint32_t)((uint64_t)rate * ms / 1000) * frame;
}

static void ring_out(uint8_t *dst, const uint8_t *ring, uint32_t cap, uint32_t pos, uint32_t n)
{
    uint32_t first = cap - pos < n ? cap - pos : n;
    memcpy(dst, ring + pos, first);
    if (n > first) memcpy(dst + first, ring, n - first);
}

static void ring_in(uint8_t *ring, uint32_t cap, uint32_t pos, const uint8_t *src, uint32_t n)
{
    uint32_t first = cap - pos < n ? cap - pos : n;
    memcpy(ring + pos, src, first);
    if (n > first) memcpy(ring, src + first, n - first);
}

static void play_consume(struct handle *h, uint8_t *dst, uint32_t copy, uint32_t consume)
{
    if (copy) ring_out(dst, h->pring, h->pcap, h->pr, copy);
    h->pr = (h->pr + consume) % h->pcap;
    atomic_fetch_sub_explicit(&h->pfill, consume, memory_order_release);
}

/* Fills one packet of 'bytes' (whole frames) from the ring. */
static void fill_packet(struct handle *h, uint8_t *dst, uint32_t bytes)
{
    uint32_t fill = atomic_load_explicit(&h->pfill, memory_order_acquire);

    if (h->since_drop < h->pppm) h->since_drop++;
    if (!h->primed) {
        if (fill < h->prefill_b) {
            memset(dst, 0, bytes);
            return;
        }
        h->primed = 1;
    }
    if (h->dropping) {
        if (fill <= h->target_b) h->dropping = 0;
    } else if (fill > h->high_b) {
        h->dropping = 1;
    }
    if (fill < bytes) {
        play_consume(h, dst, fill, fill);
        memset(dst + fill, 0, bytes - fill);
        atomic_fetch_add(&h->underruns, 1);
        h->primed = 0;
        h->dropping = 0;
        return;
    }
    uint32_t consume = bytes;
    /* At most one frame per millisecond: at high speed there are up to 8
     * packets per ms, and one drop per packet would be audible. */
    if (h->dropping && h->since_drop >= h->pppm && fill >= bytes + h->pframe) {
        consume += h->pframe;
        h->since_drop = 0;
        atomic_fetch_add(&h->drift_dropped, 1);
    }
    play_consume(h, dst, bytes, consume);
}

static void fill_play_urb(struct handle *h, struct usbdevfs_urb *u)
{
    uint32_t rate = atomic_load(&h->fb_rate);
    if (!rate) rate = h->nominal;
    uint32_t max_frames = h->pmax / h->pframe;
    uint8_t *buf = u->buffer;
    uint32_t off = 0;

    for (int p = 0; p < u->number_of_packets; p++) {
        h->acc += rate;
        uint32_t frames = h->acc >> 16;
        h->acc &= 0xffff;
        if (frames > max_frames) frames = max_frames;
        uint32_t bytes = frames * h->pframe;
        fill_packet(h, buf + off, bytes);
        u->iso_frame_desc[p].length = bytes;
        off += bytes;
    }
    u->buffer_length = (int)off;
}

/* ---- Feedback ---- */

static void handle_feedback(struct handle *h, const uint8_t *p, unsigned int len)
{
    if (len < 3) return;
    uint32_t v = p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16;
    if (len >= 4) v = (v | (uint32_t)p[3] << 24) & 0x0fffffff;
    if (!v) return;

    uint64_t nom = h->nominal;
    uint64_t lo = nom - nom * FB_TOLERANCE_PCT / 100;
    uint64_t hi = nom + nom * FB_TOLERANCE_PCT / 100;

    if (!h->fb_shift_known) {
        /* 10.14-in-3-bytes is shift 2, 16.16 is shift 0; powers of two apart,
         * so at most one shift can land within +-10% of nominal. */
        for (int s = -4; s <= 4; s++) {
            uint64_t c = s >= 0 ? (uint64_t)v << s : (uint64_t)v >> -s;
            if (c >= lo && c <= hi) {
                h->fb_shift = s;
                h->fb_shift_known = 1;
                LOGI("feedback format detected: %u bytes, shift %d (%s), raw 0x%08x",
                     len, s, s == 2 ? "10.14" : s == 0 ? "16.16" : "scaled", v);
                break;
            }
        }
        if (!h->fb_shift_known) return;
    }
    int s = h->fb_shift;
    uint64_t c = s >= 0 ? (uint64_t)v << s : (uint64_t)v >> -s;
    if (c >= lo && c <= hi)
        atomic_store(&h->fb_rate, (uint32_t)c);
}

/* ---- Capture ring ---- */

static void cap_push(struct handle *h, const uint8_t *src, uint32_t n)
{
    pthread_mutex_lock(&h->cm);
    if (n > h->ccap) {
        src += n - h->ccap;
        n = h->ccap;
    }
    uint32_t space = h->ccap - h->cfill;
    if (n > space) {
        uint32_t drop = n - space;
        h->cr = (h->cr + drop) % h->ccap;
        h->cfill -= drop;
        atomic_fetch_add(&h->overruns, 1);
    }
    ring_in(h->cring, h->ccap, (h->cr + h->cfill) % h->ccap, src, n);
    h->cfill += n;
    pthread_cond_broadcast(&h->cc);
    pthread_mutex_unlock(&h->cm);
}

/* ---- Reaper ---- */

/* Every URB of a playback or capture stream fell out of rotation while the
 * stream should be running: report the direction as failed (getState -EIO)
 * so Java falls back. Feedback is optional and never fails playback. */
static void mark_failed(struct handle *h, struct stream *s)
{
    if (s->kind == K_FB || atomic_load(&h->stopping) || atomic_load(&h->gone) ||
        atomic_load(&s->dead))
        return;
    atomic_int *f = s->kind == K_PLAY ? &h->play_failed : &h->cap_failed;
    if (atomic_exchange(f, 1) == 0)
        LOGE("%s stream failed: no URBs left in flight",
             s->kind == K_PLAY ? "playback" : "capture");
    if (s->kind == K_CAP) wake_readers(h);
}

/* Returns 1 if the URB was dropped from rotation by a resubmit error. */
static int complete_urb(struct handle *h, struct usbdevfs_urb *u)
{
    struct stream *s = u->usercontext;

    if (u->status == -ENODEV || u->status == -ESHUTDOWN) {
        set_gone(h, "urb status", -u->status);
        return 0;
    }
    if (s->kind == K_CAP && u->status == 0) {
        const uint8_t *buf = u->buffer;
        for (int p = 0; p < u->number_of_packets; p++) {
            unsigned int n = u->iso_frame_desc[p].actual_length;
            n -= n % h->cframe;
            if (u->iso_frame_desc[p].status == 0 && n)
                cap_push(h, buf + (size_t)p * (size_t)s->pkt_bytes, n);
        }
    } else if (s->kind == K_FB && u->status == 0 && u->iso_frame_desc[0].status == 0) {
        handle_feedback(h, u->buffer, u->iso_frame_desc[0].actual_length);
    }

    if (atomic_load(&h->stopping) || atomic_load(&h->gone) || atomic_load(&s->dead))
        return 0;

    reset_urb(u);
    if (s->kind == K_PLAY)
        fill_play_urb(h, u);
    int r = submit(h, u);
    if (r) {
        if (is_gone_errno(-r)) {
            set_gone(h, "resubmit", -r);
        } else {
            note_error(h, "resubmit", -r);
            return 1;
        }
    }
    return 0;
}

static void *reaper_main(void *arg)
{
    struct handle *h = arg;
    int discarded = 0;

    /* ANDROID_PRIORITY_URGENT_AUDIO; on Linux this applies to this thread. */
    if (setpriority(PRIO_PROCESS, 0, -19) != 0)
        LOGW("reaper: setpriority(-19) failed: %s", strerror(errno));

    for (;;) {
        /* in_flight is read under ctl. Start calls do increment + SUBMITURB +
         * (on failure) decrement entirely under ctl, and only this thread
         * changes it outside ctl, so a count > 0 seen here means a URB really
         * is pending in the kernel and REAPURB will return (completion,
         * discard, or ENODEV). Never block in REAPURB on a transient count. */
        pthread_mutex_lock(&h->ctl);
        /* A failed start call may be waiting for its URBs to come back
         * (drain_stream_locked); stream counts were lowered before this lock. */
        if (h->draining) pthread_cond_broadcast(&h->ctl_cond);
        while (atomic_load(&h->in_flight) <= 0 && !atomic_load(&h->stopping))
            pthread_cond_wait(&h->ctl_cond, &h->ctl);
        int pending = atomic_load(&h->in_flight);
        int stop = atomic_load(&h->stopping);
        pthread_mutex_unlock(&h->ctl);
        if (pending <= 0) break;  /* implies stopping */
        if (stop && !discarded) {
            /* Catches URBs resubmitted after close() did its discard pass. */
            discard_all(h);
            discarded = 1;
        }
        struct usbdevfs_urb *u = NULL;
        if (ioctl(h->fd, USBDEVFS_REAPURB, &u) != 0) {
            int e = errno;
            if (e == EINTR || e == EAGAIN) continue;
            /* ENODEV: the kernel has already destroyed every pending URB
             * and any later submit fails, so nothing is left to reap. */
            set_gone(h, "reap", e);
            break;
        }
        atomic_fetch_sub(&h->in_flight, 1);
        struct stream *s = u->usercontext;
        int lost = complete_urb(h, u);
        /* Lowered only after complete_urb is done with u: a draining start
         * call frees the URB memory once this count reaches 0. */
        int left = atomic_fetch_sub(&s->inflight, 1) - 1;
        if (lost && left <= 0) mark_failed(h, s);
    }
    pthread_mutex_lock(&h->ctl);
    h->reaper_done = 1;
    pthread_cond_broadcast(&h->ctl_cond);
    pthread_mutex_unlock(&h->ctl);
    return NULL;
}

/* Called with h->ctl held, from a start call that failed after allocating
 * (and possibly submitting) this stream's URBs. Stops resubmission, discards,
 * waits until the reaper has handed back every URB, then frees them so a
 * later start can allocate afresh. Iso URBs always complete or are
 * discarded, so the wait is short; if the reaper exits (device gone) the
 * kernel has already destroyed the pending URBs. close() cannot run
 * meanwhile: the caller holds a user reference. */
static void drain_stream_locked(struct handle *h, struct stream *s)
{
    atomic_store(&s->dead, 1);
    discard_stream(h, s);
    h->draining = 1;
    while (atomic_load(&s->inflight) > 0 && h->thread_started && !h->reaper_done)
        pthread_cond_wait(&h->ctl_cond, &h->ctl);
    h->draining = 0;
    free_stream(s);
}

/* Called with h->ctl held. */
static int ensure_thread(struct handle *h)
{
    if (h->thread_started) return 0;
    int r = pthread_create(&h->thread, NULL, reaper_main, h);
    if (r) return -r;
    h->thread_started = 1;
    return 0;
}

static int packets_per_ms(struct handle *h, int bInterval)
{
    if (!h->high_speed) return 1;
    if (bInterval < 1) bInterval = 1;
    if (bInterval > 4) bInterval = 4;
    return 8 >> (bInterval - 1);
}

/* ---- JNI ---- */

#define JNIFN(name) Java_com_limelight_binding_audio_usb_UsbAudioNative_##name

JNIEXPORT jlong JNICALL JNIFN(open)(JNIEnv *env, jclass cls, jint fd)
{
    (void)env; (void)cls;
    if (fd < 0) return 0;
    struct handle *h = calloc(1, sizeof(*h));
    if (!h) return 0;
    h->fd = fd;

    pthread_condattr_t ca;
    pthread_condattr_init(&ca);
    pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
    pthread_mutex_init(&h->ctl, NULL);
    pthread_cond_init(&h->ctl_cond, NULL);
    pthread_mutex_init(&h->cm, NULL);
    pthread_cond_init(&h->cc, &ca);
    pthread_condattr_destroy(&ca);

    int speed = ioctl(fd, USBDEVFS_GET_SPEED);
    h->high_speed = speed == USB_SPEED_HIGH || speed >= USB_SPEED_SUPER;
    LOGI("open fd=%d speed=%d (%s)", fd, speed, h->high_speed ? "high" : "full");
    return (jlong)(intptr_t)h;
}

JNIEXPORT jint JNICALL JNIFN(startPlayback)(JNIEnv *env, jclass cls, jlong hh, jint endpoint,
                                            jint maxPacketBytes, jint bInterval, jint frameBytes,
                                            jint sampleRate, jint feedbackEndpoint)
{
    (void)env; (void)cls;
    struct handle *h = enter(hh);
    if (!h) return -EBADF;
    if ((endpoint & USB_DIR_IN) || frameBytes <= 0 || sampleRate <= 0 ||
        maxPacketBytes < frameBytes ||
        (feedbackEndpoint > 0 && !(feedbackEndpoint & USB_DIR_IN))) {
        leave(h);
        return -EINVAL;
    }

    pthread_mutex_lock(&h->ctl);
    int r;
    if (h->play_started) { r = -EBUSY; goto out; }
    if (atomic_load(&h->gone)) { r = -ENODEV; goto out; }
    h->play_started = 1;

    h->pframe = (uint32_t)frameBytes;
    h->prate = (uint32_t)sampleRate;
    h->pppm = (uint32_t)packets_per_ms(h, bInterval);
    h->pmax = (uint32_t)maxPacketBytes;
    h->nominal = (uint32_t)((uint64_t)sampleRate * 65536 / (1000 * h->pppm));
    h->acc = 0;
    h->primed = 0;
    h->dropping = 0;
    h->since_drop = 0;
    atomic_store(&h->fb_rate, 0);  /* may be left over from a failed start */
    h->fb_shift_known = 0;
    h->prefill_b = ms_to_bytes(PREFILL_MS, h->prate, h->pframe);
    h->high_b = ms_to_bytes(DRIFT_HIGH_MS, h->prate, h->pframe);
    h->target_b = ms_to_bytes(DRIFT_TARGET_MS, h->prate, h->pframe);
    h->pcap = ms_to_bytes(PLAY_RING_MS, h->prate, h->pframe);
    h->pw = h->pr = 0;
    atomic_store(&h->pfill, 0);
    h->pring = calloc(1, h->pcap);
    if (!h->pring) { r = -ENOMEM; goto fail; }

    r = alloc_stream(&h->play, K_PLAY, endpoint, PLAY_URBS,
                     PLAY_MS_PER_URB * (int)h->pppm, maxPacketBytes);
    if (r) goto fail;
    int fb_len = 4;
    if (feedbackEndpoint > 0) {
        r = alloc_stream(&h->fb, K_FB, feedbackEndpoint, FB_URBS, 1, fb_len);
        if (r) goto fail;
    }
    if ((r = ensure_thread(h))) goto fail;

    LOGI("startPlayback ep=0x%02x maxPacket=%d bInterval=%d frameBytes=%d rate=%d fb=0x%02x "
         "pktPerMs=%u nominal=0x%08x",
         endpoint, maxPacketBytes, bInterval, frameBytes, sampleRate,
         feedbackEndpoint > 0 ? feedbackEndpoint : 0, h->pppm, h->nominal);

    /* Feedback is optional: failure is logged and playback runs on the
     * nominal rate with drift control. */
    for (int i = 0; i < h->fb.nurbs; i++) {
        int fr = submit(h, h->fb.urbs[i]);
        if (fr == -EMSGSIZE && i == 0 && fb_len == 4) {
            fb_len = 3;  /* endpoint wMaxPacketSize is 3 */
            for (int j = 0; j < h->fb.nurbs; j++) {
                h->fb.urbs[j]->iso_frame_desc[0].length = 3;
                h->fb.urbs[j]->buffer_length = 3;
            }
            fr = submit(h, h->fb.urbs[i]);
        }
        if (fr) {
            if (is_gone_errno(-fr)) { set_gone(h, "fb submit", -fr); r = fr; goto fail; }
            LOGW("feedback submit failed: %s; using nominal rate", strerror(-fr));
            atomic_store(&h->fb.dead, 1);
            break;
        }
    }

    /* Fill every URB before the first submit: once one is in flight the
     * reaper owns the consumer state (acc, primed, pr) and may refill. */
    for (int i = 0; i < h->play.nurbs; i++)
        fill_play_urb(h, h->play.urbs[i]);
    for (int i = 0; i < h->play.nurbs; i++) {
        r = submit(h, h->play.urbs[i]);
        if (r) {
            LOGE("playback submit failed: %s", strerror(-r));
            if (is_gone_errno(-r)) set_gone(h, "play submit", -r);
            goto fail;
        }
    }
    atomic_store_explicit(&h->play_active, 1, memory_order_release);
    r = 0;
    goto out;
fail:
    /* Undo everything so a later startPlayback can succeed. */
    atomic_store(&h->play.dead, 1);
    atomic_store(&h->fb.dead, 1);
    drain_stream_locked(h, &h->play);
    drain_stream_locked(h, &h->fb);
    free(h->pring);
    h->pring = NULL;
    h->play_started = 0;
out:
    pthread_cond_broadcast(&h->ctl_cond);
    pthread_mutex_unlock(&h->ctl);
    leave(h);
    return r;
}

JNIEXPORT jint JNICALL JNIFN(writePlayback)(JNIEnv *env, jclass cls, jlong hh,
                                            jbyteArray data, jint length)
{
    (void)cls;
    struct handle *h = enter(hh);
    if (!h) return -EBADF;
    int r;
    if (atomic_load(&h->gone)) {
        r = -ENODEV;
    } else if (atomic_load(&h->play_failed)) {
        r = -EIO;
    } else if (!atomic_load_explicit(&h->play_active, memory_order_acquire) || !data) {
        r = -EINVAL;
    } else {
        jsize alen = (*env)->GetArrayLength(env, data);
        if (length > alen) length = alen;
        if (length < 0) length = 0;
        uint32_t want = (uint32_t)length - (uint32_t)length % h->pframe;
        uint32_t fill = atomic_load_explicit(&h->pfill, memory_order_acquire);
        uint32_t n = h->pcap - fill;
        if (n > want) n = want;
        n -= n % h->pframe;
        if (n) {
            uint32_t first = h->pcap - h->pw < n ? h->pcap - h->pw : n;
            (*env)->GetByteArrayRegion(env, data, 0, (jsize)first, (jbyte *)h->pring + h->pw);
            if (n > first)
                (*env)->GetByteArrayRegion(env, data, (jsize)first, (jsize)(n - first),
                                           (jbyte *)h->pring);
            h->pw = (h->pw + n) % h->pcap;
            atomic_fetch_add_explicit(&h->pfill, n, memory_order_release);
        }
        if (want > n)
            atomic_fetch_add(&h->rejected, (int)((want - n) / h->pframe));
        r = (int)n;
    }
    leave(h);
    return r;
}

JNIEXPORT jint JNICALL JNIFN(startCapture)(JNIEnv *env, jclass cls, jlong hh, jint endpoint,
                                           jint maxPacketBytes, jint bInterval, jint frameBytes,
                                           jint sampleRate)
{
    (void)env; (void)cls;
    struct handle *h = enter(hh);
    if (!h) return -EBADF;
    if (!(endpoint & USB_DIR_IN) || frameBytes <= 0 || sampleRate <= 0 ||
        maxPacketBytes < frameBytes) {
        leave(h);
        return -EINVAL;
    }

    pthread_mutex_lock(&h->ctl);
    int r;
    if (h->cap_started) { r = -EBUSY; goto out; }
    if (atomic_load(&h->gone)) { r = -ENODEV; goto out; }
    h->cap_started = 1;

    int ppm = packets_per_ms(h, bInterval);
    pthread_mutex_lock(&h->cm);
    h->cframe = (uint32_t)frameBytes;
    h->ccap = ms_to_bytes(CAP_RING_MS, (uint32_t)sampleRate, h->cframe);
    h->cr = h->cfill = 0;
    h->cring = calloc(1, h->ccap);
    pthread_mutex_unlock(&h->cm);
    if (!h->cring) { r = -ENOMEM; goto fail; }

    r = alloc_stream(&h->cap, K_CAP, endpoint, CAP_URBS, CAP_MS_PER_URB * ppm, maxPacketBytes);
    if (r) goto fail;
    if ((r = ensure_thread(h))) goto fail;

    LOGI("startCapture ep=0x%02x maxPacket=%d bInterval=%d frameBytes=%d rate=%d pktPerMs=%d",
         endpoint, maxPacketBytes, bInterval, frameBytes, sampleRate, ppm);

    for (int i = 0; i < h->cap.nurbs; i++) {
        r = submit(h, h->cap.urbs[i]);
        if (r) {
            LOGE("capture submit failed: %s", strerror(-r));
            if (is_gone_errno(-r)) set_gone(h, "cap submit", -r);
            goto fail;
        }
    }
    atomic_store_explicit(&h->cap_active, 1, memory_order_release);
    r = 0;
    goto out;
fail:
    /* Undo everything so a later startCapture can succeed. The ring is freed
     * only after the drain: completing capture URBs push into it. */
    drain_stream_locked(h, &h->cap);
    pthread_mutex_lock(&h->cm);
    free(h->cring);
    h->cring = NULL;
    h->cr = h->cfill = 0;
    pthread_mutex_unlock(&h->cm);
    h->cap_started = 0;
out:
    pthread_cond_broadcast(&h->ctl_cond);
    pthread_mutex_unlock(&h->ctl);
    leave(h);
    return r;
}

JNIEXPORT jint JNICALL JNIFN(readCapture)(JNIEnv *env, jclass cls, jlong hh,
                                          jbyteArray buffer, jint maxLength, jint timeoutMs)
{
    (void)cls;
    struct handle *h = enter(hh);
    if (!h) return -EBADF;
    if (!atomic_load_explicit(&h->cap_active, memory_order_acquire) || !buffer) {
        leave(h);
        return atomic_load(&h->gone) ? -ENODEV : -EINVAL;
    }
    jsize alen = (*env)->GetArrayLength(env, buffer);
    if (maxLength > alen) maxLength = alen;
    if (maxLength < 0) maxLength = 0;
    uint32_t want = (uint32_t)maxLength - (uint32_t)maxLength % h->cframe;

    struct timespec dl;
    clock_gettime(CLOCK_MONOTONIC, &dl);
    if (timeoutMs > 0) {
        dl.tv_sec += timeoutMs / 1000;
        dl.tv_nsec += (long)(timeoutMs % 1000) * 1000000L;
        if (dl.tv_nsec >= 1000000000L) { dl.tv_sec++; dl.tv_nsec -= 1000000000L; }
    }

    int r;
    pthread_mutex_lock(&h->cm);
    while (h->cfill == 0 && !atomic_load(&h->closing) && !atomic_load(&h->gone) &&
           !atomic_load(&h->cap_failed) && timeoutMs > 0) {
        if (pthread_cond_timedwait(&h->cc, &h->cm, &dl) == ETIMEDOUT) break;
    }
    if (atomic_load(&h->closing)) {
        r = -EBADF;
    } else if (h->cfill == 0) {
        r = atomic_load(&h->gone) ? -ENODEV : atomic_load(&h->cap_failed) ? -EIO : 0;
    } else {
        uint32_t n = h->cfill < want ? h->cfill : want;
        uint32_t first = h->ccap - h->cr < n ? h->ccap - h->cr : n;
        (*env)->SetByteArrayRegion(env, buffer, 0, (jsize)first, (const jbyte *)h->cring + h->cr);
        if (n > first)
            (*env)->SetByteArrayRegion(env, buffer, (jsize)first, (jsize)(n - first),
                                       (const jbyte *)h->cring);
        h->cr = (h->cr + n) % h->ccap;
        h->cfill -= n;
        r = (int)n;
    }
    pthread_mutex_unlock(&h->cm);
    leave(h);
    return r;
}

JNIEXPORT jint JNICALL JNIFN(getState)(JNIEnv *env, jclass cls, jlong hh)
{
    (void)env; (void)cls;
    struct handle *h = enter(hh);
    if (!h) return -EBADF;
    /* -EIO: a started direction lost all its URBs (see mark_failed). */
    int r = atomic_load(&h->gone) ? -ENODEV
          : (atomic_load(&h->play_failed) || atomic_load(&h->cap_failed)) ? -EIO : 0;
    leave(h);
    return r;
}

static void format_stats(struct handle *h, char *buf, size_t len)
{
    double fbr = 0.0;
    int queued = 0;
    if (atomic_load_explicit(&h->play_active, memory_order_acquire)) {
        uint32_t fb = atomic_load(&h->fb_rate);
        fbr = (double)fb / 65536.0 * h->pppm;
        queued = (int)((uint64_t)atomic_load(&h->pfill) * 1000 / ((uint64_t)h->prate * h->pframe));
    }
    snprintf(buf, len, "underruns=%d overruns=%d driftDropped=%d rejected=%d errors=%d "
             "fbRate=%.2f queuedMs=%d",
             atomic_load(&h->underruns), atomic_load(&h->overruns),
             atomic_load(&h->drift_dropped), atomic_load(&h->rejected),
             atomic_load(&h->errors), fbr, queued);
}

JNIEXPORT jstring JNICALL JNIFN(getStats)(JNIEnv *env, jclass cls, jlong hh)
{
    (void)cls;
    char buf[256];
    struct handle *h = enter(hh);
    if (!h) return (*env)->NewStringUTF(env, "closed");
    format_stats(h, buf, sizeof(buf));
    leave(h);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL JNIFN(close)(JNIEnv *env, jclass cls, jlong hh)
{
    (void)env; (void)cls;
    struct handle *h = (struct handle *)(intptr_t)hh;
    if (!h || atomic_exchange(&h->closing, 1)) return;

    /* Wait until no JNI call is inside the handle; keep waking readers
     * in case one entered after the previous broadcast. */
    while (atomic_load(&h->users) > 0) {
        wake_readers(h);
        usleep(1000);
    }

    pthread_mutex_lock(&h->ctl);
    atomic_store(&h->stopping, 1);
    pthread_cond_broadcast(&h->ctl_cond);
    int started = h->thread_started;
    pthread_mutex_unlock(&h->ctl);

    /* URB memory stays valid until after the join; the reaper repeats this
     * pass once it observes 'stopping', then reaps until none are in flight. */
    discard_all(h);
    if (started) pthread_join(h->thread, NULL);

    char buf[256];
    format_stats(h, buf, sizeof(buf));
    LOGI("close: %s gone=%d playFailed=%d capFailed=%d", buf, atomic_load(&h->gone),
         atomic_load(&h->play_failed), atomic_load(&h->cap_failed));

    free_stream(&h->play);
    free_stream(&h->fb);
    free_stream(&h->cap);
    free(h->pring);
    free(h->cring);
    pthread_cond_destroy(&h->cc);
    pthread_mutex_destroy(&h->cm);
    pthread_cond_destroy(&h->ctl_cond);
    pthread_mutex_destroy(&h->ctl);
    free(h);
}
