// CPU feature detection. See include/lattice/dispatch.hpp for contract.
//
// This file is intentionally standalone: it must build with the conservative
// baseline ISA (no -mavx2, -mbmi2, …) because it runs *before* we know whether
// those instructions are safe. The CPUID intrinsics themselves are baseline.

// MSVC marks plain std::getenv as deprecated (C4996) and recommends
// `_dupenv_s`. We only ever read short, well-known environment variables,
// and the value is never written; the canonical std::getenv is fine. We
// silence the warning narrowly here rather than disabling the whole
// deprecated-CRT category project-wide.
#if defined(_MSC_VER)
#  define _CRT_SECURE_NO_WARNINGS 1
#  pragma warning(disable : 4996)  // std::getenv (C4996): we only read, never write.
#endif

#include "lattice/dispatch.hpp"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#if defined(_MSC_VER)
#  include <intrin.h>
#elif defined(__x86_64__) || defined(__i386__)
#  include <cpuid.h>
#  if defined(__GNUC__) || defined(__clang__)
#    include <immintrin.h>   // _xgetbv
#  endif
#endif

#if defined(_WIN32)
#  include <windows.h>
#elif defined(__linux__) || defined(__ANDROID__)
#  include <sys/auxv.h>
#  if defined(__aarch64__)
#    include <asm/hwcap.h>
#  endif
#elif defined(__APPLE__)
#  include <sys/sysctl.h>
#endif

namespace lattice::cpu {

namespace {

// ---- Environment-variable overrides ---------------------------------------

struct EnvOverrides {
    bool force_scalar = false;
    unsigned disable_mask = 0;   // bit flags mirror `Features` fields we can disable

