package net.minecraft.world.level;

/**
 * Standalone guard for the final ray-step short-circuit in ServerExplosion.
 *
 * <p>Run with {@code java -ea ...ExplosionRayStepParityCheck}; it intentionally
 * has no test-framework dependency.</p>
 */
public final class ExplosionRayStepParityCheck {
    private static final float STEP_DECAY = 0.22500001F;

    private ExplosionRayStepParityCheck() {
    }

    public static void main(String[] args) {
        for (float power : new float[] {
            Float.NEGATIVE_INFINITY, -1.0F, -0.0F, 0.0F, Float.MIN_VALUE,
            STEP_DECAY, Math.nextUp(STEP_DECAY), 1.0F, Float.POSITIVE_INFINITY, Float.NaN
        }) {
            assertParity(power, -0.3D, 0.1D, 0.3D, 0.2D, -0.5D, 0.7D);
        }
    }

    private static void assertParity(float power, double x, double y, double z, double xIncrement, double yIncrement, double zIncrement) {
        float baselinePower = power;
        double baselineX = x;
        double baselineY = y;
        double baselineZ = z;
        baselinePower -= STEP_DECAY;
        baselineX += xIncrement;
        baselineY += yIncrement;
        baselineZ += zIncrement;
        boolean baselineContinues = baselinePower > 0.0F;

        float optimizedPower = power;
        double optimizedX = x;
        double optimizedY = y;
        double optimizedZ = z;
        optimizedPower -= STEP_DECAY;
        boolean optimizedContinues = optimizedPower > 0.0F;
        if (optimizedContinues) {
            optimizedX += xIncrement;
            optimizedY += yIncrement;
            optimizedZ += zIncrement;
        }

        if (Float.floatToRawIntBits(baselinePower) != Float.floatToRawIntBits(optimizedPower)
            || baselineContinues != optimizedContinues
            || (optimizedContinues && (Double.doubleToRawLongBits(baselineX) != Double.doubleToRawLongBits(optimizedX)
                || Double.doubleToRawLongBits(baselineY) != Double.doubleToRawLongBits(optimizedY)
                || Double.doubleToRawLongBits(baselineZ) != Double.doubleToRawLongBits(optimizedZ)))) {
            throw new AssertionError("ray-step parity failure");
        }
    }
}
