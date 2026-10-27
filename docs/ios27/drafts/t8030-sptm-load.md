# Milestone 0 draft — `sptm=` load path for t8030

> **Superseded.** The implemented version is Scion-Project/pyre#2. It reuses
> `apple_boot_load_monitor()`/`t8030_map_monitor()`, builds the boot info from the rev 3
> kernel boot-args plus the handoff pointer, and sets `CPACR_EL1.FPEN` at handoff
> (spec §2.6). This draft is kept for history.

Smallest change that gets **SPTM's first instructions executing** on the boot CPU,
the way iBoot would hand over. Kernels without `sptm=` are unchanged. This is the
code implemented on Inferno branch `ios27/sptm-load`; it is reproduced here so the
spec PR is self-contained.

Faithful hand-off (per GXF analysis, ios-on-pixel#13): SPTM is entered at **non-guarded
EL1 with the MMU off**, exactly like a kernel; SPTM enables GXF and installs itself.
So the only machinery needed is: load + map the SPTM Mach-O, build a minimal
`sptm_bootinfo`, and point the boot CPU's reset vector + `x0` at SPTM instead of the
kernel.

## Which existing hooks change (or get a twin)

| Hook today | SPTM change |
|---|---|
| `apple_boot_load_kernel()` (type `krnl`) | **twin** `apple_boot_load_sptm()` (type `sptm`) |
| `apple_boot_get_kc_bounds()` | reused as-is for SPTM's own bounds |
| `apple_boot_load_macho(hdr, as, mm, phys_base, slide)` | reused as-is; called a 2nd time for SPTM with its own `phys_base`, `slide=0` |
| `apple_boot_setup_monitor_boot_args()` (0x60, v4) | **twin** `apple_boot_setup_sptm_boot_args()` (≥0x488) |
| `g_phys_base/g_virt_base` (kernel-only) | untouched; SPTM is image-relative via `apple_boot_load_macho` |
| `phys_ptr` bump in `t8030_load_kernelcache` | extended to reserve SPTM image + bootinfo + handoff |
| `allocate_ram()` calls | untouched (SPTM lives in DRAM, not a new region) |
| reset vector in `t8030_cpu_reset` | boot CPU starts at `sptm_entry`/`x0=sptm_boot_args_addr` when `sptm` present |
| `t8030_kernel_uses_sptm` error gate | kept, but bypassed when `sptm=` is given |
| sio_protocol `switch` | **+`case 27`** (→ protocol 10) so a 27 kernel is accepted |

TXM (`txm=`, branch `ios27/txm-load`) is a sibling agent's change; the agreed layout is
below so both can coexist.

## `include/hw/arm/boot.h`

```c
/* AppleBootInfo: add near the kern_* fields */
    hwaddr   sptm_phys_base;
    uint64_t sptm_size;
    vaddr    sptm_entry;
    hwaddr   sptm_boot_args_addr;
    hwaddr   sptm_handoff_addr;

/* prototypes */
MachoHeader64* apple_boot_load_sptm(const char* filename);
void apple_boot_setup_sptm_boot_args(AddressSpace* as, hwaddr addr, hwaddr handoff_addr,
                                     uint64_t version, uint64_t field8, uint64_t field10,
                                     uint64_t field18);
```

## `hw/arm/boot.c`

```c
MachoHeader64* apple_boot_load_sptm(const char* filename)
{
    uint32_t       len;
    uint8_t*       data = NULL;
    char           payload_type[4];
    MachoHeader64* header;

    extract_im4p_payload(filename, payload_type, &data, &len, NULL);

    if (memcmp(payload_type, "sptm", 4) != 0 && memcmp(payload_type, "raw", 4) != 0) {
        error_setg(&error_fatal, "`%s` is a `%.4s` object (expected `sptm`)", filename,
                   payload_type);
        return NULL;
    }
    if (len < sizeof(MachoHeader64) || ((MachoHeader64*)data)->magic != MACH_MAGIC_64) {
        error_setg(&error_fatal, "`%s` does not contain a 64-bit Mach-O SPTM", filename);
        g_free(data);
        return NULL;
    }

    header = apple_boot_parse_macho(data, len);
    g_free(data);
    return header;
}

/* Minimal sptm_bootinfo: version at +0x00 (SPTM needs >= 3), the three words SPTM
 * reads into x22/x23/x24 at +0x08/+0x10/+0x18, and the handoff-region pointer at
 * +0x480 (SPTM zeroes 0x17c0 bytes there and stamps a tag). The 0x20..0x480 body
 * is still OPEN (region descriptors / PT pool); zeroed for now. */
void apple_boot_setup_sptm_boot_args(AddressSpace* as, hwaddr addr, hwaddr handoff_addr,
                                     uint64_t version, uint64_t field8, uint64_t field10,
                                     uint64_t field18)
{
    uint8_t* buf = g_malloc0(0x4000);

    stq_le_p(buf + 0x000, version);
    stq_le_p(buf + 0x008, field8);
    stq_le_p(buf + 0x010, field10);
    stq_le_p(buf + 0x018, field18);
    stq_le_p(buf + 0x480, handoff_addr);

    address_space_rw(as, addr, MEMTXATTRS_UNSPECIFIED, buf, 0x4000, true);
    g_free(buf);
}
```

## `hw/arm/t8030.c`

```c
/* property plumbing (with the other PROP_STR_GETTER_SETTER lines) */
PROP_STR_GETTER_SETTER(sptm_filename);
/* in t8030_class_init */
object_class_property_add_str(klass, "sptm", t8030_get_sptm_filename, t8030_set_sptm_filename);
object_class_property_set_description(klass, "sptm", "SPTM firmware (experimental)");
```

```c
/* t8030_init: replace the hard SPTM gate */
if (t8030_kernel_uses_sptm(t8030->kernel)) {
    if (t8030->sptm_filename == NULL) {
        error_setg(&error_fatal,
                   "This kernel boots through SPTM/TXM, which is not emulated yet "
                   "(upstream: ChefKissInc/Inferno#268). Pass `sptm=<sptm.im4p>` to "
                   "try the experimental SPTM load path.");
        return;
    }
    t8030->sptm = apple_boot_load_sptm(t8030->sptm_filename);
    info_report("SPTM loaded (experimental); it will run before the kernel.");
}

/* t8030_init: accept iOS 27 */
case 26:
case 27: t8030->sio_protocol = 10; break;
```

```c
/* t8030_load_kernelcache: right after
 *   phys_ptr = vtop_static(ROUND_UP_16K(kc_end + g_virt_slide));
 * and before the RAM Disk block */
if (t8030->sptm != NULL) {
    vaddr sp_text, sp_kc_base, sp_kc_end;
    hwaddr sptm_base = phys_ptr;

    apple_boot_get_kc_bounds(t8030->sptm, &sp_text, &sp_kc_base, &sp_kc_end, NULL, NULL);
    info->sptm_size      = sp_kc_end - sp_text;
    info->sptm_phys_base = sptm_base - (sp_text - sp_kc_base);
    info->sptm_entry     = apple_boot_load_macho(t8030->sptm, &address_space_memory, NULL,
                                                 info->sptm_phys_base, 0);
    phys_ptr = ROUND_UP_16K(sptm_base + info->sptm_size);

    info->sptm_handoff_addr = phys_ptr;
    phys_ptr += ROUND_UP_16K(0x2000);        /* >= 0x17c0, SPTM zeroes this */
    info->sptm_boot_args_addr = phys_ptr;
    phys_ptr += 0x4000;

    apple_boot_setup_sptm_boot_args(&address_space_memory, info->sptm_boot_args_addr,
                                    info->sptm_handoff_addr, 3,
                                    info->sptm_phys_base, g_virt_base, 0);

    info_report("SPTM Physical Base: 0x" HWADDR_FMT_plx, sptm_base);
    info_report("SPTM Entry (phys): 0x%016" VADDR_PRIx, info->sptm_entry);
}
```

```c
/* t8030_cpu_reset: boot CPU enters SPTM first when present */
if (t8030->securerom_filename == NULL) {
    vaddr  entry = t8030->boot_info.kern_entry;
    hwaddr ctx   = t8030->boot_info.kern_boot_args_addr;
    if (t8030->sptm != NULL) {
        entry = t8030->boot_info.sptm_entry;
        ctx   = t8030->boot_info.sptm_boot_args_addr;
    }
    if (acpu->cpu_id != A13_MAX_CPU) {
        object_property_set_uint(OBJECT(cpu), "rvbar", entry & ~0xFFFULL, &error_abort);
        cpu_reset(cpu);
    }
    if (acpu->cpu_id == 0) {
        arm_set_cpu_on(arm_cpu->mp_affinity, entry, ctx, 1, true);
    }
}
```

## Agreed physical layout with the TXM agent

Carved from the kernel-static (`phys_ptr`) area, growing up from `DRAM_BASE`, below the
ramdisk/TZ0/SEPFW block:

```
  [ kernel image ]                        DRAM_BASE + phys_slide ...
  [ SPTM image ]        span 0x114000     sptm_base              (16K aligned)
  [ SPTM handoff ]      0x2000            sptm_handoff_addr
  [ SPTM boot-args ]    0x4000            sptm_boot_args_addr
  [ TXM image ]         span 0x8c000      txm_base   (TXM agent)
  [ TXM boot-args ]     0x4000            txm_boot_args_addr (TXM agent)
  [ ramdisk / TZ0 / SEPFW / kern boot-args / DT ]  (existing)
```

SPTM VA window `…27004000`, TXM VA window `…17004000`, kernel `…07004000` — disjoint,
all resident. The TXM agent owns everything below the `[ TXM image ]` line; SPTM owns
above. Neither edits the other's code.

## How the milestone is proven

`-d in_asm,exec -D log` (or a gdbstub) shows the boot CPU fetching at SPTM's entry
(`sptm_base + 0x94450`), taking the `b` to `_start`, masking DAIF, reading the bootinfo
(`x0`), passing the `version >= 3` gate, stamping the handoff region, then proceeding
into early MMU setup. It is expected to stop there (bootinfo body OPEN, and possibly an
`S3_x_C15_*` UNDEF until `ios27/gxf` merges) — reaching SPTM `_start` is the milestone.
