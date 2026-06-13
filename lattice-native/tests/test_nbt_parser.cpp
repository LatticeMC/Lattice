#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <cstdint>
#include <string>
#include <vector>

#include "io/nbt/nbt_parser.hpp"

using namespace lattice::io::nbt;

// Helpers for building tiny NBT byte streams.

namespace {

void push_u8(std::vector<std::uint8_t>& v, std::uint8_t x) { v.push_back(x); }
void push_u16_be(std::vector<std::uint8_t>& v, std::uint16_t x) {
    v.push_back(static_cast<std::uint8_t>((x >> 8) & 0xFF));
    v.push_back(static_cast<std::uint8_t>(x & 0xFF));
}
void push_i32_be(std::vector<std::uint8_t>& v, std::int32_t x) {
    v.push_back(static_cast<std::uint8_t>((x >> 24) & 0xFF));
    v.push_back(static_cast<std::uint8_t>((x >> 16) & 0xFF));
    v.push_back(static_cast<std::uint8_t>((x >> 8) & 0xFF));
    v.push_back(static_cast<std::uint8_t>(x & 0xFF));
}
void push_name(std::vector<std::uint8_t>& v, const std::string& name) {
    push_u16_be(v, static_cast<std::uint16_t>(name.size()));
    for (char c : name) push_u8(v, static_cast<std::uint8_t>(c));
}

ParseResult parse_into_index(const std::vector<std::uint8_t>& raw,
                             std::size_t max_depth = kDefaultMaxDepth,
                             std::size_t max_tags = kDefaultMaxTags) {
    std::vector<std::uint8_t> idx(worst_case_index_size(raw.size()));
    return parse(raw.data(), raw.size(), idx.data(), idx.size(), max_depth, max_tags);
}

ParseResult validate_only(const std::vector<std::uint8_t>& raw,
                          std::size_t max_depth = kDefaultMaxDepth,
                          std::size_t max_tags = kDefaultMaxTags) {
    return validate(raw.data(), raw.size(), max_depth, max_tags);
}

void check_parse_status(const ParseResult& r, Status expected_status,
                        std::size_t expected_offset = 0) {
    CHECK(r.status == expected_status);
    CHECK(r.error_offset == expected_offset);
}

} // namespace

TEST_CASE("nbt_parser: empty root compound") {
    // Root compound with no name and no children:
    //   [10] [0x00 0x00] [0x00]
    std::vector<std::uint8_t> raw{10, 0x00, 0x00, 0x00};
    std::vector<std::uint8_t> idx(worst_case_index_size(raw.size()));
    auto r = parse(raw.data(), raw.size(), idx.data(), idx.size());
    CHECK(r.status == Status::kOk);
    CHECK(r.tag_count == 1);
    CHECK(r.bytes_consumed == raw.size());
}

TEST_CASE("nbt_parser: one byte tag inside root compound") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);            // root tag id = compound
    push_u16_be(raw, 0);         // root name length = 0
    push_u8(raw, 1);             // child tag id = byte
    push_name(raw, "B");         // child name = "B"
    push_u8(raw, 0x7F);          // payload
    push_u8(raw, 0);             // END

    std::vector<std::uint8_t> idx(worst_case_index_size(raw.size()));
    auto r = parse(raw.data(), raw.size(), idx.data(), idx.size());
    REQUIRE(r.status == Status::kOk);
    CHECK(r.tag_count == 2);

    // Inspect the second entry — child byte tag.
    const std::uint8_t* e = idx.data() + kIndexHeaderSize + kIndexEntrySize;
    CHECK(e[0] == static_cast<std::uint8_t>(TagId::kByte));
    CHECK(e[1] == 1u);          // depth
    const std::uint16_t name_len = (std::uint16_t(e[2]) << 8) | e[3];
    CHECK(name_len == 1u);
}

TEST_CASE("nbt_parser: list of ints") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 9);          // child tag id = list
    push_name(raw, "L");
    push_u8(raw, 3);          // elem type = int
    push_i32_be(raw, 3);      // count = 3
    push_i32_be(raw, 100);
    push_i32_be(raw, 200);
    push_i32_be(raw, 300);
    push_u8(raw, 0);          // END

    std::vector<std::uint8_t> idx(worst_case_index_size(raw.size()));
    auto r = parse(raw.data(), raw.size(), idx.data(), idx.size());
    REQUIRE(r.status == Status::kOk);
    // 1 root + 1 list + 3 int elements = 5.
    CHECK(r.tag_count == 5);
}

TEST_CASE("nbt_parser: truncated input rejected") {
    // Compound but no END:
    std::vector<std::uint8_t> raw{10, 0x00, 0x00, 1};
    std::vector<std::uint8_t> idx(worst_case_index_size(raw.size()));
    auto r = parse(raw.data(), raw.size(), idx.data(), idx.size());
    CHECK(r.status == Status::kTruncated);
}

