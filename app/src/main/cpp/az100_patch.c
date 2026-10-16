/*
 * az100_patch.c -- in-process hot patches for OPPO's LE-Audio gates.
 *
 * Two independent targets, both in libbluetooth_jni.so, both keyed on the peer
 * address so that no other device's behaviour changes:
 *
 *   1. oplus_btif_is_lea_device(RawAddress const&)             + 0x643180
 *      The gate every native LE-Audio decision goes through: the EIR/LEA-bit
 *      paths, the encrypt-retry path, and -- through oplus_handle_filter_uuid()
 *      -- the UUID filter that drops 184E/184F/1846/1844.  It answers "yes"
 *      only when bt_config.conf carries broadcastVendorId, broadcastProductId
 *      and customLeaBitWritten == 1, *and* (vendor, product) appears in the
 *      hard-coded OplusLeAudioWhiteList.  customLeaBitWritten has no writer
 *      anywhere in libbluetooth_jni.so or liboplus_bt.so, and the whitelist is
 *      a .rodata table of OPPO's own product ids, so for a third-party headset
 *      the gate can never open by configuration.
 *
 *   2. oplus_handle_le_audio_ssp_auto_consent(RawAddress const&) + 0x65ce10
 *      The per-device LE pairing consent decision.  It returns true to let the
 *      stack raise the consent dialog, and for OPPO's own LE-Audio earbuds it
 *      answers the request itself (btif_dm_ssp_reply(addr, CONSENT, 1)) and
 *      returns false so no dialog appears.  A third-party headset never takes
 *      that branch, because btif_check_cod_le_audio() fails on its
 *      class-of-device.
 *
 * Answering per address, not for everyone
 * ---------------------------------------
 * Earlier revisions of this file patched both problems with a blanket answer:
 * `mov w0,#1; ret` at the gate, and a Java-side spoof of
 * `hasSystemFeature("android.software.leanback_only")` to suppress the consent
 * dialog.  Both were process-global.  The gate patch opened the gate for every
 * peer the stack ever asks about, and the leanback spoof is worse than it
 * looks: AdapterService.init reads that feature once
 * (AdapterService.java:3219) and hands the answer to native as a startup
 * global, so the whole Bluetooth process believes it is an Android TV for its
 * entire lifetime -- and it cannot be narrowed in time, because at process
 * start no device is pairing yet.
 *
 * Both functions take the peer address as their first argument, so the address
 * is exactly the discriminator needed.  Each is given a comparison stub at its
 * entry that answers for the AZ100's two addresses and falls through to the
 * untouched original body for everything else.  OPPO's own earbuds therefore
 * keep whatever answer they got before, and so does every other device.
 *
 * Call sites (verified by disassembly):
 *
 *   oplus_btif_is_lea_device -- 7 direct callers, all passing the address in x0:
 *     0x64fde8  0x64ff14   bta_hh_le_open_conn(tBTA_HH_DEV_CB*, bool)   cb+0x29
 *     0x6501d4             bta_hh_le_add_dev_bg_conn(tBTA_HH_DEV_CB*)    cb+0x29
 *     0x650bd8             bta_hh_le_open_cmpl(tBTA_HH_DEV_CB*)          cb+0x29
 *     0x652eec             bta_hh_le_open_fail(tBTA_HH_DEV_CB*, ...)     the RawAddress
 *     0x65c2fc             oplus_handle_filter_uuid(RawAddress const&, Uuid const&)
 *     0x785fec             oplus_start_lea_force_discover(RawAddress const&, bool, bool)
 *
 *   oplus_handle_le_audio_ssp_auto_consent -- 2 callers, both pairing events:
 *     0x84884c             btif_dm_sec_evt(tBTA_DM_SEC_EVT, tBTA_DM_SEC*)
 *     0x84bd3c             btif_dm_ble_sec_req_evt(tBTA_DM_BLE_SEC_REQ*, bool)
 *
 * Patching strategy
 * -----------------
 * Only the word at +0x04 is rewritten, in both functions: the `bti c` at +0x00
 * stays (the page is BTI-guarded, so it must remain a valid indirect-branch
 * landing pad) and everything from +0x08 on stays byte-for-byte intact.  +0x04
 * becomes a branch into the cave:
 *
 *     +0x00  d503245f   bti c                     (kept)
 *     +0x04  14xxxxxx   b    stub                 (was: str x30,[x18],#8)
 *     +0x08  ...        dead but intact
 *
 * The displaced prologue instructions are replayed by the stub's miss path, so
 * the original body still runs with its shadow-call-stack push and stack frame
 * intact.  Because only one aligned 32-bit store is involved, and AArch64 makes
 * those single-copy atomic, a thread executing the function concurrently sees
 * either the whole old instruction or the whole new one -- never a torn mix.
 *
 * The cave is the page tail of the executable segment.  The last PT_LOAD
 * carrying PF_X is
 *
 *     LOAD  off 0x3a8000  vaddr 0x3a8000  filesz 0xdb3a80  memsz 0xdb3a80  R E
 *
 * so its file content ends at 0x115ba80 while the loader still maps (and
 * zero-fills) through the end of that page, 0x115c000 -- 0x580 bytes of
 * executable, all-zero padding.  Confirmed on the device: the running process
 * maps 0x115ba80..0x115c000 as part of an r-xp VMA.  validate_cave() refuses to
 * patch unless the region really is the beyond-filesz tail of an executable
 * segment and really is all zeros.
 *
 * Writing to a read-only text page: /proc/self/mem writes go through
 * access_remote_vm() with FOLL_FORCE, which copies the page on write.  That
 * avoids mprotect(PROT_WRITE|PROT_EXEC), which Android's W^X policy and the
 * SELinux execmod check both dislike.  On this firmware SELinux denies the
 * bluetooth domain self:mem, so the mprotect fallback below is the path that
 * actually runs.  That is also why the cave is filled before the entry is
 * switched: the writable window is only as wide as one store to the entry.
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

/* Target 1: the LE-Audio gate. */
#define GATE_OFF   0x643180UL

