package com.latticemc.lattice.world;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.util.List;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Bounds a single {@link LivingEntity#pushEntities()} query without changing its
 * cramming, collision-cap, or push-order semantics.
 *
 * <p>Original author: HaHaWTH/Creeam &lt;102713261+HaHaWTH@users.noreply.github.com&gt;.
 * Original license: MIT.
 * Source: https://github.com/Winds-Studio/Leaf/commit/cc2a6fdfdec6245b9084b8860bdcbbbd7ddf04e6</p>
 */
public final class EntityPushState implements AbortableIterationConsumer<Entity> {
    private LivingEntity source;
    private int maxCramming;
    private int maxEntityCollisions;
    public final List<Entity> pushableEntities = new ReferenceArrayList<>();
    private final List<Entity> platformEntities = new ReferenceArrayList<>();

    private int pushableCount;
    private int nonPassengerCount;
    public boolean foundAny;
    private boolean crammingCheckStarted;
    private boolean crammingResolved;
    public boolean crammingDamage;

    private boolean inUse;

    public void reset(final LivingEntity source, final int maxCramming, final int maxEntityCollisions) {
        if (this.inUse) {
            throw new IllegalStateException("Re-entry of EntityPushState detected");
        }
        this.inUse = true;
        this.source = source;
        this.maxCramming = maxCramming;
        this.maxEntityCollisions = maxEntityCollisions;
        this.pushableEntities.clear();
        this.platformEntities.clear();
        this.pushableCount = 0;
        this.nonPassengerCount = 0;
        this.foundAny = false;
        this.crammingCheckStarted = false;
        this.crammingResolved = maxCramming <= 0;
        this.crammingDamage = false;
    }

    public void release() {
        this.source = null;
        this.pushableEntities.clear();
        this.platformEntities.clear();
        this.inUse = false;
    }

    public List<Entity> platformEntities() {
        return this.platformEntities;
    }

    @Override
    public AbortableIterationConsumer.Continuation accept(final Entity entity) {
        this.foundAny = true;
        if (this.maxEntityCollisions > 0 && this.pushableEntities.size() < this.maxEntityCollisions) {
            this.pushableEntities.add(entity);
        }

        if (!this.crammingResolved) {
            if (this.pushableCount < this.maxCramming) {
                ++this.pushableCount;
            }
            if (!entity.isPassenger()) {
                ++this.nonPassengerCount;
            }
            if (this.pushableCount >= this.maxCramming) {
                if (!this.crammingCheckStarted) {
                    this.crammingCheckStarted = true;
                    if (this.source.getRandom().nextInt(4) != 0) {
                        this.crammingResolved = true;
                    }
                }
                if (!this.crammingResolved && this.nonPassengerCount >= this.maxCramming) {
                    this.crammingDamage = true;
                    this.crammingResolved = true;
                }
            }
        }

        final boolean collisionsResolved = this.maxEntityCollisions <= 0
            || this.pushableEntities.size() >= this.maxEntityCollisions;
        return collisionsResolved && this.crammingResolved
            ? AbortableIterationConsumer.Continuation.ABORT
            : AbortableIterationConsumer.Continuation.CONTINUE;
    }
}
