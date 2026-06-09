#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "io/compression/zlib_codec.hpp"

using namespace lattice::io::compression;

static const char* kHelloWorld =
    "Hello, world!  This is a tiny chunk of text we'll compress and decompress "
    "as a smoke test for the libdeflate zlib wrapper. "
    "The same string repeated for compressibility: "
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA. "
    "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB.";

namespace {

std::vector<std::uint8_t> compress_string(const std::string& src,
                                          std::size_t* produced_out = nullptr) {
    std::vector<std::uint8_t> compressed(zlib_compress_bound(src.size()));
    std::size_t produced = 0;
    auto st = zlib_compress(reinterpret_cast<const std::uint8_t*>(src.data()), src.size(),
                            compressed.data(), compressed.size(), &produced);
    REQUIRE(st == Status::kOk);
    compressed.resize(produced);
    if (produced_out) *produced_out = produced;
    return compressed;
}

void check_status(Status actual, Status expected) {
    CHECK(actual == expected);
}

} // namespace

TEST_CASE("zlib_codec: round-trip a short text") {
    const std::string src = kHelloWorld;
    const std::size_t bound = zlib_compress_bound(src.size());
    REQUIRE(bound > 0);

    std::vector<std::uint8_t> compressed(bound);
    std::size_t produced = 0;
    auto st = zlib_compress(
        reinterpret_cast<const std::uint8_t*>(src.data()), src.size(),
        compressed.data(), compressed.size(), &produced);
    REQUIRE(st == Status::kOk);
    CHECK(produced > 0);
    CHECK(produced < src.size()); // compressible input

    std::vector<std::uint8_t> decompressed(src.size() + 16);
    std::size_t out_size = 0;
    st = zlib_decompress(
        compressed.data(), produced,
        decompressed.data(), decompressed.size(), &out_size);
    REQUIRE(st == Status::kOk);
    CHECK(out_size == src.size());
    CHECK(std::memcmp(decompressed.data(), src.data(), src.size()) == 0);
}

TEST_CASE("zlib_codec: rejects truncated input") {
    const std::string src = kHelloWorld;
    std::size_t produced = 0;
    std::vector<std::uint8_t> compressed = compress_string(src, &produced);

    // Truncate to half and try to decompress.
    std::vector<std::uint8_t> out(src.size() + 16);
    std::size_t out_size = 0;
    auto st = zlib_decompress(compressed.data(), produced / 2,
                              out.data(), out.size(), &out_size);
    check_status(st, Status::kBadData);
}

TEST_CASE("zlib_codec: rejects empty compressed input") {
    std::uint8_t dummy = 0;
    std::size_t out_size = 123;

    auto st = zlib_decompress(nullptr, 0, &dummy, 1, &out_size);

    check_status(st, Status::kBadArg);
    CHECK(out_size == 0);
}

TEST_CASE("zlib_codec: rejects invalid zlib header") {
    const std::uint8_t invalid_stream[] = {0x00, 0x00, 0x00, 0x00};
    std::uint8_t out[16]{};
    std::size_t out_size = 0;

    auto st = zlib_decompress(invalid_stream, sizeof(invalid_stream),
                              out, sizeof(out), &out_size);

    check_status(st, Status::kBadData);
}

TEST_CASE("zlib_codec: rejects checksum corruption") {
    const std::string src = kHelloWorld;
    std::size_t produced = 0;
    std::vector<std::uint8_t> compressed = compress_string(src, &produced);
    REQUIRE(produced >= 6);

    compressed[produced - 1] ^= 0x01;

    std::vector<std::uint8_t> out(src.size() + 16);
    std::size_t out_size = 0;
    auto st = zlib_decompress(compressed.data(), produced,
                              out.data(), out.size(), &out_size);
    check_status(st, Status::kBadData);
}

TEST_CASE("zlib_codec: reports kShortBuffer when output too small") {
    const std::string src(2048, 'A'); // very compressible
    std::vector<std::uint8_t> compressed = compress_string(src);

    std::vector<std::uint8_t> tiny_out(64);
    std::size_t out_size = 0;
    auto st = zlib_decompress(compressed.data(), compressed.size(),
                              tiny_out.data(), tiny_out.size(), &out_size);
    check_status(st, Status::kShortBuffer);
}

TEST_CASE("zlib_codec: zero-capacity output reports short buffer") {
    const std::string src = kHelloWorld;
    std::vector<std::uint8_t> compressed = compress_string(src);

    std::uint8_t dummy = 0;
    std::size_t out_size = 0;
    auto st = zlib_decompress(compressed.data(), compressed.size(),
                              &dummy, 0, &out_size);
    check_status(st, Status::kShortBuffer);
}

TEST_CASE("zlib_codec: empty-length output for empty input") {
    // Empty input is valid zlib; we should still get a compressed
    // header + checksum back from the encoder.
    std::vector<std::uint8_t> compressed(64);
    std::size_t produced = 0;
    auto st = zlib_compress(nullptr, 0, compressed.data(), compressed.size(),
                            &produced);
    check_status(st, Status::kOk);
    CHECK(produced > 0);
}

TEST_CASE("zlib_codec: null source with non-zero length is rejected") {
    std::vector<std::uint8_t> compressed(64);
    std::size_t produced = 123;

    auto st = zlib_compress(nullptr, 1, compressed.data(), compressed.size(),
                            &produced);

    check_status(st, Status::kBadArg);
    CHECK(produced == 0);
}

TEST_CASE("zlib_codec: validate accepts valid stream and reports size") {
    const std::string src = kHelloWorld;
    std::size_t produced = 0;
    std::vector<std::uint8_t> compressed = compress_string(src, &produced);
    (void)produced;

    std::size_t uncompressed_size = 0;
    auto st = zlib_validate(compressed.data(), compressed.size(), &uncompressed_size);

    check_status(st, Status::kOk);
    CHECK(uncompressed_size == src.size());
}

TEST_CASE("zlib_codec: validate rejects truncated stream") {
    const std::string src = kHelloWorld;
    std::vector<std::uint8_t> compressed = compress_string(src);

    auto st = zlib_validate(compressed.data(), compressed.size() / 2, nullptr);

    check_status(st, Status::kBadData);
}

TEST_CASE("zlib_codec: validate rejects invalid header") {
    const std::uint8_t invalid_stream[] = {0x00, 0x00, 0x00, 0x00};

    auto st = zlib_validate(invalid_stream, sizeof(invalid_stream), nullptr);

    check_status(st, Status::kBadData);
}

TEST_CASE("zlib_codec: validate rejects null source") {
    auto st = zlib_validate(nullptr, 1, nullptr);
    check_status(st, Status::kBadArg);
}
