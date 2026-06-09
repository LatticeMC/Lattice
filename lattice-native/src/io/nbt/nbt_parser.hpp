/**
 * @file nbt_parser.hpp
 * @brief Validating one-pass NBT parser that emits a flat off-heap index.
 *
 * Format on the wire (big-endian throughout, as Minecraft writes it):
 *
 *   compound entry  : <tag-id:u8> <name-len:u16> <name:N> <payload>
 *   list payload    : <elem-type:u8> <count:i32> <payload * count>
 *   end marker      : <tag-id:u8 == 0>           (no name, no payload)
 *   byte / short / int / long / float / double : fixed-width payload
 *   byte_array      : <len:i32> <bytes:len>
 *   int_array       : <len:i32> <int32:len * 4>
 *   long_array      : <len:i32> <int64:len * 8>
 *   string          : <len:u16> <bytes:len>      (Java "modified UTF-8")
 *
 * Root: a top-level NbtIo.read produces a COMPOUND tag, header read as
 *   <tag-id:u8 == 10> <name-len:u16> <name:N> <compound payload>
 *
 * Output: each visited tag yields a 16-byte index entry:
 *
 *   offset  size  field
 *   0       1     tag_type     (1..12, never 0)
 *   1       1     depth        (0 = root, +1 per nested compound or list elem)
 *   2       2     name_len     (raw bytes; matches on-wire u16; 0 for list elements)
 *   4       4     name_offset  (into raw bytes; 0 if name_len == 0)
 *   8       4     payload_off  (into raw bytes; first byte of payload)
 *   12      4     payload_len  (byte count of payload, inclusive of nested
 *                               structure for COMPOUND and LIST)
 *
 * For COMPOUND tags `payload_len` covers all child entries plus the END
 * marker. For LIST tags it covers <elem-type:u8> <count:i32> + all element
 * payloads. Children appear in the index immediately after their parent,
 * in pre-order DFS.
 *
 * Tag id 0 (END) never appears in the index; the END marker's byte is
 * accounted for inside the parent COMPOUND's `payload_len`.
 *
 * The parser performs full bounds validation: every advance into the
 * raw byte stream is checked against the input length. On any failure
 * the returned `ParseResult::status` is non-OK and the index buffer is
 * empty / partial.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::io::nbt {

enum class TagId : std::uint8_t {
    kEnd        = 0,
    kByte       = 1,
    kShort      = 2,
    kInt        = 3,
    kLong       = 4,
    kFloat      = 5,
    kDouble     = 6,
    kByteArray  = 7,
    kString     = 8,
    kList       = 9,
    kCompound   = 10,
    kIntArray   = 11,
    kLongArray  = 12,
};

enum class Status : std::uint8_t {
    kOk             = 0,
    kBadArg         = 1,  // null pointer, zero length, etc.
    kTruncated      = 2,  // ran off the end of the input
    kBadTagId       = 3,  // unknown tag id encountered
    kBadLength      = 4,  // negative or implausibly-large length
    kDepthOverflow  = 5,  // nesting exceeded max_depth
    kTagsOverflow   = 6,  // produced more tags than max_tags allowed
    kSizeOverflow   = 7,  // index buffer overflowed our pre-allocation
    kBadRootName    = 8,  // malformed modified UTF-8 in the root name
    kBadTagName     = 9,  // malformed modified UTF-8 in a named tag header
    kBadString      = 10, // malformed modified UTF-8 in a TAG_String payload
};

[[nodiscard]] inline constexpr const char* status_message(Status s) noexcept {
    switch (s) {
        case Status::kOk:            return "ok";
        case Status::kBadArg:        return "invalid argument";
        case Status::kTruncated:     return "truncated NBT payload";
        case Status::kBadTagId:      return "invalid NBT tag id";
        case Status::kBadLength:     return "invalid NBT length field";
        case Status::kDepthOverflow: return "NBT nesting exceeds maxDepth";
        case Status::kTagsOverflow:  return "NBT tag count exceeds maxTags";
        case Status::kSizeOverflow:  return "NBT index buffer overflow";
        case Status::kBadRootName:   return "invalid modified UTF-8 in root name";
        case Status::kBadTagName:    return "invalid modified UTF-8 in tag name";
        case Status::kBadString:     return "invalid modified UTF-8 in TAG_String payload";
    }
    return "unknown NBT parse failure";
}

/// Size in bytes of one index entry. Public for callers that need to
/// size their output buffer ahead of time.
inline constexpr std::size_t kIndexEntrySize = 16;

/// Header at the start of the index buffer. 8 bytes.
inline constexpr std::size_t kIndexHeaderSize = 8;

struct ParseResult {
    Status      status         = Status::kOk;
    std::size_t tag_count      = 0;   // total entries written (excluding END)
    std::size_t index_used     = 0;   // bytes written into the index buffer
    std::size_t bytes_consumed = 0;   // bytes read from the raw input
    std::size_t error_offset   = 0;   // raw-input offset where the error occurred
};

/// Default policy limits. Callers may override to be stricter.
inline constexpr std::size_t kDefaultMaxDepth = 512;     // NbtSizeTracker.DEFAULT_MAX_DEPTH
inline constexpr std::size_t kDefaultMaxTags  = 1u << 20; // 1 Mi tags

/// Compute an upper bound on the index buffer size needed for a worst-case
/// input: every byte is its own one-tag entry (impossible in practice, but
/// safe). Callers that don't pre-allocate `max_tags * kIndexEntrySize` may
/// pass a smaller buffer and rely on `kSizeOverflow` for short-buffer detection.
[[nodiscard]] inline constexpr std::size_t worst_case_index_size(std::size_t input_len) noexcept {
    return kIndexHeaderSize + input_len * kIndexEntrySize;
}

/// Parse `raw_data[0..raw_len)` as an NBT compound stream (i.e. starting
/// with a TAG_Compound id byte). Writes the index into `index_out[0..index_cap)`.
/// On `Status::kOk`, the index is fully populated and `result.tag_count`
/// is exact. On any error, the partial output is undefined — callers
/// should treat the input as malformed.
[[nodiscard]] ParseResult parse(const std::uint8_t* raw_data, std::size_t raw_len,
                                std::uint8_t* index_out, std::size_t index_cap,
                                std::size_t max_depth = kDefaultMaxDepth,
                                std::size_t max_tags  = kDefaultMaxTags) noexcept;

/// Validate `raw_data[0..raw_len)` as an NBT compound stream without
/// materialising the flat index. Useful when callers only need a fast
/// syntax/shape check and exact failure offset.
[[nodiscard]] ParseResult validate(const std::uint8_t* raw_data, std::size_t raw_len,
                                   std::size_t max_depth = kDefaultMaxDepth,
                                   std::size_t max_tags  = kDefaultMaxTags) noexcept;

} // namespace lattice::io::nbt
