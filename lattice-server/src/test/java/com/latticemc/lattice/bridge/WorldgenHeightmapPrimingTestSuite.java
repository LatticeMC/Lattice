package com.latticemc.lattice.bridge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeHeightmap;
import com.latticemc.lattice.nativelib.NativeWorldgenToggle;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.levelgen.Heightmap;

class WorldgenHeightmapPrimingTestSuite {
    @BeforeAll
    static void bootstrap() {
        WorldgenHeightmapFixture.bootstrap();
    }

    @Test
    void realPrimingMatchesIndependentColumnsForShapeAndPaletteMatrix() {
        LatticeNative.ensureLoaded();
        assertTrue(LatticeNative.isLoaded(), LatticeNative.failureReason());
        for (int minY : new int[] {-64, 0}) {
            for (int count : new int[] {4, 24, 64}) {
                for (var pattern : WorldgenHeightmapFixture.Pattern.values()) {
                    for (var types : java.util.List.of(EnumSet.of(Heightmap.Types.WORLD_SURFACE),
                            EnumSet.allOf(Heightmap.Types.class))) {
                        var fixture = new WorldgenHeightmapFixture(minY, count, pattern, types);
                        WorldgenNativeSupport.primeHeightmaps(fixture.chunk, fixture.types);
                        fixture.assertParity();
                    }
                }
            }
        }
    }

    @Test
    void alternatingChunksAndThreadsDoNotCarryHeightOrPaletteState() throws Exception {
        LatticeNative.ensureLoaded();
        assertTrue(LatticeNative.isLoaded(), LatticeNative.failureReason());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                final int minY = -64 + 16 * worker;
                jobs.add(executor.submit(() -> {
                    var high = new WorldgenHeightmapFixture(minY, 64, WorldgenHeightmapFixture.Pattern.GLOBAL,
                            EnumSet.allOf(Heightmap.Types.class));
                    var empty = new WorldgenHeightmapFixture(0, 4, WorldgenHeightmapFixture.Pattern.EMPTY,
                            EnumSet.of(Heightmap.Types.OCEAN_FLOOR));
                    for (int iteration = 0; iteration < 12; iteration++) {
                        WorldgenNativeSupport.primeHeightmaps(high.chunk, high.types);
                        high.assertParity();
                        WorldgenNativeSupport.primeHeightmaps(empty.chunk, empty.types);
                        empty.assertParity();
                    }
                }));
            }
            for (var job : jobs) job.get();
        }
    }

    @Test
    void productionFallbackRepopulatesAllColumnsAfterNativeFailure() {
        var fixture = new WorldgenHeightmapFixture(-64, 4, WorldgenHeightmapFixture.Pattern.MIXED_WITH_FLOOR,
                EnumSet.allOf(Heightmap.Types.class));
        boolean previous = NativeWorldgenToggle.heightmapEnabled();
        NativeWorldgenToggle.setOption("heightmap", true);
        try (var loader = mockStatic(LatticeNative.class); var nativeCall = mockStatic(NativeHeightmap.class)) {
            loader.when(LatticeNative::isLoaded).thenReturn(true);
            nativeCall.when(() -> NativeHeightmap.maskLongsPerSection(anyInt())).thenCallRealMethod();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            nativeCall.when(() -> NativeHeightmap.populateHeightmap(any(), any(), any(), anyInt(),
                    anyInt(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
                        if (calls.incrementAndGet() == 2) {
                            throw new IllegalStateException("heightmap mock failure");
                        }
                        NativeHeightmap.javaPopulate(invocation.getArgument(0), invocation.getArgument(1),
                                invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4),
                                invocation.getArgument(5), invocation.getArgument(6), invocation.getArgument(7));
                        return null;
                    });
            nativeCall.when(() -> NativeHeightmap.javaPopulate(any(), any(), any(), anyInt(),
                    anyInt(), anyInt(), anyInt(), any())).thenCallRealMethod();
            Heightmap.primeHeightmaps(fixture.chunk, fixture.types);
            org.junit.jupiter.api.Assertions.assertEquals(2, calls.get());
            fixture.assertParity();
        } finally {
            NativeWorldgenToggle.setOption("heightmap", previous);
        }
    }

    @Test
    void unavailableNativePopulateOverwritesSharedColumnsAndMasksForEveryType() {
        try (var loader = mockStatic(LatticeNative.class)) {
            loader.when(LatticeNative::isLoaded).thenReturn(false);
            for (var pattern : WorldgenHeightmapFixture.Pattern.values()) {
                var fixture = new WorldgenHeightmapFixture(-64, 24, pattern, EnumSet.allOf(Heightmap.Types.class));
                WorldgenNativeSupport.primeHeightmaps(fixture.chunk, fixture.types);
                fixture.assertParity();
            }
        }
    }

    @Test
    void explicitJavaPathMatchesCoveredMixedColumnsAndEmptyChunk() {
        boolean previous = NativeWorldgenToggle.heightmapEnabled();
        NativeWorldgenToggle.setOption("heightmap", false);
        try {
            for (var pattern : new WorldgenHeightmapFixture.Pattern[] {
                    WorldgenHeightmapFixture.Pattern.MIXED_WITH_FLOOR, WorldgenHeightmapFixture.Pattern.EMPTY}) {
                var fixture = new WorldgenHeightmapFixture(-64, 24, pattern, EnumSet.allOf(Heightmap.Types.class));
                Heightmap.primeHeightmaps(fixture.chunk, fixture.types);
                fixture.assertParity();
            }
        } finally {
            NativeWorldgenToggle.setOption("heightmap", previous);
        }
    }
}
