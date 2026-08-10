#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <array>
#include <vector>

#include "world/entity/brain_eligibility.hpp"

using namespace lattice::world::entity;

TEST_CASE("brain_eligibility: matches registered, present, and absent memory requirements") {
    constexpr std::size_t entity_count = 2;
    constexpr std::size_t memory_type_count = 65;
    constexpr std::size_t word_count = 2;
    const std::array<std::uint64_t, entity_count * word_count> registered{
        (std::uint64_t{1} << 1U) | (std::uint64_t{1} << 3U), std::uint64_t{1},
        (std::uint64_t{1} << 1U) | (std::uint64_t{1} << 3U), std::uint64_t{0},
    };
    const std::array<std::uint64_t, entity_count * word_count> present{
        std::uint64_t{1} << 1U, std::uint64_t{1},
        std::uint64_t{1} << 3U, std::uint64_t{0},
    };
    const std::array<int, 4> behavior_entities{0, 0, 1, 1};
    const std::array<int, 5> offsets{0, 2, 3, 5, 6};
    const std::array<int, 6> memories{1, 3, 64, 1, 3, 64};
    const std::array<std::uint8_t, 6> statuses{
        static_cast<std::uint8_t>(BrainMemoryStatus::value_present),
        static_cast<std::uint8_t>(BrainMemoryStatus::value_absent),
        static_cast<std::uint8_t>(BrainMemoryStatus::value_present),
        static_cast<std::uint8_t>(BrainMemoryStatus::value_present),
        static_cast<std::uint8_t>(BrainMemoryStatus::value_absent),
        static_cast<std::uint8_t>(BrainMemoryStatus::registered),
    };
    std::array<std::uint8_t, 4> output{};

    evaluate_brain_eligibility({
        registered.data(), present.data(), entity_count, memory_type_count, word_count,
        behavior_entities.data(), offsets.data(), memories.data(), statuses.data(), behavior_entities.size(),
    }, output.data());

    CHECK(output[0] == 1);
    CHECK(output[1] == 1);
    CHECK(output[2] == 0);
    CHECK(output[3] == 0);
}

TEST_CASE("brain_eligibility: preserves behavior order and accepts empty requirements") {
    const std::array<std::uint64_t, 1> registered{0};
    const std::array<std::uint64_t, 1> present{0};
    const std::array<int, 3> behavior_entities{0, 0, 0};
    const std::array<int, 4> offsets{0, 0, 1, 1};
    const std::array<int, 1> memories{0};
    const std::array<std::uint8_t, 1> statuses{
        static_cast<std::uint8_t>(BrainMemoryStatus::registered),
    };
    std::array<std::uint8_t, 3> output{};

    evaluate_brain_eligibility({
        registered.data(), present.data(), 1, 1, 1,
        behavior_entities.data(), offsets.data(), memories.data(), statuses.data(), behavior_entities.size(),
    }, output.data());

    CHECK(output[0] == 1);
    CHECK(output[1] == 0);
    CHECK(output[2] == 1);
}

TEST_CASE("brain_eligibility: packed masks preserve CSR results") {
    const std::array<std::uint64_t, 2> registered{0xFULL, 0x3ULL};
    const std::array<std::uint64_t, 2> present{0x5ULL, 0x1ULL};
    const std::array<int, 2> entities{0, 1};
    const std::array<std::uint64_t, 2> required_registered{0x3ULL, 0x7ULL};
    const std::array<std::uint64_t, 2> required_present{0x1ULL, 0x0ULL};
    const std::array<std::uint64_t, 2> required_absent{0x2ULL, 0x2ULL};
    std::array<std::uint8_t, 2> output{};

    evaluate_brain_eligibility_packed({
        registered.data(), present.data(), 2, 1, entities.data(),
        required_registered.data(), required_present.data(), required_absent.data(), entities.size(),
    }, output.data());

    CHECK(output[0] == 1);
    CHECK(output[1] == 0);
}

