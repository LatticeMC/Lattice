// JNI bindings for NativePaletteOps.
//
// Java class: com.latticemc.lattice.nativelib.NativePaletteOps
//
// All methods are static utilities operating on caller-owned `long[]` /
// `int[]` arrays. We pin via GetPrimitiveArrayCritical for the duration of
// each call. Single-element get/set are exposed for completeness, but the
// real wins are in `bulkGet` / `bulkSet` / `countUnique` where the
// Java-side fallback would loop and pay the bit-math cost N times.

#include <jni.h>

#include <cstdint>
#include <cstdio>

#include "world/palette/packed_storage.hpp"
#include "jni_helper.hpp"

namespace ps = lattice::world::palette;

namespace {

// Validate that `data` has at least `required` longs for `(element_bits, size)`.
// Returns true on success; raises IllegalArgumentException and returns false otherwise.
bool validate_storage(JNIEnv* env, jlongArray data,
                      jint element_bits, jlong size) noexcept {
    if (!data) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: null data array");
        return false;
    }
    if (element_bits <= 0 || element_bits > 32) {
        char buf[80];
        std::snprintf(buf, sizeof buf,
                      "lattice palette: elementBits=%d out of range [1,32]",
                      int(element_bits));
        lattice::jni::throw_illegal_arg(env, buf);
        return false;
    }
    if (size < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: size must be non-negative");
        return false;
    }
    const std::size_t required = ps::required_long_count(
        element_bits, static_cast<std::size_t>(size));
    const jsize have = env->GetArrayLength(data);
    if (have < 0 || static_cast<std::size_t>(have) < required) {
        char buf[120];
        std::snprintf(buf, sizeof buf,
                      "lattice palette: data has %d longs, need %zu for size=%lld bits=%d",
                      int(have), required, (long long) size, int(element_bits));
        lattice::jni::throw_illegal_arg(env, buf);
        return false;
    }
    return true;
}

} // namespace

extern "C" {

/*
 * Method:    nativeGet
 * Signature: ([JIJ)I
 *
 * Returns the i-th packed element. `element_bits` ∈ [1, 32].
 */
JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativePaletteOps_nativeGet(
        JNIEnv* env, jclass /*cls*/,
        jlongArray jdata, jint elementBits, jlong index) {
    if (!jdata) { lattice::jni::throw_illegal_arg(env, "lattice palette: null data"); return 0; }
    if (elementBits <= 0 || elementBits > 32 || index < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: bad arguments to get");
        return 0;
    }
    const jsize have = env->GetArrayLength(jdata);
    const std::size_t needed = ps::required_long_count(
        elementBits, static_cast<std::size_t>(index) + 1);
    if (have < 0 || static_cast<std::size_t>(have) < needed) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: index out of bounds for data length");
        return 0;
    }
    jint result = 0;
    {
        lattice::jni::CriticalLongArray g(env, jdata);
        if (!g) { lattice::jni::throw_oom(env, "lattice palette: pin failed"); return 0; }
        result = static_cast<jint>(ps::get(
            reinterpret_cast<const std::uint64_t*>(g.data()),
            static_cast<int>(elementBits),
            static_cast<std::size_t>(index)));
        g.release_ro();
    }
    return result;
}

/*
 * Method:    nativeSet
 * Signature: ([JIJI)I
 *
 * Writes the i-th packed element. Returns the previous value.
 */
JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativePaletteOps_nativeSet(
        JNIEnv* env, jclass /*cls*/,
        jlongArray jdata, jint elementBits, jlong index, jint value) {
    if (!jdata) { lattice::jni::throw_illegal_arg(env, "lattice palette: null data"); return 0; }
    if (elementBits <= 0 || elementBits > 32 || index < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: bad arguments to set");
        return 0;
    }
    const jsize have = env->GetArrayLength(jdata);
    const std::size_t needed = ps::required_long_count(
        elementBits, static_cast<std::size_t>(index) + 1);
    if (have < 0 || static_cast<std::size_t>(have) < needed) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: index out of bounds for data length");
        return 0;
    }
    jint old = 0;
    {
        lattice::jni::CriticalLongArray g(env, jdata);
        if (!g) { lattice::jni::throw_oom(env, "lattice palette: pin failed"); return 0; }
        old = static_cast<jint>(ps::set(
            reinterpret_cast<std::uint64_t*>(g.data()),
            static_cast<int>(elementBits),
            static_cast<std::size_t>(index),
            static_cast<std::uint32_t>(value)));
        // dtor commits the modified word back to Java.
    }
    return old;
}

/*
 * Method:    nativeBulkGet
 * Signature: ([JIJ[III)V
 *
 * Reads `count` elements starting at `startIndex`, into out[outOff .. outOff+count).
 */
JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativePaletteOps_nativeBulkGet(
        JNIEnv* env, jclass /*cls*/,
        jlongArray jdata, jint elementBits, jlong startIndex,
        jintArray jout, jint outOff, jint count) {
    if (!jdata || !jout) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: null array in bulkGet");
        return;
    }
    if (startIndex < 0 || count < 0 || outOff < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: negative count/outOff/startIndex");
        return;
    }
    if (count == 0) return;
    if (count > (INT64_MAX - startIndex)) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: startIndex+count overflows");
        return;
    }
    if (!validate_storage(env, jdata, elementBits, startIndex + count)) return;
    const jsize out_len = env->GetArrayLength(jout);
    if (outOff > out_len || count > out_len - outOff) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: out of bounds in out[]");
        return;
    }

    // All validation/throwing done before entering the critical region.
    // If any pin fails (very rare, only under memory pressure) we report
    // OOM outside the critical region.
    {
        lattice::jni::CriticalLongArray dg(env, jdata);
        if (!dg) { lattice::jni::throw_oom(env, "lattice palette: pin long[] failed"); return; }
        lattice::jni::CriticalIntArray og(env, jout);
        if (!og) {
            // Release dg before we're allowed to call throw_oom (which itself
            // uses regular JNI calls that mustn't nest inside a critical region).
            dg.release_ro();
            lattice::jni::throw_oom(env, "lattice palette: pin int[] failed");
            return;
        }
        ps::bulk_get(reinterpret_cast<const std::uint64_t*>(dg.data()),
                     static_cast<int>(elementBits),
                     static_cast<std::size_t>(startIndex),
                     static_cast<std::size_t>(count),
                     reinterpret_cast<std::uint32_t*>(og.data()) + outOff);
        dg.release_ro();
    }
}

/*
 * Method:    nativeBulkSet
 * Signature: ([JIJ[III)V
 */
JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativePaletteOps_nativeBulkSet(
        JNIEnv* env, jclass /*cls*/,
        jlongArray jdata, jint elementBits, jlong startIndex,
        jintArray jin, jint inOff, jint count) {
    if (!jdata || !jin) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: null array in bulkSet");
        return;
    }
    if (startIndex < 0 || count < 0 || inOff < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: negative count/inOff/startIndex");
        return;
    }
    if (count == 0) return;
    if (count > (INT64_MAX - startIndex)) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: startIndex+count overflows");
        return;
    }
    if (!validate_storage(env, jdata, elementBits, startIndex + count)) return;
    const jsize in_len = env->GetArrayLength(jin);
    if (inOff > in_len || count > in_len - inOff) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: out of bounds in in[]");
        return;
    }

    {
        lattice::jni::CriticalLongArray dg(env, jdata);
        if (!dg) { lattice::jni::throw_oom(env, "lattice palette: pin long[] failed"); return; }
        lattice::jni::CriticalIntArray ig(env, jin);
        if (!ig) {
            dg.release_ro();
            lattice::jni::throw_oom(env, "lattice palette: pin int[] failed");
            return;
        }
        ps::bulk_set(reinterpret_cast<std::uint64_t*>(dg.data()),
                     static_cast<int>(elementBits),
                     static_cast<std::size_t>(startIndex),
                     static_cast<std::size_t>(count),
                     reinterpret_cast<const std::uint32_t*>(ig.data()) + inOff);
        ig.release_ro();
        // dg dtor releases long[] with writes committed.
    }
}

/*
 * Method:    nativeCountUnique
 * Signature: ([JIJ[II)J
 *
 * For every value v in `data[0..size)`, increments histogram[v] (capped at
 * histogramCap). Returns the total number of values processed.
 */
JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativePaletteOps_nativeCountUnique(
        JNIEnv* env, jclass /*cls*/,
        jlongArray jdata, jint elementBits, jlong size,
        jintArray jhist, jint histogramCap) {
    if (!jdata || !jhist) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: null array in countUnique");
        return 0;
    }
    if (!validate_storage(env, jdata, elementBits, size)) return 0;
    if (histogramCap < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: negative histogramCap");
        return 0;
    }
    const jsize hlen = env->GetArrayLength(jhist);
    if (histogramCap > hlen) histogramCap = hlen;

    jlong n = 0;
    {
        lattice::jni::CriticalLongArray dg(env, jdata);
        if (!dg) { lattice::jni::throw_oom(env, "lattice palette: pin long[] failed"); return 0; }
        lattice::jni::CriticalIntArray hg(env, jhist);
        if (!hg) {
            dg.release_ro();
            lattice::jni::throw_oom(env, "lattice palette: pin histogram failed");
            return 0;
        }
        n = static_cast<jlong>(ps::count_unique(
            reinterpret_cast<const std::uint64_t*>(dg.data()),
            static_cast<int>(elementBits),
            static_cast<std::size_t>(size),
            reinterpret_cast<std::uint32_t*>(hg.data()),
            static_cast<std::size_t>(histogramCap)));
        dg.release_ro();
        // hg dtor commits histogram increments back to Java.
    }
    return n;
}

} // extern "C"
