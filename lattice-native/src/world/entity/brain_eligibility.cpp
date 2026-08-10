#include "world/entity/brain_eligibility.hpp"

#include <algorithm>
#include <atomic>

#if defined(LATTICE_HAS_BRAIN_ELIGIBILITY_NEON)
#  include "lattice/dispatch.hpp"
#endif

namespace lattice::world::entity {
namespace {

[[nodiscard]] bool matches_memory_status(
    const BrainMemoryStatus status,
    const bool registered,
    const bool present) noexcept {
    switch (status) {
    case BrainMemoryStatus::value_present:
        return registered && present;
    case BrainMemoryStatus::value_absent:
        return registered && !present;
    case BrainMemoryStatus::registered:
        return registered;
    }
    return false;
}

} // namespace

namespace {

[[nodiscard]] std::size_t output_word_count(const std::size_t behavior_count) noexcept {
    return behavior_count / 64U + (behavior_count % 64U != 0U ? 1U : 0U);
}

[[nodiscard]] bool evaluate_csr_behavior(
    const BrainEligibilityBatch& batch,
    const std::size_t behavior) noexcept {
    const std::size_t entity = static_cast<std::size_t>(batch.behavior_entity_indices[behavior]);
    const std::size_t bit_base = entity * batch.memory_word_count;
    const int requirement_begin = batch.requirement_offsets[behavior];
    const int requirement_end = batch.requirement_offsets[behavior + 1];
    for (int requirement = requirement_begin; requirement < requirement_end; ++requirement) {
        const std::size_t memory_id = static_cast<std::size_t>(batch.requirement_memory_ids[requirement]);
        const std::size_t word = memory_id >> 6U;
        const std::uint64_t bit = std::uint64_t{1} << (memory_id & 63U);
        const bool registered = (batch.registered_memory_bits[bit_base + word] & bit) != 0;
        const bool present = (batch.present_memory_bits[bit_base + word] & bit) != 0;
        const auto status = static_cast<BrainMemoryStatus>(batch.requirement_statuses[requirement]);
        if (!matches_memory_status(status, registered, present)) return false;
    }
    return true;
}

[[nodiscard]] bool evaluate_packed_behavior(
    const BrainPackedEligibilityBatch& batch,
    const std::size_t behavior) noexcept {
    const std::size_t entity_base = static_cast<std::size_t>(batch.behavior_entity_indices[behavior])
            * batch.memory_word_count;
    const std::size_t mask_base = behavior * batch.memory_word_count;
    for (std::size_t word = 0; word < batch.memory_word_count; ++word) {
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

void clear_unused_output_bits(std::span<std::uint64_t> output, const std::size_t behavior_count) noexcept {
    if (behavior_count == 0U) return;
    const std::size_t used_words = output_word_count(behavior_count);
    const std::size_t used_bits = behavior_count % 64U;
    if (used_bits != 0U) output[used_words - 1U] &= (std::uint64_t{1} << used_bits) - 1U;
}

} // namespace

void evaluate_brain_eligibility(
    const BrainEligibilityBatch& batch,
    const std::span<std::uint64_t> output_eligible) noexcept {
    std::fill(output_eligible.begin(), output_eligible.end(), std::uint64_t{0});
    for (std::size_t behavior = 0; behavior < batch.behavior_count; ++behavior) {
        if (evaluate_csr_behavior(batch, behavior)) {
            output_eligible[behavior >> 6U] |= std::uint64_t{1} << (behavior & 63U);
        }
    }
    clear_unused_output_bits(output_eligible, batch.behavior_count);
}

void evaluate_brain_eligibility(
    const BrainEligibilityBatch& batch,
    std::uint8_t* const output_eligible) noexcept {
    for (std::size_t behavior = 0; behavior < batch.behavior_count; ++behavior) {
        output_eligible[behavior] = evaluate_csr_behavior(batch, behavior) ? std::uint8_t{1} : std::uint8_t{0};
    }
}

void evaluate_brain_eligibility_packed_scalar(
    const BrainPackedEligibilityBatch& batch,
    const std::span<std::uint64_t> output_eligible) noexcept {
    std::fill(output_eligible.begin(), output_eligible.end(), std::uint64_t{0});
    for (std::size_t behavior = 0; behavior < batch.behavior_count; ++behavior) {
        if (evaluate_packed_behavior(batch, behavior)) {
            output_eligible[behavior >> 6U] |= std::uint64_t{1} << (behavior & 63U);
        }
    }
    clear_unused_output_bits(output_eligible, batch.behavior_count);
}

namespace {

using PackedBitmapFn = void (*)(
    const BrainPackedEligibilityBatch&,
    std::span<std::uint64_t>) noexcept;

std::atomic<PackedBitmapFn> g_packed_bitmap{&evaluate_brain_eligibility_packed_scalar};
std::atomic<bool> g_packed_initialised{false};

} // namespace

void init_brain_eligibility_dispatch() noexcept {
    if (g_packed_initialised.load(std::memory_order_acquire)) return;
    PackedBitmapFn fn = &evaluate_brain_eligibility_packed_scalar;

#if defined(LATTICE_HAS_BRAIN_ELIGIBILITY_NEON)
    const auto& f = lattice::cpu::features();
    if (f.neon) fn = &evaluate_brain_eligibility_packed_neon;
#endif

    g_packed_bitmap.store(fn, std::memory_order_release);
    g_packed_initialised.store(true, std::memory_order_release);
}

void evaluate_brain_eligibility_packed(
    const BrainPackedEligibilityBatch& batch,
    const std::span<std::uint64_t> output_eligible) noexcept {
    if (!g_packed_initialised.load(std::memory_order_acquire)) {
        init_brain_eligibility_dispatch();
    }
    g_packed_bitmap.load(std::memory_order_acquire)(batch, output_eligible);
}

void evaluate_brain_eligibility_packed(
    const BrainPackedEligibilityBatch& batch,
    std::uint8_t* const output_eligible) noexcept {
    for (std::size_t behavior = 0; behavior < batch.behavior_count; ++behavior) {
        output_eligible[behavior] = evaluate_packed_behavior(batch, behavior) ? std::uint8_t{1} : std::uint8_t{0};
    }
}

} // namespace lattice::world::entity
