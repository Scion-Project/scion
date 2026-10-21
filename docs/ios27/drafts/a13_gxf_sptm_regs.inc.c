/*
 * Reference snippet: the SPTM/TXM register block as implemented in
 * dylanneve1/Inferno PR (branch ios27/gxf), hw/arm/a13_gxf.c. Backing fields
 * live in env->gxf (target/arm/cpu.h). Registered from apple_a13_init_gxf()
 * via define_arm_cp_regs(cpu, apple_a13_sptm_cp_reginfo).
 *
 * iOS 27 SPTM / TXM / kernel touch a handful of Apple system registers that
 * the GL1 GXF model above does not define. An EL1 access to an undefined
 * Apple sysreg raises UNDEF, which crashes the monitors on first touch, so
 * these are defined as plain RW storage (reset 0, read returns last written
 * value). Their exact meaning is not modelled yet (see docs/ios27/GXF-GAP.md),
 * so every access is logged with LOG_UNIMP to reveal, on the first real boot,
 * which ones the firmware depends on and in what order. No poll-and-wait
 * dependency on any of these was observed in the 27.0.1 images; if one turns
 * out to be polled it should graduate to a register with real behaviour.
 */
static uint64_t sptm_impdef_read(CPUARMState* env, const ARMCPRegInfo* ri)
{
    uint64_t value = raw_read(env, ri);
    qemu_log_mask(LOG_UNIMP, "GXF/SPTM: read unmodelled Apple reg %s => 0x%" PRIx64 "\n", ri->name, value);
    return value;
}

static void sptm_impdef_write(CPUARMState* env, const ARMCPRegInfo* ri, uint64_t value)
{
    qemu_log_mask(LOG_UNIMP, "GXF/SPTM: write unmodelled Apple reg %s <= 0x%" PRIx64 "\n", ri->name, value);
    raw_write(env, ri, value);
}

#define SPTM_IMPDEF_REG(nm, op1_, crm_, op2_, field)                                                 \
    {                                                                                                \
        .name        = nm,                                                                           \
        .cp          = CP_REG_ARM64_SYSREG_CP,                                                        \
        .state       = ARM_CP_STATE_AA64,                                                            \
        .opc0        = 3,                                                                            \
        .opc1        = (op1_),                                                                       \
        .crn         = 15,                                                                           \
        .crm         = (crm_),                                                                       \
        .opc2        = (op2_),                                                                       \
        .access      = PL1_RW,                                                                       \
        .resetvalue  = 0,                                                                            \
        .readfn      = sptm_impdef_read,                                                             \
        .writefn     = sptm_impdef_write,                                                            \
        .raw_readfn  = raw_read,                                                                     \
        .raw_writefn = raw_write,                                                                    \
        .fieldoffset = offsetof(CPUARMState, field),                                                 \
    }

static const ARMCPRegInfo apple_a13_sptm_cp_reginfo[] = {
    /* GXF-space (op0=3, op1=6, CRn=15) regs the kernel/SPTM use beyond the
     * GL1 set above. Named by encoding; semantics OPEN. */
    SPTM_IMPDEF_REG("S3_6_C15_C0_0", 6, 0, 0, gxf.gxf_ctl_misc[0]),
    SPTM_IMPDEF_REG("S3_6_C15_C2_0", 6, 2, 0, gxf.gxf_ctl_misc[1]),
    SPTM_IMPDEF_REG("S3_6_C15_C8_7", 6, 8, 7, gxf.gxf_ctl_misc[2]),
    /* Apple EL2 impl-defined block (op0=3, op1=4, CRn=15). Reached from EL1
     * on Apple cores (seen in both the non-guarded kernel and guarded SPTM),
     * so PL1_RW rather than PL2_RW. Named by encoding; semantics OPEN. */
    SPTM_IMPDEF_REG("S3_4_C15_C0_0", 4, 0, 0, gxf.apple_el2_impdef[0]),
    SPTM_IMPDEF_REG("S3_4_C15_C0_1", 4, 0, 1, gxf.apple_el2_impdef[1]),
    SPTM_IMPDEF_REG("S3_4_C15_C0_2", 4, 0, 2, gxf.apple_el2_impdef[2]),
    SPTM_IMPDEF_REG("S3_4_C15_C0_3", 4, 0, 3, gxf.apple_el2_impdef[3]),
    SPTM_IMPDEF_REG("S3_4_C15_C0_4", 4, 0, 4, gxf.apple_el2_impdef[4]),
    SPTM_IMPDEF_REG("S3_4_C15_C0_5", 4, 0, 5, gxf.apple_el2_impdef[5]),
    SPTM_IMPDEF_REG("S3_4_C15_C1_0", 4, 1, 0, gxf.apple_el2_impdef[6]),
    SPTM_IMPDEF_REG("S3_4_C15_C1_1", 4, 1, 1, gxf.apple_el2_impdef[7]),
    SPTM_IMPDEF_REG("S3_4_C15_C1_2", 4, 1, 2, gxf.apple_el2_impdef[8]),
    SPTM_IMPDEF_REG("S3_4_C15_C1_3", 4, 1, 3, gxf.apple_el2_impdef[9]),
    SPTM_IMPDEF_REG("S3_4_C15_C1_4", 4, 1, 4, gxf.apple_el2_impdef[10]),
    SPTM_IMPDEF_REG("S3_4_C15_C2_2", 4, 2, 2, gxf.apple_el2_impdef[11]),
    SPTM_IMPDEF_REG("S3_4_C15_C2_3", 4, 2, 3, gxf.apple_el2_impdef[12]),
    SPTM_IMPDEF_REG("S3_4_C15_C2_4", 4, 2, 4, gxf.apple_el2_impdef[13]),
    SPTM_IMPDEF_REG("S3_4_C15_C2_5", 4, 2, 5, gxf.apple_el2_impdef[14]),
};

#undef SPTM_IMPDEF_REG
