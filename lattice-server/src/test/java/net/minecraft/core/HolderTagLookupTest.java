package net.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.junit.jupiter.api.Test;

class HolderTagLookupTest {
    @Test
    void referenceTagLookupPreservesMembershipSemantics() {
        ResourceKey<Registry<Object>> registryKey = ResourceKey.createRegistryKey(Identifier.withDefaultNamespace("lattice_test_registry"));
        HolderOwner<Object> owner = new HolderOwner<>() {};
        Holder.Reference<Object> holder = Holder.Reference.createIntrusive(owner, new Object());
        TagKey<Object> member = TagKey.create(registryKey, Identifier.withDefaultNamespace("lattice_test_member"));
        TagKey<Object> absent = TagKey.create(registryKey, Identifier.withDefaultNamespace("lattice_test_absent"));

        holder.bindTags(List.of(member));

        assertTrue(holder.is(member));
        assertFalse(holder.is(absent));
        assertTrue(holder.tags().anyMatch(member::equals));
    }
}
