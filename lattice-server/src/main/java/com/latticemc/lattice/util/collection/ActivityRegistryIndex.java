package com.latticemc.lattice.util.collection;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Dense activity registry index used by the optimized Brain collections.
 *
 * <p>Adapted from Leaf's {@code RegistryTypeManager} in "Replace brain with optimized collection".</p>
 *
 * <p>Original authors: HaHaWTH, Taiyou06, and hayanesuru<br>
 * Original license: GPL-3.0-only<br>
 * Source: https://github.com/Winds-Studio/Leaf/blob/178bcb4/leaf-server/src/main/java/org/dreeam/leaf/util/RegistryTypeManager.java</p>
 */
public final class ActivityRegistryIndex {
    public static final int SIZE;
    private static final Activity[] BY_ID;

    static {
        SIZE = BuiltInRegistries.ACTIVITY.size();
        if (SIZE == 0) {
            throw new ExceptionInInitializerError("ActivityRegistryIndex initialized before registries bootstrap");
        }
        if (SIZE > Integer.SIZE) {
            throw new ExceptionInInitializerError("minecraft:activity exceeds the 32-bit activity mask");
        }

        BY_ID = new Activity[SIZE];
        for (int id = 0; id < SIZE; id++) {
            Activity activity = BuiltInRegistries.ACTIVITY.byIdOrThrow(id);
            if (activity.id != id) {
                throw new ExceptionInInitializerError("Activity registry id mismatch at " + id);
            }
            BY_ID[id] = activity;
        }
    }

    public static Activity byId(int id) {
        return BY_ID[id];
    }

    private ActivityRegistryIndex() {
    }
}
