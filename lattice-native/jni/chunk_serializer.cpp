// JNI bindings for NativeChunkSerializer (Phase 1 zlib + Phase 2 NBT index).
//
// Java class: com.latticemc.lattice.nativelib.NativeChunkSerializer
//
// The shape we expose mirrors how Minecraft's RegionFile actually consumes
// the data: the JVM hands us a `byte[]` of zlib-wrapped DEFLATE bytes from
// a region file sector, we hand back a freshly-allocated `byte[]` of the
// inflated NBT payload. The reverse direction is symmetric.
//
// Phase 2 adds `nativeParseNbtIndex` and `nativeFreeNbtIndex`: native
// validates the inflated NBT stream and returns an off-heap index buffer
// (DirectByteBuffer) that Java walks to reconstruct an NbtCompound
// without ever calling into a DataInputStream.
//
// Errors
// ------
// Bad data, truncation, etc. are reported as a Java `java.util.zip.DataFormatException`.
// OOM is reported as `java.lang.OutOfMemoryError`. Bad arguments
// (null array, negative offset, …) are reported as IllegalArgumentException.

#include <jni.h>

#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <new>

#include "io/compression/zlib_codec.hpp"
#include "io/nbt/nbt_parser.hpp"
#include "jni_helper.hpp"

using lattice::io::compression::Status;
namespace zc  = lattice::io::compression;
namespace nbt = lattice::io::nbt;

namespace {

struct NbtIndexAllocation {
    void* payload = nullptr;
    NbtIndexAllocation* next = nullptr;
};

std::mutex g_nbt_index_mutex;
NbtIndexAllocation* g_nbt_index_allocations = nullptr;

bool register_nbt_index(void* ptr) noexcept {
    if (!ptr) return false;
    NbtIndexAllocation* node = static_cast<NbtIndexAllocation*>(
        std::malloc(sizeof(NbtIndexAllocation)));
    if (!node) return false;
    node->payload = ptr;
    std::lock_guard<std::mutex> lock(g_nbt_index_mutex);
    node->next = g_nbt_index_allocations;
    g_nbt_index_allocations = node;
    return true;
}

bool unregister_nbt_index(void* ptr) noexcept {
    if (!ptr) return false;
    std::lock_guard<std::mutex> lock(g_nbt_index_mutex);
    NbtIndexAllocation** link = &g_nbt_index_allocations;
    while (*link) {
        if ((*link)->payload == ptr) {
            NbtIndexAllocation* node = *link;
            *link = node->next;
            std::free(node);
            return true;
        }
        link = &((*link)->next);
    }
    return false;
}

// --- Common error helpers --------------------------------------------------

void throw_data_format(JNIEnv* env, const char* msg) noexcept {
    lattice::jni::throw_java(env, "java/util/zip/DataFormatException", msg);
}

void throw_status(JNIEnv* env, Status s, const char* op) noexcept {
    char buf[96];
    switch (s) {
        case Status::kBadData:
            std::snprintf(buf, sizeof buf, "lattice %s: %s", op, zc::status_message(s));
            throw_data_format(env, buf);
            return;
        case Status::kShortBuffer:
            std::snprintf(buf, sizeof buf, "lattice %s: %s", op, zc::status_message(s));
            lattice::jni::throw_illegal_arg(env, buf);
            return;
        case Status::kOom:
            std::snprintf(buf, sizeof buf, "lattice %s: %s", op, zc::status_message(s));
            lattice::jni::throw_oom(env, buf);
            return;
        case Status::kBadArg:
            std::snprintf(buf, sizeof buf, "lattice %s: %s", op, zc::status_message(s));
            lattice::jni::throw_illegal_arg(env, buf);
            return;
        case Status::kOk:
            return;
    }
}

void throw_nbt_status(JNIEnv* env, nbt::Status s, std::size_t error_offset) noexcept {
    char buf[192];
    std::snprintf(buf, sizeof buf,
                  "lattice nbt: %s at offset %zu",
                  nbt::status_message(s), error_offset);
    if (s == nbt::Status::kBadArg) {
        lattice::jni::throw_illegal_arg(env, buf);
        return;
    }
    throw_data_format(env, buf);
}

// Validate a (array, offset, length) triple against a known array length.
// On failure, raises an IllegalArgumentException and returns false.
bool validate_range(JNIEnv* env, jsize array_len, jint off, jint len,
                    const char* what) noexcept {
    if (off < 0 || len < 0 || off > array_len || len > array_len - off) {
        char buf[96];
        std::snprintf(buf, sizeof buf,
                      "lattice: %s range [%d,%d) out of bounds for length %d",
                      what, int(off), int(off + len), int(array_len));
        lattice::jni::throw_illegal_arg(env, buf);
        return false;
    }
    return true;
}

} // namespace

