/*
 * az100_patch.c -- in-process hot patch for OPPO's LE-Audio gate.
 *
 * Target
 * ------
 *     bool oplus_btif_is_lea_device(RawAddress& addr)     libbluetooth_jni.so + 0x643180
 *
 * That single function is the gate every native LEA decision goes through:
 * the EIR/LEA-bit paths, the encrypt-retry path, and -- through
 * oplus_handle_filter_uuid() -- the UUID filter that drops 184E/184F/1846/1844.
 * It answers "yes" only when four things line up:
 *
 *     1. bt_config.conf has broadcastVendorId
 *     2. bt_config.conf has broadcastProductId
 *     3. bt_config.conf has customLeaBitWritten == 1
 *     4. (vendor, product) appears in the hard-coded OplusLeAudioWhiteList
 *
 * (3) has no writer anywhere in libbluetooth_jni.so or liboplus_bt.so, and (4)
 * is a .rodata table holding only OPPO's own product ids.  So for a third-party
 * LE Audio headset the gate can never open by configuration.  We force the
 * answer at the instruction level instead -- in this process only, never on
 * disk, so nothing survives a reboot and no system file is touched.
 *
 * Patching strategy
 * -----------------
 * Original prologue (verified against the shipping libbluetooth_jni.so):
 *
 *     +0x00  d503245f   bti c
 *     +0x04  f800865e   str  x30, [x18], #0x8
 *     +0x08  d10483ff   sub  sp, sp, #0x120
 *
 * We deliberately leave `bti c` in place: the page is BTI-guarded, so it must
 * stay a valid indirect-branch landing pad.  Only the two words after it are
 * rewritten:
 *
 *     +0x04  52800020   mov  w0, #1
 *     +0x08  d65f03c0   ret
 *
 * The two stores are ordered so that a thread racing us never sees a broken
 * frame: installing `mov w0,#1` first leaves `str x30,[x18],#8` behind it, so
 * an unlucky observer still runs the genuine body (x18 / shadow-call-stack
 * bookkeeping intact) and merely returns the real value.  Only then is `ret`
 * dropped in.  Installing `ret` first would be fatal -- it would pop a return
 * without the matching shadow-stack push.
 *
 * Writing to a read-only text page: /proc/self/mem writes go through
 * access_remote_vm() with FOLL_FORCE, which copies the page on write.  That
 * avoids mprotect(PROT_WRITE|PROT_EXEC), which Android's W^X policy and the
 * SELinux execmod check both dislike.
 */

#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/mman.h>
#include <dlfcn.h>
#include <link.h>
#include <android/log.h>

#define TAG "AZ100-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define TARGET_LIB "libbluetooth_jni.so"
#define TARGET_OFF 0x643180UL

/* oplus_btif_is_lea_device prologue, as shipped.
 * Little-endian byte order: d503245f -> 5f 24 03 d5. */
static const uint8_t EXPECT[12] = {
    0x5f, 0x24, 0x03, 0xd5,   /* bti c            (d503245f) */
    0x5e, 0x86, 0x00, 0xf8,   /* str x30,[x18],#8 (f800865e) */
    0xff, 0x83, 0x04, 0xd1,   /* sub sp,sp,#0x120 (d10483ff) */
};

static const uint8_t MOV_W0_1[4] = { 0x20, 0x00, 0x80, 0x52 }; /* mov w0, #1 */
static const uint8_t RET[4]      = { 0xc0, 0x03, 0x5f, 0xd6 }; /* ret        */

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

static int phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    if (info->dlpi_name && strstr(info->dlpi_name, TARGET_LIB)) {
        *(uintptr_t *)data = (uintptr_t)info->dlpi_addr;
        return 1;   /* stop iterating */
    }
    return 0;
}

/*
 * Write into our own address space.  Uses /proc/self/mem rather than a raw
 * store so that the kernel performs a FOLL_FORCE copy-on-write of the text
 * page; no mprotect, no execmod.
 */
/*
 * Last resort: flip the page's own protection, store, flip it back.  Only used
 * when /proc/self/mem is denied -- SELinux blocks the bluetooth domain from
 * self:mem, so on this firmware the file route never works and this is the one
 * that runs.  The writable window is a few instructions wide; a thread that
 * faults on the page in that window would die, which is why the two stores are
 * ordered so the intermediate state is harmless (see the file header).
 */
