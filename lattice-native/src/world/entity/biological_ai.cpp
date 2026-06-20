// Minimal biological AI decision core. See biological_ai.hpp.

#include "world/entity/biological_ai.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace lattice::world::entity {

namespace {

constexpr BiologicalAiProfile kGenericProfile{};
constexpr BiologicalAiProfile kSheepProfile{0.45F, 0.55F, 0.55F, 0.90F, 0.90F, 0.85F, 0.35F, 0.55F, 5.0F, 2.5F};
constexpr BiologicalAiProfile kPigProfile{0.40F, 0.60F, 0.65F, 0.85F, 0.80F, 0.75F, 0.25F, 0.45F, 4.5F, 2.5F};
constexpr BiologicalAiProfile kCowProfile{0.42F, 0.60F, 0.70F, 0.90F, 0.85F, 0.80F, 0.30F, 0.45F, 4.5F, 2.5F};
constexpr BiologicalAiProfile kChickenProfile{0.55F, 0.45F, 0.35F, 0.95F, 0.95F, 0.90F, 0.28F, 0.35F, 6.0F, 2.0F};
constexpr BiologicalAiProfile kRabbitProfile{0.60F, 0.40F, 0.30F, 0.98F, 0.98F, 0.92F, 0.22F, 0.30F, 6.5F, 2.0F};
constexpr BiologicalAiProfile kBeeProfile{0.25F, 0.70F, 0.80F, 0.30F, 0.25F, 0.70F, 0.10F, 0.25F, 3.0F, 2.0F};
constexpr BiologicalAiProfile kGoatProfile{0.30F, 0.70F, 0.80F, 0.55F, 0.45F, 0.70F, 0.18F, 0.50F, 3.5F, 2.5F};
constexpr BiologicalAiProfile kArmadilloProfile{0.65F, 0.35F, 0.25F, 0.99F, 0.99F, 0.80F, 0.30F, 0.20F, 6.5F, 2.0F};
constexpr BiologicalAiProfile kCamelProfile{0.35F, 0.65F, 0.75F, 0.80F, 0.75F, 0.72F, 0.22F, 0.35F, 4.0F, 2.5F};
constexpr BiologicalAiProfile kFrogProfile{0.45F, 0.55F, 0.60F, 0.40F, 0.30F, 0.78F, 0.18F, 0.40F, 4.5F, 2.0F};
constexpr BiologicalAiProfile kTurtleProfile{0.55F, 0.45F, 0.45F, 0.95F, 0.95F, 0.82F, 0.26F, 0.20F, 6.0F, 2.0F};
constexpr BiologicalAiProfile kAxolotlProfile{0.50F, 0.50F, 0.65F, 0.55F, 0.30F, 0.78F, 0.15F, 0.30F, 4.0F, 2.0F};
constexpr BiologicalAiProfile kSnifferProfile{0.40F, 0.60F, 0.70F, 0.20F, 0.20F, 0.85F, 0.18F, 0.55F, 4.0F, 2.5F};
constexpr BiologicalAiProfile kLlamaProfile{0.38F, 0.62F, 0.70F, 0.35F, 0.30F, 0.78F, 0.18F, 0.35F, 4.0F, 2.5F};
constexpr BiologicalAiProfile kPandaProfile{0.42F, 0.58F, 0.65F, 0.35F, 0.25F, 0.82F, 0.20F, 0.25F, 4.0F, 2.0F};
constexpr BiologicalAiProfile kOcelotProfile{0.45F, 0.50F, 0.75F, 0.45F, 0.30F, 0.75F, 0.16F, 0.45F, 4.5F, 2.0F};

[[nodiscard]] inline float clamp_unit(float value, float fallback) noexcept {
    if (!std::isfinite(value)) return fallback;
    return std::clamp(value, 0.0F, 1.0F);
}

[[nodiscard]] inline float clamp_non_negative(float value, float fallback) noexcept {
    if (!std::isfinite(value)) return fallback;
    return std::max(value, 0.0F);
}

[[nodiscard]] const BiologicalStimulus* select_best_stimulus(
    const BiologicalStimulus* stimuli,
    std::size_t stimulus_count,
    BiologicalStimulusKind kind,
    bool require_visible,
    bool require_reachable,
    std::size_t& best_index) noexcept {
    const BiologicalStimulus* best = nullptr;
    float best_score = -std::numeric_limits<float>::infinity();
    best_index = 0;

    for (std::size_t index = 0; index < stimulus_count; ++index) {
        const BiologicalStimulus& stimulus = stimuli[index];
        if (stimulus.kind != kind) continue;
        if (require_visible && !stimulus.visible) continue;
        if (require_reachable && !stimulus.reachable) continue;

        const float distance = clamp_non_negative(stimulus.distance, 0.0F);
        const float strength = clamp_non_negative(stimulus.strength, 0.0F);
        const float score = strength - distance * 0.1F;
        if (!best || score > best_score) {
            best = &stimulus;
            best_score = score;
            best_index = index;
        }
    }

    return best;
}

[[nodiscard]] BiologicalDecision make_decision(BiologicalAction action,
                                               int stimulus_index,
                                               float urgency,
                                               float move_speed,
                                               float desired_range) noexcept {
    BiologicalDecision decision{};
    decision.action = action;
    decision.stimulus_index = stimulus_index;
    decision.urgency = std::clamp(urgency, 0.0F, 1.0F);
    decision.move_speed = std::max(move_speed, 0.0F);
    decision.desired_range = std::max(desired_range, 0.0F);
    return decision;
}

} // namespace

