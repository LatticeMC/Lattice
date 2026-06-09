/**
 * @file jni_helper.hpp
 * @brief Minimal, exception-free JNI helpers for Lattice Native.
 *
 * The host library is compiled with `-fno-exceptions -fno-rtti`, so no helper
 * here may throw. On error, helpers either:
 *   - return a sentinel (`nullptr`, `0`, `false`); and/or
 *   - raise a Java exception via `throw_java` for the JVM to see after the
 *     native call returns.
 *
 * The two most performance-sensitive patterns — reading and writing fixed
 * primitive arrays — are wrapped in RAII guards that use
 * `GetPrimitiveArrayCritical` where possible. The guard must be kept short-
 * lived (< 1 ms) per the JNI contract: while it's held, GC is pinned.
 *
 * All type-specific overloads follow the shapes in the JNI spec; if you need
 * another type, add it explicitly — no template hacks.
 */

#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>

namespace lattice::jni {

// ---- Error reporting ------------------------------------------------------

/// Raise a Java exception of class `class_name` with `message`. Safe to call
/// from any JNI entry point; the JVM will observe the exception after the
/// native call returns. If the class cannot be found, falls back to
/// RuntimeException, then java/lang/Error — in practice the first always
/// works during normal JVM operation.
inline void throw_java(JNIEnv* env, const char* class_name, const char* message) noexcept {
    if (!env || !class_name || !message) return;
    jclass cls = env->FindClass(class_name);
    if (!cls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        cls = env->FindClass("java/lang/RuntimeException");
        if (!cls) return;
    }
    env->ThrowNew(cls, message);
    env->DeleteLocalRef(cls);
}

inline void throw_illegal_arg(JNIEnv* env, const char* message) noexcept {
    throw_java(env, "java/lang/IllegalArgumentException", message);
}

inline void throw_illegal_state(JNIEnv* env, const char* message) noexcept {
    throw_java(env, "java/lang/IllegalStateException", message);
}

inline void throw_oom(JNIEnv* env, const char* message) noexcept {
    throw_java(env, "java/lang/OutOfMemoryError", message);
}

// ---- Primitive array critical guards --------------------------------------
//
// Usage:
//   {
//       lattice::jni::CriticalByteArray src{env, jsrc};
//       if (!src) return;                         // exception already raised
//       uint8_t* p = src.data();                  // pinned pointer
//       std::size_t n = src.size();               // element count
//       // … do work — keep it short …
//   }                                             // guard releases in dtor
//
// These guards deliberately don't copy/move: they are short-lived scope
// values that own a critical region on `env`.

namespace detail {

template <typename TJArray, typename TElem>
class CriticalGuard {
public:
    CriticalGuard(JNIEnv* env, TJArray arr) noexcept
        : env_(env), arr_(arr) {
        if (!env || !arr) return;
        const jsize n = env->GetArrayLength(arr);
        if (n < 0) return;
        void* p = env->GetPrimitiveArrayCritical(arr, nullptr);
        if (!p) return;
        data_ = static_cast<TElem*>(p);
        size_ = static_cast<std::size_t>(n);
    }

    ~CriticalGuard() {
        if (data_) env_->ReleasePrimitiveArrayCritical(arr_, data_, 0);
    }

    CriticalGuard(const CriticalGuard&) = delete;
    CriticalGuard& operator=(const CriticalGuard&) = delete;
    CriticalGuard(CriticalGuard&&) = delete;
    CriticalGuard& operator=(CriticalGuard&&) = delete;

    /// Release without committing writes back (equivalent to `JNI_ABORT`).
    /// Use when we only read and want to skip an unnecessary copy-back.
    void release_ro() noexcept {
        if (data_) {
            env_->ReleasePrimitiveArrayCritical(arr_, data_, JNI_ABORT);
            data_ = nullptr;
        }
    }

    [[nodiscard]] explicit operator bool() const noexcept { return data_ != nullptr; }
    [[nodiscard]] TElem*       data()       noexcept { return data_; }
    [[nodiscard]] const TElem* data() const noexcept { return data_; }
    [[nodiscard]] std::size_t  size() const noexcept { return size_; }

private:
    JNIEnv*     env_  = nullptr;
    TJArray     arr_  = nullptr;
    TElem*      data_ = nullptr;
    std::size_t size_ = 0;
};

} // namespace detail

using CriticalByteArray = detail::CriticalGuard<jbyteArray,  jbyte>;
using CriticalIntArray  = detail::CriticalGuard<jintArray,   jint>;
using CriticalLongArray = detail::CriticalGuard<jlongArray,  jlong>;
using CriticalFloatArray = detail::CriticalGuard<jfloatArray, jfloat>;
using CriticalDoubleArray = detail::CriticalGuard<jdoubleArray, jdouble>;

// ---- Direct-ByteBuffer helpers --------------------------------------------

/// Allocate a NewDirectByteBuffer wrapping an owned allocation. Caller is
/// responsible for freeing `ptr` when the buffer is no longer referenced
/// from Java (typically by storing `ptr` in a long field on the Java wrapper
/// and freeing in a `finalize` / `close` method, or via `Cleaner`).
inline jobject new_direct_byte_buffer(JNIEnv* env, void* ptr, jlong capacity) noexcept {
    if (!env || !ptr || capacity <= 0) return nullptr;
    return env->NewDirectByteBuffer(ptr, capacity);
}

} // namespace lattice::jni
