// JNI bindings for the NativeLightEngine module.
//
// Java class: com.latticemc.lattice.nativelib.NativeLightEngine
//
// Layout
// ------
// Each Java NativeLightEngine instance owns a single native
// ChunkLightProvider, accessed through an opaque jlong handle:
//
//     long ptr = nativeCreate(levelCount, expectedLevelSize, expectedTotalSize,
//                             /* this */ self);
//     ...
//     nativeDestroy(ptr);
//
// The Java instance also serves as the *callback target*: every time the
// native BFS needs to call back into Java (to compute propagated level
// or to enumerate neighbours), it invokes a Java method on the supplied
// `self` object. Method IDs are cached on first call.
//
// Method dispatch shape
// ---------------------
//
//   int  Java  : getPropagatedLevel(long source, long target, int level)
//   void Java  : propagateLevel(long ptr, long source, long target, int level, boolean decrease)
//   boolean Java : isMarker(long id)
//   int  Java  : getCommittedLevel(long id)
//   void Java  : setCommittedLevel(long id, int level)
//
// The `propagateLevel` callback receives the native handle (`ptr`) so
// the Java side can call back into the native `updateLevel` for each
// enumerated neighbour. This is the most expensive shape on the hot
// path; bulk callers should batch as much work as possible per
// `applyPendingUpdates` call to amortise method-lookup cost.

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <cstdint>
#include <new>

#include "jni_helper.hpp"
#include "world/light/chunk_light_provider.hpp"

namespace lwl = lattice::world::light;

// Forward declaration of the loader-supplied accessor. Must live at
// file scope (not inside the anonymous namespace below) — putting it
// there would shadow the top-level `lattice` namespace and trigger an
// ambiguity error inside `lattice::jni::throw_*` calls.
namespace lattice::runtime { JavaVM* vm() noexcept; }

// ---------------------------------------------------------------------------
// Callback method-ID cache.
//
// Filled on the first `nativeCreate` call (cheap one-shot). We hold a
// global ref to the class to keep the IDs valid for the JVM lifetime.