extern "C" {

// ---------------------------------------------------------------------------
// inflate
// ---------------------------------------------------------------------------

/*
 * Class:     com.latticemc.lattice.nativelib.NativeChunkSerializer
 * Method:    nativeInflateZlibToNewArray
 * Signature: ([BII)[B
 *
 * Decompresses `src[srcOff .. srcOff+srcLen)` from zlib format and returns a
 * freshly-allocated byte[] containing the inflated payload.
 *
 * The expected output size is unknown in advance; we use a doubling
 * strategy starting from `8 * srcLen` (or a 64 KB minimum), retrying on
 * `kShortBuffer` up to a `maxOutputBytes` ceiling supplied by Java to
 * defend against decompression-bomb inputs.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkSerializer_nativeInflateZlibToNewArray(
        JNIEnv* env, jclass /*cls*/,
        jbyteArray jsrc, jint srcOff, jint srcLen,
        jlong maxOutputBytes) {
    if (!jsrc) {
        lattice::jni::throw_illegal_arg(env, "lattice inflate: null source array");
        return nullptr;
    }
    const jsize src_array_len = env->GetArrayLength(jsrc);
    if (!validate_range(env, src_array_len, srcOff, srcLen, "source")) return nullptr;
    if (srcLen == 0) {
        // Empty input is not a valid zlib stream.
        throw_data_format(env, "lattice inflate: empty source");
        return nullptr;
    }
    if (maxOutputBytes <= 0) {
        lattice::jni::throw_illegal_arg(env, "lattice inflate: maxOutputBytes must be positive");
        return nullptr;
    }

    // Initial guess: 8x the compressed size, clamped up to 64 KB and down
    // to maxOutputBytes. Chunk NBTs typically expand 3–6x, so 8x usually
    // succeeds on the first try.
    constexpr std::size_t kMinInitial = 64 * 1024;
    std::size_t cap = static_cast<std::size_t>(srcLen) * 8u;
    if (cap < kMinInitial) cap = kMinInitial;
    const std::size_t hard_cap = static_cast<std::size_t>(maxOutputBytes);
    if (cap > hard_cap) cap = hard_cap;

    // Copy the compressed source out of the JVM heap into native memory.
    // This is cheap (compressed chunks are typically ≤ 1 MB) and lets us
    // perform the grow-and-retry decompress loop without holding any
    // critical region — so allocation, throws, and Java-side work later in
    // this function are all free of JNI critical-region restrictions.
    std::uint8_t* src_copy =
        static_cast<std::uint8_t*>(::operator new(static_cast<std::size_t>(srcLen),
                                                  std::nothrow));
    if (!src_copy) {
        lattice::jni::throw_oom(env, "lattice inflate: source copy allocation failed");
        return nullptr;
    }
    env->GetByteArrayRegion(jsrc, srcOff, srcLen,
                            reinterpret_cast<jbyte*>(src_copy));
    if (env->ExceptionCheck()) {
        ::operator delete(src_copy);
        return nullptr; // ArrayIndexOutOfBoundsException already pending
    }

    std::uint8_t* scratch = static_cast<std::uint8_t*>(::operator new(cap, std::nothrow));
    if (!scratch) {
        ::operator delete(src_copy);
        lattice::jni::throw_oom(env, "lattice inflate: scratch allocation failed");
        return nullptr;
    }

    std::size_t produced = 0;
    Status st = Status::kShortBuffer;
    for (;;) {
        st = zc::zlib_decompress(src_copy, static_cast<std::size_t>(srcLen),
                                 scratch, cap, &produced);
        if (st != Status::kShortBuffer) break;
        if (cap >= hard_cap) {
            ::operator delete(src_copy);
            ::operator delete(scratch);
            throw_data_format(env,
                "lattice inflate: decompressed size exceeds maxOutputBytes");
            return nullptr;
        }
        std::size_t new_cap = cap * 2u;
        if (new_cap > hard_cap) new_cap = hard_cap;
        std::uint8_t* bigger =
            static_cast<std::uint8_t*>(::operator new(new_cap, std::nothrow));
        if (!bigger) {
            ::operator delete(src_copy);
            ::operator delete(scratch);
            lattice::jni::throw_oom(env, "lattice inflate: scratch grow failed");
            return nullptr;
        }
        ::operator delete(scratch);
        scratch = bigger;
        cap = new_cap;
    }
    ::operator delete(src_copy);

    if (st != Status::kOk) {
        ::operator delete(scratch);
        throw_status(env, st, "inflate");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(produced));
    if (!result) {
        ::operator delete(scratch);
        return nullptr; // OOM already pending
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(produced),
                            reinterpret_cast<const jbyte*>(scratch));
    ::operator delete(scratch);
    return result;
}

