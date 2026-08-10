// NEON specialisation of the packed Brain memory eligibility evaluator.
// Processes two 64-bit memory words per iteration on AArch64; CSR remains scalar.

#include "world/entity/brain_eligibility.hpp"

#include <algorithm>

#if defined(__aarch64__) || defined(_M_ARM64)
#  include <arm_neon.h>
#endif

namespace lattice::world::entity {

#if defined(__aarch64__) || defined(_M_ARM64)

namespace {

[[nodiscard]] std::size_t output_word_count(const std::size_t behavior_count) noexcept {
    return behavior_count / 64U + (behavior_count % 64U != 0U ? 1U : 0U);
}

void clear_unused_output_bits(std::span<std::uint64_t> output, const std::size_t behavior_count) noexcept {
    if (behavior_count == 0U) return;
    const std::size_t used_words = output_word_count(behavior_count);
    const std::size_t used_bits = behavior_count % 64U;
    if (used_bits != 0U) output[used_words - 1U] &= (std::uint64_t{1} << used_bits) - 1U;
}

[[nodiscard]] bool evaluate_packed_behavior_neon(
    const BrainPackedEligibilityBatch& batch,
    const std::size_t behavior) noexcept {
    const std::size_t entity_base = static_cast<std::size_t>(batch.behavior_entity_indices[behavior])
            * batch.memory_word_count;
    const std::size_t mask_base = behavior * batch.memory_word_count;
    const uint64x2_t all_ones = vdupq_n_u64(~std::uint64_t{0});

    std::size_t word = 0;
    for (; word + 1U < batch.memory_word_count; word += 2U) {
        const uint64x2_t registered = vld1q_u64(batch.registered_memory_bits + entity_base + word);
        const uint64x2_t present = vld1q_u64(batch.present_memory_bits + entity_base + word);
        const uint64x2_t required_registered = vld1q_u64(batch.required_registered_bits + mask_base + word);
        const uint64x2_t required_present = vld1q_u64(batch.required_present_bits + mask_base + word);
        const uint64x2_t required_absent = vld1q_u64(batch.required_absent_bits + mask_base + word);

        const uint64x2_t not_registered = veorq_u64(registered, all_ones);
        const uint64x2_t not_present = veorq_u64(present, all_ones);
        const uint64x2_t missing_registered = vandq_u64(required_registered, not_registered);
        const uint64x2_t missing_present = vandq_u64(required_present, not_present);
        const uint64x2_t absent_unregistered = vandq_u64(required_absent, not_registered);
        const uint64x2_t absent_present = vandq_u64(required_absent, present);
        const uint64x2_t failed = vorrq_u64(
            vorrq_u64(missing_registered, missing_present),
            vorrq_u64(absent_unregistered, absent_present));

        if ((vgetq_lane_u64(failed, 0) | vgetq_lane_u64(failed, 1)) != 0U) return false;
    }

    for (; word < batch.memory_word_count; ++word) {
        const std::uint64_t registered = batch.registered_memory_bits[entity_base + word];
        const std::uint64_t present = batch.present_memory_bits[entity_base + word];
        const std::uint64_t required_registered = batch.required_registered_bits[mask_base + word];
        const std::uint64_t required_present = batch.required_present_bits[mask_base + word];
        const std::uint64_t required_absent = batch.required_absent_bits[mask_base + word];
        if ((registered & required_registered) != required_registered
                || (present & required_present) != required_present
                || (registered & required_absent) != required_absent
                || (present & required_absent) != 0U) {
            return false;
        }
    }
    return true;
}

} // namespace

void evaluate_brain_eligibility_packed_neon(
    const BrainPackedEligibilityBatch& batch,
    const std::span<std::uint64_t> output_eligible) noexcept {
    std::fill(output_eligible.begin(), output_eligible.end(), std::uint64_t{0});
    for (std::size_t behavior = 0; behavior < batch.behavior_count; ++behavior) {
        if (evaluate_packed_behavior_neon(batch, behavior)) {
            output_eligible[behavior >> 6U] |= std::uint64_t{1} << (behavior & 63U);
        }
    }
    clear_unused_output_bits(output_eligible, batch.behavior_count);
}

#endif // aarch64

} // namespace lattice::world::entity
