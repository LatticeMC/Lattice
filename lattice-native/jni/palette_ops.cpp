// JNI bindings for NativePaletteOps.
//
// Java class: com.latticemc.lattice.nativelib.NativePaletteOps
//
// All methods are static utilities operating on caller-owned `long[]` /
// `int[]` arrays. Single-element get/set still use critical pinning, but
// the bulk paths use region copies to avoid GCLocker serialization on G1.
// The real wins are in `bulkGet` / `bulkSet` / `countUnique` where the
// Java-side fallback would loop and pay the bit-math cost N times.

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <vector>

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

bool storage_window(jint element_bits, jlong start_index, jint count,
                    std::size_t& first_long,
                    std::size_t& long_count,
                    std::size_t& local_start_index) noexcept {
    if (count <= 0) {
        first_long = 0;
        long_count = 0;
        local_start_index = 0;
        return true;
    }

    const int elements_per_long = ps::elements_per_long(element_bits);
    if (elements_per_long <= 0 || start_index < 0) return false;

    const std::size_t start = static_cast<std::size_t>(start_index);
    const std::size_t end = start + static_cast<std::size_t>(count) - 1;
    first_long = start / static_cast<std::size_t>(elements_per_long);
    const std::size_t last_long = end / static_cast<std::size_t>(elements_per_long);
    long_count = last_long - first_long + 1;
    local_start_index = start - first_long * static_cast<std::size_t>(elements_per_long);
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

    std::size_t first_long = 0;
    std::size_t long_count = 0;
    std::size_t local_start_index = 0;
    if (!storage_window(elementBits, startIndex, count, first_long, long_count, local_start_index)) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: invalid bulkGet storage window");
        return;
    }

    std::vector<jlong> data_window(long_count);
    std::vector<jint> out_window(static_cast<std::size_t>(count));
    env->GetLongArrayRegion(jdata, static_cast<jsize>(first_long), static_cast<jsize>(long_count), data_window.data());
    if (env->ExceptionCheck()) return;

    ps::bulk_get(reinterpret_cast<const std::uint64_t*>(data_window.data()),
                 static_cast<int>(elementBits),
                 local_start_index,
                 static_cast<std::size_t>(count),
                 reinterpret_cast<std::uint32_t*>(out_window.data()));
    env->SetIntArrayRegion(jout, outOff, count, out_window.data());
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

    std::size_t first_long = 0;
    std::size_t long_count = 0;
    std::size_t local_start_index = 0;
    if (!storage_window(elementBits, startIndex, count, first_long, long_count, local_start_index)) {
        lattice::jni::throw_illegal_arg(env, "lattice palette: invalid bulkSet storage window");
        return;
    }

    std::vector<jlong> data_window(long_count);
    std::vector<jint> in_window(static_cast<std::size_t>(count));
    env->GetLongArrayRegion(jdata, static_cast<jsize>(first_long), static_cast<jsize>(long_count), data_window.data());
    if (env->ExceptionCheck()) return;
    env->GetIntArrayRegion(jin, inOff, count, in_window.data());
    if (env->ExceptionCheck()) return;

    ps::bulk_set(reinterpret_cast<std::uint64_t*>(data_window.data()),
                 static_cast<int>(elementBits),
                 local_start_index,
                 static_cast<std::size_t>(count),
                 reinterpret_cast<const std::uint32_t*>(in_window.data()));
    env->SetLongArrayRegion(jdata, static_cast<jsize>(first_long), static_cast<jsize>(long_count), data_window.data());
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

    const std::size_t long_count = ps::required_long_count(elementBits, static_cast<std::size_t>(size));
    std::vector<jlong> data_window(long_count);
    std::vector<jint> histogram(static_cast<std::size_t>(histogramCap));
    env->GetLongArrayRegion(jdata, 0, static_cast<jsize>(long_count), data_window.data());
    if (env->ExceptionCheck()) return 0;
    if (histogramCap > 0) {
        env->GetIntArrayRegion(jhist, 0, histogramCap, histogram.data());
        if (env->ExceptionCheck()) return 0;
    }

    const jlong n = static_cast<jlong>(ps::count_unique(
        reinterpret_cast<const std::uint64_t*>(data_window.data()),
        static_cast<int>(elementBits),
        static_cast<std::size_t>(size),
        reinterpret_cast<std::uint32_t*>(histogram.data()),
        static_cast<std::size_t>(histogramCap)));

    if (histogramCap > 0) {
        env->SetIntArrayRegion(jhist, 0, histogramCap, histogram.data());
    }
    return n;
}

} // extern "C"