/*
 * Method:    nativeInflateZlibInto
 * Signature: ([BII[BI)I
 *
 * Decompresses into a caller-provided byte[]. Returns the number of bytes
 * written, or -1 if the destination is too small (no exception thrown in
 * that case — the caller is expected to grow and retry).
 */
JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkSerializer_nativeInflateZlibInto(
        JNIEnv* env, jclass /*cls*/,
        jbyteArray jsrc, jint srcOff, jint srcLen,
        jbyteArray jdst, jint dstOff) {
    if (!jsrc || !jdst) {
        lattice::jni::throw_illegal_arg(env, "lattice inflate: null array");
        return -1;
    }
    const jsize sa = env->GetArrayLength(jsrc);
    const jsize da = env->GetArrayLength(jdst);
    if (!validate_range(env, sa, srcOff, srcLen, "source")) return -1;
    if (dstOff < 0 || dstOff > da) {
        lattice::jni::throw_illegal_arg(env, "lattice inflate: dstOff out of bounds");
        return -1;
    }
    if (srcLen == 0) { throw_data_format(env, "lattice inflate: empty source"); return -1; }

    // Copy source out; decompress directly into a pinned dst region.
    std::uint8_t* src_copy =
        static_cast<std::uint8_t*>(::operator new(static_cast<std::size_t>(srcLen),
                                                  std::nothrow));
    if (!src_copy) {
        lattice::jni::throw_oom(env, "lattice inflate: source copy allocation failed");
        return -1;
    }
    env->GetByteArrayRegion(jsrc, srcOff, srcLen,
                            reinterpret_cast<jbyte*>(src_copy));
    if (env->ExceptionCheck()) {
        ::operator delete(src_copy);
        return -1;
    }

    std::size_t produced = 0;
    Status st = Status::kOk;
    {
        lattice::jni::CriticalByteArray dst_guard(env, jdst);
        if (!dst_guard) {
            ::operator delete(src_copy);
            lattice::jni::throw_oom(env, "lattice inflate: failed to pin dst");
            return -1;
        }
        std::uint8_t* dp =
            reinterpret_cast<std::uint8_t*>(dst_guard.data()) + dstOff;
        const std::size_t dst_cap = static_cast<std::size_t>(da - dstOff);

        st = zc::zlib_decompress(src_copy, static_cast<std::size_t>(srcLen),
                                 dp, dst_cap, &produced);
        // If short buffer, don't commit; it's a retry signal, not real output.
        if (st != Status::kOk) dst_guard.release_ro();
    }
    ::operator delete(src_copy);
    if (st == Status::kShortBuffer) return -1;
    if (st != Status::kOk) {
        throw_status(env, st, "inflate");
        return -1;
    }
    return static_cast<jint>(produced);
}

// ---------------------------------------------------------------------------
// deflate
// ---------------------------------------------------------------------------

/*
 * Method:    nativeDeflateZlibToNewArray
 * Signature: ([BIII)[B
 *
 * Compresses `src[srcOff .. srcOff+srcLen)` to zlib format with the given
 * level (clamped to [1, 12]). Allocates a freshly-sized result byte[].
 */
