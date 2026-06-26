package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeAabbQuery;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntitySection.class)
public abstract class EntitySectionMixin<T extends EntityAccess> {
    @Shadow @Final private ClassInstanceMultiMap<T> storage;

    @Inject(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            at = @At("HEAD"), cancellable = true)
    private void lattice$getEntities(AABB bounds,
                                     AbortableIterationConsumer<T> consumer,
                                     CallbackInfoReturnable<AbortableIterationConsumer.Continuation> cir) {
        if (!NativeAabbQuery.isAvailable()) return;

        final ArrayList<T> entities = new ArrayList<>(this.storage);
        final AbortableIterationConsumer.Continuation result = lattice$scanEntities(entities, bounds, consumer);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            at = @At("HEAD"), cancellable = true)
    private <U extends T> void lattice$getEntities(EntityTypeTest<T, U> test,
                                                   AABB bounds,
                                                   AbortableIterationConsumer<? super U> consumer,
                                                   CallbackInfoReturnable<AbortableIterationConsumer.Continuation> cir) {
        if (!NativeAabbQuery.isAvailable()) return;

        Collection<? extends T> collection = this.storage.find(test.getBaseClass());
        if (collection.isEmpty()) return;

        final ArrayList<U> entities = new ArrayList<>(collection.size());
        for (T entityAccess : collection) {
            U cast = test.tryCast(entityAccess);
            if (cast != null) {
                entities.add(cast);
            }
        }

        final AbortableIterationConsumer.Continuation result = lattice$scanEntities(entities, bounds, consumer);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    private static <E extends EntityAccess> AbortableIterationConsumer.Continuation lattice$scanEntities(
            ArrayList<E> entities,
            AABB bounds,
            AbortableIterationConsumer<? super E> consumer) {
        final int entityCount = entities.size();
        if (entityCount < 8) {
            return null;
        }

        final double[] query = {
                bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ,
        };
        final double[] entityAabbs = new double[entityCount * NativeAabbQuery.AABB_STRIDE];
        for (int i = 0; i < entityCount; ++i) {
            final AABB box = entities.get(i).getBoundingBox();
            final int base = i * NativeAabbQuery.AABB_STRIDE;
            entityAabbs[base] = box.minX;
            entityAabbs[base + 1] = box.minY;
            entityAabbs[base + 2] = box.minZ;
            entityAabbs[base + 3] = box.maxX;
            entityAabbs[base + 4] = box.maxY;
            entityAabbs[base + 5] = box.maxZ;
        }

        final long[] visibility = new long[NativeAabbQuery.rowLongs(entityCount)];
        NativeAabbQuery.scan(query, 1, entityAabbs, entityCount, visibility);

        for (int i = 0; i < entityCount; ++i) {
            if (((visibility[i >>> 6] >>> (i & 63)) & 1L) == 0L) {
                continue;
            }
            if (consumer.accept(entities.get(i)).shouldAbort()) {
                return AbortableIterationConsumer.Continuation.ABORT;
            }
        }
        return AbortableIterationConsumer.Continuation.CONTINUE;
    }
}
