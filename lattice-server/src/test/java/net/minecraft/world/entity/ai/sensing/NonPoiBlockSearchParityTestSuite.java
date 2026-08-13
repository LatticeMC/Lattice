package net.minecraft.world.entity.ai.sensing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NonPoiBlockSearchParityTestSuite {
    private static final BlockPos POS = new BlockPos(32, 64, -48);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void hoglinSearchSkipsStateReadsOnlyForSectionsWithoutRepellents() throws ReflectiveOperationException {
        SearchContext context = loadedContext(false);

        assertFalse(invoke(HoglinSpecificSensor.class, context.level));
        verify(context.level).getChunk(2, -3, ChunkStatus.FULL, true);
        verify(context.section).maybeHas(any());
        verify(context.chunk, never()).getBlockState(POS);
    }

    @Test
    void invalidBoundsDoNotLoadAChunk() throws ReflectiveOperationException {
        ServerLevel level = mock(ServerLevel.class);
        when(level.isInValidBounds(POS)).thenReturn(false);

        assertFalse(invoke(HoglinSpecificSensor.class, level));
        assertFalse(invoke(PiglinSpecificSensor.class, level));
        verify(level, never()).getChunk(anyInt(), anyInt(), any(), anyBoolean());
    }

    @Test
    void piglinSearchKeepsTheStatePredicateAfterAPossiblePaletteMatch() throws ReflectiveOperationException {
        SearchContext context = loadedContext(true);
        BlockState state = mock(BlockState.class);
        when(context.chunk.getBlockState(POS)).thenReturn(state);
        when(state.is(BlockTags.PIGLIN_REPELLENTS)).thenReturn(true);
        when(state.is(Blocks.SOUL_CAMPFIRE)).thenReturn(false);

        assertTrue(invoke(PiglinSpecificSensor.class, context.level));
        verify(context.level).getChunk(2, -3, ChunkStatus.FULL, true);
        verify(context.chunk).getBlockState(POS);
    }

    @Test
    void treeGenerationCaptureUsesTheVanillaLevelLookup() throws ReflectiveOperationException {
        ServerLevel level = mock(ServerLevel.class);
        level.captureTreeGeneration = true;
        BlockState state = mock(BlockState.class);
        when(level.getBlockState(POS)).thenReturn(state);
        when(state.is(BlockTags.HOGLIN_REPELLENTS)).thenReturn(true);

        assertTrue(invoke(HoglinSpecificSensor.class, level));
        verify(level).getBlockState(POS);
        verify(level, never()).getChunk(anyInt(), anyInt(), any(), anyBoolean());
    }

    @Test
    void piglinTreeGenerationCaptureUsesTheVanillaLevelLookup() throws ReflectiveOperationException {
        ServerLevel level = mock(ServerLevel.class);
        level.captureTreeGeneration = true;
        BlockState state = mock(BlockState.class);
        when(level.getBlockState(POS)).thenReturn(state);
        when(state.is(BlockTags.PIGLIN_REPELLENTS)).thenReturn(true);
        when(state.is(Blocks.SOUL_CAMPFIRE)).thenReturn(false);

        assertTrue(invoke(PiglinSpecificSensor.class, level));
        verify(level).getBlockState(POS);
        verify(level, never()).getChunk(anyInt(), anyInt(), any(), anyBoolean());
    }

    private static SearchContext loadedContext(boolean mayHaveRepellent) {
        ServerLevel level = mock(ServerLevel.class);
        ChunkAccess chunk = mock(ChunkAccess.class);
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(level.isInValidBounds(POS)).thenReturn(true);
        when(level.getChunk(2, -3, ChunkStatus.FULL, true)).thenReturn(chunk);
        when(chunk.getSectionIndex(64)).thenReturn(4);
        when(chunk.getSection(4)).thenReturn(section);
        when(section.maybeHas(any())).thenReturn(mayHaveRepellent);
        return new SearchContext(level, chunk, section);
    }

    private static boolean invoke(Class<?> sensor, ServerLevel level) throws ReflectiveOperationException {
        Method method = sensor.getDeclaredMethod("isValidRepellent", ServerLevel.class, BlockPos.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, level, POS);
    }

    private record SearchContext(ServerLevel level, ChunkAccess chunk, LevelChunkSection section) {
    }
}
