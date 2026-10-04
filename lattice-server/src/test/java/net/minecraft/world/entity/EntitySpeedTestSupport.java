package net.minecraft.world.entity;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** 实际 Entity 速度方法的无世界依赖夹具；位置输入由测试预先分配。 */
final class EntitySpeedTestSupport {
    static class SpeedEntity extends Entity {
        Vec3 suppliedPosition = Vec3.ZERO;
        LivingEntity controller;
        boolean alive = true;

        SpeedEntity() { super(EntityType.PIG, null); }
        void sample() { computeSpeed(); }
        @Override public Vec3 position() { return suppliedPosition == null ? Vec3.ZERO : suppliedPosition; }
        @Override public void setPos(double x, double y, double z) { }
        @Override public LivingEntity getControllingPassenger() { return controller; }
        @Override public boolean isAlive() { return alive; }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
        @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
        @Override protected void readAdditionalSaveData(ValueInput input) { }
        @Override protected void addAdditionalSaveData(ValueOutput output) { }
    }

    /** 未改动的原采样逻辑，作为分配/耗时基线；不调用优化后的采样实现。 */
    static final class OriginalEntity extends SpeedEntity {
        private Vec3 previous;
        private Vec3 speed = Vec3.ZERO;
        @Override protected void computeSpeed() {
            if (previous == null) previous = position();
            speed = position().subtract(previous);
            previous = position();
        }
        @Override public Vec3 getKnownSpeed() {
            return getControllingPassenger() instanceof Player player && isAlive() ? player.getKnownSpeed() : speed;
        }
        @Override public boolean hasMovedHorizontallyRecently() {
            return Math.abs(speed.horizontalDistance()) > 1.0E-5F;
        }
    }
}
