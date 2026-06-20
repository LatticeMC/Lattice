package com.latticemc.lattice.nativelib;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class NativeLineOfSight {
    private static final double MIN_NATIVE_DISTANCE_SQ = 4.0D * 4.0D;
    private static final double MAX_LOS_DISTANCE_SQ = 128.0D * 128.0D;
    private static final int MAX_RAY_MASK_VOLUME = 32768;

    private NativeLineOfSight() {}

    public record SolidMask(
            byte[] data,
            int regionMinX, int regionMinY, int regionMinZ,
            int regionSizeX, int regionSizeY, int regionSizeZ
    ) {}

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static SolidMask computeSolidMask(Level level, int centerX, int centerY, int centerZ, int radius) {
        if (radius < 0) throw new IllegalArgumentException("negative radius");
        int minX = centerX - radius;
        int minY = Math.max(level.getMinY(), centerY - radius);
        int minZ = centerZ - radius;
        int sizeX = radius * 2 + 1;
        int sizeY = Math.min(level.getMaxY(), centerY + radius) - minY + 1;
        int sizeZ = radius * 2 + 1;
        byte[] data = new byte[sizeX * sizeY * sizeZ];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < sizeY; ++y) {
            for (int z = 0; z < sizeZ; ++z) {
                for (int x = 0; x < sizeX; ++x) {
                    pos.set(minX + x, minY + y, minZ + z);
                    if (isOpaqueToLos(level.getBlockState(pos))) {
                        data[index(x, y, z, sizeX, sizeZ)] = 1;
                    }
                }
            }
        }
        return new SolidMask(data, minX, minY, minZ, sizeX, sizeY, sizeZ);
    }

    public static boolean hasLineOfSight(double fromX, double fromY, double fromZ,
                                         double toX, double toY, double toZ,
                                         SolidMask mask) {
        validate(mask);
        if (LatticeNative.isLoaded()) {
            return nativeCheckSingle(fromX, fromY, fromZ, toX, toY, toZ,
                    mask.data, mask.regionMinX, mask.regionMinY, mask.regionMinZ,
                    mask.regionSizeX, mask.regionSizeY, mask.regionSizeZ);
        }
        LatticeNative.logFallbackOnce("native_los", "native line-of-sight unavailable");
        return javaHasLineOfSight(fromX, fromY, fromZ, toX, toY, toZ, mask);
    }

    public static boolean[] hasLineOfSightBatch(double[] fromX, double[] fromY, double[] fromZ,
                                                double[] toX, double[] toY, double[] toZ,
                                                SolidMask mask) {
        validate(mask);
        int count = validateBatch(fromX, fromY, fromZ, toX, toY, toZ);
        boolean[] results = new boolean[count];
        if (LatticeNative.isLoaded()) {
            nativeCheckBatch(fromX, fromY, fromZ, toX, toY, toZ,
                    mask.data, mask.regionMinX, mask.regionMinY, mask.regionMinZ,
                    mask.regionSizeX, mask.regionSizeY, mask.regionSizeZ, results);
            return results;
        }
        LatticeNative.logFallbackOnce("native_los", "native line-of-sight unavailable");
        for (int i = 0; i < count; ++i) {
            results[i] = javaHasLineOfSight(fromX[i], fromY[i], fromZ[i], toX[i], toY[i], toZ[i], mask);
        }
        return results;
    }

    public static Boolean tryHasLineOfSight(Mob mob, Entity entity) {
        if (entity.level() != mob.level()) return Boolean.FALSE;
        if (!isAvailable()) return null;

        double fromX = mob.getX();
        double fromY = mob.getEyeY();
        double fromZ = mob.getZ();
        double toX = entity.getX();
        double toY = entity.getEyeY();
        double toZ = entity.getZ();
        double distanceSq = distanceToSqr(fromX, fromY, fromZ, toX, toY, toZ);
        if (distanceSq > MAX_LOS_DISTANCE_SQ) return Boolean.FALSE;
        if (distanceSq < MIN_NATIVE_DISTANCE_SQ) return null;

        SolidMask mask = computeRaySolidMask(mob.level(), fromX, fromY, fromZ, toX, toY, toZ);
        if (mask == null) return null;
        return hasLineOfSight(fromX, fromY, fromZ, toX, toY, toZ, mask) ? Boolean.TRUE : null;
    }

    private static SolidMask computeRaySolidMask(Level level,
                                                 double fromX, double fromY, double fromZ,
                                                 double toX, double toY, double toZ) {
        int minX = Math.min(Mth.floor(fromX), Mth.floor(toX)) - 1;
        int minY = Math.max(level.getMinY(), Math.min(Mth.floor(fromY), Mth.floor(toY)) - 1);
        int minZ = Math.min(Mth.floor(fromZ), Mth.floor(toZ)) - 1;
        int maxX = Math.max(Mth.floor(fromX), Mth.floor(toX)) + 1;
        int maxY = Math.min(level.getMaxY(), Math.max(Mth.floor(fromY), Mth.floor(toY)) + 1);
        int maxZ = Math.max(Mth.floor(fromZ), Mth.floor(toZ)) + 1;
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || sizeX * sizeY * sizeZ > MAX_RAY_MASK_VOLUME) {
            return null;
        }

        SolidMask mask = new SolidMask(new byte[sizeX * sizeY * sizeZ], minX, minY, minZ, sizeX, sizeY, sizeZ);
        fillRayMask(level, fromX, fromY, fromZ, toX, toY, toZ, mask);
        return mask;
    }

    private static void fillRayMask(Level level,
                                    double fromX, double fromY, double fromZ,
                                    double toX, double toY, double toZ,
                                    SolidMask mask) {
        double adjX = 1.0E-7D * (fromX - toX);
        double adjY = 1.0E-7D * (fromY - toY);
        double adjZ = 1.0E-7D * (fromZ - toZ);
        if (adjX == 0.0D && adjY == 0.0D && adjZ == 0.0D) return;

        double fromXAdj = fromX + adjX;
        double fromYAdj = fromY + adjY;
        double fromZAdj = fromZ + adjZ;
        double toXAdj = toX - adjX;
        double toYAdj = toY - adjY;
        double toZAdj = toZ - adjZ;

        int currX = Mth.floor(fromXAdj);
        int currY = Mth.floor(fromYAdj);
        int currZ = Mth.floor(fromZAdj);
        double diffX = toXAdj - fromXAdj;
        double diffY = toYAdj - fromYAdj;
        double diffZ = toZAdj - fromZAdj;
        int dx = (int)Math.signum(diffX);
        int dy = (int)Math.signum(diffY);
        int dz = (int)Math.signum(diffZ);
        double normalizedDiffX = diffX == 0.0D ? Double.MAX_VALUE : dx / diffX;
        double normalizedDiffY = diffY == 0.0D ? Double.MAX_VALUE : dy / diffY;
        double normalizedDiffZ = diffZ == 0.0D ? Double.MAX_VALUE : dz / diffZ;
        double normalizedCurrX = normalizedDiffX * (diffX > 0.0D ? 1.0D - Mth.frac(fromXAdj) : Mth.frac(fromXAdj));
        double normalizedCurrY = normalizedDiffY * (diffY > 0.0D ? 1.0D - Mth.frac(fromYAdj) : Mth.frac(fromYAdj));
        double normalizedCurrZ = normalizedDiffZ * (diffZ > 0.0D ? 1.0D - Mth.frac(fromZAdj) : Mth.frac(fromZAdj));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (;;) {
            setRayMaskBlock(level, pos, currX, currY, currZ, mask);
            if (normalizedCurrX > 1.0D && normalizedCurrY > 1.0D && normalizedCurrZ > 1.0D) return;
            if (normalizedCurrX < normalizedCurrY) {
                if (normalizedCurrX < normalizedCurrZ) {
                    currX += dx;
                    normalizedCurrX += normalizedDiffX;
                } else {
                    currZ += dz;
                    normalizedCurrZ += normalizedDiffZ;
                }
            } else if (normalizedCurrY < normalizedCurrZ) {
                currY += dy;
                normalizedCurrY += normalizedDiffY;
            } else {
                currZ += dz;
                normalizedCurrZ += normalizedDiffZ;
            }
        }
    }

    private static void setRayMaskBlock(Level level, BlockPos.MutableBlockPos pos, int x, int y, int z, SolidMask mask) {
        if (x < mask.regionMinX || y < mask.regionMinY || z < mask.regionMinZ
                || x >= mask.regionMinX + mask.regionSizeX
                || y >= mask.regionMinY + mask.regionSizeY
                || z >= mask.regionMinZ + mask.regionSizeZ) {
            return;
        }
        pos.set(x, y, z);
        if (isOpaqueToLos(level.getBlockState(pos))) {
            int localX = x - mask.regionMinX;
            int localY = y - mask.regionMinY;
            int localZ = z - mask.regionMinZ;
            mask.data[index(localX, localY, localZ, mask.regionSizeX, mask.regionSizeZ)] = 1;
        }
    }

    private static boolean javaHasLineOfSight(double fromX, double fromY, double fromZ,
                                              double toX, double toY, double toZ,
                                              SolidMask mask) {
        double[] fx = {fromX};
        double[] fy = {fromY};
        double[] fz = {fromZ};
        double[] tx = {toX};
        double[] ty = {toY};
        double[] tz = {toZ};
        return hasLineOfSightBatchJava(fx, fy, fz, tx, ty, tz, mask)[0];
    }

    private static boolean[] hasLineOfSightBatchJava(double[] fromX, double[] fromY, double[] fromZ,
                                                     double[] toX, double[] toY, double[] toZ,
                                                     SolidMask mask) {
        boolean[] results = new boolean[fromX.length];
        for (int i = 0; i < fromX.length; ++i) {
            results[i] = nativeLikeDda(fromX[i], fromY[i], fromZ[i], toX[i], toY[i], toZ[i], mask);
        }
        return results;
    }

    private static boolean nativeLikeDda(double fromX, double fromY, double fromZ,
                                         double toX, double toY, double toZ,
                                         SolidMask mask) {
        double adjX = 1.0E-7D * (fromX - toX);
        double adjY = 1.0E-7D * (fromY - toY);
        double adjZ = 1.0E-7D * (fromZ - toZ);
        if (adjX == 0.0D && adjY == 0.0D && adjZ == 0.0D) return true;
        double fromXAdj = fromX + adjX;
        double fromYAdj = fromY + adjY;
        double fromZAdj = fromZ + adjZ;
        double toXAdj = toX - adjX;
        double toYAdj = toY - adjY;
        double toZAdj = toZ - adjZ;
        int currX = Mth.floor(fromXAdj);
        int currY = Mth.floor(fromYAdj);
        int currZ = Mth.floor(fromZAdj);
        double diffX = toXAdj - fromXAdj;
        double diffY = toYAdj - fromYAdj;
        double diffZ = toZAdj - fromZAdj;
        int dx = (int)Math.signum(diffX);
        int dy = (int)Math.signum(diffY);
        int dz = (int)Math.signum(diffZ);
        double normalizedDiffX = diffX == 0.0D ? Double.MAX_VALUE : dx / diffX;
        double normalizedDiffY = diffY == 0.0D ? Double.MAX_VALUE : dy / diffY;
        double normalizedDiffZ = diffZ == 0.0D ? Double.MAX_VALUE : dz / diffZ;
        double normalizedCurrX = normalizedDiffX * (diffX > 0.0D ? 1.0D - Mth.frac(fromXAdj) : Mth.frac(fromXAdj));
        double normalizedCurrY = normalizedDiffY * (diffY > 0.0D ? 1.0D - Mth.frac(fromYAdj) : Mth.frac(fromYAdj));
        double normalizedCurrZ = normalizedDiffZ * (diffZ > 0.0D ? 1.0D - Mth.frac(fromZAdj) : Mth.frac(fromZAdj));
        for (;;) {
            if (currX < mask.regionMinX || currY < mask.regionMinY || currZ < mask.regionMinZ
                    || currX >= mask.regionMinX + mask.regionSizeX
                    || currY >= mask.regionMinY + mask.regionSizeY
                    || currZ >= mask.regionMinZ + mask.regionSizeZ) {
                return false;
            }
            int localX = currX - mask.regionMinX;
            int localY = currY - mask.regionMinY;
            int localZ = currZ - mask.regionMinZ;
            if (mask.data[index(localX, localY, localZ, mask.regionSizeX, mask.regionSizeZ)] != 0) return false;
            if (normalizedCurrX > 1.0D && normalizedCurrY > 1.0D && normalizedCurrZ > 1.0D) return true;
            if (normalizedCurrX < normalizedCurrY) {
                if (normalizedCurrX < normalizedCurrZ) {
                    currX += dx;
                    normalizedCurrX += normalizedDiffX;
                } else {
                    currZ += dz;
                    normalizedCurrZ += normalizedDiffZ;
                }
            } else if (normalizedCurrY < normalizedCurrZ) {
                currY += dy;
                normalizedCurrY += normalizedDiffY;
            } else {
                currZ += dz;
                normalizedCurrZ += normalizedDiffZ;
            }
        }
    }

    private static boolean isOpaqueToLos(BlockState state) {
        return !state.isAir();
    }

    private static int index(int x, int y, int z, int sizeX, int sizeZ) {
        return (y * sizeZ + z) * sizeX + x;
    }

    private static void validate(SolidMask mask) {
        if (mask == null || mask.data == null) throw new IllegalArgumentException("null mask");
        int needed = mask.regionSizeX * mask.regionSizeY * mask.regionSizeZ;
        if (mask.regionSizeX <= 0 || mask.regionSizeY <= 0 || mask.regionSizeZ <= 0 || mask.data.length < needed) {
            throw new IllegalArgumentException("invalid mask");
        }
    }

    private static int validateBatch(double[] fromX, double[] fromY, double[] fromZ,
                                     double[] toX, double[] toY, double[] toZ) {
        if (fromX == null || fromY == null || fromZ == null || toX == null || toY == null || toZ == null) {
            throw new IllegalArgumentException("null coordinate array");
        }
        int count = fromX.length;
        if (fromY.length != count || fromZ.length != count || toX.length != count || toY.length != count || toZ.length != count) {
            throw new IllegalArgumentException("coordinate array length mismatch: "
                    + Arrays.asList(fromX.length, fromY.length, fromZ.length, toX.length, toY.length, toZ.length));
        }
        return count;
    }

    private static double distanceToSqr(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static native boolean nativeCheckSingle(
            double fromX, double fromY, double fromZ,
            double toX, double toY, double toZ,
            byte[] solidMask,
            int regionMinX, int regionMinY, int regionMinZ,
            int regionSizeX, int regionSizeY, int regionSizeZ);

    private static native void nativeCheckBatch(
            double[] fromX, double[] fromY, double[] fromZ,
            double[] toX, double[] toY, double[] toZ,
            byte[] solidMask,
            int regionMinX, int regionMinY, int regionMinZ,
            int regionSizeX, int regionSizeY, int regionSizeZ,
            boolean[] results);
}