/* Target 2: the per-device LE pairing consent decision. */
#define CONSENT_OFF 0x65ce10UL

/* btif_dm_ssp_reply(RawAddress, PairingVariant, unsigned char) */
#define SSP_REPLY_OFF 0x8500f0UL

/* Page tail of the executable segment; see the header. */
#define CAVE_OFF   0x115ba80UL
#define CAVE_LEN   0x580UL

/* One slot per stub, both inside the cave. */
#define SLOT_GATE    (CAVE_OFF + 0x000UL)
#define SLOT_CONSENT (CAVE_OFF + 0x100UL)

/* ------------------------------------------------------------------ */
/* AArch64 encoders                                                    */
/*                                                                     */
/* Hand-encoded rather than compiled so each stub is a plain constant   */
/* array with no relocations: every branch in it is between two fixed   */
/* offsets from the library base, so the whole thing is position-       */
/* independent and known at compile time.                               */
/* ------------------------------------------------------------------ */

#define A64_LDR_W(t, n, off)  (0xB9400000u | ((((off) / 4) & 0xFFF) << 10) | ((n) << 5) | (t))
#define A64_LDRH_W(t, n, off) (0x79400000u | ((((off) / 2) & 0xFFF) << 10) | ((n) << 5) | (t))
#define A64_MOVZ_W(d, imm)    (0x52800000u | (((imm) & 0xFFFF) << 5) | (d))
#define A64_MOVK_W16(d, imm)  (0x72A00000u | (((imm) & 0xFFFF) << 5) | (d))
#define A64_CMP_W(n, m)       (0x6B00001Fu | ((m) << 16) | ((n) << 5))
#define A64_RET               (0xD65F03C0u)

/* orr xd, xn, xm, lsl #32 */
#define A64_ORR_X_LSL32(d, n, m) (0xAA000000u | ((m) << 16) | (32u << 10) | ((n) << 5) | (d))

