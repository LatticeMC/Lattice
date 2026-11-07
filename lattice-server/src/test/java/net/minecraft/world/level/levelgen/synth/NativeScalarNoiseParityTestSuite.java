package net.minecraft.world.level.levelgen.synth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeOctavePerlinNoise;
import com.latticemc.lattice.nativelib.NativePerlinNoise;
import com.latticemc.lattice.nativelib.NativeScalarNoiseControl;
import java.lang.reflect.Field;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NativeScalarNoiseParityTestSuite {
    private final boolean original = NativeScalarNoiseControl.perlinEnabled();

    @AfterEach
    void restoreControl() {
        NativeScalarNoiseControl.setPerlinEnabled(original);
    }

    @Test
    void disabledConstructionDoesNotEnterEitherScalarFactory() throws Exception {
        NativeScalarNoiseControl.setPerlinEnabled(false);
        try (var perlin = mockStatic(NativePerlinNoise.class);
             var octave = mockStatic(NativeOctavePerlinNoise.class)) {
            ImprovedNoise improved = improved();
            PerlinNoise combined = octave();
            assertNull(owner(improved));
            assertNull(owner(combined));
            perlin.verifyNoInteractions();
            octave.verifyNoInteractions();
        }
    }

    @Test
    void unavailableLibraryPreservesJavaSamples() throws Exception {
        NativeScalarNoiseControl.setPerlinEnabled(false);
        ImprovedNoise expected = improved();
        PerlinNoise expectedOctave = octave();
        NativeScalarNoiseControl.setPerlinEnabled(true);
        try (var library = mockStatic(LatticeNative.class)) {
            library.when(LatticeNative::isLoaded).thenReturn(false);
            ImprovedNoise actual = improved();
            PerlinNoise actualOctave = octave();
            assertNull(owner(actual));
            assertNull(owner(actualOctave));
            assertEquals(expected.noise(-100.25, 64.5, 327.75), actual.noise(-100.25, 64.5, 327.75));
            assertEquals(expectedOctave.getValue(-100.25, 64.5, 327.75),
                    actualOctave.getValue(-100.25, 64.5, 327.75));
        }
    }

    @Test
    void enabledObjectsSelectNativeButDisabledObjectsStayJavaWhenReenabled() throws Exception {
        NativeScalarNoiseControl.setPerlinEnabled(false);
        ImprovedNoise javaNoise = improved();
        PerlinNoise javaOctave = octave();
        double expectedNoise = javaNoise.noise(1, 2, 3);
        double expectedOctave = javaOctave.getValue(1, 2, 3);
        NativePerlinNoise sampler = mock(NativePerlinNoise.class);
        NativeOctavePerlinNoise combinedSampler = mock(NativeOctavePerlinNoise.class);
        when(sampler.sample(1, 2, 3)).thenReturn(0.125);
        when(combinedSampler.sample(1, 2, 3)).thenReturn(0.25);
        try (var perlinFactory = mockStatic(NativePerlinNoise.class);
             var octaveFactory = mockStatic(NativeOctavePerlinNoise.class)) {
            perlinFactory.when(() -> NativePerlinNoise.tryCreate(any(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(sampler);
            octaveFactory.when(() -> NativeOctavePerlinNoise.tryCreate(
                    any(), any(), any(), anyDouble(), anyDouble())).thenReturn(combinedSampler);
            NativeScalarNoiseControl.setPerlinEnabled(true);
            ImprovedNoise nativeNoise = improved();
            PerlinNoise nativeOctave = octave();
            assertSame(sampler, owner(nativeNoise));
            assertSame(combinedSampler, owner(nativeOctave));
            assertEquals(0.125, nativeNoise.noise(1, 2, 3));
            assertEquals(0.25, nativeOctave.getValue(1, 2, 3));
            assertEquals(expectedNoise, javaNoise.noise(1, 2, 3));
            assertEquals(expectedOctave, javaOctave.getValue(1, 2, 3));
            NativeScalarNoiseControl.setPerlinEnabled(false);
            assertEquals(expectedNoise, nativeNoise.noise(1, 2, 3));
            assertEquals(expectedOctave, nativeOctave.getValue(1, 2, 3));
            verify(sampler).sample(1, 2, 3);
            verify(combinedSampler).sample(1, 2, 3);
            verifyNoMoreInteractions(sampler, combinedSampler);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void actualJniMatchesJavaIncludingYScalingDerivativeAndOctaves() throws Exception {
        assertTrue(NativePerlinNoise.isAvailable(), LatticeNative.failureReason());
        NativeScalarNoiseControl.setPerlinEnabled(false);
        ImprovedNoise javaNoise = improved();
        PerlinNoise javaOctave = octave();
        NativeScalarNoiseControl.setPerlinEnabled(true);
        ImprovedNoise nativeNoise = improved();
        PerlinNoise nativeOctave = octave();
        assertNotNull(owner(nativeNoise));
        assertNotNull(owner(nativeOctave));
        for (int i = 0; i < 1024; ++i) {
            double x = -8192.25 + i * 0.371, y = -64.5 + i * 0.413, z = 4096.75 - i * 0.437;
            assertEquals(javaNoise.noise(x, y, z), nativeNoise.noise(x, y, z), 1.0E-10);
            assertEquals(javaNoise.noise(x, y, z, 0.2, 0.7), nativeNoise.noise(x, y, z, 0.2, 0.7), 1.0E-10);
            double[] jd = {1, 2, 3}, nd = jd.clone();
            assertEquals(javaNoise.noiseWithDerivative(x, y, z, jd),
                    nativeNoise.noiseWithDerivative(x, y, z, nd), 1.0E-10);
            assertArrayEquals(jd, nd, 1.0E-10);
            assertEquals(javaOctave.getValue(x, y, z), nativeOctave.getValue(x, y, z), 1.0E-10);
            for (boolean fixed : new boolean[] {false, true}) {
                assertEquals(javaOctave.getValue(x, y, z, 0.2, 0.7, fixed),
                        nativeOctave.getValue(x, y, z, 0.2, 0.7, fixed), 1.0E-10);
            }
        }
        NativeScalarNoiseControl.setPerlinEnabled(false);
        assertEquals(javaNoise.noise(1, 2, 3), nativeNoise.noise(1, 2, 3));
        assertEquals(javaOctave.getValue(1, 2, 3), nativeOctave.getValue(1, 2, 3));
    }

    private static ImprovedNoise improved() {
        return new ImprovedNoise(RandomSource.create(0x1A2B3C4DL));
    }

    private static PerlinNoise octave() {
        return PerlinNoise.create(RandomSource.create(0x22334455L), -7, 1.0, 1.0, 0.0, 1.0);
    }

    private static Object owner(Object noise) throws Exception {
        Field field = noise.getClass().getDeclaredField("lattice$native");
        field.setAccessible(true);
        return field.get(noise);
    }
}
