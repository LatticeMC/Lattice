#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest/doctest.h>

#include <limits>

#include "world/entity/biological_ai.hpp"

using namespace lattice::world::entity;

TEST_CASE("biological_ai: flee takes priority over other options") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::prey, 1.0F, 0.9F, true, true},
        {BiologicalStimulusKind::threat, 2.0F, 0.7F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.2F;
    inputs.entity.energy_ratio = 0.8F;
    inputs.entity.aggression = 0.9F;
    inputs.environment.ambient_danger = 0.8F;
    inputs.environment.has_shelter = true;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 2;

    const BiologicalDecision decision = decide_biological_action(inputs);
    CHECK(decision.action == BiologicalAction::flee);
    CHECK(decision.stimulus_index == 1);
    CHECK(decision.move_speed > 1.0F);
}

TEST_CASE("biological_ai: healthy aggressive entity pursues prey") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::prey, 3.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.7F;
    inputs.entity.aggression = 0.8F;
    inputs.entity.attack_range = 1.75F;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(inputs);
    CHECK(decision.action == BiologicalAction::pursue);
    CHECK(decision.stimulus_index == 0);
    CHECK(decision.desired_range == doctest::Approx(1.75F));
}

TEST_CASE("biological_ai: low energy prefers food over rest when reachable") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 1.0F, 0.6F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.7F;
    inputs.entity.energy_ratio = 0.15F;
    inputs.entity.can_consume_food = true;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = true;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(inputs);
    CHECK(decision.action == BiologicalAction::eat);
    CHECK(decision.stimulus_index == 0);
}

TEST_CASE("biological_ai: exhausted entity rests when no food is available") {
    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.8F;
    inputs.entity.energy_ratio = 0.1F;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = false;

    const BiologicalDecision decision = decide_biological_action(inputs);
    CHECK(decision.action == BiologicalAction::rest);
    CHECK(decision.stimulus_index == -1);
    CHECK(decision.move_speed == doctest::Approx(0.0F));
}

TEST_CASE("biological_ai: curiosity falls back to investigate") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::curiosity, 4.0F, 0.7F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.8F;
    inputs.entity.energy_ratio = 0.9F;
    inputs.environment.can_idle_safely = true;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(inputs);
    CHECK(decision.action == BiologicalAction::investigate);
    CHECK(decision.stimulus_index == 0);
}

TEST_CASE("biological_ai: invalid numeric inputs are sanitized") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, -4.0F, -1.0F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = -1.0F;
    inputs.entity.energy_ratio = std::numeric_limits<float>::quiet_NaN();
    inputs.entity.aggression = 4.0F;
    inputs.entity.attack_range = -3.0F;
    inputs.environment.ambient_danger = std::numeric_limits<float>::infinity();
    inputs.environment.can_idle_safely = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(inputs);
    CHECK(decision.action == BiologicalAction::wander);
    CHECK(decision.urgency >= 0.0F);
    CHECK(decision.move_speed >= 0.0F);
}

TEST_CASE("biological_ai: unreachable invisible stimuli do not drive pursuit") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::prey, 1.0F, 1.0F, false, false},
        {BiologicalStimulusKind::food, 2.0F, 0.8F, false, false},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.3F;
    inputs.entity.aggression = 1.0F;
    inputs.environment.can_idle_safely = true;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 2;

    const BiologicalDecision decision = decide_biological_action(inputs);
    CHECK(decision.action == BiologicalAction::rest);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: custom profile changes food threshold") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 1.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.7F;
    inputs.entity.can_consume_food = true;
    inputs.environment.can_path_to_food = true;
    inputs.environment.can_idle_safely = true;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    BiologicalAiProfile profile{};
    profile.seek_food_energy_threshold = 0.75F;

    const BiologicalDecision decision = decide_biological_action(inputs, profile);
    CHECK(decision.action == BiologicalAction::eat);
    CHECK(decision.stimulus_index == 0);
}

TEST_CASE("biological_ai: species registry changes threat response") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::threat, 5.0F, 1.0F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.5F;
    inputs.entity.energy_ratio = 0.6F;
    inputs.environment.ambient_danger = 0.5F;
    inputs.environment.has_shelter = true;
    inputs.environment.can_idle_safely = true;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision chicken = decide_biological_action(BiologicalSpecies::chicken, inputs);
    const BiologicalDecision cow = decide_biological_action(BiologicalSpecies::cow, inputs);

    CHECK(chicken.action == BiologicalAction::flee);
    CHECK(cow.action == BiologicalAction::wander);
}