/* Shadow-call-stack push/pop, as emitted by the compiler. */
#define A64_PUSH_X30 (0xF800865Eu)   /* str x30, [x18], #0x8  */
#define A64_POP_X30  (0xF85F8E5Eu)   /* ldr x30, [x18, #-0x8]! */

#define A64_B_NE(f, t) (0x54000001u | ((((int)(t) - (int)(f)) & 0x7FFFF) << 5))
#define A64_B_EQ(f, t) (0x54000000u | ((((int)(t) - (int)(f)) & 0x7FFFF) << 5))
#define A64_B(f, t)    (0x14000000u | (((int)(t) - (int)(f)) & 0x03FFFFFF))
#define A64_BL(f, t)   (0x94000000u | (((int)(t) - (int)(f)) & 0x03FFFFFF))

/*
 * Long jumps, addressed in bytes from the library base rather than by
 * instruction index.  The signed difference is taken as `long` before masking,
 * so a backwards jump cannot wrap through unsigned arithmetic.
 */
#define A64_B_ABS(from_off, to_off)  \
    (0x14000000u | (unsigned)((((long)(to_off) - (long)(from_off)) / 4) & 0x03FFFFFFL))
#define A64_BL_ABS(from_off, to_off) \
    (0x94000000u | (unsigned)((((long)(to_off) - (long)(from_off)) / 4) & 0x03FFFFFFL))

/*
 * The AZ100's two addresses.  RawAddress stores the octets
 * least-significant first, so the string B8:20:8E:EE:84:85 is
 * {0x85,0x84,0xEE,0x8E,0x20,0xB8} in memory: a 32-bit little-endian load
 * yields 0x8EEE8485 and a 16-bit load at +4 yields 0xB820.  Both byte orders
 * are accepted so that a firmware that flips the convention cannot silently
 * turn the patch into a no-op.
 *
 *   B8:20:8E:EE:84:85   LE  w8=0x8EEE8485 w9=0xB820   BE  w8=0xEE8E20B8 w9=0x8584
 *   B8:20:8E:EE:3C:62   LE  w8=0x8EEE3C62 w9=0xB820   BE  w8=0xEE8E20B8 w9=0x623C
 */

/* ------------------------------------------------------------------ */
/* stub 1 -- the LE-Audio gate                                         */
/*                                                                     */
/* x0 = RawAddress const* (pointer).  On a hit, answer true without      */
/* touching the frame at all.                                           */
/* ------------------------------------------------------------------ */

enum {
    S_LDR_W8, S_LDRH_W9, S_MOVZ_B820, S_CMP_W9_LE, S_BNE_BE,
    S_MOVZ_8485, S_MOVK_8EEE, S_CMP_W8_M1LE, S_BEQ_HIT1,
    S_MOVZ_3C62, S_MOVK_8EEE2, S_CMP_W8_M2LE, S_BEQ_HIT2, S_B_ORIG_LE,
    S_BE_MOVZ_20B8, S_BE_MOVK_EE8E, S_BE_CMP_W8, S_BE_BNE_ORIG,
    S_BE_MOVZ_8584, S_BE_CMP_W9_M1, S_BE_BEQ_HIT3,
    S_BE_MOVZ_623C, S_BE_CMP_W9_M2, S_BE_BEQ_HIT4, S_BE_B_ORIG,
    S_HIT_MOV_W0_1, S_HIT_RET,
    S_ORIG_STR, S_ORIG_SUB, S_ORIG_B_BODY,
    S_COUNT
};

#define GATE_HIT   S_HIT_MOV_W0_1
#define GATE_ORIG  S_ORIG_STR
#define GATE_BE    S_BE_MOVZ_20B8