JNIEXPORT jbyteArray JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkSerializer_nativeDeflateZlibToNewArray(
        JNIEnv* env, jclass /*cls*/,
        jbyteArray jsrc, jint srcOff, jint srcLen, jint level) {
    if (!jsrc) {
        lattice::jni::throw_illegal_arg(env, "lattice deflate: null source");
        return nullptr;
    }
    const jsize sa = env->GetArrayLength(jsrc);
    if (!validate_range(env, sa, srcOff, srcLen, "source")) return nullptr;

    const std::size_t bound = zc::zlib_compress_bound(static_cast<std::size_t>(srcLen),
                                                      static_cast<int>(level));
    if (bound == 0) {
        lattice::jni::throw_oom(env, "lattice deflate: bound query failed");
        return nullptr;
    }
    std::uint8_t* scratch = static_cast<std::uint8_t*>(::operator new(bound, std::nothrow));
    if (!scratch) {
        lattice::jni::throw_oom(env, "lattice deflate: scratch allocation failed");
        return nullptr;
    }

    // Same pattern as inflate: copy source out of the JVM heap so we don't
    // run the (potentially multi-millisecond) compress under a critical
    // region, and so our error/OOM throws happen outside the pin.
    std::uint8_t* src_copy =
        static_cast<std::uint8_t*>(::operator new(static_cast<std::size_t>(srcLen),
                                                  std::nothrow));
    if (!src_copy) {
        ::operator delete(scratch);
        lattice::jni::throw_oom(env, "lattice deflate: source copy allocation failed");
        return nullptr;
    }
    env->GetByteArrayRegion(jsrc, srcOff, srcLen,
                            reinterpret_cast<jbyte*>(src_copy));
    if (env->ExceptionCheck()) {
        ::operator delete(src_copy);
        ::operator delete(scratch);
        return nullptr;
    }

    std::size_t produced = 0;
    const auto st = zc::zlib_compress(src_copy, static_cast<std::size_t>(srcLen),
                                      scratch, bound, &produced,
                                      static_cast<int>(level));
    ::operator delete(src_copy);
    if (st != Status::kOk) {
        ::operator delete(scratch);
        throw_status(env, st, "deflate");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(produced));
    if (!result) {
        ::operator delete(scratch);
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(produced),
                            reinterpret_cast<const jbyte*>(scratch));
    ::operator delete(scratch);
    return result;
}

/*
 * Method:    nativeZlibCompressBound
 * Signature: (II)J
 *
 * Reports the worst-case compressed size for `srcLen` bytes at `level`.
 * Useful when callers want to size their own destination buffer.
 */
JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkSerializer_nativeZlibCompressBound(
        JNIEnv* /*env*/, jclass /*cls*/, jint srcLen, jint level) {
    if (srcLen < 0) return 0;
    return static_cast<jlong>(zc::zlib_compress_bound(
        static_cast<std::size_t>(srcLen), static_cast<int>(level)));
}

// ---------------------------------------------------------------------------
// nativeParseNbtIndex
// ---------------------------------------------------------------------------

/*
 * Method:    nativeParseNbtIndex
 * Signature: ([BIIIJ)Ljava/nio/ByteBuffer;
 *
 * Parses `raw[rawOff..rawOff+rawLen)` as an NBT compound stream and
 * returns a freshly-allocated DirectByteBuffer containing the flat index
 * (header + N × 16-byte entries). The buffer is heap-allocated on the
 * native side; the caller MUST eventually pass the same buffer back to
 * `nativeFreeNbtIndex` to release it.
 *
 * `maxDepth` and `maxTags` bound the parser's complexity to defend
 * against malformed input. Passing -1 selects the parser's default for
 * each. Pass `LATTICE_NBT_DEFAULT_LIMITS` from the Java side.
 *
 * Returns null + raises a DataFormatException on malformed input.
 */
