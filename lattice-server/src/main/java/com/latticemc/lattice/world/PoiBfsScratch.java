package com.latticemc.lattice.world;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.Arrays;
import java.util.NoSuchElementException;

/** POI BFS 的临时 primitive 容器；缓存不持有世界、记录或回调。 */
public final class PoiBfsScratch implements AutoCloseable {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lattice.poiBfsScratch", "true"));
    private static final int MAX_RETAINED_SEEN = 16_384;
    private static final int MAX_RETAINED_QUEUE = 4_096;
    private static final ThreadLocal<PoiBfsScratch> LOCAL = ThreadLocal.withInitial(() -> new PoiBfsScratch(true));

    public final SeenSet seen = new SeenSet();
    public final LongArrayFIFOQueue queue = new RetainedQueue();
    private final boolean retained;
    private boolean inUse;

    private PoiBfsScratch(boolean retained) {
        this.retained = retained;
    }

    public static PoiBfsScratch acquire() {
        PoiBfsScratch scratch = LOCAL.get();
        if (scratch.inUse) return new PoiBfsScratch(false);
        scratch.inUse = true;
        return scratch;
    }

    @Override
    public void close() {
        if (!this.retained) return;
        this.inUse = false;
        if (this.seen.size() > MAX_RETAINED_SEEN || this.queue.capacity() > MAX_RETAINED_QUEUE) {
            LOCAL.remove();
        } else {
            this.seen.clear();
            this.queue.clear();
        }
    }

    /** 沿用 fastutil 的环形缓冲与扩容，仅去掉取出元素后的自动缩容。 */
    static final class RetainedQueue extends LongArrayFIFOQueue {
        @Override
        public long dequeueLong() {
            if (this.start == this.end) throw new NoSuchElementException();
            long value = this.array[this.start];
            if (++this.start == this.length) this.start = 0;
            return value;
        }
    }

    /** 仅供 BFS 去重，不提供删除或迭代；零 key 与其他 long 使用相同槽规则。 */
    public static final class SeenSet {
        private long[] keys = new long[32];
        private int[] stamps = new int[32];
        private int generation = 1;
        private int size;

        public int size() {
            return this.size;
        }

        public boolean isEmpty() {
            return this.size == 0;
        }

        public boolean add(long key) {
            int mask = this.keys.length - 1;
            int slot = (int) HashCommon.mix(key) & mask;
            while (this.stamps[slot] == this.generation) {
                if (this.keys[slot] == key) return false;
                slot = (slot + 1) & mask;
            }
            // 保持至多 75% 负载，总有空槽供线性探测终止。
            if (this.size >= this.keys.length - (this.keys.length >>> 2)) {
                this.grow();
                return this.add(key);
            }
            this.keys[slot] = key;
            this.stamps[slot] = this.generation;
            ++this.size;
            return true;
        }

        public void clear() {
            this.size = 0;
            if (this.generation == Integer.MAX_VALUE) {
                Arrays.fill(this.stamps, 0);
                this.generation = 1;
            } else {
                ++this.generation;
            }
        }

        private void grow() {
            long[] oldKeys = this.keys;
            int[] oldStamps = this.stamps;
            int capacity = Math.multiplyExact(oldKeys.length, 2);
            long[] newKeys = new long[capacity];
            int[] newStamps = new int[capacity];
            int mask = capacity - 1;
            for (int i = 0; i < oldKeys.length; ++i) {
                if (oldStamps[i] != this.generation) continue;
                long key = oldKeys[i];
                int slot = (int) HashCommon.mix(key) & mask;
                while (newStamps[slot] == this.generation) slot = (slot + 1) & mask;
                newKeys[slot] = key;
                newStamps[slot] = this.generation;
            }
            this.keys = newKeys;
            this.stamps = newStamps;
        }
    }
}