    enum DisableBit : unsigned {
        kDisableAvx    = 1u << 0,
        kDisableAvx2   = 1u << 1,
        kDisableAvx512 = 1u << 2,
        kDisableBmi2   = 1u << 3,
        kDisableSve    = 1u << 4,
    };
};

EnvOverrides read_env_overrides() noexcept {
    EnvOverrides r;
    if (const char* s = std::getenv("LATTICE_CPU_FORCE_SCALAR"); s && s[0] == '1') {
        r.force_scalar = true;
    }
    if (const char* s = std::getenv("LATTICE_CPU_DISABLE"); s && *s) {
        // Very small parser: comma-separated, case-insensitive.
        const char* p = s;
        while (*p) {
            const char* q = p;
            while (*q && *q != ',') ++q;
            const auto eq = [&](const char* lit) {
                const size_t n = q - p;
                if (std::strlen(lit) != n) return false;
                for (size_t i = 0; i < n; ++i) {
                    char a = p[i]; if (a >= 'A' && a <= 'Z') a = char(a + 32);
                    if (a != lit[i]) return false;
                }
                return true;
            };
            if      (eq("avx"))    r.disable_mask |= EnvOverrides::kDisableAvx;
            else if (eq("avx2"))   r.disable_mask |= EnvOverrides::kDisableAvx2;
            else if (eq("avx512")) r.disable_mask |= EnvOverrides::kDisableAvx512;
            else if (eq("bmi2"))   r.disable_mask |= EnvOverrides::kDisableBmi2;
            else if (eq("sve"))    r.disable_mask |= EnvOverrides::kDisableSve;
            p = (*q) ? q + 1 : q;
        }
    }
    return r;
}

// ---- x86/x64 detection ----------------------------------------------------

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)

inline void cpuid_leaf(uint32_t leaf, uint32_t subleaf, uint32_t out[4]) noexcept {
#  if defined(_MSC_VER)
    int regs[4];
    __cpuidex(regs, static_cast<int>(leaf), static_cast<int>(subleaf));
    out[0] = uint32_t(regs[0]); out[1] = uint32_t(regs[1]);
    out[2] = uint32_t(regs[2]); out[3] = uint32_t(regs[3]);
#  else
    unsigned eax, ebx, ecx, edx;
    __cpuid_count(leaf, subleaf, eax, ebx, ecx, edx);
    out[0] = eax; out[1] = ebx; out[2] = ecx; out[3] = edx;
#  endif
}

inline uint64_t read_xcr0() noexcept {
#  if defined(_MSC_VER)
    return _xgetbv(0);
#  elif defined(__GNUC__) || defined(__clang__)
    uint32_t eax, edx;
    __asm__ volatile("xgetbv" : "=a"(eax), "=d"(edx) : "c"(0));
    return (uint64_t(edx) << 32) | eax;
#  else
    return 0;
#  endif
}

void detect_x86(Features& f, const EnvOverrides& ov) noexcept {
    uint32_t r[4] = {0,0,0,0};

    // --- Vendor --------------------------------------------------------
    cpuid_leaf(0, 0, r);
    const uint32_t max_basic = r[0];
    char vendor[13]; std::memcpy(vendor + 0, &r[1], 4);
    std::memcpy(vendor + 4, &r[3], 4); std::memcpy(vendor + 8, &r[2], 4);
    vendor[12] = 0;
    if      (!std::strcmp(vendor, "GenuineIntel")) f.vendor = Features::Vendor::Intel;
    else if (!std::strcmp(vendor, "AuthenticAMD")) f.vendor = Features::Vendor::AMD;
    else                                           f.vendor = Features::Vendor::Other;

    if (max_basic < 1) return;

    // --- Leaf 1 ECX / EDX ---------------------------------------------
    cpuid_leaf(1, 0, r);
    const uint32_t eax = r[0], ecx = r[2], edx = r[3];
    f.family = ((eax >> 8) & 0xF) + ((eax >> 20) & 0xFF);
    f.model  = ((eax >> 4) & 0xF) | (((eax >> 16) & 0xF) << 4);

    f.sse2   = (edx >> 26) & 1u;
    f.sse3   = (ecx >>  0) & 1u;
    f.ssse3  = (ecx >>  9) & 1u;
    f.sse41  = (ecx >> 19) & 1u;
    f.sse42  = (ecx >> 20) & 1u;
    f.popcnt = (ecx >> 23) & 1u;
    const bool osxsave   = (ecx >> 27) & 1u;
    const bool avx_cpuid = (ecx >> 28) & 1u;
    f.fma3   = (ecx >> 12) & 1u;

    // AVX/AVX-512 require kernel to save the extended state. XGETBV-gated.
    bool avx_ok = false, avx512_ok = false;
    if (osxsave && avx_cpuid) {
        const uint64_t xcr0 = read_xcr0();
        const bool xmm_ok = (xcr0 & 0x2) != 0;
        const bool ymm_ok = (xcr0 & 0x4) != 0;
        avx_ok = xmm_ok && ymm_ok;
        // ZMM state = opmask (bit 5) + ZMM_Hi256 (bit 6) + Hi16_ZMM (bit 7)
        avx512_ok = avx_ok && ((xcr0 & 0xE0) == 0xE0);
    }
    f.avx = avx_ok;

    // --- Leaf 7 subleaf 0 ---------------------------------------------
    if (max_basic >= 7) {
        cpuid_leaf(7, 0, r);
        const uint32_t ebx7 = r[1], ecx7 = r[2];
        f.bmi1 = (ebx7 >>  3) & 1u;
        f.bmi2 = (ebx7 >>  8) & 1u;
        f.avx2 = avx_ok && ((ebx7 >> 5) & 1u);

        f.avx512f     = avx512_ok && ((ebx7 >> 16) & 1u);
        f.avx512dq    = avx512_ok && ((ebx7 >> 17) & 1u);
        f.avx512bw    = avx512_ok && ((ebx7 >> 30) & 1u);
        f.avx512vl    = avx512_ok && ((ebx7 >> 31) & 1u);
        f.avx512vbmi  = avx512_ok && ((ecx7 >>  1) & 1u);
        f.avx512vbmi2 = avx512_ok && ((ecx7 >>  6) & 1u);
        f.avx512vpopcnt = avx512_ok && ((ecx7 >> 14) & 1u);
    }

    // --- BMI2 fast-path heuristic -------------------------------------
    // AMD Zen 1/Zen 2 implement PEXT/PDEP in microcode at ~18-cycle latency.
    // Zen 3 (family 0x19, model < 0x50) and later are native-fast.
    // Intel: native-fast since Haswell.
    if (f.bmi2) {
        if (f.vendor == Features::Vendor::Intel) {
            f.bmi2_fast = true;
        } else if (f.vendor == Features::Vendor::AMD) {
            // family 0x17 == Zen 1/2 (slow), family >= 0x19 == Zen 3+ (fast)
            f.bmi2_fast = (f.family >= 0x19);
        } else {
            // Conservative: assume slow for unknown vendors.
            f.bmi2_fast = false;
        }
    }

    // --- Apply environment overrides ----------------------------------
    if (ov.disable_mask & EnvOverrides::kDisableAvx)    { f.avx = false; }
    if (ov.disable_mask & EnvOverrides::kDisableAvx2)   { f.avx2 = false; }
    if (ov.disable_mask & EnvOverrides::kDisableAvx512) {
        f.avx512f = f.avx512bw = f.avx512dq = f.avx512vl = false;
        f.avx512vbmi = f.avx512vbmi2 = f.avx512vpopcnt = false;
    }
    if (ov.disable_mask & EnvOverrides::kDisableBmi2)   { f.bmi2 = f.bmi2_fast = false; }
}

#endif // x86

// ---- AArch64 detection ----------------------------------------------------

#if defined(__aarch64__) || defined(_M_ARM64)

void detect_aarch64(Features& f, const EnvOverrides& ov) noexcept {
    f.vendor = Features::Vendor::Arm;
    // NEON is mandatory on AArch64.
    f.neon = true;

#  if defined(__linux__) || defined(__ANDROID__)
    const unsigned long hwcap  = getauxval(AT_HWCAP);
#    ifdef HWCAP_CRC32
    f.crc32 = (hwcap & HWCAP_CRC32) != 0;
#    endif
#    ifdef HWCAP_AES
    f.aes   = (hwcap & HWCAP_AES) != 0;
#    endif
#    ifdef HWCAP_SVE
    f.sve   = (hwcap & HWCAP_SVE) != 0;
#    endif
#    ifdef AT_HWCAP2
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);
#      ifdef HWCAP2_SVE2
    f.sve2  = (hwcap2 & HWCAP2_SVE2) != 0;
#      endif
    (void)hwcap2;
#    endif
    (void)hwcap;
#  elif defined(__APPLE__)
    auto sysctl_bool = [](const char* name) {
        int v = 0; size_t sz = sizeof v;
        return sysctlbyname(name, &v, &sz, nullptr, 0) == 0 && v != 0;
    };
    f.crc32 = sysctl_bool("hw.optional.armv8_crc32");
    // SVE / SVE2 not available on Apple Silicon as of M-series.
    f.vendor = Features::Vendor::Apple;
#  elif defined(_WIN32)
    // Windows on ARM64: NEON guaranteed; CRC32 / crypto via IsProcessorFeaturePresent
    f.crc32 = IsProcessorFeaturePresent(PF_ARM_V8_CRC32_INSTRUCTIONS_AVAILABLE) != 0;
#  endif

