package com.latticemc.lattice.bootstrap;

import java.util.HashMap;
import java.util.Map;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

public final class MixinGlobalPropertyServiceStandalone implements IGlobalPropertyService {

    private final Map<String, Object> properties = new HashMap<>();

    private static class PropertyKey implements IPropertyKey {
        private final String name;

        PropertyKey(String name) {
            this.name = name;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PropertyKey other)) return false;
            return name.equals(other.name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Override
    public IPropertyKey resolveKey(String name) {
        return new PropertyKey(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) properties.get(key.toString());
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        properties.put(key.toString(), value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object value = properties.get(key.toString());
        return value != null ? (T) value : defaultValue;
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = properties.get(key.toString());
        return value != null ? value.toString() : defaultValue;
    }
}
