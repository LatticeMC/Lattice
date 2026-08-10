/**
 * @file brain_eligibility.hpp
 * @brief Side-effect-free batch evaluator for Brain memory requirements.
 *
 * This is intentionally limited to the same memory-state predicate checked by
 * `Brain#checkMemory`. It does not run sensors or behaviors, inspect the
 * world, or mutate a Brain. Java remains responsible for all callbacks and
 * committing every behavior transition.
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <span>

namespace lattice::world::entity {

enum class BrainMemoryStatus : std::uint8_t {
    value_present = 0,
    value_absent = 1,
    registered = 2,
};

/// A compact SoA/CSR view of memory requirements for a tick-local batch.
/// Each entity owns `memory_word_count` consecutive 64-bit words in the
/// registered and present bit planes. Every behavior maps to an entity and a
/// range in the CSR requirement arrays. Output order always follows behavior
/// order exactly.
struct BrainEligibilityBatch {
    const std::uint64_t* registered_memory_bits = nullptr;
    const std::uint64_t* present_memory_bits = nullptr;
    std::size_t entity_count = 0;
    std::size_t memory_type_count = 0;
    std::size_t memory_word_count = 0;

    const int* behavior_entity_indices = nullptr;
    const int* requirement_offsets = nullptr;
    const int* requirement_memory_ids = nullptr;
    const std::uint8_t* requirement_statuses = nullptr;
    std::size_t behavior_count = 0;
};

struct BrainPackedEligibilityBatch {
    const std::uint64_t* registered_memory_bits = nullptr;
    const std::uint64_t* present_memory_bits = nullptr;
    std::size_t entity_count = 0;
    std::size_t memory_word_count = 0;
    const int* behavior_entity_indices = nullptr;
    const std::uint64_t* required_registered_bits = nullptr;
    const std::uint64_t* required_present_bits = nullptr;
    const std::uint64_t* required_absent_bits = nullptr;
    std::size_t behavior_count = 0;
};

/// Evaluates memory eligibility only. The caller validates all indices and
/// array lengths before entry. The span overload writes one LSB-first bit per
/// behavior (bit i is set when behavior i is eligible), clearing its output
/// words before evaluation. No input is modified.
void evaluate_brain_eligibility(
    const BrainEligibilityBatch& batch,
    std::span<std::uint64_t> output_eligible) noexcept;

void evaluate_brain_eligibility(
    const BrainEligibilityBatch& batch,
    std::uint8_t* output_eligible) noexcept;

void evaluate_brain_eligibility_packed(
    const BrainPackedEligibilityBatch& batch,
    std::span<std::uint64_t> output_eligible) noexcept;

/// Scalar packed evaluator. Always available; exposed so dispatch tests can
/// compare specialised implementations against the reference path.
void evaluate_brain_eligibility_packed_scalar(
    const BrainPackedEligibilityBatch& batch,
    std::span<std::uint64_t> output_eligible) noexcept;

#if defined(__aarch64__) || defined(_M_ARM64)
/// AArch64 NEON packed evaluator. Same bitmap contract as the scalar path.
void evaluate_brain_eligibility_packed_neon(
    const BrainPackedEligibilityBatch& batch,
    std::span<std::uint64_t> output_eligible) noexcept;
#endif

void evaluate_brain_eligibility_packed(
    const BrainPackedEligibilityBatch& batch,
    std::uint8_t* output_eligible) noexcept;

void init_brain_eligibility_dispatch() noexcept;

} // namespace lattice::world::entity