static int poke_mprotect(void *dst, const void *src, size_t n) {
    long ps = sysconf(_SC_PAGESIZE);
    if (ps <= 0) ps = 4096;

    uintptr_t start = (uintptr_t)dst & ~(uintptr_t)(ps - 1);
    uintptr_t end   = ((uintptr_t)dst + n + (uintptr_t)ps - 1) & ~(uintptr_t)(ps - 1);
    size_t    len   = end - start;

    /* a) read-write-execute in one step, if the kernel allows it */
    if (mprotect((void *)start, len, PROT_READ | PROT_WRITE | PROT_EXEC) == 0) {
        memcpy(dst, src, n);
        __builtin___clear_cache((char *)dst, (char *)dst + n);
        if (mprotect((void *)start, len, PROT_READ | PROT_EXEC) == 0) {
            LOGI("poke via mprotect(RWX) at %p", dst);
            return 0;
        }
        LOGE("mprotect back to RX failed: %s", strerror(errno));
        return -11;
    }
    LOGE("mprotect(RWX) %p: %s", (void *)start, strerror(errno));

    /* b) read-write, store, then back to read-execute */
    if (mprotect((void *)start, len, PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect(RW) %p: %s", (void *)start, strerror(errno));
        return -12;
    }
    memcpy(dst, src, n);
    __builtin___clear_cache((char *)dst, (char *)dst + n);
    if (mprotect((void *)start, len, PROT_READ | PROT_EXEC) != 0) {
        LOGE("mprotect back to RX failed: %s", strerror(errno));
        return -13;
    }
    LOGI("poke via mprotect(RW->RX) at %p", dst);
    return 0;
}

static int poke(const void *dst, const void *src, size_t n) {
    int fd = open("/proc/self/mem", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LOGI("/proc/self/mem denied (%s) -> mprotect", strerror(errno));
        return poke_mprotect((void *)dst, src, n);
    }

    off_t off = (off_t)(uintptr_t)dst;
    if (lseek(fd, off, SEEK_SET) != off) {
        int e = errno;
        close(fd);
        LOGE("lseek(%p): %s -> mprotect", dst, strerror(e));
        return poke_mprotect((void *)dst, src, n);
    }

    ssize_t w = write(fd, src, n);
    int err = errno;
    close(fd);

    if (w != (ssize_t)n) {
        LOGE("write(%zu) to %p -> %zd (%s) -> mprotect", n, dst, w, strerror(err));
        return poke_mprotect((void *)dst, src, n);
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* JNI entry                                                           */
/* ------------------------------------------------------------------ */

/*
 * Returns
 *    0  patched now
 *    1  already patched (idempotent)
 *   -1  libbluetooth_jni.so not loaded yet
 *   -2  prologue does not match (wrong firmware revision) -- refuses to touch
 *   -3  /proc/self/mem write failed
 *   -4  write reported success but did not stick
 */
JNIEXPORT jint JNICALL
Java_com_tosasitill_az100_NativeLeaDeviceHook_nativePatch(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;

    uintptr_t base = 0;
    dl_iterate_phdr(phdr_cb, &base);
    if (base == 0) {
        LOGE("%s is not mapped", TARGET_LIB);
        return -1;
    }

    uint8_t *t = (uint8_t *)(base + TARGET_OFF);

    if (memcmp(t, EXPECT, sizeof EXPECT) != 0) {
        LOGE("prologue mismatch at %p: "
             "%02x %02x %02x %02x  %02x %02x %02x %02x  %02x %02x %02x %02x",
             t, t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7],
             t[8], t[9], t[10], t[11]);
        return -2;
    }

    if (memcmp(t + 4, MOV_W0_1, 4) == 0 && memcmp(t + 8, RET, 4) == 0) {
        LOGI("already patched at %p", t);
        return 1;
    }

    /* install the return value first, the `ret` second -- see the header */
    int r = poke(t + 4, MOV_W0_1, 4);
    if (r != 0) return r;

    r = poke(t + 8, RET, 4);
    if (r != 0) return r;

    __builtin___clear_cache((char *)t, (char *)t + 12);

    if (memcmp(t + 4, MOV_W0_1, 4) != 0 || memcmp(t + 8, RET, 4) != 0) {
        LOGE("write did not stick at %p", t);
        return -4;
    }

    LOGI("patched oplus_btif_is_lea_device @ %p (base=%p) -> mov w0,#1; ret",
         t, (void *)base);
    return 0;
}
