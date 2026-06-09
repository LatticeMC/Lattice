/**
 * @file lattice.hpp
 * @brief Lattice Native — public umbrella header.
 *
 * This library is currently in the "clean skeleton" state following the
 * T1-rewrite cleanup pass. The intent is to expose three Java-facing
 * modules, each with a 1:1 mapping to a Minecraft 1.21.11 hotspot:
 *
 *   - NativeLightEngine       (src/world/light/       ← ChunkLightProvider + LevelPropagator)
 *   - NativePaletteOps        (src/world/palette/     ← PalettedContainer + PackedIntegerArray)
 *   - NativeChunkSerializer   (src/io/anvil/          ← RegionFile + NbtIo, libdeflate/zlib)
 *
 * Everything else that previously lived here (AI, redstone, cache,
 * entity-tracking, pathfinder, coroutine scheduler, etc.) has been moved to
 * `lattice-native/attic/` and is not built. See README.md / the project notes
 * for rationale.
 *
 * Legacy sub-headers (ai.hpp, cache.hpp, io.hpp, net.hpp, redstone.hpp,
 * world.hpp, jni.hpp) have likewise been archived.
 */

#pragma once

#include "lattice/config.hpp"

namespace lattice {

struct version {
    static constexpr int major = 0;
    static constexpr int minor = 1;
    static constexpr int patch = 0;
    static constexpr const char* string = "0.1.0";
};

} // namespace lattice