static const uint32_t GATE_STUB[S_COUNT] = {
    [S_LDR_W8]       = A64_LDR_W(8, 0, 0),
    [S_LDRH_W9]      = A64_LDRH_W(9, 0, 4),
    [S_MOVZ_B820]    = A64_MOVZ_W(10, 0xB820),
    [S_CMP_W9_LE]    = A64_CMP_W(9, 10),
    [S_BNE_BE]       = A64_B_NE(S_BNE_BE, GATE_BE),
    [S_MOVZ_8485]    = A64_MOVZ_W(10, 0x8485),
    [S_MOVK_8EEE]    = A64_MOVK_W16(10, 0x8EEE),
    [S_CMP_W8_M1LE]  = A64_CMP_W(8, 10),
    [S_BEQ_HIT1]     = A64_B_EQ(S_BEQ_HIT1, GATE_HIT),
    [S_MOVZ_3C62]    = A64_MOVZ_W(10, 0x3C62),
    [S_MOVK_8EEE2]   = A64_MOVK_W16(10, 0x8EEE),
    [S_CMP_W8_M2LE]  = A64_CMP_W(8, 10),
    [S_BEQ_HIT2]     = A64_B_EQ(S_BEQ_HIT2, GATE_HIT),
    [S_B_ORIG_LE]    = A64_B(S_B_ORIG_LE, GATE_ORIG),
    [S_BE_MOVZ_20B8] = A64_MOVZ_W(10, 0x20B8),
    [S_BE_MOVK_EE8E] = A64_MOVK_W16(10, 0xEE8E),
    [S_BE_CMP_W8]    = A64_CMP_W(8, 10),
    [S_BE_BNE_ORIG]  = A64_B_NE(S_BE_BNE_ORIG, GATE_ORIG),
    [S_BE_MOVZ_8584] = A64_MOVZ_W(10, 0x8584),
    [S_BE_CMP_W9_M1] = A64_CMP_W(9, 10),
    [S_BE_BEQ_HIT3]  = A64_B_EQ(S_BE_BEQ_HIT3, GATE_HIT),
    [S_BE_MOVZ_623C] = A64_MOVZ_W(10, 0x623C),
    [S_BE_CMP_W9_M2] = A64_CMP_W(9, 10),
    [S_BE_BEQ_HIT4]  = A64_B_EQ(S_BE_BEQ_HIT4, GATE_HIT),
    [S_BE_B_ORIG]    = A64_B(S_BE_B_ORIG, GATE_ORIG),
    [S_HIT_MOV_W0_1] = A64_MOVZ_W(0, 1),
    [S_HIT_RET]      = A64_RET,
    [S_ORIG_STR]     = A64_PUSH_X30,
    [S_ORIG_SUB]     = 0xD10483FFu,  /* sub sp, sp, #0x120 */
    /* Relative, so base-independent: from the cave back to the original body. */
    [S_ORIG_B_BODY]  = A64_B_ABS(SLOT_GATE + S_ORIG_B_BODY * 4, GATE_OFF + 0x0C),
};

/* The single word that replaces the gate's +0x04. */
#define GATE_BRANCH (A64_B_ABS(GATE_OFF + 4, SLOT_GATE))

/* ------------------------------------------------------------------ */
/* stub 2 -- the LE pairing consent                                    */
/*                                                                     */
/* x0 = RawAddress const* (pointer).  On a hit, answer the consent the   */
/* same way OPPO answers it for its own earbuds:                         */
/*                                                                       */
/*     btif_dm_ssp_reply(addr, PairingVariant::CONSENT, 1);              */
/*     return false;      // "handled -- do not raise the dialog"        */
/*                                                                       */
/* btif_dm_ssp_reply takes RawAddress *by value*, so the six octets are  */
/* packed back into x0 before the call.                                  */
/* ------------------------------------------------------------------ */

enum {
    C_LDR_W8, C_LDRH_W9, C_MOVZ_B820, C_CMP_W9_LE, C_BNE_BE,
    C_MOVZ_8485, C_MOVK_8EEE, C_CMP_W8_M1LE, C_BEQ_HIT1,
    C_MOVZ_3C62, C_MOVK_8EEE2, C_CMP_W8_M2LE, C_BEQ_HIT2, C_B_ORIG_LE,
    C_BE_MOVZ_20B8, C_BE_MOVK_EE8E, C_BE_CMP_W8, C_BE_BNE_ORIG,
    C_BE_MOVZ_8584, C_BE_CMP_W9_M1, C_BE_BEQ_HIT3,
    C_BE_MOVZ_623C, C_BE_CMP_W9_M2, C_BE_BEQ_HIT4, C_BE_B_ORIG,
    C_HIT_ORR, C_HIT_MOV_W1_2, C_HIT_MOV_W2_1,
    C_HIT_PUSH, C_HIT_BL, C_HIT_POP, C_HIT_MOV_W0_0, C_HIT_RET,
    C_ORIG_PUSH, C_ORIG_STP, C_ORIG_STR19, C_ORIG_MOV_FP, C_ORIG_MOV19,
    C_ORIG_B_BODY,
    C_COUNT
};

