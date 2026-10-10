package net.minecraft.core;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.Test;

class MathFlatteningRegistryTestSuite {
    private static ResourceKey<Registry<Object>> key(String name) {
        return ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("test", name));
    }

    @Test
    void adapterRemainsLiveAndRetainsUnboundAndCrossRegistrySemantics() {
        MappedRegistry<Object> a = new MappedRegistry<>(key("a"), Lifecycle.stable());
        MappedRegistry<Object> b = new MappedRegistry<>(key("b"), Lifecycle.stable());
        IdMap<Holder<Object>> adapter = a.asHolderIdMap();
        assertSame(adapter, a.asHolderIdMap()); assertEquals(0, adapter.size());
        Object shared = new Object();
        Holder.Reference<Object> holder = a.register(ResourceKey.create(a.key(), Identifier.parse("test:shared")), shared, RegistrationInfo.BUILT_IN);
        assertEquals(1, adapter.size()); assertSame(holder, adapter.byId(0));
        assertThrows(IllegalStateException.class, () -> adapter.getId(holder));
        b.register(ResourceKey.create(b.key(), Identifier.parse("test:first")), new Object(), RegistrationInfo.BUILT_IN);
        b.register(ResourceKey.create(b.key(), Identifier.parse("test:shared")), shared, RegistrationInfo.BUILT_IN);
        a.freeze(); b.freeze();
        assertEquals(0, adapter.getId(holder)); assertEquals(1, b.asHolderIdMap().getId(holder));
        assertEquals(-1, adapter.getId(Holder.direct(new Object())));
        assertSame(holder, adapter.iterator().next());
    }

    @Test
    void adapterDispatchesToDefaultedRegistry() {
        var registry = new DefaultedMappedRegistry<Object>("test:default", key("defaulted"), Lifecycle.stable(), false);
        var adapter = registry.asHolderIdMap();
        var holder = registry.register(ResourceKey.create(registry.key(), Identifier.parse("test:default")), new Object(), RegistrationInfo.BUILT_IN);
        registry.freeze();
        assertEquals(0, adapter.getId(Holder.direct(new Object())));
        assertSame(holder, adapter.byId(0));
    }
}