namespace {

struct CallbackIds {
    std::atomic<bool> initialised{false};
    jclass    cls            = nullptr; // global ref, never released until JNI_OnUnload
    jmethodID get_propagated = nullptr;
    jmethodID propagate      = nullptr;
    jmethodID is_marker      = nullptr;
    jmethodID recalculate    = nullptr;
    jmethodID get_committed  = nullptr;
    jmethodID set_committed  = nullptr;
};

CallbackIds g_ids{};

bool resolve_ids(JNIEnv* env, jobject self) noexcept {
    if (g_ids.initialised.load(std::memory_order_acquire)) return true;

    jclass local_cls = env->GetObjectClass(self);
    if (!local_cls) return false;
    jclass global_cls = static_cast<jclass>(env->NewGlobalRef(local_cls));
    env->DeleteLocalRef(local_cls);
    if (!global_cls) return false;

    g_ids.get_propagated = env->GetMethodID(
        global_cls, "callbackGetPropagatedLevel", "(JJI)I");
    g_ids.propagate      = env->GetMethodID(
        global_cls, "callbackPropagateLevel", "(JJJIZ)V");
    g_ids.is_marker      = env->GetMethodID(
        global_cls, "callbackIsMarker", "(J)Z");
    g_ids.recalculate    = env->GetMethodID(
        global_cls, "callbackRecalculateLevel", "(JJI)I");
    if (!g_ids.recalculate && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    g_ids.get_committed  = env->GetMethodID(
        global_cls, "callbackGetCommittedLevel", "(J)I");
    g_ids.set_committed  = env->GetMethodID(
        global_cls, "callbackSetCommittedLevel", "(JI)V");

    if (!g_ids.get_propagated || !g_ids.propagate ||
        !g_ids.is_marker || !g_ids.get_committed || !g_ids.set_committed) {
        env->DeleteGlobalRef(global_cls);
        return false;
    }
    g_ids.cls = global_cls;
    g_ids.initialised.store(true, std::memory_order_release);
    return true;
}

// ---- Bridge state held in each ChunkLightProvider's `user_data`. ----------

struct BridgeState {
    jobject self_global; // GlobalRef to the Java NativeLightEngine instance
    int level_count = 0;
    std::atomic<bool> callback_failed{false};
    const char* failure_message = "lattice light: bridge is in failed state";
};

void mark_bridge_failure(JNIEnv* env, BridgeState* st, const char* message) noexcept {
    if (st) {
        st->failure_message = message;
        st->callback_failed.store(true, std::memory_order_release);
    }
    if (env && !env->ExceptionCheck()) {
        lattice::jni::throw_illegal_state(env, message);
    }
}

JNIEnv* attached_env() noexcept {
    JavaVM* vm = lattice::runtime::vm();
    if (!vm) return nullptr;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK) {
        return env;
    }
    return nullptr;
}

// ---- C-style callbacks plugged into LightProviderCallbacks. --------------

int cb_get_propagated_level(void* user, std::int64_t src, std::int64_t tgt, int level) noexcept {
    auto* st = static_cast<BridgeState*>(user);
    if (st->callback_failed.load(std::memory_order_acquire)) return st->level_count;

    JNIEnv* env = attached_env();
    if (!env) {
        mark_bridge_failure(nullptr, st, "lattice light: JNI callback thread is not attached");
        return st->level_count;
    }

    const int result = static_cast<int>(env->CallIntMethod(
        st->self_global, g_ids.get_propagated,
        static_cast<jlong>(src), static_cast<jlong>(tgt), static_cast<jint>(level)));
    if (env->ExceptionCheck()) {
        mark_bridge_failure(env, st, "lattice light: callbackGetPropagatedLevel failed");
        return st->level_count;
    }
    return result;
}

void cb_propagate_level(void* user, lwl::LevelPropagator* prop,
                        std::int64_t src, std::int64_t tgt, int level, bool decrease) noexcept {
    auto* st = static_cast<BridgeState*>(user);
    if (st->callback_failed.load(std::memory_order_acquire)) return;

    JNIEnv* env = attached_env();
    if (!env) {
        mark_bridge_failure(nullptr, st, "lattice light: JNI callback thread is not attached");
        return;
    }

    env->CallVoidMethod(
        st->self_global, g_ids.propagate,
        reinterpret_cast<jlong>(prop),
        static_cast<jlong>(src), static_cast<jlong>(tgt),
        static_cast<jint>(level), static_cast<jboolean>(decrease));
    if (env->ExceptionCheck()) {
        mark_bridge_failure(env, st, "lattice light: callbackPropagateLevel failed");
    }
}

bool cb_is_marker(void* user, std::int64_t id) noexcept {
    auto* st = static_cast<BridgeState*>(user);
    if (st->callback_failed.load(std::memory_order_acquire)) return true;

    JNIEnv* env = attached_env();
    if (!env) {
        mark_bridge_failure(nullptr, st, "lattice light: JNI callback thread is not attached");
        return true;
    }

    const bool result = env->CallBooleanMethod(st->self_global, g_ids.is_marker,
                                               static_cast<jlong>(id)) == JNI_TRUE;
    if (env->ExceptionCheck()) {
        mark_bridge_failure(env, st, "lattice light: callbackIsMarker failed");
        return true;
    }
    return result;
}

int cb_recalculate_level(void* user, std::int64_t id,
                         std::int64_t excluded_id, int max_level) noexcept {
    auto* st = static_cast<BridgeState*>(user);
    if (st->callback_failed.load(std::memory_order_acquire)) return st->level_count;
    if (!g_ids.recalculate) return max_level;

    JNIEnv* env = attached_env();
    if (!env) {
        mark_bridge_failure(nullptr, st, "lattice light: JNI callback thread is not attached");
        return st->level_count;
    }

    const int result = static_cast<int>(env->CallIntMethod(
        st->self_global, g_ids.recalculate,
        static_cast<jlong>(id), static_cast<jlong>(excluded_id), static_cast<jint>(max_level)));
    if (env->ExceptionCheck()) {
        mark_bridge_failure(env, st, "lattice light: callbackRecalculateLevel failed");
        return st->level_count;
    }
    return result;
}

int cb_get_committed(void* user, std::int64_t id) noexcept {
    auto* st = static_cast<BridgeState*>(user);
    if (st->callback_failed.load(std::memory_order_acquire)) return st->level_count;

    JNIEnv* env = attached_env();
    if (!env) {
        mark_bridge_failure(nullptr, st, "lattice light: JNI callback thread is not attached");
        return st->level_count;
    }

    const int result = static_cast<int>(env->CallIntMethod(
        st->self_global, g_ids.get_committed, static_cast<jlong>(id)));
    if (env->ExceptionCheck()) {
        mark_bridge_failure(env, st, "lattice light: callbackGetCommittedLevel failed");
        return st->level_count;
    }
    return result;
}

void cb_set_committed(void* user, std::int64_t id, int level) noexcept {
    auto* st = static_cast<BridgeState*>(user);
    if (st->callback_failed.load(std::memory_order_acquire)) return;

    JNIEnv* env = attached_env();
    if (!env) {
        mark_bridge_failure(nullptr, st, "lattice light: JNI callback thread is not attached");
        return;
    }

    env->CallVoidMethod(st->self_global, g_ids.set_committed,
                        static_cast<jlong>(id), static_cast<jint>(level));
    if (env->ExceptionCheck()) {
        mark_bridge_failure(env, st, "lattice light: callbackSetCommittedLevel failed");
    }
}

struct HandleBundle {
    lwl::ChunkLightProvider* prop;
    BridgeState              bridge;
};

} // namespace

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

