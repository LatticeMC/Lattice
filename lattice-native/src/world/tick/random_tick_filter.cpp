// Random-tick candidate filter. See random_tick_filter.hpp.
//
// Trivial loop: for each candidate, decode (sectionIdx, localIdx), read
// the packed palette index from the section storage, test the bit in
// the section's tick mask. Output the accepted candidate's index into
// out_accepted_indices.

#include "world/tick/random_tick_filter.hpp"

namespace lattice::world::tick {

namespace {

inline std::uint32_t read_packed_idx(const std::uint64_t* storage,
                                     std::size_t storage_len_longs,
                                     int element_bits,
                                     std::uint32_t local_idx) noexcept {
    if (!storage || element_bits <= 0 || element_bits > 32) return 0;
    const int           epl  = 64 / element_bits;
    const std::uint64_t mask = (element_bits >= 64)
        ? ~std::uint64_t{0}
        : (std::uint64_t{1} << element_bits) - 1u;
    const std::size_t long_idx = local_idx / static_cast<std::size_t>(epl);
    if (long_idx >= storage_len_longs) return 0;
    const int bit_off = int(local_idx % static_cast<std::size_t>(epl)) * element_bits;
    return static_cast<std::uint32_t>((storage[long_idx] >> bit_off) & mask);
}

} // namespace

std::size_t filter_random_ticks(const RandomTickFilterInputs& in,
                                std::uint32_t* out_accepted_indices) noexcept {
    if (!out_accepted_indices) return 0;
    if (!in.candidates_packed || in.candidate_count == 0
        || !in.section_tick_masks || in.section_count == 0
        || in.mask_longs_per_section == 0) {
        return 0;
    }

    std::size_t out_count = 0;
    for (std::size_t i = 0; i < in.candidate_count; ++i) {
        const std::uint32_t packed = in.candidates_packed[i];
        const std::uint32_t local_idx = packed & 0xFFFu;
        const std::uint32_t section_idx = packed >> 12;
        if (section_idx >= in.section_count) continue;
        if (local_idx >= 4096) continue; // 16*16*16

        const std::uint64_t* storage = in.section_storages
            ? in.section_storages[section_idx] : nullptr;
        const std::size_t    s_len   = in.section_storage_lens
            ? in.section_storage_lens[section_idx] : 0;
        const int            bits    = in.section_element_bits
            ? in.section_element_bits[section_idx] : 0;

        std::uint32_t pal_idx;
        if (storage == nullptr || bits == 0) {
            pal_idx = 0;
        } else {
            pal_idx = read_packed_idx(storage, s_len, bits, local_idx);
        }
        const std::size_t mask_word = static_cast<std::size_t>(pal_idx) / 64u;
        if (mask_word >= in.mask_longs_per_section) continue;

        const std::uint64_t* mask = in.section_tick_masks
                                  + section_idx * in.mask_longs_per_section;
        if (((mask[pal_idx / 64] >> (pal_idx % 64)) & 1ULL) != 0) {
            out_accepted_indices[out_count++] = static_cast<std::uint32_t>(i);
        }
    }
    return out_count;
}

} // namespace lattice::world::tick
