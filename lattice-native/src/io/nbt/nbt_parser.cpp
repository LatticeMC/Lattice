// One-pass NBT parser with full bounds validation. See nbt_parser.hpp
// for the wire format and output index layout.
//
// Implementation notes:
//
//   - Big-endian throughout. All multi-byte values are read MSB-first
//     using a tiny inline helper, never via memcpy + bswap (which the
//     compiler turns into the same code anyway under -O2+ but is less
//     obvious to read).
//
//   - No recursion. NBT is bounded-depth (vanilla limit 512) but unbounded
//     fan-out within a level. We use an explicit stack of frames and let
//     the compiler turn the dispatch into a tight switch. This keeps
//     stack usage independent of input depth and avoids the recursive-
//     call overhead.
//
//   - No exceptions. All errors funnel through ParseResult::status.

#include "io/nbt/nbt_parser.hpp"

#include <cstdlib>  // std::malloc, std::free
#include <cstring>

namespace lattice::io::nbt {

namespace {

// ---- Big-endian primitive reads ----

inline std::uint8_t  read_u8 (const std::uint8_t* p) noexcept { return p[0]; }
inline std::uint16_t read_u16(const std::uint8_t* p) noexcept {
    return static_cast<std::uint16_t>((std::uint16_t(p[0]) << 8) | p[1]);
}
inline std::int32_t  read_i32(const std::uint8_t* p) noexcept {
    return static_cast<std::int32_t>(
        (std::uint32_t(p[0]) << 24) | (std::uint32_t(p[1]) << 16)
      | (std::uint32_t(p[2]) << 8)  |  std::uint32_t(p[3]));
}

// ---- Index writer ----

class IndexWriter {
public:
    IndexWriter(std::uint8_t* buf, std::size_t cap) noexcept
        : buf_(buf), cap_(cap), pos_(kIndexHeaderSize) {
        // Reserve the header; final tag_count etc. is written at the end.
        if (cap_ >= kIndexHeaderSize) {
            std::memset(buf_, 0, kIndexHeaderSize);
        }
    }

    [[nodiscard]] bool write(TagId type, std::uint8_t depth,
                             std::uint16_t name_len, std::uint32_t name_offset,
                             std::uint32_t payload_offset,
                             std::uint32_t payload_len) noexcept {
        if (pos_ + kIndexEntrySize > cap_) return false;
        buf_[pos_ + 0] = static_cast<std::uint8_t>(type);
        buf_[pos_ + 1] = depth;
        buf_[pos_ + 2] = static_cast<std::uint8_t>(name_len >> 8);
        buf_[pos_ + 3] = static_cast<std::uint8_t>(name_len & 0xFF);
        write_u32_le(pos_ + 4,  name_offset);
        write_u32_le(pos_ + 8,  payload_offset);
        write_u32_le(pos_ + 12, payload_len);
        pos_ += kIndexEntrySize;
        ++tag_count_;
        return true;
    }

    /// Patch the `payload_len` of the entry written at offset `entry_pos`.
    /// Used after a COMPOUND/LIST has had all its children consumed: we
    /// only know the total payload length after we've walked the children.
    void patch_payload_len(std::size_t entry_pos, std::uint32_t payload_len) noexcept {
        if (entry_pos + kIndexEntrySize > cap_) return;
        write_u32_le(entry_pos + 12, payload_len);
    }

    void finalize() noexcept {
        // Write header: u32 tag_count + u32 reserved (0).
        write_u32_le(0, static_cast<std::uint32_t>(tag_count_));
        write_u32_le(4, 0);
    }

