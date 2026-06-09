// Thin libdeflate zlib wrapper. See zlib_codec.hpp for the contract.

#include "io/compression/zlib_codec.hpp"

#include <algorithm>
#include <libdeflate.h>

namespace lattice::io::compression {

namespace {

inline int clamp_level(int level) noexcept {
    if (level < kMinLevel) return kMinLevel;
    if (level > kMaxLevel) return kMaxLevel;
    return level;
}

} // namespace

Status zlib_compress(const std::uint8_t* src, std::size_t src_len,
                     std::uint8_t* dst, std::size_t dst_cap,
                     std::size_t* out_written, int level) noexcept {
    if (out_written) *out_written = 0;
    if (!dst || !out_written) return Status::kBadArg;
    if (src_len != 0 && !src) return Status::kBadArg;
    // Zero-length input is legal in zlib; libdeflate will emit a ~8-byte
    // empty zlib stream. Don't short-circuit it.

    libdeflate_compressor* c = libdeflate_alloc_compressor(clamp_level(level));
    if (!c) return Status::kOom;

    const std::size_t n =
        libdeflate_zlib_compress(c, src, src_len, dst, dst_cap);
    libdeflate_free_compressor(c);

    if (n == 0) {
        // libdeflate's zlib_compress returns 0 only when the destination is
        // too small. (It doesn't fail for "bad data" on the compress side.)
        return Status::kShortBuffer;
    }
    *out_written = n;
    return Status::kOk;
}

std::size_t zlib_compress_bound(std::size_t src_len, int level) noexcept {
    libdeflate_compressor* c = libdeflate_alloc_compressor(clamp_level(level));
    if (!c) return 0;
    const std::size_t bound = libdeflate_zlib_compress_bound(c, src_len);
    libdeflate_free_compressor(c);
    return bound;
}

Status zlib_decompress(const std::uint8_t* src, std::size_t src_len,
                       std::uint8_t* dst, std::size_t dst_cap,
                       std::size_t* out_written) noexcept {
    if (out_written) *out_written = 0;
    if (!src || !dst || !out_written) return Status::kBadArg;
    if (src_len == 0) return Status::kBadData; // zlib requires at least a 2-byte header

    libdeflate_decompressor* d = libdeflate_alloc_decompressor();
    if (!d) return Status::kOom;

    std::size_t actual = 0;
    const auto r = libdeflate_zlib_decompress(d, src, src_len,
                                              dst, dst_cap, &actual);
    libdeflate_free_decompressor(d);

    switch (r) {
        case LIBDEFLATE_SUCCESS:
            *out_written = actual;
            return Status::kOk;
        case LIBDEFLATE_INSUFFICIENT_SPACE:
            return Status::kShortBuffer;
        case LIBDEFLATE_BAD_DATA:
        case LIBDEFLATE_SHORT_OUTPUT: // "stream ended mid-stream" — treat as bad data
        default:
            return Status::kBadData;
    }
}

Status zlib_validate(const std::uint8_t* src, std::size_t src_len,
                     std::size_t* out_uncompressed_size) noexcept {
    if (out_uncompressed_size) *out_uncompressed_size = 0;
    if (!src) return Status::kBadArg;
    if (src_len == 0) return Status::kBadData;

    constexpr std::size_t kMinInitial = 64 * 1024;
    std::size_t cap = std::max(kMinInitial, src_len * 8u);
    if (cap < src_len) cap = kMinInitial; // overflow fallback

    for (;;) {
        std::uint8_t* scratch = static_cast<std::uint8_t*>(::operator new(cap, std::nothrow));
        if (!scratch) return Status::kOom;

        std::size_t produced = 0;
        const Status st = zlib_decompress(src, src_len, scratch, cap, &produced);
        ::operator delete(scratch);

        if (st == Status::kOk) {
            if (out_uncompressed_size) *out_uncompressed_size = produced;
            return Status::kOk;
        }
        if (st != Status::kShortBuffer) return st;

        if (cap > (static_cast<std::size_t>(-1) / 2u)) return Status::kOom;
        cap *= 2u;
    }
}

} // namespace lattice::io::compression