    if (ov.disable_mask & EnvOverrides::kDisableSve) { f.sve = f.sve2 = false; }
}

#endif // aarch64

// ---- Singleton ------------------------------------------------------------

Features                 g_features{};
std::atomic<bool>        g_initialised{false};
char                     g_summary[128] = {0};

void populate_summary(const Features& f) noexcept {
    const char* vendor =
        f.vendor == Features::Vendor::Intel ? "Intel" :
        f.vendor == Features::Vendor::AMD   ? "AMD"   :
        f.vendor == Features::Vendor::Apple ? "Apple" :
        f.vendor == Features::Vendor::Arm   ? "Arm"   : "Other";
    const char* tier =
        f.forced_scalar  ? "scalar (forced)"   :
        f.avx512f        ? "AVX-512"           :
        f.avx2           ? "AVX2"              :
        f.sse42          ? "SSE4.2"            :
        f.sse2           ? "SSE2"              :
        f.sve2           ? "SVE2"              :
        f.sve            ? "SVE"               :
        f.neon           ? "NEON"              : "scalar";
    const char* bmi2 = f.bmi2 ? (f.bmi2_fast ? " +BMI2(fast)" : " +BMI2(slow)") : "";
    std::snprintf(g_summary, sizeof g_summary,
                  "lattice cpu: vendor=%s tier=%s%s family=0x%X model=0x%X",
                  vendor, tier, bmi2, f.family, f.model);
}

} // namespace

const Features& initialize() noexcept {
    if (g_initialised.load(std::memory_order_acquire)) return g_features;

    Features f{};
    const auto ov = read_env_overrides();

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
    detect_x86(f, ov);
#elif defined(__aarch64__) || defined(_M_ARM64)
    detect_aarch64(f, ov);
#endif

    if (ov.force_scalar) {
        // Wipe everything SIMD-ish, leave only vendor/family/model for logging.
        const auto v = f.vendor; const auto fam = f.family; const auto mod = f.model;
        f = Features{};
        f.vendor = v; f.family = fam; f.model = mod;
        f.forced_scalar = true;
    }

    populate_summary(f);

    // Write-once. Relaxed store for the flag is fine: the payload is written
    // before the store, and any reader observing `true` must see the payload.
    g_features = f;
    g_initialised.store(true, std::memory_order_release);
    return g_features;
}

const Features& features() noexcept {
    if (!g_initialised.load(std::memory_order_acquire)) {
        return initialize();
    }
    return g_features;
}

const char* summary() noexcept {
    if (!g_initialised.load(std::memory_order_acquire)) (void)initialize();
    return g_summary;
}

} // namespace lattice::cpu