TEST_CASE("nbt_parser: bad root tag rejected") {
    std::vector<std::uint8_t> raw{1, 0x00, 0x00}; // root claims to be a byte
    std::vector<std::uint8_t> idx(worst_case_index_size(raw.size()));
    auto r = parse(raw.data(), raw.size(), idx.data(), idx.size());
    CHECK(r.status == Status::kBadTagId);
}

TEST_CASE("nbt_parser: validate-only accepts valid compound") {
    std::vector<std::uint8_t> raw{10, 0x00, 0x00, 0x00};

    auto r = validate_only(raw);

    CHECK(r.status == Status::kOk);
    CHECK(r.tag_count == 1);
    CHECK(r.index_used == 0);
    CHECK(r.bytes_consumed == raw.size());
}

TEST_CASE("nbt_parser: validate-only reports malformed string") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 1);
    push_u8(raw, 0x00);
    push_u8(raw, 0);

    auto r = validate_only(raw);

    check_parse_status(r, Status::kBadRootName, 3);
    CHECK(r.index_used == 0);
}

TEST_CASE("nbt_parser: negative array length rejected") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 7);            // TAG_Byte_Array
    push_name(raw, "A");
    push_i32_be(raw, -1);
    push_u8(raw, 0);

    std::vector<std::uint8_t> idx(worst_case_index_size(raw.size()));
    auto r = parse(raw.data(), raw.size(), idx.data(), idx.size());
    CHECK(r.status == Status::kBadLength);
}

TEST_CASE("nbt_parser: depth overflow rejected") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 10);           // child compound
    push_name(raw, "A");
    push_u8(raw, 0);            // child END
    push_u8(raw, 0);            // root END

    auto r = parse_into_index(raw, 1, kDefaultMaxTags);
    check_parse_status(r, Status::kDepthOverflow, 7);
}

TEST_CASE("nbt_parser: tag cap overflow rejected") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 1);            // child byte
    push_name(raw, "B");
    push_u8(raw, 0x01);
    push_u8(raw, 0);

    auto r = parse_into_index(raw, kDefaultMaxDepth, 1);
    check_parse_status(r, Status::kTagsOverflow, 7);
}

TEST_CASE("nbt_parser: empty list accepts arbitrary element type byte") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 9);            // TAG_List
    push_name(raw, "L");
    push_u8(raw, 99);           // invalid type id, but count=0
    push_i32_be(raw, 0);
    push_u8(raw, 0);

    auto r = parse_into_index(raw);
    CHECK(r.status == Status::kOk);
    CHECK(r.tag_count == 2);
}

TEST_CASE("nbt_parser: invalid modified UTF-8 in tag name rejected") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 1);            // child byte
    push_u16_be(raw, 1);        // one byte of name data
    push_u8(raw, 0x00);         // invalid MUTF-8: raw NUL byte
    push_u8(raw, 0x01);
    push_u8(raw, 0);

    auto r = parse_into_index(raw);
    check_parse_status(r, Status::kBadTagName, 6);
}

TEST_CASE("nbt_parser: invalid modified UTF-8 in TAG_String rejected") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 8);            // TAG_String
    push_name(raw, "S");
    push_u16_be(raw, 1);        // one byte of string payload
    push_u8(raw, 0x00);         // invalid MUTF-8: raw NUL byte
    push_u8(raw, 0);

    auto r = parse_into_index(raw);
    check_parse_status(r, Status::kBadString, 7);
}

TEST_CASE("nbt_parser: invalid modified UTF-8 in root name rejected") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 1);
    push_u8(raw, 0x00);         // invalid MUTF-8: raw NUL byte
    push_u8(raw, 0);

    auto r = parse_into_index(raw);
    check_parse_status(r, Status::kBadRootName, 3);
}

TEST_CASE("nbt_parser: truncated TAG_String payload rejected") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 8);            // TAG_String
    push_name(raw, "S");
    push_u16_be(raw, 2);        // declares two payload bytes
    push_u8(raw, 'A');          // only one byte present — stream ends here

    auto r = parse_into_index(raw);
    check_parse_status(r, Status::kTruncated, 7);
}

TEST_CASE("nbt_parser: invalid two-byte modified UTF-8 rejected") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 8);            // TAG_String
    push_name(raw, "S");
    push_u16_be(raw, 2);
    push_u8(raw, 0xC2);         // missing continuation semantics
    push_u8(raw, 'A');
    push_u8(raw, 0);

    auto r = parse_into_index(raw);
    check_parse_status(r, Status::kBadString, 7);
}

TEST_CASE("nbt_parser: invalid three-byte modified UTF-8 rejected") {
    std::vector<std::uint8_t> raw;
    push_u8(raw, 10);
    push_u16_be(raw, 0);
    push_u8(raw, 8);            // TAG_String
    push_name(raw, "S");
    push_u16_be(raw, 3);
    push_u8(raw, 0xE0);         // overlong 3-byte form for ASCII range
    push_u8(raw, 0x80);
    push_u8(raw, 0x41);
    push_u8(raw, 0);

    auto r = parse_into_index(raw);
    check_parse_status(r, Status::kBadString, 7);
}