TEST_CASE("brain_eligibility: bitmap output clears empty and tail words") {
    std::array<std::uint64_t, 2> empty_output{~std::uint64_t{0}, ~std::uint64_t{0}};
    evaluate_brain_eligibility(
        BrainEligibilityBatch{.behavior_count = 0}, std::span<std::uint64_t>(empty_output));
    CHECK(empty_output[0] == 0);
    CHECK(empty_output[1] == 0);

    constexpr std::size_t behavior_count = 66;
    constexpr std::size_t entity_count = 3;
    const std::array<std::uint64_t, entity_count> registered{0x7, 0x7, 0x7};
    const std::array<std::uint64_t, entity_count> present{0x7, 0x7, 0x7};
    std::vector<int> behavior_entities(behavior_count);
    std::vector<int> offsets(behavior_count + 1);
    std::vector<int> memory_ids(behavior_count, 0);
    std::vector<std::uint8_t> statuses(
        behavior_count, static_cast<std::uint8_t>(BrainMemoryStatus::registered));
    for (std::size_t behavior = 0; behavior < behavior_count; ++behavior) {
        behavior_entities[behavior] = static_cast<int>(behavior % entity_count);
        offsets[behavior] = static_cast<int>(behavior);
    }
    offsets[behavior_count] = static_cast<int>(behavior_count);

    std::array<std::uint64_t, 2> output{~std::uint64_t{0}, ~std::uint64_t{0}};
    evaluate_brain_eligibility({
        registered.data(), present.data(), entity_count, 3, 1,
        behavior_entities.data(), offsets.data(), memory_ids.data(), statuses.data(), behavior_count,
    }, std::span<std::uint64_t>(output));
    CHECK(output[0] == ~std::uint64_t{0});
    CHECK(output[1] == 0x3);

    std::vector<std::uint64_t> packed_registered(entity_count, 0x7);
    std::vector<std::uint64_t> packed_present(entity_count, 0x7);
    std::vector<std::uint64_t> required_registered(behavior_count, 0);
    std::vector<std::uint64_t> required_present(behavior_count, 0);
    std::vector<std::uint64_t> required_absent(behavior_count, 0);
    output = {~std::uint64_t{0}, ~std::uint64_t{0}};
    evaluate_brain_eligibility_packed({
        packed_registered.data(), packed_present.data(), entity_count, 1,
        behavior_entities.data(), required_registered.data(), required_present.data(),
        required_absent.data(), behavior_count,
    }, std::span<std::uint64_t>(output));
    CHECK(output[0] == ~std::uint64_t{0});
    CHECK(output[1] == 0x3);
}

TEST_CASE("brain_eligibility: packed dispatcher matches scalar across NEON pair and tail words") {
    constexpr std::size_t behavior_count = 67;
    constexpr std::size_t entity_count = 3;
    constexpr std::size_t word_count = 3;

    const std::array<std::uint64_t, entity_count * word_count> registered{
        0xFULL, 0x3ULL, 0x5ULL,
        0x7ULL, 0xCULL, 0x1ULL,
        0x3ULL, 0x6ULL, 0x9ULL,
    };
    const std::array<std::uint64_t, entity_count * word_count> present{
        0x5ULL, 0x1ULL, 0x4ULL,
        0x3ULL, 0x8ULL, 0x0ULL,
        0x1ULL, 0x2ULL, 0x8ULL,
    };
    std::vector<int> behavior_entities(behavior_count);
    std::vector<std::uint64_t> required_registered(behavior_count * word_count);
    std::vector<std::uint64_t> required_present(behavior_count * word_count);
    std::vector<std::uint64_t> required_absent(behavior_count * word_count);

    for (std::size_t behavior = 0; behavior < behavior_count; ++behavior) {
        const std::size_t entity = behavior % entity_count;
        behavior_entities[behavior] = static_cast<int>(entity);
        const std::size_t entity_base = entity * word_count;
        const std::size_t mask_base = behavior * word_count;
        for (std::size_t word = 0; word < word_count; ++word) {
            const std::uint64_t registered_word = registered[entity_base + word];
            const std::uint64_t present_word = present[entity_base + word];
            required_registered[mask_base + word] = registered_word & 0x3ULL;
            required_present[mask_base + word] = present_word & 0x1ULL;
            required_absent[mask_base + word] = registered_word & ~present_word & 0x4ULL;
        }
        if (behavior % 5U == 0U) {
            required_present[mask_base + 2U] |= 0x2ULL;
        }
    }

    const BrainPackedEligibilityBatch batch{
        registered.data(), present.data(), entity_count, word_count,
        behavior_entities.data(), required_registered.data(), required_present.data(),
        required_absent.data(), behavior_count,
    };
    std::array<std::uint64_t, 2> scalar{~std::uint64_t{0}, ~std::uint64_t{0}};
    std::array<std::uint64_t, 2> dispatched{~std::uint64_t{0}, ~std::uint64_t{0}};

    evaluate_brain_eligibility_packed_scalar(batch, scalar);
    init_brain_eligibility_dispatch();
    evaluate_brain_eligibility_packed(batch, dispatched);

    CHECK(dispatched == scalar);
    CHECK((dispatched[1] & ~std::uint64_t{0x7}) == 0U);
}
