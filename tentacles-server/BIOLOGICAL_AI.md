# Biological AI Module

This document describes the current biological AI integration in `tentacles-server`.

## Scope

The current module does not replace vanilla `Goal`, `GoalSelector`, `Brain`, or `Behavior` systems.
It adds a native decision layer that accepts a compact, already-sensed snapshot and returns one immediate decision.

Code entry points:

- Native core: `lattice-native/src/world/entity/biological_ai.hpp`
- JNI bridge: `lattice-native/jni/biological_ai.cpp`
- Java wrapper: `tentacles-server/src/main/java/com/latticemc/lattice/nativelib/NativeBiologicalAi.java`
- Species profiles: `tentacles-server/src/main/java/com/latticemc/lattice/nativelib/BiologicalAiProfiles.java`

## Decision Model

Inputs stay intentionally small:

- entity state: health, energy proxy, aggression, attack range, fire state
- environment summary: ambient danger, shelter, idle safety, food pathability
- stimuli: threat, prey, food, curiosity

Outputs remain small:

- `IDLE`
- `WANDER`
- `REST`
- `FLEE`
- `PURSUE`
- `EAT`
- `INVESTIGATE`

## Current Integration Layers

There are currently three server-side action-mapping layers in this migration branch:

- `HerbivoreAiSupport`
  - used by `Sheep`, `Pig`, `Cow`, `Chicken`, `Rabbit`, `Goat`, `Armadillo`, `Camel`, `Frog`, `Turtle`, `Axolotl`, `Sniffer`, `Llama`, `Panda`
- `PollinatorAiSupport`
  - used by `Bee`
- `AquaticAiSupport`
  - used by `Frog`, `Turtle`, `Axolotl`

These helpers are deliberately thin and do not re-implement vanilla scheduling.

## Species Table

Profiles currently live in `BiologicalAiProfiles`.

Currently defined:

- `SHEEP`
- `PIG`
- `COW`
- `CHICKEN`
- `RABBIT`
- `BEE`
- `GOAT`
- `ARMADILLO`
- `CAMEL`
- `FROG`
- `TURTLE`
- `AXOLOTL`
- `SNIFFER`
- `LLAMA`
- `PANDA`

Each species still owns its own sensed summary in its mixin. The profile only tunes thresholds.

## Species Routing

`tentacles-server` now calls the Java wrapper with a `Species` identifier so the
native side can choose the authoritative per-species profile.

- Native species registry lives in `lattice-native/src/world/entity/biological_ai.cpp`
- Java wrapper species enum lives in `tentacles-server/src/main/java/com/latticemc/lattice/nativelib/NativeBiologicalAi.java`
- Java profile constants remain as fallback values when the native library is unavailable

## Local Navigation Primitives

The current native side also exposes lightweight path-related primitives.
These do not replace vanilla `PathNavigation`; they only help choose better local target points.

Currently available:

- `flee target sampler`
- `approach target sampler`
- `water target sampler`
- `home target sampler`

JNI / Java wrappers currently live under:

- `tentacles-server/src/main/java/com/latticemc/lattice/nativelib/NativeFleeTargetSampler.java`
- `tentacles-server/src/main/java/com/latticemc/lattice/nativelib/NativeApproachTargetSampler.java`
- `tentacles-server/src/main/java/com/latticemc/lattice/nativelib/NativeWaterTargetSampler.java`
- `tentacles-server/src/main/java/com/latticemc/lattice/nativelib/NativeHomeTargetSampler.java`

Current consumers:

- `HerbivoreAiSupport`
  - flee uses `flee target sampler`
  - approach to food uses `approach target sampler`
- `PollinatorAiSupport`
  - approach to prey or food uses `approach target sampler`
- `AquaticAiSupport`
  - approach behavior can prefer water-biased local targets
- `TurtleMixin`
  - home-going and egg-carrying behavior can bias toward `homePos`
- `BeeMixin`
  - hive return behavior can bias toward `hivePos`

## Vanilla Alignment

The design stays close to vanilla intent where possible:

- herbivores bias toward food and safety
- water-biased animals use water state as part of their energy/shelter proxy
- pose- or state-heavy animals only expose summary signals, leaving vanilla state machines intact
- bees keep hive/flower/nectar logic in vanilla and only use native decisions for a thin attack or temptation layer
- local navigation target choice is native-assisted, but final execution still goes through vanilla navigation

The native layer complements vanilla logic rather than replacing it.

## Tests

Java smoke coverage currently lives in:

- `tentacles-server/src/test/java/com/latticemc/lattice/nativelib/NativeBiologicalAiTest.java`

These tests validate profile-based divergence and representative behavior for the currently migrated species.
They also validate wrapper-level fallback behavior for the local navigation samplers.

## Known Gaps

- profiles are still code constants, not external data
- tests are decision-level smoke tests, not integrated entity tick tests
- native and Java paths still need full compile/run verification in an environment with toolchains installed
- candidate generation is still done on the Java side; only candidate scoring and selection is native-assisted