extern "C" {

/*
 * nativeCreate(int levelCount, int expectedLevelSize, int expectedTotalSize)
 * returns long handle
 */
JNIEXPORT jlong JNICALL
Java_com_latticemc_lattice_nativelib_NativeLightEngine_nativeCreate(
        JNIEnv* env, jobject self,
        jint levelCount, jint expectedLevelSize, jint expectedTotalSize) {
    if (levelCount <= 0 || levelCount >= 254) {
        lattice::jni::throw_illegal_arg(env, "lattice light: levelCount must be in (0, 254)");
        return 0;
    }
    if (!resolve_ids(env, self)) {
        lattice::jni::throw_illegal_state(env,
            "lattice light: failed to bind callback method IDs (check method signatures)");
        return 0;
    }
    auto* bundle = new (std::nothrow) HandleBundle{};
    if (!bundle) {
        lattice::jni::throw_oom(env, "lattice light: HandleBundle allocation");
        return 0;
    }
    bundle->bridge.self_global = env->NewGlobalRef(self);
    bundle->bridge.level_count = static_cast<int>(levelCount);
    if (!bundle->bridge.self_global) {
        delete bundle;
        lattice::jni::throw_oom(env, "lattice light: failed to make global ref to self");
        return 0;
    }
    lwl::LightProviderCallbacks cbs{};
    cbs.user_data            = &bundle->bridge;
    cbs.get_propagated_level = &cb_get_propagated_level;
    cbs.propagate_level      = &cb_propagate_level;
    cbs.is_marker            = &cb_is_marker;
    cbs.recalculate_level    = &cb_recalculate_level;
    cbs.get_level            = &cb_get_committed;
    cbs.set_level            = &cb_set_committed;

    bundle->prop = new (std::nothrow) lwl::ChunkLightProvider(
        static_cast<int>(levelCount),
        static_cast<std::size_t>(expectedLevelSize > 0 ? expectedLevelSize : 256),
        static_cast<std::size_t>(expectedTotalSize > 0 ? expectedTotalSize : 4096),
        cbs);
    if (!bundle->prop) {
        env->DeleteGlobalRef(bundle->bridge.self_global);
        delete bundle;
        lattice::jni::throw_oom(env, "lattice light: ChunkLightProvider allocation");
        return 0;
    }
    return reinterpret_cast<jlong>(bundle);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeLightEngine_nativeDestroy(
        JNIEnv* env, jclass /*cls*/, jlong handle) {
    auto* bundle = reinterpret_cast<HandleBundle*>(handle);
    if (!bundle) return;
    delete bundle->prop;
    if (bundle->bridge.self_global) env->DeleteGlobalRef(bundle->bridge.self_global);
    delete bundle;
}

JNIEXPORT jboolean JNICALL
Java_com_latticemc_lattice_nativelib_NativeLightEngine_nativeHasPendingUpdates(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* bundle = reinterpret_cast<HandleBundle*>(handle);
    return bundle && bundle->prop && bundle->prop->has_pending_updates() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeLightEngine_nativeGetPendingCount(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    auto* bundle = reinterpret_cast<HandleBundle*>(handle);
    return bundle && bundle->prop
        ? static_cast<jint>(bundle->prop->get_pending_update_count())
        : 0;
}

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeLightEngine_nativeApplyPendingUpdates(
        JNIEnv* env, jclass /*cls*/, jlong handle, jint maxSteps) {
    auto* bundle = reinterpret_cast<HandleBundle*>(handle);
    if (!bundle || !bundle->prop) return maxSteps;
    if (bundle->bridge.callback_failed.load(std::memory_order_acquire)) {
        lattice::jni::throw_illegal_state(env, bundle->bridge.failure_message);
        return maxSteps;
    }
    const jint remaining = bundle->prop->apply_pending_updates(static_cast<int>(maxSteps));
    if (bundle->bridge.callback_failed.load(std::memory_order_acquire) && !env->ExceptionCheck()) {
        lattice::jni::throw_illegal_state(env, bundle->bridge.failure_message);
    }
    return remaining;
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeLightEngine_nativeUpdateLevel(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jlong sourceId, jlong id, jint level, jboolean decrease) {
    auto* bundle = reinterpret_cast<HandleBundle*>(handle);
    if (!bundle || !bundle->prop) return;
    if (bundle->bridge.callback_failed.load(std::memory_order_acquire)) {
        lattice::jni::throw_illegal_state(env, bundle->bridge.failure_message);
        return;
    }
    bundle->prop->update_level(static_cast<std::int64_t>(sourceId),
                               static_cast<std::int64_t>(id),
                               static_cast<int>(level),
                               decrease == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeLightEngine_nativePropagateLevel(
        JNIEnv* env, jclass /*cls*/,
        jlong handle, jlong id, jint level, jboolean decrease) {
    auto* bundle = reinterpret_cast<HandleBundle*>(handle);
    if (!bundle || !bundle->prop) return;
    if (bundle->bridge.callback_failed.load(std::memory_order_acquire)) {
        lattice::jni::throw_illegal_state(env, bundle->bridge.failure_message);
        return;
    }
    bundle->prop->propagate_level(static_cast<std::int64_t>(id),
                                  static_cast<int>(level),
                                  decrease == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeLightEngine_nativeRemovePendingUpdate(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jlong id) {
    auto* bundle = reinterpret_cast<HandleBundle*>(handle);
    if (!bundle || !bundle->prop) return;
    bundle->prop->remove_pending_update(static_cast<std::int64_t>(id));
}

/*
 * nativeUpdateLevelByPtr — same as nativeUpdateLevel, but takes a raw
 * `LevelPropagator*` instead of a HandleBundle*. The native propagate
 * callback into Java passes the raw propagator pointer so Java can call
 * back here without bookkeeping the HandleBundle around.
 */
JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeLightEngine_nativeUpdateLevelByPtr(
        JNIEnv* /*env*/, jclass /*cls*/,
        jlong propPtr, jlong sourceId, jlong id, jint level, jboolean decrease) {
    auto* prop = reinterpret_cast<lwl::LevelPropagator*>(propPtr);
    if (!prop) return;
    prop->update_level(static_cast<std::int64_t>(sourceId),
                       static_cast<std::int64_t>(id),
                       static_cast<int>(level),
                       decrease == JNI_TRUE);
}

} // extern "C"
