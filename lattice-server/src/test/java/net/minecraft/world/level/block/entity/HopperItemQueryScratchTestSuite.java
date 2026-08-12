package net.minecraft.world.level.block.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import org.junit.jupiter.api.Test;

class HopperItemQueryScratchTestSuite {
    @Test
    void reusesAndClearsSmallSnapshots() {
        HopperBlockEntity.ItemEntityQueryScratch scratch = new HopperBlockEntity.ItemEntityQueryScratch();
        List<ItemEntity> first = scratch.acquire();
        first.add(null);
        scratch.release();

        List<ItemEntity> second = scratch.acquire();
        assertSame(first, second);
        assertEquals(0, second.size());
        scratch.release();
    }

    @Test
    void keepsNestedSnapshotsIsolated() {
        HopperBlockEntity.ItemEntityQueryScratch scratch = new HopperBlockEntity.ItemEntityQueryScratch();
        List<ItemEntity> outer = scratch.acquire();
        outer.add(null);
        List<ItemEntity> inner = scratch.acquire();
        inner.add(null);

        scratch.release();
        assertEquals(1, outer.size());
        scratch.release();
    }

    @Test
    void dropsOversizedSnapshotsOnRelease() {
        HopperBlockEntity.ItemEntityQueryScratch scratch = new HopperBlockEntity.ItemEntityQueryScratch();
        List<ItemEntity> oversized = scratch.acquire();
        for (int index = 0; index <= 256; index++) {
            oversized.add(null);
        }
        scratch.release();

        List<ItemEntity> replacement = scratch.acquire();
        assertNotSame(oversized, replacement);
        assertEquals(0, replacement.size());
        scratch.release();
    }

    @Test
    void trimsReleasedNestedSnapshotsToFourSlots() throws ReflectiveOperationException {
        HopperBlockEntity.ItemEntityQueryScratch scratch = new HopperBlockEntity.ItemEntityQueryScratch();
        for (int depth = 0; depth < 5; depth++) {
            scratch.acquire();
        }

        for (int depth = 0; depth < 5; depth++) {
            scratch.release();
        }

        assertEquals(4, scratchLists(scratch).size());
    }

    @SuppressWarnings("unchecked")
    private static List<ArrayList<ItemEntity>> scratchLists(HopperBlockEntity.ItemEntityQueryScratch scratch) throws ReflectiveOperationException {
        Field lists = HopperBlockEntity.ItemEntityQueryScratch.class.getDeclaredField("lists");
        lists.setAccessible(true);
        return (List<ArrayList<ItemEntity>>)lists.get(scratch);
    }
}