JNIEXPORT jobject JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkSerializer_nativeParseNbtIndex(
        JNIEnv* env, jclass /*cls*/,
        jbyteArray jraw, jint rawOff, jint rawLen,
        jint maxDepth, jint maxTags) {
    if (!jraw) {
        lattice::jni::throw_illegal_arg(env, "lattice nbt: null raw array");
        return nullptr;
    }
    if (rawLen <= 0) {
        throw_data_format(env, "lattice nbt: empty raw input");
        return nullptr;
    }
    const jsize sa = env->GetArrayLength(jraw);
    if (!validate_range(env, sa, rawOff, rawLen, "raw")) return nullptr;

    // Copy raw bytes into native memory. The parser does not modify the
    // input, but we don't want to hold a GetPrimitiveArrayCritical region
    // across the JNI throws below; copying into native is cheaper than
    // careful pin/release management.
    std::uint8_t* raw_copy = static_cast<std::uint8_t*>(
        ::operator new(static_cast<std::size_t>(rawLen), std::nothrow));
    if (!raw_copy) {
        lattice::jni::throw_oom(env, "lattice nbt: raw copy alloc");
        return nullptr;
    }
    env->GetByteArrayRegion(jraw, rawOff, rawLen,
                            reinterpret_cast<jbyte*>(raw_copy));
    if (env->ExceptionCheck()) {
        ::operator delete(raw_copy);
        return nullptr;
    }

    // The worst-case index is one 16-byte entry per input byte; for
    // realistic chunk NBTs we get one entry per 5-30 bytes. Allocate a
    // conservative upper bound. For very large inputs we cap at
    // (maxTags + 1) entries which is the true mathematical limit; any
    // request beyond that exits with kTagsOverflow.
    const std::size_t input_len = static_cast<std::size_t>(rawLen);
    const std::size_t cap_tags  = maxTags > 0
        ? static_cast<std::size_t>(maxTags)
        : nbt::kDefaultMaxTags;
    std::size_t worst = nbt::worst_case_index_size(input_len);
    const std::size_t hard_cap = nbt::kIndexHeaderSize + (cap_tags + 1) * nbt::kIndexEntrySize;
    if (worst > hard_cap) worst = hard_cap;

    std::uint8_t* index = static_cast<std::uint8_t*>(
        ::operator new(worst, std::nothrow));
    if (!index) {
        ::operator delete(raw_copy);
        lattice::jni::throw_oom(env, "lattice nbt: index alloc");
        return nullptr;
    }

    const std::size_t md = maxDepth > 0
        ? static_cast<std::size_t>(maxDepth)
        : nbt::kDefaultMaxDepth;
    const auto r = nbt::parse(raw_copy, input_len, index, worst, md, cap_tags);
    ::operator delete(raw_copy);

    if (r.status != nbt::Status::kOk) {
        ::operator delete(index);
        throw_nbt_status(env, r.status, r.error_offset);
        return nullptr;
    }

    jobject buf = lattice::jni::new_direct_byte_buffer(
        env, index, static_cast<jlong>(r.index_used));
    if (!buf) {
        ::operator delete(index);
        if (!env->ExceptionCheck()) {
            lattice::jni::throw_oom(env, "lattice nbt: NewDirectByteBuffer failed");
        }
        return nullptr;
    }
    if (!register_nbt_index(index)) {
        env->DeleteLocalRef(buf);
        ::operator delete(index);
        lattice::jni::throw_oom(env, "lattice nbt: index registry alloc");
        return nullptr;
    }
    // Ownership now conceptually held by `buf`; Java will hand the
    // address back to nativeFreeNbtIndex when done.
    return buf;
}

/*
 * Method:    nativeFreeNbtIndex
 * Signature: (Ljava/nio/ByteBuffer;)V
 *
 * Releases the heap allocation backing a DirectByteBuffer returned by
 * nativeParseNbtIndex. The buffer must not be used after this call.
 * Calling with null is a no-op.
 */
JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeChunkSerializer_nativeFreeNbtIndex(
        JNIEnv* env, jclass /*cls*/, jobject buf) {
    if (!buf) return;
    void* addr = env->GetDirectBufferAddress(buf);
    if (!addr) {
        lattice::jni::throw_illegal_arg(env, "lattice nbt: buffer is not a direct byte buffer");
        return;
    }
    if (!unregister_nbt_index(addr)) {
        lattice::jni::throw_illegal_arg(env, "lattice nbt: buffer is not a live native NBT index");
        return;
    }
    ::operator delete(addr);
}

} // extern "C"
