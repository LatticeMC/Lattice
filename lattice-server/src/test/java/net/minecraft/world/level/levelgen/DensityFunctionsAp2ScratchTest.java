package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DensityFunctionsAp2ScratchTest {
    private static final DensityFunction.ContextProvider CONTEXT_PROVIDER = new DensityFunction.ContextProvider() {
        @Override
        public DensityFunction.FunctionContext forIndex(int arrayIndex) {
            return new DensityFunction.SinglePointContext(arrayIndex, arrayIndex * 2, -arrayIndex);
        }

        @Override
        public void fillAllDirectly(double[] values, DensityFunction function) {
            for (int i = 0; i < values.length; i++) {
                values[i] = function.compute(this.forIndex(i));
            }
        }
    };

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void nestedAddsPreserveEveryOperand() {
        DensityFunction nested = add(function(1.0), add(function(10.0), function(100.0)));
        double[] values = new double[17];

        nested.fillArray(values, CONTEXT_PROVIDER);

        double[] expected = new double[17];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = 111.0 + 9.0 * i;
        }
        assertArrayEquals(expected, values);
    }

    @Test
    void failedNestedFillReleasesScratchDepth() {
        DensityFunction failing = add(function(1.0), new TestFunction(0.0, true));
        assertThrows(IllegalStateException.class, () -> failing.fillArray(new double[8], CONTEXT_PROVIDER));

        double[] values = new double[257];
        add(function(2.0), function(3.0)).fillArray(values, CONTEXT_PROVIDER);

        double[] expected = new double[257];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = 5.0 + 6.0 * i;
        }
        assertArrayEquals(expected, values);
    }

    @Test
    void directPureTransformersMatchScalarAndArraySemantics() {
        DensityFunction input = function(3.0);
        DensityFunction clamp = new DensityFunctions.Clamp(input, -4.0, 4.0);
        DensityFunction cube = DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.CUBE, input);
        DensityFunction multiply = new DensityFunctions.MulOrAdd(
                DensityFunctions.MulOrAdd.Type.MUL, input, -1_000_000.0, 1_000_000.0, -2.0);
        DensityFunction add = new DensityFunctions.MulOrAdd(
                DensityFunctions.MulOrAdd.Type.ADD, input, -1_000_000.0, 1_000_000.0, 5.0);

        DensityFunction.FunctionContext context = CONTEXT_PROVIDER.forIndex(2);
        assertEquals(4.0, clamp.compute(context));
        assertEquals(729.0, cube.compute(context));
        assertEquals(-18.0, multiply.compute(context));
        assertEquals(14.0, add.compute(context));

        double[] values = new double[9];
        cube.fillArray(values, CONTEXT_PROVIDER);
        for (int i = 0; i < values.length; i++) {
            double source = 3.0 + 3.0 * i;
            assertEquals(source * source * source, values[i]);
        }
    }

    @Test
    void nonShortCircuitingOperationsBatchTheirSecondArgument() {
        TrackingFunction multiplyRight = new TrackingFunction(2.0, -1_000_000.0, 1_000_000.0);
        DensityFunction multiply = ap2(DensityFunctions.TwoArgumentSimpleFunction.Type.MUL, function(1.0), multiplyRight);
        double[] multiplyValues = new double[9];
        multiply.fillArray(multiplyValues, CONTEXT_PROVIDER);
        assertEquals(1, multiplyRight.fillCalls);
        assertEquals(0, multiplyRight.computeCalls);
        assertMatchesScalar(multiply, multiplyValues);

        TrackingFunction minRight = new TrackingFunction(20.0, -1_000_000.0, 1_000_000.0);
        DensityFunction min = ap2(DensityFunctions.TwoArgumentSimpleFunction.Type.MIN, function(5.0), minRight);
        double[] minValues = new double[9];
        min.fillArray(minValues, CONTEXT_PROVIDER);
        assertEquals(1, minRight.fillCalls);
        assertEquals(0, minRight.computeCalls);
        assertMatchesScalar(min, minValues);

        TrackingFunction maxRight = new TrackingFunction(-20.0, -1_000_000.0, 1_000_000.0);
        DensityFunction max = ap2(DensityFunctions.TwoArgumentSimpleFunction.Type.MAX, function(-5.0), maxRight);
        double[] maxValues = new double[9];
        max.fillArray(maxValues, CONTEXT_PROVIDER);
        assertEquals(1, maxRight.fillCalls);
        assertEquals(0, maxRight.computeCalls);
        assertMatchesScalar(max, maxValues);
    }

    @Test
    void shortCircuitingOperationsPreserveScalarEvaluation() {
        DensityFunction alternatingZero = new DensityFunction.SimpleFunction() {
            @Override
            public double compute(DensityFunction.FunctionContext context) {
                return (context.blockX() & 1) == 0 ? 0.0 : context.blockX();
            }

            @Override
            public double minValue() {
                return 0.0;
            }

            @Override
            public double maxValue() {
                return 8.0;
            }

            @Override
            public KeyDispatchDataCodec<? extends DensityFunction> codec() {
                throw new UnsupportedOperationException();
            }
        };
        TrackingFunction multiplyRight = new TrackingFunction(2.0, -1_000_000.0, 1_000_000.0);
        DensityFunction multiply = ap2(DensityFunctions.TwoArgumentSimpleFunction.Type.MUL, alternatingZero, multiplyRight);
        double[] values = new double[9];
        multiply.fillArray(values, CONTEXT_PROVIDER);
        assertEquals(0, multiplyRight.fillCalls);
        assertEquals(4, multiplyRight.computeCalls);
        assertMatchesScalar(multiply, values);
    }

    @Test
    void rangeChoiceBatchesHomogeneousBranchesAndPreservesMixedParity() {
        TrackingFunction inRange = new TrackingFunction(10.0, -1_000_000.0, 1_000_000.0);
        TrackingFunction outOfRange = new TrackingFunction(-10.0, -1_000_000.0, 1_000_000.0);
        DensityFunction allIn = new DensityFunctions.RangeChoice(function(2.0), -1_000.0, 1_000.0, inRange, outOfRange);
        double[] homogeneous = new double[9];
        allIn.fillArray(homogeneous, CONTEXT_PROVIDER);
        assertEquals(1, inRange.fillCalls);
        assertEquals(0, inRange.computeCalls);
        assertEquals(0, outOfRange.fillCalls);
        assertMatchesScalar(allIn, homogeneous);

        TrackingFunction mixedIn = new TrackingFunction(10.0, -1_000_000.0, 1_000_000.0);
        TrackingFunction mixedOut = new TrackingFunction(-10.0, -1_000_000.0, 1_000_000.0);
        DensityFunction mixed = new DensityFunctions.RangeChoice(function(-4.0), 0.0, 10.0, mixedIn, mixedOut);
        double[] mixedValues = new double[9];
        mixed.fillArray(mixedValues, CONTEXT_PROVIDER);
        assertEquals(0, mixedIn.fillCalls);
        assertEquals(0, mixedOut.fillCalls);
        assertMatchesScalar(mixed, mixedValues);
    }

    private static DensityFunction add(DensityFunction left, DensityFunction right) {
        return ap2(DensityFunctions.TwoArgumentSimpleFunction.Type.ADD, left, right);
    }

    private static DensityFunction ap2(DensityFunctions.TwoArgumentSimpleFunction.Type type, DensityFunction left, DensityFunction right) {
        return new DensityFunctions.Ap2(type, left, right, -1_000_000.0, 1_000_000.0);
    }

    private static void assertMatchesScalar(DensityFunction function, double[] values) {
        for (int i = 0; i < values.length; i++) {
            assertEquals(function.compute(CONTEXT_PROVIDER.forIndex(i)), values[i]);
        }
    }

    private static DensityFunction function(double base) {
        return new TestFunction(base, false);
    }

    private record TestFunction(double base, boolean fail) implements DensityFunction.SimpleFunction {
        @Override
        public double compute(DensityFunction.FunctionContext context) {
            if (this.fail) {
                throw new IllegalStateException("expected test failure");
            }
            return this.base + context.blockX() + context.blockY();
        }

        @Override
        public double minValue() {
            return -1_000_000.0;
        }

        @Override
        public double maxValue() {
            return 1_000_000.0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TrackingFunction implements DensityFunction.SimpleFunction {
        private final double base;
        private final double minValue;
        private final double maxValue;
        private int computeCalls;
        private int fillCalls;

        private TrackingFunction(double base, double minValue, double maxValue) {
            this.base = base;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            this.computeCalls++;
            return this.base + context.blockX() + context.blockY();
        }

        @Override
        public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
            this.fillCalls++;
            for (int i = 0; i < array.length; i++) {
                array[i] = this.base + contextProvider.forIndex(i).blockX() + contextProvider.forIndex(i).blockY();
            }
        }

        @Override
        public double minValue() {
            return this.minValue;
        }

        @Override
        public double maxValue() {
            return this.maxValue;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }
}
