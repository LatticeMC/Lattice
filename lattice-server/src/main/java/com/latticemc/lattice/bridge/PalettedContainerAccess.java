package com.latticemc.lattice.bridge;

import java.lang.reflect.Field;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

public final class PalettedContainerAccess {
    private static final Field DATA_FIELD = fieldByType(PalettedContainer.class, "Data");
    private static final Field STORAGE_FIELD = fieldByType(DATA_FIELD.getType(), BitStorage.class);
    private static final Field PALETTE_FIELD = fieldByType(DATA_FIELD.getType(), Palette.class);

    private PalettedContainerAccess() {
    }

    @SuppressWarnings("unchecked")
    public static <T> Data<T> dataOf(PalettedContainer<T> container) {
        try {
            Object data = DATA_FIELD.get(container);
            return new Data<>((BitStorage) STORAGE_FIELD.get(data), (Palette<T>) PALETTE_FIELD.get(data));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("lattice heightmap: failed to read paletted container data", e);
        }
    }

    public record Data<T>(BitStorage storage, Palette<T> palette) {
    }

    private static Field fieldByType(Class<?> owner, Class<?> type) {
        for (Field field : owner.getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new IllegalStateException("lattice heightmap: missing " + type.getSimpleName() + " field in " + owner.getName());
    }

    private static Field fieldByType(Class<?> owner, String simpleTypeName) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType().getSimpleName().equals(simpleTypeName)) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new IllegalStateException("lattice heightmap: missing " + simpleTypeName + " field in " + owner.getName());
    }
}
