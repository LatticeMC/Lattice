/**
 * @file biological_ai.hpp
 * @brief Minimal biological AI decision core for mob-like entities.
 *
 * This module intentionally does not mirror vanilla's Goal / Brain /
 * Behavior stack. It accepts a compact snapshot of already-sensed state
 * and returns a single native decision that higher layers can translate
 * into movement, navigation, or animation work.
 */

#pragma once

#include <cstddef>

namespace lattice::world::entity {

enum class BiologicalStimulusKind {
    threat,
    prey,
    food,
    curiosity,
};

enum class BiologicalAction {
    idle,
    wander,
    rest,
    flee,
    pursue,
    eat,
    investigate,
};

enum class BiologicalSpecies {
    generic,
    sheep,
    pig,
    cow,
    chicken,
    rabbit,
    bee,
    goat,
    armadillo,
    camel,
    frog,
    turtle,
    axolotl,
    sniffer,
    llama,
    panda,
};

struct BiologicalEntityState {
    float health_ratio     = 1.0F;
    float energy_ratio     = 1.0F;
    float aggression       = 0.0F;
    float attack_range     = 1.5F;
    bool  is_on_fire       = false;
    bool  can_attack       = true;
    bool  can_consume_food = true;
};

struct BiologicalEnvironmentView {
    float ambient_danger    = 0.0F;
    bool  has_shelter       = false;
    bool  can_idle_safely   = true;
    bool  can_path_to_food  = true;
};

struct BiologicalStimulus {
    BiologicalStimulusKind kind = BiologicalStimulusKind::curiosity;
    float distance              = 0.0F;
    float strength              = 0.0F;
    bool  visible               = false;
    bool  reachable             = false;
};

struct BiologicalAiInputs {
    BiologicalEntityState            entity{};
    BiologicalEnvironmentView        environment{};
    const BiologicalStimulus*        stimuli        = nullptr;
    std::size_t                      stimulus_count = 0;
};

struct BiologicalAiProfile {
    float flee_health_threshold        = 0.35F;
    float flee_danger_threshold        = 0.65F;
    float attack_health_threshold      = 0.55F;
    float attack_energy_threshold      = 0.35F;
    float seek_food_energy_threshold   = 0.60F;
    float rest_energy_threshold        = 0.20F;
    float curiosity_strength_threshold = 0.40F;
    float close_threat_distance        = 4.0F;
    float close_food_distance          = 2.5F;
};

struct BiologicalDecision {
    BiologicalAction action        = BiologicalAction::idle;
    int              stimulus_index = -1;
    float            urgency       = 0.0F;
    float            move_speed    = 0.0F;
    float            desired_range = 0.0F;
};

[[nodiscard]] BiologicalDecision decide_biological_action(
    const BiologicalAiInputs& inputs,
    const BiologicalAiProfile& profile = {}) noexcept;

[[nodiscard]] const BiologicalAiProfile& biological_profile(BiologicalSpecies species) noexcept;

[[nodiscard]] BiologicalDecision decide_biological_action(
    BiologicalSpecies species,
    const BiologicalAiInputs& inputs) noexcept;

} // namespace lattice::world::entity