BiologicalDecision decide_biological_action(const BiologicalAiInputs& inputs,
                                            const BiologicalAiProfile& profile) noexcept {
    const float health = clamp_unit(inputs.entity.health_ratio, 1.0F);
    const float energy = clamp_unit(inputs.entity.energy_ratio, 1.0F);
    const float aggression = clamp_unit(inputs.entity.aggression, 0.0F);
    const float attack_range = clamp_non_negative(inputs.entity.attack_range, 1.5F);
    const float ambient_danger = clamp_unit(inputs.environment.ambient_danger, 0.0F);

    std::size_t threat_index = 0;
    const BiologicalStimulus* threat = select_best_stimulus(
        inputs.stimuli,
        inputs.stimulus_count,
        BiologicalStimulusKind::threat,
        true,
        false,
        threat_index);

    const bool under_immediate_threat =
        threat && clamp_non_negative(threat->distance, 0.0F) <= profile.close_threat_distance;
    const bool under_strong_threat = under_immediate_threat
        && clamp_non_negative(threat->strength, 0.0F) >= profile.flee_threat_strength;
    const bool should_flee = inputs.entity.is_on_fire
        || (threat && (health <= profile.flee_health_threshold || ambient_danger >= profile.flee_danger_threshold))
        || under_strong_threat
        || (under_immediate_threat && health < 0.5F);
    if (should_flee) {
        const float urgency = std::max(ambient_danger, 1.0F - health);
        const float desired_range = threat ? std::max(clamp_non_negative(threat->distance, 0.0F), profile.close_threat_distance) : 8.0F;
        return make_decision(BiologicalAction::flee,
                             threat ? static_cast<int>(threat_index) : -1,
                             urgency,
                             inputs.environment.has_shelter ? 1.2F : 1.0F,
                             desired_range);
    }

    if (inputs.entity.can_attack
        && health >= profile.attack_health_threshold
        && energy >= profile.attack_energy_threshold
        && aggression >= 0.5F) {
        std::size_t prey_index = 0;
        const BiologicalStimulus* prey = select_best_stimulus(
            inputs.stimuli,
            inputs.stimulus_count,
            BiologicalStimulusKind::prey,
            true,
            true,
            prey_index);
        if (prey) {
            const float distance = clamp_non_negative(prey->distance, 0.0F);
            const float urgency = std::clamp(aggression * 0.6F + clamp_non_negative(prey->strength, 0.0F) * 0.4F, 0.0F, 1.0F);
            return make_decision(BiologicalAction::pursue,
                                 static_cast<int>(prey_index),
                                 urgency,
                                 distance <= attack_range ? 0.8F : 1.0F,
                                 attack_range);
        }
    }

    if (inputs.entity.can_consume_food
        && energy <= profile.seek_food_energy_threshold
        && inputs.environment.can_path_to_food) {
        std::size_t food_index = 0;
        const BiologicalStimulus* food = select_best_stimulus(
            inputs.stimuli,
            inputs.stimulus_count,
            BiologicalStimulusKind::food,
            true,
            true,
            food_index);
        if (food) {
            const float distance = clamp_non_negative(food->distance, 0.0F);
            if (distance <= profile.close_food_distance) {
                return make_decision(BiologicalAction::eat,
                                     static_cast<int>(food_index),
                                     std::clamp(1.0F - energy, 0.0F, 1.0F),
                                     0.2F,
                                     0.0F);
            }
            return make_decision(BiologicalAction::pursue,
                                 static_cast<int>(food_index),
                                 std::clamp(1.0F - energy, 0.0F, 1.0F),
                                 0.8F,
                                 0.5F);
        }
    }

    if (energy <= profile.rest_energy_threshold && inputs.environment.can_idle_safely) {
        return make_decision(BiologicalAction::rest, -1, 1.0F - energy, 0.0F, 0.0F);
    }

    std::size_t curiosity_index = 0;
    const BiologicalStimulus* curiosity = select_best_stimulus(
        inputs.stimuli,
        inputs.stimulus_count,
        BiologicalStimulusKind::curiosity,
        true,
        true,
        curiosity_index);
    if (curiosity && clamp_non_negative(curiosity->strength, 0.0F) >= profile.curiosity_strength_threshold) {
        return make_decision(BiologicalAction::investigate,
                             static_cast<int>(curiosity_index),
                             clamp_non_negative(curiosity->strength, 0.0F),
                             0.6F,
                             1.0F);
    }

    if (inputs.environment.can_idle_safely && energy < 0.4F) {
        return make_decision(BiologicalAction::rest, -1, 0.4F - energy, 0.0F, 0.0F);
    }

    if (!inputs.environment.can_idle_safely || inputs.stimulus_count > 0) {
        return make_decision(BiologicalAction::wander, -1, 0.25F, 0.5F, 0.0F);
    }

    return make_decision(BiologicalAction::idle, -1, 0.1F, 0.0F, 0.0F);
}

