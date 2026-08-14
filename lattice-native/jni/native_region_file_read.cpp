// Synchronous positioned RegionFile payload reads. This deliberately does not
// implement an async backend: Java retains RegionFile's ordering, lifecycle,
// and all write ownership.

#include <jni.h>

#include <cerrno>
#include <cstring>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <memory>
#include <new>

#if defined(_WIN32)
#include <windows.h>
#elif defined(__unix__) || defined(__APPLE__)
#include <fcntl.h>
#include <sys/types.h>
#include <unistd.h>
#else
#define LATTICE_NATIVE_REGION_READ_UNSUPPORTED 1
#endif

#include "jni_helper.hpp"

namespace {

struct NativeRegionFile {
#if defined(_WIN32)
    HANDLE handle = INVALID_HANDLE_VALUE;
#elif defined(__unix__) || defined(__APPLE__)
    int file_descriptor = -1;
#endif
};

void throw_io_exception(JNIEnv* env, const char* operation, unsigned long error_code) noexcept {
    char message[160];
#if defined(_WIN32)
    std::snprintf(message, sizeof message, "lattice native region read: %s failed (Windows error %lu)",
                  operation, error_code);
#else
    std::snprintf(message, sizeof message, "lattice native region read: %s failed (errno %lu)",
                  operation, error_code);
#endif
    lattice::jni::throw_java(env, "java/io/IOException", message);
}

bool validate_destination(JNIEnv* env, jbyteArray destination, jint offset, jint length,
                          jlong position) noexcept {
    if (!destination) {
        lattice::jni::throw_java(env, "java/lang/NullPointerException", "destination");
        return false;
    }
    const jsize destination_length = env->GetArrayLength(destination);
    if (offset < 0 || length < 0 || offset > destination_length
            || length > destination_length - offset) {
        lattice::jni::throw_java(env, "java/lang/IndexOutOfBoundsException",
                                 "destination offset and length are out of bounds");
        return false;
    }
    if (position < 0) {
        lattice::jni::throw_illegal_arg(env, "position must not be negative");
        return false;
    }
    return true;
}

bool contains_nul(JNIEnv* env, jstring path) noexcept {
    const jsize length = env->GetStringLength(path);
    const jchar* chars = env->GetStringChars(path, nullptr);
    if (!chars) return true;
    bool found = false;
    for (jsize index = 0; index < length; ++index) {
        if (chars[index] == 0) {
            found = true;
            break;
        }
    }
    env->ReleaseStringChars(path, chars);
    return found;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeRegionFileRead_nativeOpen(
        JNIEnv* env, jclass /*cls*/, jstring path) {
#if defined(LATTICE_NATIVE_REGION_READ_UNSUPPORTED)
    (void)path;
    lattice::jni::throw_java(env, "java/io/IOException",
                             "native RegionFile reads are unsupported on this platform");
    return 0;
#else
    if (!path) {
        lattice::jni::throw_java(env, "java/lang/NullPointerException", "path");
        return 0;
    }
    if (contains_nul(env, path)) {
        if (!env->ExceptionCheck()) {
            lattice::jni::throw_illegal_arg(env, "path must not contain a NUL character");
        }
        return 0;
    }

#if defined(_WIN32)
    const jsize path_length = env->GetStringLength(path);
    const jchar* chars = env->GetStringChars(path, nullptr);
    if (!chars) return 0;
    std::unique_ptr<jchar[]> terminated_path(
            new (std::nothrow) jchar[static_cast<std::size_t>(path_length) + 1U]);
    if (!terminated_path) {
        env->ReleaseStringChars(path, chars);
        lattice::jni::throw_oom(env, "native RegionFile path allocation failed");
        return 0;
    }
    std::memcpy(terminated_path.get(), chars,
                static_cast<std::size_t>(path_length) * sizeof(jchar));
    terminated_path[static_cast<std::size_t>(path_length)] = 0;
    env->ReleaseStringChars(path, chars);
    HANDLE handle = CreateFileW(reinterpret_cast<LPCWSTR>(terminated_path.get()), GENERIC_READ,
                                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        throw_io_exception(env, "open", GetLastError());
        return 0;
    }
#else
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    const int file_descriptor = open(chars, O_RDONLY);
    const int open_error = errno;
    env->ReleaseStringUTFChars(path, chars);
    if (file_descriptor < 0) {
        throw_io_exception(env, "open", static_cast<unsigned long>(open_error));
        return 0;
    }
#endif

    NativeRegionFile* file = new (std::nothrow) NativeRegionFile{};
    if (!file) {
#if defined(_WIN32)
        CloseHandle(handle);
#else
        close(file_descriptor);
#endif
        lattice::jni::throw_oom(env, "native RegionFile handle allocation failed");
        return 0;
    }
#if defined(_WIN32)
    file->handle = handle;
#else
    file->file_descriptor = file_descriptor;
#endif
    return reinterpret_cast<jlong>(file);
#endif
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeRegionFileRead_nativeReadAt(
        JNIEnv* env, jclass /*cls*/, jlong raw_handle, jbyteArray destination,
        jint offset, jint length, jlong position) {
    if (!validate_destination(env, destination, offset, length, position)) return 0;
    if (raw_handle == 0) {
        lattice::jni::throw_java(env, "java/io/IOException", "native RegionFile handle is closed");
        return 0;
    }
    if (length == 0) return 0;

    NativeRegionFile* file = reinterpret_cast<NativeRegionFile*>(raw_handle);
    std::unique_ptr<jbyte[]> buffer(new (std::nothrow) jbyte[static_cast<std::size_t>(length)]);
    if (!buffer) {
        lattice::jni::throw_oom(env, "native RegionFile read buffer allocation failed");
        return 0;
    }

#if defined(_WIN32)
    OVERLAPPED overlapped{};
    const std::uint64_t unsigned_position = static_cast<std::uint64_t>(position);
    overlapped.Offset = static_cast<DWORD>(unsigned_position & 0xFFFFFFFFULL);
    overlapped.OffsetHigh = static_cast<DWORD>(unsigned_position >> 32U);
    DWORD bytes_read = 0;
    if (!ReadFile(file->handle, buffer.get(), static_cast<DWORD>(length), &bytes_read, &overlapped)) {
        const unsigned long read_error = GetLastError();
        // A synchronous positioned ReadFile reports normal EOF this way when
        // an OVERLAPPED offset is supplied.
        if (read_error == ERROR_HANDLE_EOF) return -1;
        throw_io_exception(env, "read", read_error);
        return 0;
    }
    const jint result = bytes_read == 0 ? -1 : static_cast<jint>(bytes_read);
#elif defined(__unix__) || defined(__APPLE__)
    if (position > static_cast<jlong>(std::numeric_limits<off_t>::max())) {
        lattice::jni::throw_illegal_arg(env, "position is outside the native file-offset range");
        return 0;
    }
    const ssize_t bytes_read = pread(file->file_descriptor, buffer.get(),
                                     static_cast<std::size_t>(length),
                                     static_cast<off_t>(position));
    if (bytes_read < 0) {
        throw_io_exception(env, "read", static_cast<unsigned long>(errno));
        return 0;
    }
    const jint result = bytes_read == 0 ? -1 : static_cast<jint>(bytes_read);
#else
    (void)file;
    (void)buffer;
    lattice::jni::throw_java(env, "java/io/IOException",
                             "native RegionFile reads are unsupported on this platform");
    return 0;
#endif

    if (result > 0) {
        env->SetByteArrayRegion(destination, offset, result, buffer.get());
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeRegionFileRead_nativeClose(
        JNIEnv* env, jclass /*cls*/, jlong raw_handle) {
    if (raw_handle == 0) return;
    NativeRegionFile* file = reinterpret_cast<NativeRegionFile*>(raw_handle);
#if defined(_WIN32)
    const bool closed = CloseHandle(file->handle) != 0;
    const unsigned long close_error = closed ? 0UL : GetLastError();
#elif defined(__unix__) || defined(__APPLE__)
    const int close_result = close(file->file_descriptor);
    const int close_error = close_result == 0 ? 0 : errno;
#else
    const bool closed = false;
#endif
    delete file;
#if defined(_WIN32)
    if (!closed) throw_io_exception(env, "close", close_error);
#elif defined(__unix__) || defined(__APPLE__)
    if (close_result != 0) {
        throw_io_exception(env, "close", static_cast<unsigned long>(close_error));
    }
#else
    lattice::jni::throw_java(env, "java/io/IOException",
                             "native RegionFile reads are unsupported on this platform");
#endif
}

} // extern "C"