#define CONSENT_HIT   C_HIT_ORR
#define CONSENT_ORIG  C_ORIG_PUSH
#define CONSENT_BE    C_BE_MOVZ_20B8

static const uint32_t CONSENT_STUB[C_COUNT] = {
    [C_LDR_W8]       = A64_LDR_W(8, 0, 0),
    [C_LDRH_W9]      = A64_LDRH_W(9, 0, 4),
    [C_MOVZ_B820]    = A64_MOVZ_W(10, 0xB820),
    [C_CMP_W9_LE]    = A64_CMP_W(9, 10),
    [C_BNE_BE]       = A64_B_NE(C_BNE_BE, CONSENT_BE),
    [C_MOVZ_8485]    = A64_MOVZ_W(10, 0x8485),
    [C_MOVK_8EEE]    = A64_MOVK_W16(10, 0x8EEE),
    [C_CMP_W8_M1LE]  = A64_CMP_W(8, 10),
    [C_BEQ_HIT1]     = A64_B_EQ(C_BEQ_HIT1, CONSENT_HIT),
    [C_MOVZ_3C62]    = A64_MOVZ_W(10, 0x3C62),
    [C_MOVK_8EEE2]   = A64_MOVK_W16(10, 0x8EEE),
    [C_CMP_W8_M2LE]  = A64_CMP_W(8, 10),
    [C_BEQ_HIT2]     = A64_B_EQ(C_BEQ_HIT2, CONSENT_HIT),
    [C_B_ORIG_LE]    = A64_B(C_B_ORIG_LE, CONSENT_ORIG),
    [C_BE_MOVZ_20B8] = A64_MOVZ_W(10, 0x20B8),
    [C_BE_MOVK_EE8E] = A64_MOVK_W16(10, 0xEE8E),
    [C_BE_CMP_W8]    = A64_CMP_W(8, 10),
    [C_BE_BNE_ORIG]  = A64_B_NE(C_BE_BNE_ORIG, CONSENT_ORIG),
    [C_BE_MOVZ_8584] = A64_MOVZ_W(10, 0x8584),
    [C_BE_CMP_W9_M1] = A64_CMP_W(9, 10),
    [C_BE_BEQ_HIT3]  = A64_B_EQ(C_BE_BEQ_HIT3, CONSENT_HIT),
    [C_BE_MOVZ_623C] = A64_MOVZ_W(10, 0x623C),
    [C_BE_CMP_W9_M2] = A64_CMP_W(9, 10),
    [C_BE_BEQ_HIT4]  = A64_B_EQ(C_BE_BEQ_HIT4, CONSENT_HIT),
    [C_BE_B_ORIG]    = A64_B(C_BE_B_ORIG, CONSENT_ORIG),
    [C_HIT_ORR]      = A64_ORR_X_LSL32(0, 8, 9),   /* orr x0, x8, x9, lsl #32 */
    [C_HIT_MOV_W1_2] = A64_MOVZ_W(1, 2),           /* PairingVariant::CONSENT */
    [C_HIT_MOV_W2_1] = A64_MOVZ_W(2, 1),           /* accept                  */
    [C_HIT_PUSH]     = A64_PUSH_X30,
    [C_HIT_BL]       = A64_BL_ABS(SLOT_CONSENT + C_HIT_BL * 4, SSP_REPLY_OFF),
    [C_HIT_POP]      = A64_POP_X30,
    [C_HIT_MOV_W0_0] = A64_MOVZ_W(0, 0),
    [C_HIT_RET]      = A64_RET,
    /* Replay the displaced prologue, then resume at +0x18. */
    [C_ORIG_PUSH]    = A64_PUSH_X30,
    [C_ORIG_STP]     = 0xA9BE7BFDu,  /* stp x29, x30, [sp, #-0x20]! */
    [C_ORIG_STR19]   = 0xF9000BF3u,  /* str x19, [sp, #0x10]        */
    [C_ORIG_MOV_FP]  = 0x910003FDu,  /* mov x29, sp                 */
    [C_ORIG_MOV19]   = 0xAA0003F3u,  /* mov x19, x0                 */
    [C_ORIG_B_BODY]  = A64_B_ABS(SLOT_CONSENT + C_ORIG_B_BODY * 4, CONSENT_OFF + 0x18),
};

