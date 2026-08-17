package net.minecraft.world.level.levelgen.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTestType;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.junit.jupiter.api.Test;

class OreFeatureTagMatchCacheTestSuite {
    private static final TagKey<Block> TEST_TAG = TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("lattice_test_ore_cache"));

    @Test
    void exactTagMatchCachesHitsAndInvalidatesAfterBlockTagReload() {
        MappedRegistry<Block> registry = this.newBoundBlockRegistry(TEST_TAG, "lattice_test_ore_cache_reload");
        BlockState state = mock(BlockState.class);
        when(state.is(TEST_TAG)).thenReturn(true, false);
        TagMatchTest matcher = new TagMatchTest(TEST_TAG);
        RandomSource random = mock(RandomSource.class);
        OreConfiguration configuration = new OreConfiguration(matcher, Blocks.DIAMOND_ORE.defaultBlockState(), 1, 0.0F);

        assertTrue(this.canPlaceOre(state, random, configuration));
        assertTrue(this.canPlaceOre(state, random, configuration));
        verify(state, times(1)).is(TEST_TAG);
        verifyNoInteractions(random);

        long beforeReload = MappedRegistry.blockTagUpdateEpoch();
        registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, Map.of(TEST_TAG, List.of()))).apply();
        long afterReload = MappedRegistry.blockTagUpdateEpoch();

        assertTrue(afterReload > beforeReload);
        assertEquals(0L, afterReload & 1L);
        assertFalse(this.canPlaceOre(state, random, configuration));
        verify(state, times(2)).is(TEST_TAG);
        verifyNoInteractions(random);
    }

    @Test
    void failedBlockTagRefreshAlwaysLeavesTheEpochStable() {
        MappedRegistry<Block> registry = this.newBoundBlockRegistry(TEST_TAG, "lattice_test_ore_cache_failed_refresh");

        Registry.PendingTags<Block> pending = registry.prepareTagReload(
            new TagLoader.LoadResult<>(Registries.BLOCK, Map.of(TEST_TAG, List.of(Holder.direct(Blocks.DIRT))))
        );
        long beforeFailure = MappedRegistry.blockTagUpdateEpoch();

        assertThrows(IllegalStateException.class, pending::apply);

        long afterFailure = MappedRegistry.blockTagUpdateEpoch();
        assertTrue(afterFailure > beforeFailure);
        assertEquals(0L, afterFailure & 1L);
    }

    @Test
    void nonExactTagRuleTestsKeepTheirVirtualCallAndRandomConsumption() {
        BlockState state = mock(BlockState.class);
        RandomSource random = mock(RandomSource.class);
        OreConfiguration configuration = new OreConfiguration(this.randomConsumingRuleTest(), Blocks.DIAMOND_ORE.defaultBlockState(), 1, 0.0F);

        assertTrue(
            this.canPlaceOre(state, random, configuration)
        );
        verify(random).nextInt();

        RandomSource subclassRandom = mock(RandomSource.class);
        OreConfiguration subclassConfiguration = new OreConfiguration(new RandomConsumingTagMatchTest(TEST_TAG), Blocks.DIAMOND_ORE.defaultBlockState(), 1, 0.0F);
        assertTrue(
            this.canPlaceOre(state, subclassRandom, subclassConfiguration)
        );
        verify(subclassRandom).nextInt();
    }

    private MappedRegistry<Block> newBlockRegistry() {
        return new MappedRegistry<>(Registries.BLOCK, Lifecycle.stable());
    }

    private MappedRegistry<Block> newBoundBlockRegistry(TagKey<Block> tag, String keyName) {
        MappedRegistry<Block> registry = this.newBlockRegistry();
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(keyName));
        Holder.Reference<Block> holder = registry.register(key, Blocks.STONE, RegistrationInfo.BUILT_IN);
        registry.bindTag(tag, List.of(holder));
        registry.freeze();
        return registry;
    }

    private boolean canPlaceOre(BlockState state, RandomSource random, OreConfiguration configuration) {
        return OreFeature.canPlaceOre(
            state,
            ignored -> state,
            random,
            configuration,
            configuration.targetStates.getFirst(),
            new BlockPos.MutableBlockPos()
        );
    }

    private RuleTest randomConsumingRuleTest() {
        return new RuleTest() {
            @Override
            public boolean test(BlockState state, RandomSource random) {
                random.nextInt();
                return true;
            }

            @Override
            protected RuleTestType<?> getType() {
                return null;
            }
        };
    }

    private static final class RandomConsumingTagMatchTest extends TagMatchTest {
        private RandomConsumingTagMatchTest(TagKey<Block> tag) {
            super(tag);
        }

        @Override
        public boolean test(BlockState state, RandomSource random) {
            random.nextInt();
            return true;
        }
    }
}
