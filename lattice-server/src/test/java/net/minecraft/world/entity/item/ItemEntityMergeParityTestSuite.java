package net.minecraft.world.entity.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Covers the observable stack boundaries used by ItemEntity's merge fast path.
 * Candidate traversal itself depends on a live Level entity index, so this test
 * keeps the parity assertion at the stack-operation boundary instead of adding
 * a production-only traversal counter.
 */
class ItemEntityMergeParityTestSuite {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void fullStackCannotAcceptAnotherItem() {
        ItemStack full = new ItemStack(Items.STONE, 64);
        ItemStack one = new ItemStack(Items.STONE, 1);

        assertEquals(64, full.getMaxStackSize());
        assertFalse(ItemEntity.areMergable(full, one));
        assertFalse(ItemEntity.areMergable(one, full));
    }

    @Test
    void partialStacksKeepVanillaMergeCounts() {
        ItemStack destination = new ItemStack(Items.STONE, 40);
        ItemStack origin = new ItemStack(Items.STONE, 24);

        assertTrue(ItemEntity.areMergable(destination, origin));
        ItemStack merged = ItemEntity.merge(destination, origin, 64);

        assertEquals(64, merged.getCount());
        assertEquals(0, origin.getCount());
        assertEquals(40, destination.getCount());
    }
}