#define CONSENT_BRANCH (A64_B_ABS(CONSENT_OFF + 4, SLOT_CONSENT))

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

#define MAX_EXEC_SEGS 8

struct target {
    uintptr_t base;
    int found;
    int nseg;
    struct {
        uintptr_t file_end;  /* p_vaddr + p_filesz */
        uintptr_t page_end;  /* PAGE_END(p_vaddr + span) */
    } exec[MAX_EXEC_SEGS];
};

static int phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    if (!info->dlpi_name || !strstr(info->dlpi_name, TARGET_LIB)) return 0;

    struct target *t = (struct target *)data;
    long ps = sysconf(_SC_PAGESIZE);
    if (ps <= 0) ps = 4096;

    t->base = (uintptr_t)info->dlpi_addr;
    t->found = 1;

    for (int i = 0; i < info->dlpi_phnum && t->nseg < MAX_EXEC_SEGS; i++) {
        const ElfW(Phdr) *p = &info->dlpi_phdr[i];
        if (p->p_type != PT_LOAD || !(p->p_flags & PF_X)) continue;
        uintptr_t seg = (uintptr_t)info->dlpi_addr + (uintptr_t)p->p_vaddr;
        uintptr_t span = p->p_filesz > p->p_memsz ? p->p_filesz : p->p_memsz;
        t->exec[t->nseg].file_end = seg + (uintptr_t)p->p_filesz;
        t->exec[t->nseg].page_end =
            (seg + (uintptr_t)span + (uintptr_t)ps - 1) & ~(uintptr_t)(ps - 1);
        t->nseg++;
    }
    return 1;   /* stop iterating */
}

/*
 * A slot is only usable if it sits in the gap between the end of an executable
 * segment's file content and the end of the page the loader mapped for it.
 * Anything else -- past the page, or inside real code -- is refused.
 */
static int validate_slot(const struct target *t, const uint8_t *slot, size_t len) {
    for (int i = 0; i < t->nseg; i++) {
        if ((uintptr_t)slot >= t->exec[i].file_end &&
            (uintptr_t)slot + len <= t->exec[i].page_end) {
            for (size_t k = 0; k < len; k++) {
                if (slot[k] != 0) {
                    LOGE("cave slot %p is not zero-filled (byte %zu = %02x)",
                         slot, k, slot[k]);
                    return -5;
                }
            }
            return 0;
        }
    }
    LOGE("cave slot %p is outside every executable segment's padding", slot);
    return -5;
}

/*
 * Last resort: flip the page's own protection, store, flip it back.  Only used
 * when /proc/self/mem is denied -- SELinux blocks the bluetooth domain from
 * self:mem, so on this firmware the file route never works and this is the one
 * that runs.  The writable window is a few instructions wide; a thread that
 * faults on the page in that window would die, which is why the cave is filled
 * before the entry is switched, and the switch itself is one atomic store.
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

/*
 * Shared install path.  Returns
 *    0  patched now
 *    1  already patched with this stub (idempotent)
 *   -1  libbluetooth_jni.so not loaded yet
 *   -2  prologue does not match (wrong firmware revision) -- refuses to touch
 *   -3  /proc/self/mem write failed
 *   -4  write reported success but did not stick
 *   -5  no usable cave slot (refuses to patch rather than corrupt .text)
 */
