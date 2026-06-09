// JNI bindings for tick-related batch primitives.
//
// Java class: com.latticemc.lattice.nativelib.NativeRandomTickFilter
//
// Currently exposes one entry: nativeFilterRandomTicks, which takes
// a list of candidate (sectionIdx | localIdx) packed positions and
// returns the subset whose palette entry's "has random ticks" mask
// bit is set.

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <new>

#include "jni_helper.hpp"
#include "world/tick/random_tick_filter.hpp"

namespace tk = lattice::world::tick;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeRandomTickFilter_nativeFilterRandomTicks(
        JNIEnv* env, jclass /*cls*/,
        jintArray    jCandidatesPacked, jint candidateCount,
        jobjectArray jSectionStorages,
        jintArray    jSectionElementBits,
        jlongArray   jSectionTickMasks,
        jint         maskLongsPerSection,
        jint         sectionCount,
        jintArray    jOutAcceptedIndices) {
    if (!jOutAcceptedIndices) {
        lattice::jni::throw_illegal_arg(env, "lattice tick: null output");
        return 0;
    }
    if (candidateCount < 0 || sectionCount < 0 || maskLongsPerSection < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice tick: negative count");
        return 0;
    }
    if (env->GetArrayLength(jOutAcceptedIndices) < candidateCount) {
        lattice::jni::throw_illegal_arg(env, "lattice tick: out too short");
        return 0;
    }
    if (candidateCount > 0 && !jCandidatesPacked) {
        lattice::jni::throw_illegal_arg(env, "lattice tick: null candidates");
        return 0;
    }
    if (sectionCount > 0 && !jSectionTickMasks) {
        lattice::jni::throw_illegal_arg(env, "lattice tick: null tick masks");
        return 0;
    }
    if (sectionCount > 0 && maskLongsPerSection == 0) {
        lattice::jni::throw_illegal_arg(env, "lattice tick: zero maskLongsPerSection");
        return 0;
    }
    if (sectionCount > 0) {
        if (!jSectionElementBits || env->GetArrayLength(jSectionElementBits) < sectionCount) {
            lattice::jni::throw_illegal_arg(env, "lattice tick: elementBits too short");
            return 0;
        }
        if (env->GetArrayLength(jSectionTickMasks) < sectionCount * maskLongsPerSection) {
            lattice::jni::throw_illegal_arg(env, "lattice tick: tick masks too short");
            return 0;
        }
        if (jSectionStorages && env->GetArrayLength(jSectionStorages) < sectionCount) {
            lattice::jni::throw_illegal_arg(env, "lattice tick: storages too short");
            return 0;
        }
    }

    const std::size_t sc = static_cast<std::size_t>(sectionCount);
    const std::size_t mask_longs = static_cast<std::size_t>(maskLongsPerSection);

    // Pin per-section storages (long[][]). Same pattern as spawn_filter.
    const std::uint64_t**  storage_ptrs   = nullptr;
    std::size_t*           storage_lens   = nullptr;
    jlongArray*            storage_jarrs  = nullptr;
    jlong**                storage_pinned = nullptr;

    if (sc > 0) {
        storage_ptrs   = static_cast<const std::uint64_t**>(::operator new(sc * sizeof(void*), std::nothrow));
        storage_lens   = static_cast<std::size_t*>(::operator new(sc * sizeof(std::size_t), std::nothrow));
        storage_jarrs  = static_cast<jlongArray*>(::operator new(sc * sizeof(jlongArray), std::nothrow));
        storage_pinned = static_cast<jlong**>(::operator new(sc * sizeof(void*), std::nothrow));
        if (!storage_ptrs || !storage_lens || !storage_jarrs || !storage_pinned) {
            ::operator delete(storage_ptrs);
            ::operator delete(storage_lens);
            ::operator delete(storage_jarrs);
            ::operator delete(storage_pinned);
            lattice::jni::throw_oom(env, "lattice tick: section alloc");
            return 0;
        }
        for (std::size_t i = 0; i < sc; ++i) {
            storage_ptrs[i]   = nullptr;
            storage_lens[i]   = 0;
            storage_jarrs[i]  = nullptr;
            storage_pinned[i] = nullptr;
        }
    }

    auto cleanup_storages = [&]() noexcept {
        if (storage_pinned) {
            for (std::size_t i = 0; i < sc; ++i) {
                if (storage_pinned[i]) {
                    env->ReleaseLongArrayElements(storage_jarrs[i], storage_pinned[i], JNI_ABORT);
                    env->DeleteLocalRef(storage_jarrs[i]);
                }
            }
        }
        ::operator delete(storage_ptrs);
        ::operator delete(storage_lens);
        ::operator delete(storage_jarrs);
        ::operator delete(storage_pinned);
    };

    if (sc > 0 && jSectionStorages) {
        for (std::size_t i = 0; i < sc; ++i) {
            jobject elem = env->GetObjectArrayElement(jSectionStorages, static_cast<jsize>(i));
            if (!elem) continue;
            jlongArray arr = static_cast<jlongArray>(elem);
            jlong* ptr = env->GetLongArrayElements(arr, nullptr);
            if (!ptr) {
                env->DeleteLocalRef(elem);
                cleanup_storages();
                lattice::jni::throw_oom(env, "lattice tick: pin storage");
                return 0;
            }
            storage_jarrs[i]  = arr;
            storage_pinned[i] = ptr;
            storage_ptrs[i]   = reinterpret_cast<const std::uint64_t*>(ptr);
            storage_lens[i]   = static_cast<std::size_t>(env->GetArrayLength(arr));
        }
    }

    jint*  element_bits = nullptr;
    jlong* tick_masks   = nullptr;
    jint*  candidates   = nullptr;
    jint*  out_p        = nullptr;

    auto cleanup_pins = [&]() noexcept {
        if (out_p)        env->ReleaseIntArrayElements(jOutAcceptedIndices, out_p, JNI_ABORT);
        if (candidates)   env->ReleaseIntArrayElements(jCandidatesPacked, candidates, JNI_ABORT);
        if (tick_masks)   env->ReleaseLongArrayElements(jSectionTickMasks, tick_masks, JNI_ABORT);
        if (element_bits) env->ReleaseIntArrayElements(jSectionElementBits, element_bits, JNI_ABORT);
        cleanup_storages();
    };

    if (sc > 0) {
        if (!jSectionElementBits) {
            cleanup_storages();
            lattice::jni::throw_illegal_arg(env, "lattice tick: null elementBits");
            return 0;
        }
        element_bits = env->GetIntArrayElements(jSectionElementBits, nullptr);
        tick_masks   = env->GetLongArrayElements(jSectionTickMasks, nullptr);
        if (!element_bits || !tick_masks) {
            cleanup_pins();
            lattice::jni::throw_oom(env, "lattice tick: pin ints/masks");
            return 0;
        }
    }
    if (candidateCount > 0) {
        candidates = env->GetIntArrayElements(jCandidatesPacked, nullptr);
        if (!candidates) {
            cleanup_pins();
            lattice::jni::throw_oom(env, "lattice tick: pin candidates");
            return 0;
        }
    }
    out_p = env->GetIntArrayElements(jOutAcceptedIndices, nullptr);
    if (!out_p) {
        cleanup_pins();
        lattice::jni::throw_oom(env, "lattice tick: pin out");
        return 0;
    }

    tk::RandomTickFilterInputs in{};
    in.candidates_packed     = reinterpret_cast<const std::uint32_t*>(candidates);
    in.candidate_count       = static_cast<std::size_t>(candidateCount);
    in.section_storages      = storage_ptrs;
    in.section_storage_lens  = storage_lens;
    in.section_element_bits  = reinterpret_cast<const int*>(element_bits);
    in.section_tick_masks    = reinterpret_cast<const std::uint64_t*>(tick_masks);
    in.mask_longs_per_section = mask_longs;
    in.section_count         = sc;

    const std::size_t accepted = tk::filter_random_ticks(
        in, reinterpret_cast<std::uint32_t*>(out_p));

    env->ReleaseIntArrayElements(jOutAcceptedIndices, out_p, 0); // commit
    out_p = nullptr;
    cleanup_pins();
    return static_cast<jint>(accepted);
}

} // extern "C"