const BiologicalAiProfile& biological_profile(BiologicalSpecies species) noexcept {
    switch (species) {
    case BiologicalSpecies::sheep: return kSheepProfile;
    case BiologicalSpecies::pig: return kPigProfile;
    case BiologicalSpecies::cow: return kCowProfile;
    case BiologicalSpecies::chicken: return kChickenProfile;
    case BiologicalSpecies::rabbit: return kRabbitProfile;
    case BiologicalSpecies::bee: return kBeeProfile;
    case BiologicalSpecies::goat: return kGoatProfile;
    case BiologicalSpecies::armadillo: return kArmadilloProfile;
    case BiologicalSpecies::camel: return kCamelProfile;
    case BiologicalSpecies::frog: return kFrogProfile;
    case BiologicalSpecies::turtle: return kTurtleProfile;
    case BiologicalSpecies::axolotl: return kAxolotlProfile;
    case BiologicalSpecies::sniffer: return kSnifferProfile;
    case BiologicalSpecies::llama: return kLlamaProfile;
    case BiologicalSpecies::panda: return kPandaProfile;
    case BiologicalSpecies::ocelot: return kOcelotProfile;
    case BiologicalSpecies::generic:
    default:
        return kGenericProfile;
    }
}

BiologicalDecision decide_biological_action(BiologicalSpecies species,
                                            const BiologicalAiInputs& inputs) noexcept {
    return decide_biological_action(inputs, biological_profile(species));
}

} // namespace lattice::world::entity
