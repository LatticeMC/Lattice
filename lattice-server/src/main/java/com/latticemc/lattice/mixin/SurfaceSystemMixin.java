package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.CompiledSurfaceRules;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeMaterialRules;
import com.latticemc.lattice.nativelib.NativeWorldgenToggle;
import com.latticemc.lattice.nativelib.SurfaceRuleCompiler;
import com.latticemc.lattice.nativelib.WorldgenProfiler;
import com.latticemc.lattice.nativelib.WorldStateSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystemMixin implements SurfaceSystemCallbacks {
    @Unique private static final Logger lattice$logger = LoggerFactory.getLogger("Lattice");
    @Unique private static final AtomicBoolean lattice$compileLogged = new AtomicBoolean(false);
    @Unique private static final AtomicBoolean lattice$nokLogged = new AtomicBoolean(false);
    @Unique private volatile boolean lattice$nativeSurfaceDisabled = false;
    @Unique private final ThreadLocal<Long> lattice$buildSurfaceStart = ThreadLocal.withInitial(() -> 0L);
    private final Map<SurfaceRules.RuleSource, CompiledSurfaceRules> lattice$compiled = new ConcurrentHashMap<>();

    @Shadow protected abstract BlockState getBand(int x, int y, int z);
    @Shadow protected abstract int getSurfaceDepth(int x, int z);
    @Shadow protected abstract double getSurfaceSecondary(int x, int z);
    @Shadow protected abstract boolean isStone(BlockState state);
    @Shadow private BlockState defaultBlock;
    @Shadow private void frozenOceanExtension(int minSurfaceLevel, Biome biome, BlockColumn blockColumn, BlockPos.MutableBlockPos topWaterPos, int x, int z, int height) {}
    @Shadow private void erodedBadlandsExtension(BlockColumn blockColumn, int x, int z, int height, LevelHeightAccessor level) {}

    @Override
    public int getSeaLevel() { return ((SurfaceSystemAccessor) this).lattice$seaLevel(); }
    @Override
    public double getSurfaceNoiseValue(int x, int z) { return ((SurfaceSystemAccessor) this).lattice$surfaceNoise().getValue(x, 0.0, z); }
    @Override
    public double getSurfaceSecondaryValue(int x, int z) { return ((SurfaceSystemAccessor) this).lattice$surfaceSecondaryNoise().getValue(x, 0.0, z); }
    @Override
    public BlockState getBandlands(int x, int y, int z) { return this.getBand(x, y, z); }

    @Inject(method = "buildSurface", at = @At("HEAD"), cancellable = true)
    private void lattice$buildSurface(RandomState randomState,
                                      BiomeManager biomeManager,
                                      Registry<Biome> biomes,
                                      boolean useLegacyRandomSource,
                                      WorldGenerationContext context,
                                      ChunkAccess chunk,
                                      NoiseChunk noiseChunk,
                                      SurfaceRules.RuleSource ruleSource,
                                      CallbackInfo ci) {
        this.lattice$buildSurfaceStart.set(WorldgenProfiler.start());
        if (!NativeWorldgenToggle.surfaceEnabled()) return;
        if (!LatticeNative.isLoaded()) return;
        if (lattice$nativeSurfaceDisabled) return;
        CompiledSurfaceRules compiled = lattice$compile(ruleSource, randomState, biomes, context);
        if (compiled == null) {
            lattice$nativeSurfaceDisabled = true;
            if (lattice$nokLogged.compareAndSet(false, true)) {
                lattice$logger.warn("[Lattice] SurfaceSystem native path DISABLED — compile returned null, falling back to vanilla");
            }
            return;
        }

        final BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        final ChunkPos pos = chunk.getPos();
        final int minBlockX = pos.getMinBlockX();
        final int minBlockZ = pos.getMinBlockZ();
        final BlockColumn blockColumn = new ChunkBlockColumn(chunk, mutableBlockPos);
        final BlockPos.MutableBlockPos topPos = new BlockPos.MutableBlockPos();
        final WorldStateSnapshot snapshot = WorldStateSnapshot.create(chunk, biomeManager, noiseChunk, biomes, useLegacyRandomSource);

        final SurfaceSystemAccessImpl systemAccess = new SurfaceSystemAccessImpl(this, biomes);
        final int seaLevel = systemAccess.seaLevel();
        final int[] batchBlockData = new int[chunk.getHeight() * 5];
        final int[] batchYs = new int[chunk.getHeight()];
        final int[] columnCtx = new int[6];
        final byte[] columnBools = new byte[2];
        final int namedNoiseCount = compiled.getNamedNoiseCount();
        final double[] namedNoiseValues = new double[namedNoiseCount];
        final BlockPos.MutableBlockPos coldCheckPos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minBlockX + lx;
                int z = minBlockZ + lz;
                int surfaceTop = snapshot.surfaceTop(lx, lz);
                mutableBlockPos.setX(x).setZ(z);
                Holder<Biome> biome = snapshot.biome(lx, lz);
                if (biome.is(Biomes.ERODED_BADLANDS)) {
                    this.erodedBadlandsExtension(blockColumn, x, z, surfaceTop, chunk);
                }

                int surfaceDepth = this.getSurfaceDepth(x, z);
                int fluidHeight = Integer.MIN_VALUE;
                int stoneBase = Integer.MAX_VALUE;
                int stoneDepthAbove = 0;
                int minY = chunk.getMinY();
                int minSurfaceLevel = snapshot.minSurfaceLevel(lx, lz, surfaceDepth);
                boolean steep = lattice$isSteep(chunk, lx, lz);
                boolean hole = surfaceDepth <= 0;

                final double surfaceNoise = systemAccess.surfaceNoiseValue(x, z);
                final double surfaceSecondaryNoise = systemAccess.surfaceSecondaryValue(x, z);
                for (int i = 0; i < namedNoiseCount; ++i) {
                    namedNoiseValues[i] = compiled.getNamedNoiseValue(i, x, z);
                }

                int count = 0;
                for (int y = surfaceTop; y >= minY; y--) {
                    BlockState block = blockColumn.getBlock(y);
                    if (block.isAir()) {
                        stoneDepthAbove = 0;
                        fluidHeight = Integer.MIN_VALUE;
                    } else if (!block.getFluidState().isEmpty()) {
                        if (fluidHeight == Integer.MIN_VALUE) fluidHeight = y + 1;
                    } else {
                        if (stoneBase >= y) {
                            stoneBase = DimensionType.WAY_BELOW_MIN_Y;
                            for (int scan = y - 1; scan >= minY - 1; scan--) {
                                if (!this.isStone(blockColumn.getBlock(scan))) {
                                    stoneBase = scan + 1;
                                    break;
                                }
                            }
                        }

                        stoneDepthAbove++;
                        int stoneDepthBelow = y - stoneBase + 1;
                        if (block == this.defaultBlock) {
                            batchYs[count] = y;
                            compiled.appendBatchBlockData(seaLevel, biome, x, y, z, fluidHeight, stoneDepthAbove, stoneDepthBelow, batchBlockData, count, coldCheckPos);
                            count++;
                        }
                    }
                }

                if (count > 0) {
                    int[] results = compiled.tryApplyBatch(
                            systemAccess,
                            snapshot.biomeId(lx, lz),
                            x,
                            z,
                            surfaceTop,
                            surfaceDepth,
                            minSurfaceLevel,
                            hole,
                            steep,
                            surfaceNoise,
                            surfaceSecondaryNoise,
                            namedNoiseValues,
                            columnCtx,
                            columnBools,
                            count,
                            batchBlockData);
                    for (int i = 0; i < count; i++) {
                        int id = results[i];
                        if (id == NativeMaterialRules.NO_MATCH) continue;
                        BlockState out = id == NativeMaterialRules.BANDLANDS_SENTINEL
                                ? this.getBand(x, batchYs[i], z)
                                : Block.stateById(id);
                        if (out != null) blockColumn.setBlock(batchYs[i], out);
                    }
                }

                if (biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                    this.frozenOceanExtension(minSurfaceLevel, biome.value(), blockColumn, topPos, x, z, surfaceTop);
                }
            }
        }

        ci.cancel();
    }

    @Inject(method = "buildSurface", at = @At("RETURN"))
    private void lattice$profileBuildSurfaceEnd(RandomState randomState,
                                                BiomeManager biomeManager,
                                                Registry<Biome> biomes,
                                                boolean useLegacyRandomSource,
                                                WorldGenerationContext context,
                                                ChunkAccess chunk,
                                                NoiseChunk noiseChunk,
                                                SurfaceRules.RuleSource ruleSource,
                                                CallbackInfo ci) {
        WorldgenProfiler.end("surface.buildSurface", this.lattice$buildSurfaceStart.get().longValue());
    }

    @Inject(method = "topMaterial", at = @At("HEAD"), cancellable = true)
    private void lattice$topMaterial(SurfaceRules.RuleSource rule,
                                     CarvingContext context,
                                     Function<BlockPos, Holder<Biome>> biomeGetter,
                                     ChunkAccess chunk,
                                     NoiseChunk noiseChunk,
                                     BlockPos pos,
                                     boolean hasFluid,
                                     CallbackInfoReturnable<Optional<BlockState>> cir) {
        if (!NativeWorldgenToggle.surfaceEnabled()) return;
        if (!LatticeNative.isLoaded()) return;
        if (lattice$nativeSurfaceDisabled) return;
        Registry<Biome> biomes = context.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        CompiledSurfaceRules compiled = lattice$compile(rule, context.randomState(), biomes, context);
        if (compiled == null) {
            lattice$nativeSurfaceDisabled = true;
            return;
        }

        Holder<Biome> biome = biomeGetter.apply(pos);
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int surfaceTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15) + 1;
        int surfaceDepth = this.getSurfaceDepth(x, z);
        int minSurfaceLevel = lattice$minSurfaceLevel(noiseChunk, x, z, surfaceDepth);
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
        int fluidHeight = lattice$fluidHeight(chunk, x, y, z, surfaceTop, scanPos);
        int stoneDepthFloor = lattice$stoneDepthFloor(chunk, x, y, z, scanPos);
        int stoneDepthCeiling = lattice$stoneDepthCeiling(chunk, x, y, z, scanPos);
        int[] ints = new int[10];
        byte[] bools = new byte[2];
        double[] namedNoiseValues = new double[compiled.getDoublesLength() - 3];
        SurfaceSystemAccessImpl sa = new SurfaceSystemAccessImpl(this, biomes);
        for (int i = 0; i < namedNoiseValues.length; ++i) {
            namedNoiseValues[i] = compiled.getNamedNoiseValue(i, x, z);
        }
        BlockState out = compiled.tryApply(
                sa,
                biome,
                x,
                y,
                z,
                surfaceTop,
                fluidHeight,
                stoneDepthFloor,
                stoneDepthCeiling,
                surfaceDepth,
                minSurfaceLevel,
                surfaceDepth <= 0,
                lattice$isSteep(chunk, x & 15, z & 15),
                ints,
                sa.surfaceNoiseValue(x, z),
                sa.surfaceSecondaryValue(x, z),
                namedNoiseValues,
                bools);
        cir.setReturnValue(Optional.ofNullable(out));
    }

    private CompiledSurfaceRules lattice$compile(SurfaceRules.RuleSource ruleSource,
                                                 RandomState randomState,
                                                 Registry<Biome> biomes,
                                                 WorldGenerationContext context) {
        try {
            return lattice$compiled.computeIfAbsent(ruleSource, key -> {
                NativeMaterialRules rules = new NativeMaterialRules();
                return new SurfaceRuleCompiler(rules, randomState, biomes, context).compile(key);
            });
        } catch (Exception e) {
            if (lattice$compileLogged.compareAndSet(false, true)) {
                lattice$logger.error("[Lattice] SurfaceSystem compile FAILED — falling back to vanilla", e);
            }
            return null;
        }
    }

    private static boolean lattice$isSteep(ChunkAccess chunk, int localX, int localZ) {
        int maxZ = Math.max(localZ - 1, 0);
        int minZ = Math.min(localZ + 1, 15);
        int h0 = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, maxZ);
        int h1 = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, minZ);
        if (h1 >= h0 + 4) return true;
        int maxX = Math.max(localX - 1, 0);
        int minX = Math.min(localX + 1, 15);
        int h2 = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, maxX, localZ);
        int h3 = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, minX, localZ);
        return h2 >= h3 + 4;
    }

    private static int lattice$fluidHeight(ChunkAccess chunk, int x, int y, int z, int surfaceTop, BlockPos.MutableBlockPos pos) {
        int fluidHeight = Integer.MIN_VALUE;
        for (int scan = surfaceTop; scan > y; --scan) {
            BlockState state = chunk.getBlockState(pos.set(x, scan, z));
            if (state.isAir()) {
                fluidHeight = Integer.MIN_VALUE;
            } else if (!state.getFluidState().isEmpty() && fluidHeight == Integer.MIN_VALUE) {
                fluidHeight = scan + 1;
            }
        }
        return fluidHeight;
    }

    private static int lattice$stoneDepthFloor(ChunkAccess chunk, int x, int y, int z, BlockPos.MutableBlockPos pos) {
        int depth = 0;
        int maxY = chunk.getMaxY();
        for (int scan = y; scan <= maxY; ++scan) {
            BlockState state = chunk.getBlockState(pos.set(x, scan, z));
            if (!lattice$isStoneBlock(state)) break;
            depth++;
        }
        return depth;
    }

    private static int lattice$stoneDepthCeiling(ChunkAccess chunk, int x, int y, int z, BlockPos.MutableBlockPos pos) {
        int depth = 0;
        int minY = chunk.getMinY();
        for (int scan = y; scan >= minY; --scan) {
            BlockState state = chunk.getBlockState(pos.set(x, scan, z));
            if (!lattice$isStoneBlock(state)) break;
            depth++;
        }
        return depth;
    }

    private static boolean lattice$isStoneBlock(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private static int lattice$minSurfaceLevel(NoiseChunk noiseChunk, int blockX, int blockZ, int surfaceDepth) {
        int cellX = blockX >> 4;
        int cellZ = blockZ >> 4;
        int s00 = noiseChunk.preliminarySurfaceLevel(cellX << 4, cellZ << 4);
        int s10 = noiseChunk.preliminarySurfaceLevel((cellX + 1) << 4, cellZ << 4);
        int s01 = noiseChunk.preliminarySurfaceLevel(cellX << 4, (cellZ + 1) << 4);
        int s11 = noiseChunk.preliminarySurfaceLevel((cellX + 1) << 4, (cellZ + 1) << 4);
        double tx = (blockX & 15) / 16.0;
        double tz = (blockZ & 15) / 16.0;
        double a = s00 + (s10 - s00) * tx;
        double b = s01 + (s11 - s01) * tx;
        int floor = (int) Math.floor(a + (b - a) * tz);
        return floor + surfaceDepth - 8;
    }
}
