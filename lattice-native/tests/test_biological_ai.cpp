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

TEST_CASE("biological_ai: timid species flee strong nearby threats while healthy") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::threat, 4.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.95F;
    inputs.entity.energy_ratio = 0.8F;
    inputs.environment.can_idle_safely = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision rabbit = decide_biological_action(BiologicalSpecies::rabbit, inputs);
    const BiologicalDecision goat = decide_biological_action(BiologicalSpecies::goat, inputs);

    CHECK(rabbit.action == BiologicalAction::flee);
    CHECK(goat.action == BiologicalAction::wander);
}

TEST_CASE("biological_ai: weak nearby threats do not force panic") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::threat, 3.0F, 0.2F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.95F;
    inputs.entity.energy_ratio = 0.8F;
    inputs.environment.can_idle_safely = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::rabbit, inputs);
    CHECK(decision.action == BiologicalAction::wander);
}

TEST_CASE("biological_ai: weak threat does not mask reachable food") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::threat, 6.0F, 0.2F, true, true},
        {BiologicalStimulusKind::food, 1.5F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.95F;
    inputs.entity.energy_ratio = 0.35F;
    inputs.entity.can_consume_food = true;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = true;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 2;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::sheep, inputs);
    CHECK(decision.action == BiologicalAction::eat);
    CHECK(decision.stimulus_index == 1);
}

TEST_CASE("biological_ai: bee rests after stinging energy collapse") {
    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.8F;
    inputs.entity.energy_ratio = 0.05F;
    inputs.entity.aggression = 0.0F;
    inputs.entity.can_attack = false;
    inputs.entity.can_consume_food = false;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = false;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::bee, inputs);
    CHECK(decision.action == BiologicalAction::rest);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: turtle carrying egg ignores food stimulus") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 2.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.35F;
    inputs.entity.can_consume_food = false;
    inputs.environment.can_idle_safely = false;
    inputs.environment.can_path_to_food = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::turtle, inputs);
    CHECK(decision.action == BiologicalAction::wander);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: axolotl rests when dry and low energy") {
    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.30F;
    inputs.entity.can_attack = false;
    inputs.entity.can_consume_food = false;
    inputs.environment.ambient_danger = 0.65F;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = false;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::axolotl, inputs);
    CHECK(decision.action == BiologicalAction::rest);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: scared panda rests instead of seeking food") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 2.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.15F;
    inputs.entity.can_attack = false;
    inputs.entity.can_consume_food = false;
    inputs.environment.ambient_danger = 0.75F;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::panda, inputs);
    CHECK(decision.action == BiologicalAction::rest);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: sniffer digging state rests instead of seeking food") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 2.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.10F;
    inputs.entity.can_attack = false;
    inputs.entity.can_consume_food = false;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::sniffer, inputs);
    CHECK(decision.action == BiologicalAction::rest);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: scared armadillo rests instead of seeking food") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 2.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.10F;
    inputs.entity.can_attack = false;
    inputs.entity.can_consume_food = false;
    inputs.environment.ambient_danger = 0.75F;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::armadillo, inputs);
    CHECK(decision.action == BiologicalAction::rest);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: goat preparing ram ignores food stimulus") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 2.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.90F;
    inputs.entity.can_attack = true;
    inputs.entity.can_consume_food = false;
    inputs.environment.can_idle_safely = false;
    inputs.environment.can_path_to_food = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::goat, inputs);
    CHECK(decision.action == BiologicalAction::wander);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: sitting camel rests instead of seeking food") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 2.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.15F;
    inputs.entity.can_attack = false;
    inputs.entity.can_consume_food = false;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::camel, inputs);
    CHECK(decision.action == BiologicalAction::rest);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: caravan llama ignores food stimulus") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 2.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.80F;
    inputs.entity.can_attack = false;
    inputs.entity.can_consume_food = false;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::llama, inputs);
    CHECK(decision.action == BiologicalAction::wander);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: eating sheep rests instead of seeking food") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::food, 2.0F, 0.8F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.15F;
    inputs.entity.can_attack = false;
    inputs.entity.can_consume_food = false;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = false;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::sheep, inputs);
    CHECK(decision.action == BiologicalAction::rest);
    CHECK(decision.stimulus_index == -1);
}

TEST_CASE("biological_ai: frog can pursue prey when prey stimulus is present") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::prey, 2.5F, 0.85F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.85F;
    inputs.entity.aggression = 0.45F;
    inputs.entity.attack_range = 1.2F;
    inputs.entity.can_attack = true;
    inputs.entity.can_consume_food = false;
    inputs.environment.can_idle_safely = true;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 1;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::frog, inputs);
    CHECK(decision.action == BiologicalAction::pursue);
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

TEST_CASE("biological_ai: ocelot profile can pursue prey") {
    const BiologicalStimulus stimuli[] = {
        {BiologicalStimulusKind::prey, 4.0F, 0.85F, true, true},
        {BiologicalStimulusKind::food, 3.0F, 0.70F, true, true},
    };

    BiologicalAiInputs inputs{};
    inputs.entity.health_ratio = 0.9F;
    inputs.entity.energy_ratio = 0.9F;
    inputs.entity.aggression = 0.55F;
    inputs.entity.attack_range = 1.33F;
    inputs.entity.can_attack = true;
    inputs.entity.can_consume_food = true;
    inputs.environment.can_idle_safely = true;
    inputs.environment.can_path_to_food = true;
    inputs.stimuli = stimuli;
    inputs.stimulus_count = 2;

    const BiologicalDecision decision = decide_biological_action(BiologicalSpecies::ocelot, inputs);
    CHECK(decision.action == BiologicalAction::pursue);
    CHECK(decision.stimulus_index == 0);
}
