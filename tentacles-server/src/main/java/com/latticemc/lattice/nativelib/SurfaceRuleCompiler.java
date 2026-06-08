package com.latticemc.lattice.nativelib;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class SurfaceRuleCompiler {
    private static final String PKG = "net.minecraft.world.level.levelgen.SurfaceRules$";

    private final NativeMaterialRules nativeRules;
    private final RandomState randomState;
    private final Registry<Biome> biomeRegistry;
    private final WorldGenerationContext worldContext;
    private final Map<SurfaceRules.ConditionSource, Integer> condCache = new IdentityHashMap<>();
    private final Map<SurfaceRules.RuleSource, Integer> ruleCache = new IdentityHashMap<>();
    private final LinkedHashMap<String, NormalNoise> namedNoises = new LinkedHashMap<>();

    public SurfaceRuleCompiler(NativeMaterialRules nativeRules,
                               RandomState randomState,
                               Registry<Biome> biomeRegistry,
                               WorldGenerationContext worldContext) {
        this.nativeRules = nativeRules;
        this.randomState = randomState;
        this.biomeRegistry = biomeRegistry;
        this.worldContext = worldContext;
    }

    public CompiledSurfaceRules compile(SurfaceRules.RuleSource root) {
        int rootRef = compileRule(root);
        nativeRules.setRootRule(rootRef);
        return new CompiledSurfaceRules(nativeRules, new ArrayList<>(namedNoises.entrySet()));
    }

    private int compileRule(SurfaceRules.RuleSource src) {
        Integer cached = ruleCache.get(src);
        if (cached != null) return cached.intValue();

        final int ref;
        if (isType(src, "BlockRuleSource")) {
            BlockState state = (BlockState) getField(src, "resultState");
            ref = nativeRules.addRuleBlock(Block.getId(state));
        } else if (isType(src, "TestRuleSource")) {
            SurfaceRules.ConditionSource cond = (SurfaceRules.ConditionSource) getField(src, "ifTrue");
            SurfaceRules.RuleSource thenRun = (SurfaceRules.RuleSource) getField(src, "thenRun");
            ref = nativeRules.addRuleConditional(compileCondition(cond), compileRule(thenRun));
        } else if (isType(src, "SequenceRuleSource")) {
            @SuppressWarnings("unchecked")
            List<SurfaceRules.RuleSource> sequence = (List<SurfaceRules.RuleSource>) getField(src, "sequence");
            int[] childRefs = new int[sequence.size()];
            for (int i = 0; i < childRefs.length; ++i) childRefs[i] = compileRule(sequence.get(i));
            ref = nativeRules.addRuleSequence(childRefs);
        } else if (isEnumSingleton(src, "Bandlands")) {
            ref = nativeRules.addRuleBandlands();
        } else {
            throw new IllegalStateException("Unsupported SurfaceRules.RuleSource: " + src.getClass().getName());
        }

        ruleCache.put(src, ref);
        return ref;
    }

    private int compileCondition(SurfaceRules.ConditionSource src) {
        Integer cached = condCache.get(src);
        if (cached != null) return cached.intValue();

        final int ref;
        if (isEnumSingleton(src, "Hole")) {
            ref = nativeRules.addCondHole();
        } else if (isEnumSingleton(src, "Steep")) {
            ref = nativeRules.addCondSteepSlope();
        } else if (isEnumSingleton(src, "Temperature")) {
            ref = nativeRules.addCondTemperatureFrozen();
        } else if (isEnumSingleton(src, "AbovePreliminarySurface")) {
            ref = nativeRules.addCondAbovePreliminarySurface();
        } else if (isType(src, "NotConditionSource")) {
            SurfaceRules.ConditionSource target = (SurfaceRules.ConditionSource) getField(src, "target");
            ref = nativeRules.addCondNot(compileCondition(target));
        } else if (isType(src, "StoneDepthCheck")) {
            int offset = (Integer) getField(src, "offset");
            boolean addSurfaceDepth = (Boolean) getField(src, "addSurfaceDepth");
            int secondaryDepthRange = (Integer) getField(src, "secondaryDepthRange");
            CaveSurface surfaceType = (CaveSurface) getField(src, "surfaceType");
            ref = nativeRules.addCondStoneDepth(offset, addSurfaceDepth, secondaryDepthRange, surfaceType == CaveSurface.CEILING);
        } else if (isType(src, "YConditionSource")) {
            VerticalAnchor anchor = (VerticalAnchor) getField(src, "anchor");
            int multiplier = (Integer) getField(src, "surfaceDepthMultiplier");
            boolean addStoneDepth = (Boolean) getField(src, "addStoneDepth");
            int y = anchor.resolveY(worldContext);
            ref = addStoneDepth
                    ? nativeRules.addCondAboveYWithStoneDepth(y, multiplier)
                    : nativeRules.addCondAboveYWithSurface(y, multiplier);
        } else if (isType(src, "WaterConditionSource")) {
            int offset = (Integer) getField(src, "offset");
            int multiplier = (Integer) getField(src, "surfaceDepthMultiplier");
            boolean addStoneDepth = (Boolean) getField(src, "addStoneDepth");
            ref = nativeRules.addCondWater(offset, multiplier, addStoneDepth);
        } else if (isType(src, "VerticalGradientConditionSource")) {
            Identifier randomName = (Identifier) getField(src, "randomName");
            VerticalAnchor trueAt = (VerticalAnchor) getField(src, "trueAtAndBelow");
            VerticalAnchor falseAt = (VerticalAnchor) getField(src, "falseAtAndAbove");
            ref = nativeRules.addCondVerticalGradient(trueAt.resolveY(worldContext), falseAt.resolveY(worldContext), randomState, randomName);
        } else if (isType(src, "NoiseThresholdConditionSource")) {
            @SuppressWarnings("unchecked")
            ResourceKey<NormalNoise.NoiseParameters> noise = (ResourceKey<NormalNoise.NoiseParameters>) getField(src, "noise");
            double min = (Double) getField(src, "minThreshold");
            double max = (Double) getField(src, "maxThreshold");
            if (noise == Noises.SURFACE) {
                ref = nativeRules.addCondNoiseThreshold(min, max);
            } else if (noise == Noises.SURFACE_SECONDARY) {
                ref = nativeRules.addCondSecondaryNoiseThreshold(min, max);
            } else {
                String noiseName = noise.identifier().toString();
                namedNoises.computeIfAbsent(noiseName, key -> randomState.getOrCreateNoise(noise));
                ref = nativeRules.addCondNamedNoiseThreshold(noiseName, min, max);
            }
        } else if (isType(src, "BiomeConditionSource")) {
            @SuppressWarnings("unchecked")
            List<ResourceKey<Biome>> biomes = (List<ResourceKey<Biome>>) getField(src, "biomes");
            int[] ids = new int[biomes.size()];
            for (int i = 0; i < ids.length; ++i) {
                Holder.Reference<Biome> holder = biomeRegistry.get(biomes.get(i)).orElseThrow();
                ids[i] = biomeRegistry.getId(holder.value());
            }
            ref = nativeRules.addCondBiomeIs(ids);
        } else {
            throw new IllegalStateException("Unsupported SurfaceRules.ConditionSource: " + src.getClass().getName());
        }

        condCache.put(src, ref);
        return ref;
    }

    private static boolean isType(Object value, String simpleName) {
        return value.getClass().getName().equals(PKG + simpleName);
    }

    private static boolean isEnumSingleton(Object value, String simpleName) {
        return isType(value, simpleName);
    }

    private static Object getField(Object owner, String fieldName) {
        try {
            Field f = owner.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(owner);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(owner.getClass().getName() + "." + fieldName + " changed shape", e);
        }
    }
}
