package net.minecraft.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PatchedDataComponentMapMaxStackSizeTestSuite {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void maxStackSizeCacheTracksEveryPatchMutationWithoutChangingComponentState() {
        final ItemStack stack = stackWithPrototypeMaxStackSize(64);
        final PatchedDataComponentMap components = components(stack);

        assertStackState(stack);

        stack.set(DataComponents.MAX_STACK_SIZE, 16);
        assertStackState(stack);

        stack.set(DataComponents.MAX_STACK_SIZE, 64);
        assertStackState(stack);

        stack.remove(DataComponents.MAX_STACK_SIZE);
        assertStackState(stack);

        stack.applyComponents(DataComponentPatch.builder().set(DataComponents.MAX_STACK_SIZE, 32).build());
        assertStackState(stack);

        stack.applyComponents(DataComponentPatch.builder().remove(DataComponents.MAX_STACK_SIZE).build());
        assertStackState(stack);

        stack.restorePatch(DataComponentPatch.builder().set(DataComponents.MAX_STACK_SIZE, 48).build());
        assertStackState(stack);

        components.clearPatch();
        assertStackState(stack);
    }

    @Test
    void maxStackSizeCacheInitializesForSanitizedAndUnsanitizedPatchesAndCopies() {
        final DataComponentPatch sanitizedPatch = DataComponentPatch.builder().set(DataComponents.MAX_STACK_SIZE, 12).build();
        final ItemStack sanitized = new ItemStack(Items.STONE.builtInRegistryHolder(), 1, sanitizedPatch);
        assertStackState(sanitized);

        // A value equal to the prototype is intentionally not sanitized; fromPatch must still initialize the cache.
        final DataComponentPatch unsanitizedPatch = DataComponentPatch.builder().set(DataComponents.MAX_STACK_SIZE, 64).build();
        final ItemStack unsanitized = new ItemStack(Items.STONE.builtInRegistryHolder(), 1, unsanitizedPatch);
        assertStackState(unsanitized);

        final ItemStack original = sanitized.copy();
        final ItemStack copy = original.copy();
        original.set(DataComponents.MAX_STACK_SIZE, 20);
        copy.set(DataComponents.MAX_STACK_SIZE, 40);
        assertStackState(original);
        assertStackState(copy);
        assertEquals(20, original.getMaxStackSize());
        assertEquals(40, copy.getMaxStackSize());
    }

    private static ItemStack stackWithPrototypeMaxStackSize(final int maxStackSize) {
        assertEquals(64, maxStackSize);
        return new ItemStack(Items.STONE);
    }

    private static PatchedDataComponentMap components(final ItemStack stack) {
        return (PatchedDataComponentMap)stack.getComponents();
    }

    private static void assertStackState(final ItemStack stack) {
        final PatchedDataComponentMap components = components(stack);
        final int expected = components.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
        final DataComponentPatch patchBeforeCacheRead = components.asPatch();
        final DataComponentMap immutableBeforeCacheRead = components.toImmutableMap();
        final int hashBeforeCacheRead = components.hashCode();
        final Integer componentBeforeCacheRead = components.get(DataComponents.MAX_STACK_SIZE);

        assertEquals(expected, components.getMaxStackSize());
        assertEquals(expected, stack.getMaxStackSize());
        assertEquals(componentBeforeCacheRead, components.get(DataComponents.MAX_STACK_SIZE));
        assertEquals(patchBeforeCacheRead, components.asPatch());
        assertEquals(immutableBeforeCacheRead, components.toImmutableMap());
        assertEquals(hashBeforeCacheRead, components.hashCode());
    }
}