static int install_stub(struct target *t, uintptr_t entry_off, uintptr_t slot_off,
                        const uint32_t *stub, size_t stub_words,
                        uint32_t expect_word4, const char *what) {
    uint8_t *entry = (uint8_t *)(t->base + entry_off);
    uint32_t branch = 0;

    switch (slot_off) {
        case SLOT_GATE:    branch = GATE_BRANCH;    break;
        case SLOT_CONSENT: branch = CONSENT_BRANCH; break;
        default:           return -2;
    }

    uint32_t word4 = 0;
    memcpy(&word4, entry + 4, 4);

    /*
     * Check for an installed patch *before* the prologue test: once patched,
     * the prologue no longer matches, so testing it first would make this
     * branch unreachable and report a false -2 on every call after the first.
     */
    if (word4 == branch) {
        LOGI("%s: already patched at %p", what, entry);
        return 1;
    }

    if (memcmp(entry, "\x5f\x24\x03\xd5", 4) != 0) {   /* bti c */
        LOGE("%s: prologue mismatch at %p: %08x", what, entry, *(const uint32_t *)entry);
        return -2;
    }

    /* Accept the earlier blanket patch (mov w0,#1; ret) as a valid starting
     * point -- we are replacing exactly that. */
    uint32_t word8 = 0;
    memcpy(&word8, entry + 8, 4);
    int replacing_blanket = (word4 == 0x52800020u && word8 == 0xD65F03C0u);
    if (replacing_blanket) {
        LOGI("%s: replacing the earlier blanket patch at %p", what, entry);
    } else if (word4 != expect_word4) {
        LOGE("%s: prologue mismatch at %p: %08x %08x", what, entry, word4, word8);
        return -2;
    }

    uint8_t *slot = (uint8_t *)(t->base + slot_off);
    int v = validate_slot(t, slot, stub_words * 4);
    if (v != 0) return v;

    /*
     * Fill the slot completely first.  Nothing branches into it yet, so a
     * thread running the original function is unaffected by these writes.
     */
    int r = poke(slot, stub, stub_words * 4);
    if (r != 0) return r;

    if (memcmp(slot, stub, stub_words * 4) != 0) {
        LOGE("%s: cave write did not stick at %p", what, slot);
        return -4;
    }

    /* Now the one-word switch: entry+4 -> cave. */
    r = poke(entry + 4, &branch, 4);
    if (r != 0) return r;

    __builtin___clear_cache((char *)entry, (char *)entry + 12);

    uint32_t check = 0;
    memcpy(&check, entry + 4, 4);
    if (check != branch) {
        LOGE("%s: write did not stick at %p", what, entry);
        return -4;
    }

    LOGI("%s: patched @ %p (base=%p) -> address gate, stub @ %p",
         what, entry, (void *)t->base, slot);
    return 0;
}

static int locate(struct target *t) {
    memset(t, 0, sizeof *t);
    dl_iterate_phdr(phdr_cb, t);
    if (!t->found) {
        LOGE("%s is not mapped", TARGET_LIB);
        return -1;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* JNI entries                                                         */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_com_tosasitill_az100_NativeLeaDeviceHook_nativePatch(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;

    struct target t;
    int r = locate(&t);
    if (r != 0) return r;

    return install_stub(&t, GATE_OFF, SLOT_GATE,
                        GATE_STUB, S_COUNT,
                        A64_PUSH_X30,          /* str x30, [x18], #0x8 */
                        "oplus_btif_is_lea_device");
}

JNIEXPORT jint JNICALL
Java_com_tosasitill_az100_NativeLeaDeviceHook_nativeConsentPatch(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;

    struct target t;
    int r = locate(&t);
    if (r != 0) return r;

    return install_stub(&t, CONSENT_OFF, SLOT_CONSENT,
                        CONSENT_STUB, C_COUNT,
                        A64_PUSH_X30,          /* str x30, [x18], #0x8 */
                        "oplus_handle_le_audio_ssp_auto_consent");
}
