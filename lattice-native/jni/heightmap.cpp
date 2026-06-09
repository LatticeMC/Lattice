// JNI bindings for NativeHeightmap.
//
// Java class: com.latticemc.lattice.nativelib.NativeHeightmap
//
// The Java side has already walked each section's palette to produce a
// 256-bit "passing" mask telling us which packed indices satisfy the
// caller's predicate. The native side combines those masks with the
// section storage arrays to find the top-most matching y per column,
// without ever calling back into Java.
//
// API (single entry point):
//
//   void nativePopulateHeightmap(
//       long[][] storages,         // length = section_count; entry may be null
//       int[]    elementBits,      // length = section_count; 0 ↔ null storage
//       long[]   passingMasksFlat, // length = section_count * maskLongsPerSection
//       int      maskLongsPerSection,
//       int      sectionCount,
//       int      sectionBaseY,     // world Y of the bottom of section[0]
//       int      defaultHeight,    // value when no cell passes
//       int[]    out               // length = 256; z*16+x order
//   )

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <new>

#include "jni_helper.hpp"
#include "world/heightmap/heightmap_scan.hpp"

namespace hm = lattice::world::heightmap;

extern "C" {

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeHeightmap_nativePopulateHeightmap(
        JNIEnv* env, jclass /*cls*/,
        jobjectArray jStorages,
        jintArray    jElementBits,
        jlongArray   jPassingMasksFlat,
        jint         maskLongsPerSection,
        jint         sectionCount,
        jint         sectionBaseY,
        jint         defaultHeight,
        jintArray    jOut) {
    if (!jElementBits || !jPassingMasksFlat || !jOut) {
        lattice::jni::throw_illegal_arg(env, "lattice heightmap: null argument");
        return;
    }
    if (sectionCount < 0 || sectionCount > 1024 || maskLongsPerSection <= 0) {
        lattice::jni::throw_illegal_arg(env, "lattice heightmap: invalid sectionCount/maskLongsPerSection");
        return;
    }

    const std::size_t sc = static_cast<std::size_t>(sectionCount);
    const std::size_t mask_longs = static_cast<std::size_t>(maskLongsPerSection);

    // Validate array lengths.
    const jsize eb_len = env->GetArrayLength(jElementBits);
    const jsize pm_len = env->GetArrayLength(jPassingMasksFlat);
    const jsize out_len = env->GetArrayLength(jOut);
    const jsize storages_len = jStorages ? env->GetArrayLength(jStorages) : 0;
    if (eb_len < sectionCount
        || pm_len < sectionCount * maskLongsPerSection
        || (jStorages && storages_len < sectionCount)
        || out_len < hm::kColumnCount) {
        lattice::jni::throw_illegal_arg(env, "lattice heightmap: input array too short");
        return;
    }

    // Build SectionView[] on native heap (24-ish small structs; no critical region cost).
    hm::SectionView* views = static_cast<hm::SectionView*>(
        ::operator new(sizeof(hm::SectionView) * sc, std::nothrow));
    if (!views) {
        lattice::jni::throw_oom(env, "lattice heightmap: SectionView alloc");
        return;
    }
    // Default-initialise.
    for (std::size_t i = 0; i < sc; ++i) views[i] = hm::SectionView{};

    // We need to pin: passing masks (one big long[]), element_bits (int[]),
    // out (int[]), and each non-null storage long[]. Pinning is via
    // GetLongArrayElements / GetIntArrayElements (regular, not critical) so
    // we can hold them all simultaneously without nesting critical regions.
    // For the storages we keep a side array of (jlongArray, jlong*) pairs so
    // we can release them at the end.

    struct StoragePin {
        jlongArray arr     = nullptr;
        jlong*     ptr     = nullptr;
        jsize      length  = 0;
    };
    StoragePin* pins = static_cast<StoragePin*>(
        ::operator new(sizeof(StoragePin) * sc, std::nothrow));
    if (!pins) {
        ::operator delete(views);
        lattice::jni::throw_oom(env, "lattice heightmap: StoragePin alloc");
        return;
    }
    for (std::size_t i = 0; i < sc; ++i) pins[i] = StoragePin{};

    auto cleanup = [&]() noexcept {
        for (std::size_t i = 0; i < sc; ++i) {
            if (pins[i].ptr) {
                env->ReleaseLongArrayElements(pins[i].arr, pins[i].ptr, JNI_ABORT);
                env->DeleteLocalRef(pins[i].arr);
            }
        }
        ::operator delete(pins);
        ::operator delete(views);
    };

    // Get the element_bits int[] (we only read; small, copy is fine).
    jint* element_bits = env->GetIntArrayElements(jElementBits, nullptr);
    if (!element_bits) {
        cleanup();
        if (!env->ExceptionCheck()) lattice::jni::throw_oom(env, "lattice heightmap: pin elementBits");
        return;
    }

    // Get the flat passing masks long[].
    jlong* passing_flat = env->GetLongArrayElements(jPassingMasksFlat, nullptr);
    if (!passing_flat) {
        env->ReleaseIntArrayElements(jElementBits, element_bits, JNI_ABORT);
        cleanup();
        if (!env->ExceptionCheck()) lattice::jni::throw_oom(env, "lattice heightmap: pin passingMasks");
        return;
    }

    // Pin each storage long[]. Skip null entries.
    bool ok = true;
    for (std::size_t i = 0; i < sc; ++i) {
        jobject elem = (jStorages != nullptr)
            ? env->GetObjectArrayElement(jStorages, static_cast<jsize>(i))
            : nullptr;
        if (!elem) continue;
        jlongArray arr = static_cast<jlongArray>(elem);
        jlong* ptr = env->GetLongArrayElements(arr, nullptr);
        if (!ptr) {
            env->DeleteLocalRef(elem);
            ok = false;
            break;
        }
        pins[i].arr    = arr;
        pins[i].ptr    = ptr;
        pins[i].length = env->GetArrayLength(arr);
    }

    if (!ok) {
        env->ReleaseLongArrayElements(jPassingMasksFlat, passing_flat, JNI_ABORT);
        env->ReleaseIntArrayElements(jElementBits, element_bits, JNI_ABORT);
        cleanup();
        if (!env->ExceptionCheck()) lattice::jni::throw_oom(env, "lattice heightmap: pin storage");
        return;
    }

    // Fill the SectionView array.
    for (std::size_t i = 0; i < sc; ++i) {
        views[i].element_bits  = static_cast<int>(element_bits[i]);
        views[i].passing_mask  = reinterpret_cast<const std::uint64_t*>(
            passing_flat + i * mask_longs);
        if (pins[i].ptr) {
            views[i].storage       = reinterpret_cast<const std::uint64_t*>(pins[i].ptr);
            views[i].storage_longs = static_cast<std::size_t>(pins[i].length);
        }
    }

    // Output buffer: pin separately. int[] = 32-bit; we write 256 entries.
    jint* out_ptr = env->GetIntArrayElements(jOut, nullptr);
    if (!out_ptr) {
        env->ReleaseLongArrayElements(jPassingMasksFlat, passing_flat, JNI_ABORT);
        env->ReleaseIntArrayElements(jElementBits, element_bits, JNI_ABORT);
        cleanup();
        if (!env->ExceptionCheck()) lattice::jni::throw_oom(env, "lattice heightmap: pin out");
        return;
    }

    hm::populate(views, sc, sectionBaseY, mask_longs, defaultHeight,
                 reinterpret_cast<std::int32_t*>(out_ptr));

    // Commit out, abort everything else (we didn't modify them).
    env->ReleaseIntArrayElements(jOut, out_ptr, 0);
    env->ReleaseLongArrayElements(jPassingMasksFlat, passing_flat, JNI_ABORT);
    env->ReleaseIntArrayElements(jElementBits, element_bits, JNI_ABORT);
    cleanup();
}

} // extern "C"
