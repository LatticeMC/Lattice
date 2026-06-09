/**
 * @file zlib_codec.hpp
 * @brief Thin, thread-safe libdeflate wrapper speaking the RFC 1950 (zlib)
 *        format used by Minecraft's RegionFile with COMPRESSION_ZLIB = 2.
 *
 * Responsibilities
 * ----------------
 * - Compress a raw byte run into zlib-wrapped DEFLATE bytes.
 * - Decompress zlib-wrapped DEFLATE bytes into a raw byte run.
 * - Provide a "get uncompressed size" probe for callers that don't know the
 *   output size in advance. libdeflate requires an upper bound on output
 *   size for decompression; we expose a grow-and-retry helper for that.
 *
 * What this module is explicitly *not*
 * ------------------------------------
 * - Not a general-purpose compression façade. Only zlib is supported.
 *   (gzip, uncompressed, lz4 and custom are handled on the Java side.)
 * - Not an NBT parser. Callers hand us bytes and get bytes back.
 * - Not a buffer-cache / arena. The caller owns input and output memory.
 *
 * Thread safety
 * -------------
 * libdeflate's compressor / decompressor objects are not thread-safe
 * individually, but our entry points allocate a fresh object per call.
 * Cheap (~a few KB malloc) and avoids synchronisation complexity; revisit
 * once profiling shows it matters.
 *
 * Error model
 * -----------
 * All functions are noexcept and return a `Status` enum plus (for
 * decompression) the number of bytes actually written. Output-size
 * overflow (`kShortBuffer`) is a recoverable outcome, not an error.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::io::compression {

enum class Status : std::uint8_t {
    kOk         = 0,
    kBadData    = 1,   // input is not valid zlib / DEFLATE
    kShortBuffer= 2,   // output buffer too small to hold decompressed data
    kOom        = 3,   // libdeflate_alloc_* returned null
    kBadArg     = 4,   // null pointer, zero length, etc.
};

[[nodiscard]] inline constexpr const char* status_message(Status s) noexcept {
    switch (s) {
        case Status::kOk:          return "ok";
        case Status::kBadData:     return "corrupt or truncated zlib stream";
        case Status::kShortBuffer: return "output buffer too small";
        case Status::kOom:         return "out of memory";
        case Status::kBadArg:      return "invalid argument";
    }
    return "unknown zlib status";
}

/// Compression level. 1 = fastest, 12 = slowest/densest (libdeflate range).
/// Minecraft's vanilla deflater runs at level 6 (Java default); we keep
/// that as the default here for bit-similar output density. Output bytes
/// need not be bit-identical to JDK's output (decoders only care about
/// semantic equivalence), so comparing to JDK is a smoke test, not a
/// conformance test.
constexpr int kDefaultLevel  = 6;
constexpr int kMinLevel      = 1;
constexpr int kMaxLevel      = 12;

/// Compress `src` (`src_len` bytes) into the caller-provided buffer
/// `dst` (`dst_cap` bytes) using zlib framing. On success, writes the
/// number of bytes produced into `*out_written` and returns `kOk`.
///
/// The required output capacity is bounded above by
/// `zlib_compress_bound(src_len)` — callers that cannot grow their buffer
/// should size it to that bound.
///
/// `level` is clamped to `[kMinLevel, kMaxLevel]`.
[[nodiscard]] Status zlib_compress(
    const std::uint8_t* src, std::size_t src_len,
    std::uint8_t* dst, std::size_t dst_cap,
    std::size_t* out_written,
    int level = kDefaultLevel) noexcept;

/// Worst-case compressed size for a given input length and level.
/// Returns `0` on bad arguments. Useful to right-size `dst_cap`.
[[nodiscard]] std::size_t zlib_compress_bound(std::size_t src_len,
                                              int level = kDefaultLevel) noexcept;

/// Decompress `src` (`src_len` bytes) of zlib data into `dst` (`dst_cap`
/// bytes). On success, writes the exact uncompressed length into
/// `*out_written` and returns `kOk`. If `dst_cap` is too small for the
/// decompressed data, returns `kShortBuffer` (the caller may retry with a
/// larger buffer). Returns `kBadData` if the input is not a valid zlib
/// stream or is truncated.
[[nodiscard]] Status zlib_decompress(
    const std::uint8_t* src, std::size_t src_len,
    std::uint8_t* dst, std::size_t dst_cap,
    std::size_t* out_written) noexcept;

/// Validate that `src[0..src_len)` is a syntactically-correct zlib stream
/// without requiring the caller to provide an output buffer. On success,
/// optionally stores the exact uncompressed byte length in
/// `*out_uncompressed_size`.
[[nodiscard]] Status zlib_validate(
    const std::uint8_t* src, std::size_t src_len,
    std::size_t* out_uncompressed_size = nullptr) noexcept;

} // namespace lattice::io::compression
