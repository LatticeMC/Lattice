// JNI binding for the side-effect-free Brain memory eligibility batch.

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>
#include <span>

#include "jni_helper.hpp"
#include "world/entity/brain_eligibility.hpp"

namespace be = lattice::world::entity;

namespace {

constexpr jint kBrainEligibilityAbiVersion = 2;
constexpr jint kValuePresent = 0;
constexpr jint kValueAbsent = 1;
constexpr jint kRegistered = 2;

[[nodiscard]] bool checked_product(const std::size_t left, const std::size_t right,
                                   std::size_t& output) noexcept {
    if (left != 0 && right > std::numeric_limits<std::size_t>::max() / left) return false;
    output = left * right;
    return true;
}

[[nodiscard]] bool is_valid_status(const jbyte value) noexcept {
    return value == kValuePresent || value == kValueAbsent || value == kRegistered;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeBrainEligibility_nativeAbiVersion(
        JNIEnv* /*env*/, jclass /*cls*/) {
    return kBrainEligibilityAbiVersion;
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeBrainEligibility_nativeEvaluate(
        JNIEnv* env, jclass /*cls*/,
        jint entityCount,
        jint memoryTypeCount,
        jint memoryWordCount,
        jlongArray jRegisteredMemoryBits,
        jlongArray jPresentMemoryBits,
        jintArray jBehaviorEntityIndices,
        jintArray jRequirementOffsets,
        jintArray jRequirementMemoryIds,
        jbyteArray jRequirementStatuses,
        jlongArray jOutputEligible) {
    if (entityCount < 0 || memoryTypeCount < 0 || memoryWordCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice brain-eligibility: negative count");
        return;
    }
    if (!jRegisteredMemoryBits || !jPresentMemoryBits || !jBehaviorEntityIndices || !jRequirementOffsets
            || !jRequirementMemoryIds || !jRequirementStatuses || !jOutputEligible) {
        lattice::jni::throw_illegal_arg(env, "lattice brain-eligibility: null array");
        return;
    }

    const auto entity_count = static_cast<std::size_t>(entityCount);
    const auto memory_type_count = static_cast<std::size_t>(memoryTypeCount);
    const auto memory_word_count = static_cast<std::size_t>(memoryWordCount);
    const std::size_t expected_word_count = (memory_type_count + 63U) >> 6U;
    if (memory_word_count != expected_word_count) {
        lattice::jni::throw_illegal_arg(env, "lattice brain-eligibility: invalid memory word count");
        return;
    }

    std::size_t entity_words = 0;
    if (!checked_product(entity_count, memory_word_count, entity_words)
            || entity_words > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        lattice::jni::throw_illegal_arg(env, "lattice brain-eligibility: bitset size overflow");
        return;
    }

    const jsize behavior_count_jsize = env->GetArrayLength(jBehaviorEntityIndices);
    const jsize requirement_count_jsize = env->GetArrayLength(jRequirementMemoryIds);
    const auto behavior_count = static_cast<std::size_t>(behavior_count_jsize);
    const std::size_t output_word_count = behavior_count / 64U + (behavior_count % 64U != 0U ? 1U : 0U);
    if (env->GetArrayLength(jRegisteredMemoryBits) != static_cast<jsize>(entity_words)
            || env->GetArrayLength(jPresentMemoryBits) != static_cast<jsize>(entity_words)
            || env->GetArrayLength(jRequirementOffsets) != behavior_count_jsize + 1
            || env->GetArrayLength(jRequirementStatuses) != requirement_count_jsize
            || output_word_count > static_cast<std::size_t>(std::numeric_limits<jsize>::max())
            || env->GetArrayLength(jOutputEligible) < static_cast<jsize>(output_word_count)) {
        lattice::jni::throw_illegal_arg(env, "lattice brain-eligibility: inconsistent array lengths");
        return;
    }

    bool pin_failed = false;
    const char* validation_error = nullptr;
    {
        lattice::jni::CriticalLongArray registered_memory_bits{env, jRegisteredMemoryBits};
        lattice::jni::CriticalLongArray present_memory_bits{env, jPresentMemoryBits};
        lattice::jni::CriticalIntArray behavior_entity_indices{env, jBehaviorEntityIndices};
        lattice::jni::CriticalIntArray requirement_offsets{env, jRequirementOffsets};
        lattice::jni::CriticalIntArray requirement_memory_ids{env, jRequirementMemoryIds};
        lattice::jni::CriticalByteArray requirement_statuses{env, jRequirementStatuses};
        lattice::jni::CriticalLongArray output_eligible{env, jOutputEligible};
        pin_failed = (entity_words != 0U && (!registered_memory_bits || !present_memory_bits))
                || (behavior_count != 0U && !behavior_entity_indices)
                || !requirement_offsets
                || (requirement_count_jsize != 0 && (!requirement_memory_ids || !requirement_statuses))
                || (output_word_count != 0U && !output_eligible);

        const std::size_t requirement_count = static_cast<std::size_t>(requirement_count_jsize);
        if (!pin_failed
                && (requirement_offsets.data()[0] != 0
                    || requirement_offsets.data()[behavior_count] != requirement_count_jsize)) {
            validation_error = "lattice brain-eligibility: invalid CSR offsets";
        }
        for (std::size_t behavior = 0; !pin_failed && !validation_error && behavior < behavior_count; ++behavior) {
            const jint entity = behavior_entity_indices.data()[behavior];
            const jint begin = requirement_offsets.data()[behavior];
            const jint end = requirement_offsets.data()[behavior + 1];
            if (entity < 0 || entity >= entityCount || begin < 0 || end < begin || end > requirement_count_jsize) {
                validation_error = "lattice brain-eligibility: invalid behavior CSR entry";
            }
        }
        for (std::size_t requirement = 0;
             !pin_failed && !validation_error && requirement < requirement_count;
             ++requirement) {
            const jint memory = requirement_memory_ids.data()[requirement];
            if (memory < 0 || memory >= memoryTypeCount || !is_valid_status(requirement_statuses.data()[requirement])) {
                validation_error = "lattice brain-eligibility: invalid memory requirement";
            }
        }

        if (!pin_failed && !validation_error) {
            const be::BrainEligibilityBatch batch{
                reinterpret_cast<const std::uint64_t*>(registered_memory_bits.data()),
                reinterpret_cast<const std::uint64_t*>(present_memory_bits.data()),
                entity_count,
                memory_type_count,
                memory_word_count,
                reinterpret_cast<const int*>(behavior_entity_indices.data()),
                reinterpret_cast<const int*>(requirement_offsets.data()),
                reinterpret_cast<const int*>(requirement_memory_ids.data()),
                reinterpret_cast<const std::uint8_t*>(requirement_statuses.data()),
                behavior_count,
            };
            be::evaluate_brain_eligibility(batch, std::span<std::uint64_t>{
                    reinterpret_cast<std::uint64_t*>(output_eligible.data()), output_word_count});
        } else {
            output_eligible.release_ro();
        }

        registered_memory_bits.release_ro();
        present_memory_bits.release_ro();
        behavior_entity_indices.release_ro();
        requirement_offsets.release_ro();
        requirement_memory_ids.release_ro();
        requirement_statuses.release_ro();
    }
    if (pin_failed) {
        lattice::jni::throw_oom(env, "lattice brain-eligibility: pin arrays");
        return;
    }
    if (validation_error) {
        lattice::jni::throw_illegal_arg(env, validation_error);
    }
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeBrainEligibility_nativeEvaluatePackedInto(
        JNIEnv* env, jclass /*cls*/,
        jint entityCount,
        jint memoryWordCount,
        jint behaviorCount,
        jlongArray jRegisteredMemoryBits,
        jlongArray jPresentMemoryBits,
        jintArray jBehaviorEntityIndices,
        jlongArray jRequiredRegisteredBits,
        jlongArray jRequiredPresentBits,
        jlongArray jRequiredAbsentBits,
        jlongArray jOutputEligible) {
    if (entityCount < 0 || memoryWordCount < 0 || behaviorCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice brain-eligibility packed: negative count");
        return;
    }
    if (!jRegisteredMemoryBits || !jPresentMemoryBits || !jBehaviorEntityIndices
            || !jRequiredRegisteredBits || !jRequiredPresentBits || !jRequiredAbsentBits || !jOutputEligible) {
        lattice::jni::throw_illegal_arg(env, "lattice brain-eligibility packed: null array");
        return;
    }

    const auto entity_count = static_cast<std::size_t>(entityCount);
    const auto memory_word_count = static_cast<std::size_t>(memoryWordCount);
    std::size_t entity_words = 0;
    if (!checked_product(entity_count, memory_word_count, entity_words)
            || entity_words > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        lattice::jni::throw_illegal_arg(env, "lattice brain-eligibility packed: bitset size overflow");
        return;
    }

    const auto behavior_count = static_cast<std::size_t>(behaviorCount);
    const std::size_t output_word_count = behavior_count / 64U + (behavior_count % 64U != 0U ? 1U : 0U);
    std::size_t behavior_words = 0;
    if (!checked_product(behavior_count, memory_word_count, behavior_words)) {
        lattice::jni::throw_illegal_arg(env, "lattice brain-eligibility packed: mask size overflow");
        return;
    }

    bool pin_failed = false;
    {
        lattice::jni::CriticalLongArray registered_memory_bits{env, jRegisteredMemoryBits};
        lattice::jni::CriticalLongArray present_memory_bits{env, jPresentMemoryBits};
        lattice::jni::CriticalIntArray behavior_entity_indices{env, jBehaviorEntityIndices};
        lattice::jni::CriticalLongArray required_registered_bits{env, jRequiredRegisteredBits};
        lattice::jni::CriticalLongArray required_present_bits{env, jRequiredPresentBits};
        lattice::jni::CriticalLongArray required_absent_bits{env, jRequiredAbsentBits};
        lattice::jni::CriticalLongArray output_eligible{env, jOutputEligible};
        pin_failed = (entity_words != 0U && (!registered_memory_bits || !present_memory_bits))
                || (behavior_count != 0U && !behavior_entity_indices)
                || (behavior_words != 0U
                    && (!required_registered_bits || !required_present_bits || !required_absent_bits))
                || (output_word_count != 0U && !output_eligible);
        if (!pin_failed) {
            const be::BrainPackedEligibilityBatch batch{
                reinterpret_cast<const std::uint64_t*>(registered_memory_bits.data()),
                reinterpret_cast<const std::uint64_t*>(present_memory_bits.data()),
                entity_count,
                memory_word_count,
                reinterpret_cast<const int*>(behavior_entity_indices.data()),
                reinterpret_cast<const std::uint64_t*>(required_registered_bits.data()),
                reinterpret_cast<const std::uint64_t*>(required_present_bits.data()),
                reinterpret_cast<const std::uint64_t*>(required_absent_bits.data()),
                behavior_count,
            };
            be::evaluate_brain_eligibility_packed(batch, std::span<std::uint64_t>{
                    reinterpret_cast<std::uint64_t*>(output_eligible.data()), output_word_count});
        } else {
            output_eligible.release_ro();
        }
        registered_memory_bits.release_ro();
        present_memory_bits.release_ro();
        behavior_entity_indices.release_ro();
        required_registered_bits.release_ro();
        required_present_bits.release_ro();
        required_absent_bits.release_ro();
    }
    if (pin_failed) {
        lattice::jni::throw_oom(env, "lattice brain-eligibility packed: pin arrays");
        return;
    }
}

} // extern "C"