    [[nodiscard]] std::size_t bytes_used() const noexcept { return pos_; }
    [[nodiscard]] std::size_t tag_count() const noexcept  { return tag_count_; }
    [[nodiscard]] std::size_t last_entry_pos() const noexcept {
        return pos_ - kIndexEntrySize;
    }

private:
    // Little-endian writes inside the index buffer, so Java can read
    // them with ByteBuffer.order(ByteOrder.LITTLE_ENDIAN).getInt(). This
    // matches host endianness on x86 and AArch64-LE — same in CI; for
    // big-endian hosts (none in our matrix) the encoding is explicit.
    inline void write_u32_le(std::size_t off, std::uint32_t v) noexcept {
        buf_[off + 0] = static_cast<std::uint8_t>(v & 0xFF);
        buf_[off + 1] = static_cast<std::uint8_t>((v >> 8) & 0xFF);
        buf_[off + 2] = static_cast<std::uint8_t>((v >> 16) & 0xFF);
        buf_[off + 3] = static_cast<std::uint8_t>((v >> 24) & 0xFF);
    }

    std::uint8_t* buf_;
    std::size_t   cap_;
    std::size_t   pos_;
    std::size_t   tag_count_ = 0;
};

class NullIndexWriter {
public:
    [[nodiscard]] bool write(TagId /*type*/, std::uint8_t /*depth*/,
                             std::uint16_t /*name_len*/, std::uint32_t /*name_offset*/,
                             std::uint32_t /*payload_offset*/,
                             std::uint32_t /*payload_len*/) noexcept {
        last_entry_pos_ = next_entry_pos_;
        next_entry_pos_ += kIndexEntrySize;
        ++tag_count_;
        return true;
    }

    void patch_payload_len(std::size_t /*entry_pos*/, std::uint32_t /*payload_len*/) noexcept {}
    void finalize() noexcept {}

