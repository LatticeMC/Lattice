package com.latticemc.lattice.util.collection;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import java.util.Optional;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * Brain 专用的固定注册集合索引。值只保存在原哈希表，索引不会改变其迭代顺序。
 * 向外暴露 Map 前必须调用 {@link #disableIndex()}，随后保留 fastutil 的全部可变视图语义。
 */
public final class BrainMemoryMap extends Reference2ReferenceOpenHashMap<MemoryModuleType<?>, Optional<? extends ExpirableValue<?>>> {
    private static final boolean MEMORY_INDEX = Boolean.parseBoolean(System.getProperty("lattice.brainMemoryIndex", "false"));
    private static final boolean EXPIRY_INDEX = Boolean.parseBoolean(System.getProperty("lattice.brainExpiryIndex", "false"));

    private int[] slotsById;
    private long[] expiringSlots;
    private boolean permanentlyDisabled;

    /** 仅在 Brain 完成注册、反序列化和 trim 后调用一次。 */
    public void initializeIndex() {
        if (this.permanentlyDisabled || (!MEMORY_INDEX && !EXPIRY_INDEX) || this.containsNullKey) {
            return;
        }
        Object[] keys = this.key;
        int maxId = -1;
        for (Object key : keys) {
            if (key != null) {
                maxId = Math.max(maxId, ((MemoryModuleType<?>) key).latticeMemoryId);
            }
        }
        this.slotsById = new int[maxId + 1];
        this.expiringSlots = EXPIRY_INDEX ? new long[(this.n + 63) >>> 6] : null;
        for (int slot = 0; slot < this.n; slot++) {
            if (keys[slot] != null) {
                this.slotsById[((MemoryModuleType<?>) keys[slot]).latticeMemoryId] = slot + 1;
                if (!this.updateExpiry(slot, this.valueAt(slot))) {
                    return;
                }
            }
        }
    }

    public void disableIndex() {
        this.permanentlyDisabled = true;
        this.slotsById = null;
        this.expiringSlots = null;
    }

    private int indexedSlot(Object key) {
        if (key instanceof MemoryModuleType<?> type) {
            int id = type.latticeMemoryId;
            return id < this.slotsById.length ? this.slotsById[id] - 1 : -1;
        }
        return -1;
    }

    @Override
    public Optional<? extends ExpirableValue<?>> get(Object key) {
        if (MEMORY_INDEX && this.slotsById != null) {
            int slot = this.indexedSlot(key);
            return slot < 0 ? this.defRetValue : this.valueAt(slot);
        }
        return super.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return MEMORY_INDEX && this.slotsById != null ? this.indexedSlot(key) >= 0 : super.containsKey(key);
    }

    @Override
    public Optional<? extends ExpirableValue<?>> put(MemoryModuleType<?> key, Optional<? extends ExpirableValue<?>> value) {
        if (this.slotsById != null) {
            int slot = this.indexedSlot(key);
            if (slot >= 0) {
                Optional<? extends ExpirableValue<?>> previous = this.valueAt(slot);
                Object[] values = this.value;
                values[slot] = value;
                this.updateExpiry(slot, value);
                return previous;
            }
            // 新增键可能触发 rehash；暴露后的普通 Map 操作同样走原实现。
            this.disableIndex();
        }
        return super.put(key, value);
    }

    private boolean updateExpiry(int slot, Optional<? extends ExpirableValue<?>> value) {
        if (value == null || value.isPresent() && value.get().getClass() != ExpirableValue.class) {
            // 子类的 hasExpired/tick/canExpire 可能有副作用，不能省略调用或重排。
            this.disableIndex();
            return false;
        }
        if (this.expiringSlots != null) {
            long bit = 1L << slot;
            if (value.isPresent() && value.get().canExpire()) {
                this.expiringSlots[slot >>> 6] |= bit;
            } else {
                this.expiringSlots[slot >>> 6] &= ~bit;
            }
        }
        return true;
    }

    public boolean hasExpiryIndex() {
        return this.expiringSlots != null;
    }

    /** 与 fastutil MapIterator 相同的降序，exclusiveUpperBound 首次传 Integer.MAX_VALUE。 */
    public int previousExpiringSlot(int exclusiveUpperBound) {
        int slot = Math.min(exclusiveUpperBound, this.n) - 1;
        if (slot < 0) {
            return -1;
        }
        int word = slot >>> 6;
        long bits = this.expiringSlots[word] & (-1L >>> (63 - (slot & 63)));
        while (true) {
            if (bits != 0L) {
                return (word << 6) + 63 - Long.numberOfLeadingZeros(bits);
            }
            if (--word < 0) {
                return -1;
            }
            bits = this.expiringSlots[word];
        }
    }

    public MemoryModuleType<?> keyAt(int slot) {
        Object[] keys = this.key;
        return (MemoryModuleType<?>) keys[slot];
    }

    @SuppressWarnings("unchecked")
    public Optional<? extends ExpirableValue<?>> valueAt(int slot) {
        Object[] values = this.value;
        return (Optional<? extends ExpirableValue<?>>) values[slot];
    }
}
