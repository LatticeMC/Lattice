// JNI bindings for the biological AI decision core.

#include <jni.h>

#include <cstddef>
#include <new>

#include "jni_helper.hpp"
#include "world/entity/biological_ai.hpp"

namespace ba = lattice::world::entity;

namespace {

constexpr jint kVisibleFlag = 1;
constexpr jint kReachableFlag = 2;
constexpr jint kProfileFieldCount = 10;
constexpr jint kBiologicalAiAbiVersion = 4;

[[nodiscard]] inline ba::BiologicalSpecies decode_species(jint raw_species) noexcept {
    switch (raw_species) {
    case 1: return ba::BiologicalSpecies::sheep;
    case 2: return ba::BiologicalSpecies::pig;
    case 3: return ba::BiologicalSpecies::cow;
    case 4: return ba::BiologicalSpecies::chicken;
    case 5: return ba::BiologicalSpecies::rabbit;
    case 6: return ba::BiologicalSpecies::bee;
    case 7: return ba::BiologicalSpecies::goat;
    case 8: return ba::BiologicalSpecies::armadillo;
    case 9: return ba::BiologicalSpecies::camel;
    case 10: return ba::BiologicalSpecies::frog;
    case 11: return ba::BiologicalSpecies::turtle;
    case 12: return ba::BiologicalSpecies::axolotl;
    case 13: return ba::BiologicalSpecies::sniffer;
    case 14: return ba::BiologicalSpecies::llama;
    case 15: return ba::BiologicalSpecies::panda;
    case 16: return ba::BiologicalSpecies::ocelot;
    case 17: return ba::BiologicalSpecies::wolf;
    case 18: return ba::BiologicalSpecies::cat;
    default: return ba::BiologicalSpecies::generic;
    }
}

[[nodiscard]] inline ba::BiologicalStimulusKind decode_kind(jint raw_kind) noexcept {
    switch (raw_kind) {
    case 0: return ba::BiologicalStimulusKind::threat;
    case 1: return ba::BiologicalStimulusKind::prey;
    case 2: return ba::BiologicalStimulusKind::food;
    default: return ba::BiologicalStimulusKind::curiosity;
    }
}

[[nodiscard]] inline jint encode_action(ba::BiologicalAction action) noexcept {
    switch (action) {
    case ba::BiologicalAction::idle:        return 0;
    case ba::BiologicalAction::wander:      return 1;
    case ba::BiologicalAction::rest:        return 2;
    case ba::BiologicalAction::flee:        return 3;
    case ba::BiologicalAction::pursue:      return 4;
    case ba::BiologicalAction::eat:         return 5;
    case ba::BiologicalAction::investigate: return 6;
    }
    return 0;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_latticemc_lattice_nativelib_NativeBiologicalAi_nativeAbiVersion(
        JNIEnv* /*env*/, jclass /*cls*/) {
    return kBiologicalAiAbiVersion;
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeBiologicalAi_nativeDecide(
        JNIEnv* env, jclass /*cls*/,
        jfloat healthRatio,
        jfloat energyRatio,
        jfloat aggression,
        jfloat attackRange,
        jboolean isOnFire,
        jboolean canAttack,
        jboolean canConsumeFood,
        jfloat ambientDanger,
        jboolean hasShelter,
        jboolean canIdleSafely,
        jboolean canPathToFood,
        jfloatArray jProfileValues,
        jintArray jStimulusKinds,
        jfloatArray jStimulusDistances,
        jfloatArray jStimulusStrengths,
        jintArray jStimulusFlags,
        jint stimulusCount,
        jintArray jOutInts,
        jfloatArray jOutFloats) {
    if (!jOutInts || !jOutFloats) {
        lattice::jni::throw_illegal_arg(env, "lattice biological-ai: null output");
        return;
    }
    if (stimulusCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice biological-ai: negative stimulusCount");
        return;
    }
    if (env->GetArrayLength(jOutInts) < 2 || env->GetArrayLength(jOutFloats) < 3) {
        lattice::jni::throw_illegal_arg(env, "lattice biological-ai: output arrays too short");
        return;
    }
    if (!jProfileValues || env->GetArrayLength(jProfileValues) < kProfileFieldCount) {
        lattice::jni::throw_illegal_arg(env, "lattice biological-ai: profile array too short");
        return;
    }
    if (stimulusCount > 0) {
        if (!jStimulusKinds || !jStimulusDistances || !jStimulusStrengths || !jStimulusFlags) {
            lattice::jni::throw_illegal_arg(env, "lattice biological-ai: null stimulus arrays");
            return;
        }
        if (env->GetArrayLength(jStimulusKinds) < stimulusCount
            || env->GetArrayLength(jStimulusDistances) < stimulusCount
            || env->GetArrayLength(jStimulusStrengths) < stimulusCount
            || env->GetArrayLength(jStimulusFlags) < stimulusCount) {
            lattice::jni::throw_illegal_arg(env, "lattice biological-ai: stimulus arrays too short");
            return;
        }
    }

    lattice::jni::CriticalIntArray out_ints{env, jOutInts};
    lattice::jni::CriticalFloatArray out_floats{env, jOutFloats};
    lattice::jni::CriticalFloatArray profile_values{env, jProfileValues};
    if (!out_ints || !out_floats || !profile_values) {
        lattice::jni::throw_oom(env, "lattice biological-ai: pin output");
        return;
    }

    lattice::jni::CriticalIntArray stimulus_kinds{env, jStimulusKinds};
    lattice::jni::CriticalFloatArray stimulus_distances{env, jStimulusDistances};
    lattice::jni::CriticalFloatArray stimulus_strengths{env, jStimulusStrengths};
    lattice::jni::CriticalIntArray stimulus_flags{env, jStimulusFlags};
    if (stimulusCount > 0 && (!stimulus_kinds || !stimulus_distances || !stimulus_strengths || !stimulus_flags)) {
        lattice::jni::throw_oom(env, "lattice biological-ai: pin stimuli");
        return;
    }

    ba::BiologicalEntityState entity{};
    entity.health_ratio = healthRatio;
    entity.energy_ratio = energyRatio;
    entity.aggression = aggression;
    entity.attack_range = attackRange;
    entity.is_on_fire = isOnFire == JNI_TRUE;
    entity.can_attack = canAttack == JNI_TRUE;
    entity.can_consume_food = canConsumeFood == JNI_TRUE;

    ba::BiologicalEnvironmentView environment{};
    environment.ambient_danger = ambientDanger;
    environment.has_shelter = hasShelter == JNI_TRUE;
    environment.can_idle_safely = canIdleSafely == JNI_TRUE;
    environment.can_path_to_food = canPathToFood == JNI_TRUE;

    ba::BiologicalAiProfile profile{};
    profile.flee_health_threshold = profile_values.data()[0];
    profile.flee_danger_threshold = profile_values.data()[1];
    profile.flee_threat_strength = profile_values.data()[2];
    profile.attack_health_threshold = profile_values.data()[3];
    profile.attack_energy_threshold = profile_values.data()[4];
    profile.seek_food_energy_threshold = profile_values.data()[5];
    profile.rest_energy_threshold = profile_values.data()[6];
    profile.curiosity_strength_threshold = profile_values.data()[7];
    profile.close_threat_distance = profile_values.data()[8];
    profile.close_food_distance = profile_values.data()[9];

    ba::BiologicalStimulus* stimuli = nullptr;
    if (stimulusCount > 0) {
        stimuli = new (std::nothrow) ba::BiologicalStimulus[static_cast<std::size_t>(stimulusCount)];
        if (!stimuli) {
            lattice::jni::throw_oom(env, "lattice biological-ai: alloc stimuli");
            return;
        }
        for (jint i = 0; i < stimulusCount; ++i) {
            ba::BiologicalStimulus stimulus{};
            stimulus.kind = decode_kind(stimulus_kinds.data()[i]);
            stimulus.distance = stimulus_distances.data()[i];
            stimulus.strength = stimulus_strengths.data()[i];
            const jint flags = stimulus_flags.data()[i];
            stimulus.visible = (flags & kVisibleFlag) != 0;
            stimulus.reachable = (flags & kReachableFlag) != 0;
            stimuli[i] = stimulus;
        }
    }

    const ba::BiologicalAiInputs inputs{
        entity,
        environment,
        stimuli,
        static_cast<std::size_t>(stimulusCount),
    };
    const ba::BiologicalDecision decision = ba::decide_biological_action(inputs, profile);
    delete[] stimuli;

    out_ints.data()[0] = encode_action(decision.action);
    out_ints.data()[1] = decision.stimulus_index;
    out_floats.data()[0] = decision.urgency;
    out_floats.data()[1] = decision.move_speed;
    out_floats.data()[2] = decision.desired_range;
}

JNIEXPORT void JNICALL
Java_com_latticemc_lattice_nativelib_NativeBiologicalAi_nativeDecideForSpecies(
        JNIEnv* env, jclass /*cls*/,
        jint species,
        jfloat healthRatio,
        jfloat energyRatio,
        jfloat aggression,
        jfloat attackRange,
        jboolean isOnFire,
        jboolean canAttack,
        jboolean canConsumeFood,
        jfloat ambientDanger,
        jboolean hasShelter,
        jboolean canIdleSafely,
        jboolean canPathToFood,
        jintArray jStimulusKinds,
        jfloatArray jStimulusDistances,
        jfloatArray jStimulusStrengths,
        jintArray jStimulusFlags,
        jint stimulusCount,
        jintArray jOutInts,
        jfloatArray jOutFloats) {
    if (!jOutInts || !jOutFloats) {
        lattice::jni::throw_illegal_arg(env, "lattice biological-ai: null output");
        return;
    }
    if (stimulusCount < 0) {
        lattice::jni::throw_illegal_arg(env, "lattice biological-ai: negative stimulusCount");
        return;
    }
    if (env->GetArrayLength(jOutInts) < 2 || env->GetArrayLength(jOutFloats) < 3) {
        lattice::jni::throw_illegal_arg(env, "lattice biological-ai: output arrays too short");
        return;
    }
    if (stimulusCount > 0) {
        if (!jStimulusKinds || !jStimulusDistances || !jStimulusStrengths || !jStimulusFlags) {
            lattice::jni::throw_illegal_arg(env, "lattice biological-ai: null stimulus arrays");
            return;
        }
        if (env->GetArrayLength(jStimulusKinds) < stimulusCount
            || env->GetArrayLength(jStimulusDistances) < stimulusCount
            || env->GetArrayLength(jStimulusStrengths) < stimulusCount
            || env->GetArrayLength(jStimulusFlags) < stimulusCount) {
            lattice::jni::throw_illegal_arg(env, "lattice biological-ai: stimulus arrays too short");
            return;
        }
    }

    lattice::jni::CriticalIntArray out_ints{env, jOutInts};
    lattice::jni::CriticalFloatArray out_floats{env, jOutFloats};
    if (!out_ints || !out_floats) {
        lattice::jni::throw_oom(env, "lattice biological-ai: pin output");
        return;
    }

    lattice::jni::CriticalIntArray stimulus_kinds{env, jStimulusKinds};
    lattice::jni::CriticalFloatArray stimulus_distances{env, jStimulusDistances};
    lattice::jni::CriticalFloatArray stimulus_strengths{env, jStimulusStrengths};
    lattice::jni::CriticalIntArray stimulus_flags{env, jStimulusFlags};
    if (stimulusCount > 0 && (!stimulus_kinds || !stimulus_distances || !stimulus_strengths || !stimulus_flags)) {
        lattice::jni::throw_oom(env, "lattice biological-ai: pin stimuli");
        return;
    }

    ba::BiologicalEntityState entity{};
    entity.health_ratio = healthRatio;
    entity.energy_ratio = energyRatio;
    entity.aggression = aggression;
    entity.attack_range = attackRange;
    entity.is_on_fire = isOnFire == JNI_TRUE;
    entity.can_attack = canAttack == JNI_TRUE;
    entity.can_consume_food = canConsumeFood == JNI_TRUE;

    ba::BiologicalEnvironmentView environment{};
    environment.ambient_danger = ambientDanger;
    environment.has_shelter = hasShelter == JNI_TRUE;
    environment.can_idle_safely = canIdleSafely == JNI_TRUE;
    environment.can_path_to_food = canPathToFood == JNI_TRUE;

    ba::BiologicalStimulus* stimuli = nullptr;
    if (stimulusCount > 0) {
        stimuli = new (std::nothrow) ba::BiologicalStimulus[static_cast<std::size_t>(stimulusCount)];
        if (!stimuli) {
            lattice::jni::throw_oom(env, "lattice biological-ai: alloc stimuli");
            return;
        }
        for (jint i = 0; i < stimulusCount; ++i) {
            ba::BiologicalStimulus stimulus{};
            stimulus.kind = decode_kind(stimulus_kinds.data()[i]);
            stimulus.distance = stimulus_distances.data()[i];
            stimulus.strength = stimulus_strengths.data()[i];
            const jint flags = stimulus_flags.data()[i];
            stimulus.visible = (flags & kVisibleFlag) != 0;
            stimulus.reachable = (flags & kReachableFlag) != 0;
            stimuli[i] = stimulus;
        }
    }

    const ba::BiologicalAiInputs inputs{
        entity,
        environment,
        stimuli,
        static_cast<std::size_t>(stimulusCount),
    };
    const ba::BiologicalDecision decision = ba::decide_biological_action(decode_species(species), inputs);
    delete[] stimuli;

    out_ints.data()[0] = encode_action(decision.action);
    out_ints.data()[1] = decision.stimulus_index;
    out_floats.data()[0] = decision.urgency;
    out_floats.data()[1] = decision.move_speed;
    out_floats.data()[2] = decision.desired_range;
}

} // extern "C"