    [[nodiscard]] std::size_t bytes_used() const noexcept { return 0; }
    [[nodiscard]] std::size_t tag_count() const noexcept { return tag_count_; }
    [[nodiscard]] std::size_t last_entry_pos() const noexcept { return last_entry_pos_; }

private:
    std::size_t next_entry_pos_ = kIndexHeaderSize;
    std::size_t last_entry_pos_ = 0;
    std::size_t tag_count_ = 0;
};

// ---- Parsing frame.  One per open COMPOUND or LIST. ----

struct Frame {
    TagId       container;        // kCompound or kList
    std::uint8_t depth;
    std::size_t entry_pos;        // index buffer position of this container's entry
    std::size_t payload_start;    // raw-byte position where this container's payload begins
    // For LIST only:
    TagId       list_elem_type = TagId::kEnd;
    std::int32_t list_remaining = 0;
};

inline bool is_valid_tag(std::uint8_t id) noexcept {
    return id >= 1 && id <= 12;
}

[[nodiscard]] bool is_valid_mutf8(const std::uint8_t* p, std::size_t len) noexcept {
    std::size_t i = 0;
    while (i < len) {
        const std::uint8_t b0 = p[i++];
        if ((b0 & 0x80u) == 0) {
            if (b0 == 0) return false; // NUL must be encoded as C0 80 in modified UTF-8.
            continue;
        }
        if ((b0 & 0xE0u) == 0xC0u) {
            if (i >= len) return false;
            const std::uint8_t b1 = p[i++];
            if ((b1 & 0xC0u) != 0x80u) return false;
            const std::uint16_t code =
                static_cast<std::uint16_t>(((b0 & 0x1Fu) << 6) | (b1 & 0x3Fu));
            if (code != 0 && code < 0x80u) return false;
            continue;
        }
        if ((b0 & 0xF0u) == 0xE0u) {
            if (i + 1 >= len) return false;
            const std::uint8_t b1 = p[i++];
            const std::uint8_t b2 = p[i++];
            if ((b1 & 0xC0u) != 0x80u || (b2 & 0xC0u) != 0x80u) return false;
            const std::uint16_t code = static_cast<std::uint16_t>(
                ((b0 & 0x0Fu) << 12) | ((b1 & 0x3Fu) << 6) | (b2 & 0x3Fu));
            if (code < 0x800u) return false;
            continue;
        }
        return false;
    }
    return true;
}

// ---- Skip / measure a single payload of known type ----
//
// Returns the byte length of the payload, or kBadLength on any
// inconsistency (negative length, overflow, …). Used only for "scalar"
// types — COMPOUND and LIST are handled by the main parse loop.

[[nodiscard]] std::size_t payload_size_of_scalar(TagId type, const std::uint8_t* p,
                                                 std::size_t remaining,
                                                 Status* out_status) noexcept {
    auto fail = [&](Status s) -> std::size_t { *out_status = s; return 0; };
    switch (type) {
        case TagId::kByte:   if (remaining < 1) return fail(Status::kTruncated); return 1;
        case TagId::kShort:  if (remaining < 2) return fail(Status::kTruncated); return 2;
        case TagId::kInt:    if (remaining < 4) return fail(Status::kTruncated); return 4;
        case TagId::kLong:   if (remaining < 8) return fail(Status::kTruncated); return 8;
        case TagId::kFloat:  if (remaining < 4) return fail(Status::kTruncated); return 4;
        case TagId::kDouble: if (remaining < 8) return fail(Status::kTruncated); return 8;
        case TagId::kByteArray: {
            if (remaining < 4) return fail(Status::kTruncated);
            const std::int32_t n = read_i32(p);
            if (n < 0) return fail(Status::kBadLength);
            const std::size_t un = static_cast<std::size_t>(n);
            if (un > remaining - 4) return fail(Status::kTruncated);
            return 4 + un;
        }
        case TagId::kString: {
            if (remaining < 2) return fail(Status::kTruncated);
            const std::uint16_t n = read_u16(p);
            if (n > remaining - 2) return fail(Status::kTruncated);
            if (!is_valid_mutf8(p + 2, n)) return fail(Status::kBadString);
            return 2 + n;
        }
        case TagId::kIntArray: {
            if (remaining < 4) return fail(Status::kTruncated);
            const std::int32_t n = read_i32(p);
            if (n < 0) return fail(Status::kBadLength);
            const std::size_t bytes = static_cast<std::size_t>(n) * 4u;
            if (static_cast<std::size_t>(n) > (remaining - 4) / 4) return fail(Status::kTruncated);
            return 4 + bytes;
        }
        case TagId::kLongArray: {
            if (remaining < 4) return fail(Status::kTruncated);
            const std::int32_t n = read_i32(p);
            if (n < 0) return fail(Status::kBadLength);
            const std::size_t bytes = static_cast<std::size_t>(n) * 8u;
            if (static_cast<std::size_t>(n) > (remaining - 4) / 8) return fail(Status::kTruncated);
            return 4 + bytes;
        }
        case TagId::kEnd:
        case TagId::kList:
        case TagId::kCompound:
        default:
            *out_status = Status::kBadTagId;
            return 0;
    }
}

} // namespace

template <typename TWriter>
ParseResult parse_impl(const std::uint8_t* raw_data, std::size_t raw_len,
                       TWriter& writer, std::size_t max_depth,
                       std::size_t max_tags) noexcept {
    ParseResult res{};

    if (!raw_data || raw_len == 0) {
        res.status = Status::kBadArg;
        return res;
    }

    // Read the root header: <tag-id> <name-len> <name>. The first byte
    // must be TAG_Compound (10). Vanilla also accepts a top-level
    // TAG_List in some packet contexts, but for chunk NBT this is always
    // a Compound.
    std::size_t pos = 0;
    if (raw_len < 1) { res.status = Status::kTruncated; res.error_offset = 0; return res; }
    const std::uint8_t root_tag = read_u8(raw_data);
    if (!is_valid_tag(root_tag) || root_tag != static_cast<std::uint8_t>(TagId::kCompound)) {
        res.status = Status::kBadTagId;
        res.error_offset = 0;
        return res;
    }
    pos = 1;

    // Root name (typically empty, length 0).
    if (raw_len - pos < 2) { res.status = Status::kTruncated; res.error_offset = pos; return res; }
    const std::uint16_t root_name_len = read_u16(raw_data + pos);
    pos += 2;
    if (root_name_len > raw_len - pos) { res.status = Status::kTruncated; res.error_offset = pos; return res; }
    const std::uint32_t root_name_off = static_cast<std::uint32_t>(pos);
    if (!is_valid_mutf8(raw_data + pos, root_name_len)) {
        res.status = Status::kBadRootName;
        res.error_offset = pos;
        return res;
    }
    pos += root_name_len;

    // Stack of open containers.
    constexpr std::size_t kStackBuf = 64;
    Frame inline_stack[kStackBuf];
    Frame* stack = inline_stack;
    std::size_t stack_cap = kStackBuf;
    std::size_t stack_size = 0;

    auto push_frame = [&](const Frame& f) noexcept -> bool {
        if (stack_size == stack_cap) {
            const std::size_t new_cap = stack_cap * 2;
            if (new_cap > max_depth) return false;
            Frame* fresh = static_cast<Frame*>(std::malloc(new_cap * sizeof(Frame)));
            if (!fresh) return false;
            std::memcpy(fresh, stack, stack_size * sizeof(Frame));
            if (stack != inline_stack) std::free(stack);
            stack = fresh;
            stack_cap = new_cap;
        }
        stack[stack_size++] = f;
        return true;
    };

    auto finalize_writer = [&](Status s) noexcept {
        writer.finalize();
        res.status         = s;
        res.tag_count      = writer.tag_count();
        res.index_used     = writer.bytes_used();
        res.bytes_consumed = pos;
        if (stack != inline_stack) std::free(stack);
    };

    // Open the root compound entry. We'll patch its payload_len at the end.
    if (!writer.write(TagId::kCompound, 0, root_name_len, root_name_off,
                      static_cast<std::uint32_t>(pos), 0)) {
        finalize_writer(Status::kSizeOverflow);
        res.error_offset = pos;
        return res;
    }
    Frame root{};
    root.container     = TagId::kCompound;
    root.depth         = 0;
    root.entry_pos     = writer.last_entry_pos();
    root.payload_start = pos;
    if (!push_frame(root)) {
        finalize_writer(Status::kDepthOverflow);
        res.error_offset = pos;
        return res;
    }

    // Main loop.
    while (stack_size > 0) {
        Frame& top = stack[stack_size - 1];

        if (top.container == TagId::kList) {
            // List frame: consume one element of the predetermined type
            // until list_remaining hits zero.
            if (top.list_remaining == 0) {
                // Close the list. payload_len = current pos - payload_start.
                const std::uint32_t plen =
                    static_cast<std::uint32_t>(pos - top.payload_start);
                writer.patch_payload_len(top.entry_pos, plen);
                --stack_size;
                continue;
            }

            const TagId elem_type = top.list_elem_type;
            if (elem_type == TagId::kEnd) {
                // A list of END is only legal if its count is 0. We
                // already short-circuited count==0 above; if we get here,
                // it's malformed input.
                finalize_writer(Status::kBadTagId);
                res.error_offset = pos;
                return res;
            }

            // List elements have no name. Write the index entry for
            // this element, then either inline-parse (scalar) or
            // push a frame (COMPOUND / nested LIST).
            const std::uint8_t child_depth =
                static_cast<std::uint8_t>(top.depth + 1u);
            if (child_depth >= max_depth) {
                finalize_writer(Status::kDepthOverflow);
                res.error_offset = pos;
                return res;
            }

            if (elem_type == TagId::kCompound) {
                if (writer.tag_count() >= max_tags) {
                    finalize_writer(Status::kTagsOverflow);
                    res.error_offset = pos;
                    return res;
                }
                if (!writer.write(TagId::kCompound, child_depth, 0, 0,
                                  static_cast<std::uint32_t>(pos), 0)) {
                    finalize_writer(Status::kSizeOverflow);
                    res.error_offset = pos;
                    return res;
                }
                Frame f{};
                f.container = TagId::kCompound;
                f.depth = child_depth;
                f.entry_pos = writer.last_entry_pos();
                f.payload_start = pos;
                if (!push_frame(f)) {
                    finalize_writer(Status::kDepthOverflow);
                    res.error_offset = pos;
                    return res;
                }
                --top.list_remaining;
                continue;
            }

            if (elem_type == TagId::kList) {
                if (raw_len - pos < 1 + 4) {
                    finalize_writer(Status::kTruncated);
                    res.error_offset = pos;
                    return res;
                }
                const std::uint8_t inner_elem_id = read_u8(raw_data + pos);
                const std::int32_t inner_count   = read_i32(raw_data + pos + 1);
                if (inner_count < 0) {
                    finalize_writer(Status::kBadLength);
                    res.error_offset = pos;
                    return res;
                }
                if (writer.tag_count() >= max_tags) {
                    finalize_writer(Status::kTagsOverflow);
                    res.error_offset = pos;
                    return res;
                }
                const std::uint32_t pstart = static_cast<std::uint32_t>(pos);
                if (!writer.write(TagId::kList, child_depth, 0, 0, pstart, 0)) {
                    finalize_writer(Status::kSizeOverflow);
                    res.error_offset = pos;
                    return res;
                }
                Frame f{};
                f.container = TagId::kList;
                f.depth = child_depth;
                f.entry_pos = writer.last_entry_pos();
                f.payload_start = pos;
                f.list_elem_type =
                    is_valid_tag(inner_elem_id)
                        ? static_cast<TagId>(inner_elem_id)
                        : (inner_count == 0 ? TagId::kEnd : TagId::kEnd);
                f.list_remaining = inner_count;
                if (inner_count > 0 && !is_valid_tag(inner_elem_id)) {
                    finalize_writer(Status::kBadTagId);
                    res.error_offset = pos;
                    return res;
                }
                pos += 1 + 4;
                if (!push_frame(f)) {
                    finalize_writer(Status::kDepthOverflow);
                    res.error_offset = pos;
                    return res;
                }
                --top.list_remaining;
                continue;
            }

            // Scalar list element.
            Status pst = Status::kOk;
            const std::size_t plen = payload_size_of_scalar(
                elem_type, raw_data + pos, raw_len - pos, &pst);
            if (pst != Status::kOk) {
                finalize_writer(pst);
                res.error_offset = pos;
                return res;
            }
            if (writer.tag_count() >= max_tags) {
                finalize_writer(Status::kTagsOverflow);
                res.error_offset = pos;
                return res;
            }
            if (!writer.write(elem_type, child_depth, 0, 0,
                              static_cast<std::uint32_t>(pos),
                              static_cast<std::uint32_t>(plen))) {
                finalize_writer(Status::kSizeOverflow);
                res.error_offset = pos;
                return res;
            }
            pos += plen;
            --top.list_remaining;
            continue;
        }

        // Compound frame: read <tag-id> <name-len> <name> <payload>.
        if (raw_len - pos < 1) {
            finalize_writer(Status::kTruncated);
            res.error_offset = pos;
            return res;
        }
        const std::uint8_t tag = read_u8(raw_data + pos);
        if (tag == static_cast<std::uint8_t>(TagId::kEnd)) {
            // Close compound. payload_len covers from payload_start through
            // (and including) this END byte.
            pos += 1;
            const std::uint32_t plen =
                static_cast<std::uint32_t>(pos - top.payload_start);
            writer.patch_payload_len(top.entry_pos, plen);
            --stack_size;
            continue;
        }
        if (!is_valid_tag(tag)) {
            finalize_writer(Status::kBadTagId);
            res.error_offset = pos;
            return res;
        }
        pos += 1;

        // Read the name length + name.
        if (raw_len - pos < 2) {
            finalize_writer(Status::kTruncated);
            res.error_offset = pos;
            return res;
        }
        const std::uint16_t name_len = read_u16(raw_data + pos);
        pos += 2;
        if (name_len > raw_len - pos) {
            finalize_writer(Status::kTruncated);
            res.error_offset = pos;
            return res;
        }
        const std::uint32_t name_off = static_cast<std::uint32_t>(pos);
        if (!is_valid_mutf8(raw_data + pos, name_len)) {
            finalize_writer(Status::kBadTagName);
            res.error_offset = pos;
            return res;
        }
        pos += name_len;

        const std::uint8_t child_depth =
            static_cast<std::uint8_t>(top.depth + 1u);
        if (child_depth >= max_depth) {
            finalize_writer(Status::kDepthOverflow);
            res.error_offset = pos;
            return res;
        }

        const TagId child_type = static_cast<TagId>(tag);

        if (child_type == TagId::kCompound) {
            if (writer.tag_count() >= max_tags) {
                finalize_writer(Status::kTagsOverflow);
                res.error_offset = pos;
                return res;
            }
            if (!writer.write(TagId::kCompound, child_depth, name_len, name_off,
                              static_cast<std::uint32_t>(pos), 0)) {
                finalize_writer(Status::kSizeOverflow);
                res.error_offset = pos;
                return res;
            }
            Frame f{};
            f.container = TagId::kCompound;
            f.depth = child_depth;
            f.entry_pos = writer.last_entry_pos();
            f.payload_start = pos;
            if (!push_frame(f)) {
                finalize_writer(Status::kDepthOverflow);
                res.error_offset = pos;
                return res;
            }
            continue;
        }
        if (child_type == TagId::kList) {
            if (raw_len - pos < 1 + 4) {
                finalize_writer(Status::kTruncated);
                res.error_offset = pos;
                return res;
            }
            const std::uint8_t  elem_id    = read_u8(raw_data + pos);
            const std::int32_t  elem_count = read_i32(raw_data + pos + 1);
            if (elem_count < 0) {
                finalize_writer(Status::kBadLength);
                res.error_offset = pos;
                return res;
            }
            if (elem_count > 0 && !is_valid_tag(elem_id)) {
                finalize_writer(Status::kBadTagId);
                res.error_offset = pos;
                return res;
            }
            if (writer.tag_count() >= max_tags) {
                finalize_writer(Status::kTagsOverflow);
                res.error_offset = pos;
                return res;
            }
            const std::uint32_t plist_start = static_cast<std::uint32_t>(pos);
            if (!writer.write(TagId::kList, child_depth, name_len, name_off,
                              plist_start, 0)) {
                finalize_writer(Status::kSizeOverflow);
                res.error_offset = pos;
                return res;
            }
            Frame f{};
            f.container      = TagId::kList;
            f.depth          = child_depth;
            f.entry_pos      = writer.last_entry_pos();
            f.payload_start  = pos;
            f.list_elem_type = elem_count == 0 ? TagId::kEnd : static_cast<TagId>(elem_id);
            f.list_remaining = elem_count;
            pos += 1 + 4;
            if (!push_frame(f)) {
                finalize_writer(Status::kDepthOverflow);
                res.error_offset = pos;
                return res;
            }
            continue;
        }

        // Scalar in a compound.
        Status pst = Status::kOk;
        const std::size_t plen = payload_size_of_scalar(
            child_type, raw_data + pos, raw_len - pos, &pst);
        if (pst != Status::kOk) {
            finalize_writer(pst);
            res.error_offset = pos;
            return res;
        }
        if (writer.tag_count() >= max_tags) {
            finalize_writer(Status::kTagsOverflow);
            res.error_offset = pos;
            return res;
        }
        if (!writer.write(child_type, child_depth, name_len, name_off,
                          static_cast<std::uint32_t>(pos),
                          static_cast<std::uint32_t>(plen))) {
            finalize_writer(Status::kSizeOverflow);
            res.error_offset = pos;
            return res;
        }
        pos += plen;
    }

    finalize_writer(Status::kOk);
    return res;
}

ParseResult parse(const std::uint8_t* raw_data, std::size_t raw_len,
                  std::uint8_t* index_out, std::size_t index_cap,
                  std::size_t max_depth, std::size_t max_tags) noexcept {
    ParseResult res{};
    if (!index_out || index_cap < kIndexHeaderSize) {
        res.status = Status::kBadArg;
        return res;
    }
    IndexWriter writer(index_out, index_cap);
    return parse_impl(raw_data, raw_len, writer, max_depth, max_tags);
}

ParseResult validate(const std::uint8_t* raw_data, std::size_t raw_len,
                     std::size_t max_depth, std::size_t max_tags) noexcept {
    NullIndexWriter writer;
    return parse_impl(raw_data, raw_len, writer, max_depth, max_tags);
}

} // namespace lattice::io::nbt
