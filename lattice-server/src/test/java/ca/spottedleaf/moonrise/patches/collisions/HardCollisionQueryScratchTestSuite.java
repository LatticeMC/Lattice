package ca.spottedleaf.moonrise.patches.collisions;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class HardCollisionQueryScratchTestSuite {

    @Test
    void reusesAndClearsSmallSnapshots() {
        CollisionUtil.HardCollisionQueryScratch scratch = new CollisionUtil.HardCollisionQueryScratch();
        List<Entity> first = scratch.acquire();
        first.add(null);
        scratch.release();

        List<Entity> second = scratch.acquire();
        assertSame(first, second);
        assertEquals(0, second.size());
        scratch.release();
    }

    @Test
    void nestedSnapshotsDoNotAlias() {
        CollisionUtil.HardCollisionQueryScratch scratch = new CollisionUtil.HardCollisionQueryScratch();
        List<Entity> outer = scratch.acquire();
        outer.add(null);
        List<Entity> inner = scratch.acquire();
        assertNotSame(outer, inner);
        inner.add(null);
        scratch.release();
        assertEquals(1, outer.size());
        scratch.release();
    }

    @Test
    void oversizedSnapshotsAreNotRetained() {
        CollisionUtil.HardCollisionQueryScratch scratch = new CollisionUtil.HardCollisionQueryScratch();
        List<Entity> oversized = scratch.acquire();
        for (int i = 0; i <= 256; ++i) {
            oversized.add(null);
        }
        scratch.release();

        List<Entity> replacement = scratch.acquire();
        assertNotSame(oversized, replacement);
        assertEquals(0, replacement.size());
        scratch.release();
    }

    @Test
    void outermostReleaseTrimsDeepReentrySlots() throws ReflectiveOperationException {
        CollisionUtil.HardCollisionQueryScratch scratch = new CollisionUtil.HardCollisionQueryScratch();
        for (int i = 0; i < 5; ++i) {
            scratch.acquire();
        }
        for (int i = 0; i < 5; ++i) {
            scratch.release();
        }
        assertEquals(4, scratchLists(scratch).size());
    }

    @SuppressWarnings("unchecked")
    private static List<ArrayList<Entity>> scratchLists(CollisionUtil.HardCollisionQueryScratch scratch) throws ReflectiveOperationException {
        Field lists = CollisionUtil.HardCollisionQueryScratch.class.getDeclaredField("lists");
        lists.setAccessible(true);
        return (List<ArrayList<Entity>>)lists.get(scratch);
    }
}
