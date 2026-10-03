package com.latticemc.lattice.nativelib;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/** 仅用于无 chunk 外部绑定的 grid；直接检查原图，不调用会隐藏动态节点的 mapAll。 */
final class NativeDensityGridKey {
    private static final String DF = "net.minecraft.world.level.levelgen.DensityFunctions$";
    private static final Set<String> RECORDS = Set.of(
            DF + "Constant", DF + "Noise", DF + "Shift", DF + "ShiftA", DF + "ShiftB",
            DF + "ShiftedNoise", DF + "Mapped", DF + "Ap2", DF + "MulOrAdd",
            DF + "Clamp", DF + "RangeChoice", DF + "WeirdScaledSampler", DF + "YClampedGradient",
            DF + "FindTopSurface", DF + "Spline", DF + "Spline$Coordinate",
            "net.minecraft.util.CubicSpline$Constant", "net.minecraft.util.CubicSpline$Multipoint");
    private static final Set<String> WRAPPERS = Set.of(
            "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D",
            "net.minecraft.world.level.levelgen.NoiseChunk$CacheOnce",
            "net.minecraft.world.level.levelgen.NoiseChunk$FlatCache");
    private static final ClassValue<Method[]> COMPONENTS = new ClassValue<>() {
        @Override protected Method[] computeValue(Class<?> type) {
            var components = type.getRecordComponents();
            Method[] methods = new Method[components.length];
            for (int i = 0; i < methods.length; i++) {
                methods[i] = components[i].getAccessor();
                methods[i].setAccessible(true);
            }
            return methods;
        }
    };

    private final List<Object> parts;
    private final int hash;

    private NativeDensityGridKey(List<Object> parts) {
        this.parts = List.copyOf(parts);
        this.hash = this.parts.hashCode();
    }

    static NativeDensityGridKey create(DensityFunction function) {
        Builder builder = new Builder();
        try {
            return builder.visit(function, 0) ? new NativeDensityGridKey(builder.parts) : null;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot inspect native grid key", error);
        }
    }

    static String rejectionReason(DensityFunction function) throws ReflectiveOperationException {
        Builder builder = new Builder();
        return builder.visit(function, 0) ? "eligible" : builder.reason;
    }

    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
        return other instanceof NativeDensityGridKey key && parts.equals(key.parts);
    }

    private record Reference(int index) {}
    private record DoubleBits(long bits) {}
    private record FloatBits(int bits) {}

    // 保留实际 NormalNoise，不只保留 identityHashCode；也为 native sampler 提供强生命周期锚点。
    private static final class Identity {
        private final Object value;
        private Identity(Object value) { this.value = value; }
        @Override public int hashCode() { return System.identityHashCode(value); }
        @Override public boolean equals(Object other) {
            return other instanceof Identity identity && value == identity.value;
        }
    }

    private static final class Builder {
        private final List<Object> parts = new ArrayList<>();
        private String reason = "key-budget-or-cycle";
        private final IdentityHashMap<DensityFunction, Integer> references = new IdentityHashMap<>();
        private final IdentityHashMap<DensityFunction, Boolean> active = new IdentityHashMap<>();

        private boolean visit(Object value, int depth) throws ReflectiveOperationException {
            if (value == null || depth > 256 || parts.size() >= 65_536) return false;
            Class<?> type = value.getClass();
            String name = type.getName();
            // 只接受固定返回 1/0 的静态 singleton；NoiseChunk 的动态 blend 仍拒绝。
            if (name.equals(DF + "BlendAlpha") || name.equals(DF + "BlendOffset")) {
                parts.add(value);
                return true;
            }
            if (value instanceof DensityFunction function) {
                if (!RECORDS.contains(name) && !WRAPPERS.contains(name)) { reason = name; return false; }
                if (active.containsKey(function)) return false;
                Integer previous = references.get(function);
                if (previous != null) {
                    parts.add(new Reference(previous));
                    return true;
                }
                references.put(function, references.size());
                active.put(function, Boolean.TRUE);
                boolean accepted;
                if (WRAPPERS.contains(name)) {
                    parts.add(type);
                    accepted = visit(((DensityFunctions.MarkerOrMarked) function).wrapped(), depth + 1);
                } else {
                    accepted = record(value, depth);
                }
                active.remove(function);
                return accepted;
            }
            if (value instanceof DensityFunction.NoiseHolder noise) {
                if (noise.noise() == null) return false;
                parts.add(new Identity(noise.noise()));
                return true;
            }
            if (value instanceof Double number) parts.add(new DoubleBits(Double.doubleToRawLongBits(number)));
            else if (value instanceof Float number) parts.add(new FloatBits(Float.floatToRawIntBits(number)));
            else if (value instanceof Integer || value instanceof Enum<?>) parts.add(value);
            else if (value instanceof float[] array) {
                if (array.length > 65_536 - parts.size() - 2) return false;
                parts.add(float[].class);
                parts.add(array.length);
                for (float number : array) parts.add(new FloatBits(Float.floatToRawIntBits(number)));
            } else if (value instanceof List<?> list) {
                if (list.size() > 65_536 - parts.size() - 2) return false;
                parts.add(List.class);
                parts.add(list.size());
                for (Object item : list) if (!visit(item, depth + 1)) return false;
            } else if (value instanceof Holder.Direct<?> holder) {
                parts.add(Holder.Direct.class);
                return visit(holder.value(), depth + 1);
            } else if (RECORDS.contains(name)) {
                return record(value, depth);
            } else { reason = name; return false; }
            return parts.size() <= 65_536;
        }

        private boolean record(Object value, int depth) throws ReflectiveOperationException {
            parts.add(value.getClass());
            for (Method component : COMPONENTS.get(value.getClass())) {
                if (!visit(component.invoke(value), depth + 1)) return false;
            }
            return parts.size() <= 65_536;
        }
    }
}
