package net.minecraft.world.entity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class EntityCollisionScratchTestSuite {

    @Test
    void reusesAndClearsAllBuffers() {
        Entity.CollisionScratch scratch = new Entity.CollisionScratch();
        Entity.CollisionScratch.CollisionLists first = scratch.acquire();
        first.voxels.add(null);
        first.aabbs.add(null);
        first.entityAabbs.add(null);
        first.stepVoxels.add(null);
        scratch.release();

        Entity.CollisionScratch.CollisionLists second = scratch.acquire();
        assertSame(first, second);
        assertEquals(0, second.voxels.size());
        assertEquals(0, second.aabbs.size());
        assertEquals(0, second.entityAabbs.size());
        assertEquals(0, second.stepVoxels.size());
        scratch.release();
    }

    @Test
    void nestedCollisionQueriesUseIndependentBuffers() {
        Entity.CollisionScratch scratch = new Entity.CollisionScratch();
        Entity.CollisionScratch.CollisionLists outer = scratch.acquire();
        outer.aabbs.add(null);
        Entity.CollisionScratch.CollisionLists inner = scratch.acquire();
        assertNotSame(outer, inner);
        inner.entityAabbs.add(null);
        scratch.release();
        assertEquals(1, outer.aabbs.size());
        scratch.release();
    }

    @Test
    void oversizedBuffersReleaseReferencesAndCapacity() {
        Entity.CollisionScratch scratch = new Entity.CollisionScratch();
        Entity.CollisionScratch.CollisionLists lists = scratch.acquire();
        for (int i = 0; i <= 256; ++i) {
            lists.aabbs.add(null);
        }
        scratch.release();
        assertEquals(0, lists.aabbs.size());
    }

    @Test
    void outermostReleaseTrimsDeepReentrySlots() throws ReflectiveOperationException {
        Entity.CollisionScratch scratch = new Entity.CollisionScratch();
        for (int i = 0; i < 5; ++i) {
            scratch.acquire();
        }
        for (int i = 0; i < 5; ++i) {
            scratch.release();
        }
        assertEquals(4, scratchLists(scratch).size());
    }

    @SuppressWarnings("unchecked")
    private static List<Entity.CollisionScratch.CollisionLists> scratchLists(Entity.CollisionScratch scratch) throws ReflectiveOperationException {
        Field lists = Entity.CollisionScratch.class.getDeclaredField("lists");
        lists.setAccessible(true);
        return (List<Entity.CollisionScratch.CollisionLists>)lists.get(scratch);
    }
}
