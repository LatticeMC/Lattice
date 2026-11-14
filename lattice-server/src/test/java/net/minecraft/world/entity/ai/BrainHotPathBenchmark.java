package net.minecraft.world.entity.ai;

import com.sun.management.ThreadMXBean;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

/**
 * Brain 定向热路径微基准：把 {@code 2d67702}（cache-brain-memory-and-sensing-generation）
 * 相对 {@code ab7e283} 的三处改动，按村民真实数据形状在同一进程里 old-vs-new 对着跑。
 *
 * <ul>
 *   <li>A: {@code hasRequiredMemories} 版本门控缓存（Behavior.java）——按"每 tick 记忆写次数"参数化，
 *       暴露"版本每 tick 都变则跨 tick 缓存命不中"这一关键点。</li>
 *   <li>B: {@code forgetOutdatedMemories} 跳过无 TTL 的标准 ExpirableValue（Brain.java）——每 tick 稳健收益。</li>
 *   <li>C: {@code Sensing} seen/unseen 的 GenerationSet 代际 clear（Sensing.java）——每 tick 稳健收益。</li>
 * </ul>
 *
 * 算法级复刻（用真实数据结构 Reference2ReferenceOpenHashMap / Optional / ExpirableValue /
 * IntOpenHashSet / HashCommon），测量与生产相同的操作序列与量级；不是服务端 MSPT 基准。
 */
public final class BrainHotPathBenchmark {
    private static final ThreadMXBean ALLOC = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private static volatile int sinkInt;

    private static final int MEMORY_TYPES = 30; // 约 30 个已注册记忆类型
    private static final int PRESENT = 20;      // 20 个有值
    private static final int EXPIRING = 3;      // 其中仅 3 个带 TTL
    private static final int BEHAVIORS = 27;    // 约 27 个行为，每个 1-3 条 entry 条件

    public static void main(final String[] args) {
        Locale.setDefault(Locale.ROOT);
        if (!ALLOC.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation counter unavailable");
        ALLOC.setThreadAllocatedMemoryEnabled(true);

        final int warmup = Integer.getInteger("lattice.brainWarmup", 12);
        final int samples = Integer.getInteger("lattice.brainSamples", 15);
        final int ticks = Integer.getInteger("lattice.brainTicks", 4000);
        final boolean reverse = Boolean.getBoolean("lattice.brainReverse");

        System.out.printf("BRAIN_HOTPATH java=%s warmup=%d samples=%d ticks=%d reverse=%s%n",
                System.getProperty("java.version"), warmup, samples, ticks, reverse);

        runForget(warmup, samples, ticks, reverse);
        runSensing(warmup, samples, ticks, reverse);
        runHasRequired(warmup, samples, ticks, reverse, 0, 1.0);
        runHasRequired(warmup, samples, ticks, reverse, 3, 1.0);
        // 真实村民：传感器 scanRate 20/40 且相位随机 → 只有一部分 tick 有写（突发式）。
        for (final double frac : new double[]{0.1, 0.3, 0.5, 0.7}) {
            runHasRequired(warmup, samples, ticks, reverse, 3, frac);
        }
        if (sink == Long.MIN_VALUE || sinkInt == Integer.MIN_VALUE) throw new AssertionError("no samples ran");
    }

    // ---------- A: hasRequiredMemories 版本门控缓存 ----------

    private static void runHasRequired(final int warmup, final int samples, final int ticks,
                                       final boolean reverse, final int writesPerTick, final double writeFraction) {
        final Condition[][] behaviors = buildBehaviors();
        final Object[] types = buildTypes();
        final double[] oldNs = new double[samples];
        final double[] newNs = new double[samples];
        for (int i = 0; i < warmup; i++) {
            hasOld(behaviors, types, ticks, writesPerTick, writeFraction);
            hasNew(behaviors, types, ticks, writesPerTick, writeFraction);
        }
        for (int i = 0; i < samples; i++) {
            for (int order = 0; order < 2; order++) {
                final boolean isNew = ((order + i + (reverse ? 1 : 0)) & 1) == 1;
                final long ns = isNew ? hasNew(behaviors, types, ticks, writesPerTick, writeFraction)
                                      : hasOld(behaviors, types, ticks, writesPerTick, writeFraction);
                (isNew ? newNs : oldNs)[i] = (double) ns / ticks;
            }
        }
        Arrays.sort(oldNs); Arrays.sort(newNs);
        final String label = writeFraction >= 1.0
                ? "A.hasRequiredMemories writes=" + writesPerTick + "/tick(always)"
                : "A.hasRequiredMemories burst=" + writesPerTick + " on " + Math.round(writeFraction * 100) + "% ticks";
        report(label, oldNs[samples / 2], newNs[samples / 2]);
    }

    private static long hasOld(final Condition[][] behaviors, final Object[] types, final int ticks, final int writesPerTick, final double writeFraction) {
        final Reference2ReferenceOpenHashMap<Object, Optional<ExpirableValue<?>>> memories = buildMemories(types);
        final long t0 = System.nanoTime();
        int acc = 0;
        for (int t = 0; t < ticks; t++) {
            if (tickWrites(t, writeFraction)) {
                for (int w = 0; w < writesPerTick; w++) {
                    memories.put(types[(t * 7 + w) % MEMORY_TYPES], Optional.of(ExpirableValue.of(t, 120L + w)));
                }
            }
            for (final Condition[] behavior : behaviors) {
                boolean ok = true;
                for (final Condition c : behavior) {
                    if (!checkMemory(memories, c.type, c.status)) { ok = false; break; }
                }
                if (ok) acc++;
            }
        }
        final long ns = System.nanoTime() - t0;
        sinkInt = acc;
        return ns;
    }

    private static long hasNew(final Condition[][] behaviors, final Object[] types, final int ticks, final int writesPerTick, final double writeFraction) {
        final Reference2ReferenceOpenHashMap<Object, Optional<ExpirableValue<?>>> memories = buildMemories(types);
        final Object brain = new Object();
        final Object[] cacheBrain = new Object[BEHAVIORS];
        final long[] cacheVersion = new long[BEHAVIORS];
        final boolean[] cacheResult = new boolean[BEHAVIORS];
        Arrays.fill(cacheVersion, Long.MIN_VALUE);
        long version = 0;
        final long t0 = System.nanoTime();
        int acc = 0;
        for (int t = 0; t < ticks; t++) {
            if (tickWrites(t, writeFraction)) {
                for (int w = 0; w < writesPerTick; w++) {
                    memories.put(types[(t * 7 + w) % MEMORY_TYPES], Optional.of(ExpirableValue.of(t, 120L + w)));
                    version++;
                }
            }
            for (int b = 0; b < BEHAVIORS; b++) {
                boolean result;
                if (cacheBrain[b] == brain && cacheVersion[b] == version) {
                    result = cacheResult[b]; // 命中：3 字段读 + 比较 + 返回
                } else {
                    result = true;
                    for (final Condition c : behaviors[b]) {
                        if (!checkMemory(memories, c.type, c.status)) { result = false; break; }
                    }
                    cacheBrain[b] = brain; cacheVersion[b] = version; cacheResult[b] = result;
                }
                if (result) acc++;
            }
        }
        final long ns = System.nanoTime() - t0;
        sinkInt = acc;
        return ns;
    }

    // ---------- B: forgetOutdatedMemories 跳过非过期 ----------

    private static void runForget(final int warmup, final int samples, final int ticks, final boolean reverse) {
        final Object[] types = buildTypes();
        final double[] oldNs = new double[samples];
        final double[] newNs = new double[samples];
        for (int i = 0; i < warmup; i++) { forgetOld(buildMemories(types), ticks); forgetNew(buildMemories(types), ticks); }
        for (int i = 0; i < samples; i++) {
            for (int order = 0; order < 2; order++) {
                final boolean isNew = ((order + i + (reverse ? 1 : 0)) & 1) == 1;
                final long ns = isNew ? forgetNew(buildMemories(types), ticks) : forgetOld(buildMemories(types), ticks);
                (isNew ? newNs : oldNs)[i] = (double) ns / ticks;
            }
        }
        Arrays.sort(oldNs); Arrays.sort(newNs);
        report("B.forgetOutdatedMemories", oldNs[samples / 2], newNs[samples / 2]);
    }

    private static long forgetOld(final Reference2ReferenceOpenHashMap<Object, Optional<ExpirableValue<?>>> memories, final int ticks) {
        final long t0 = System.nanoTime();
        int acc = 0;
        for (int t = 0; t < ticks; t++) {
            for (final Map.Entry<Object, Optional<ExpirableValue<?>>> entry : memories.entrySet()) {
                if (entry.getValue().isPresent()) {
                    final ExpirableValue<?> v = entry.getValue().get();
                    if (v.hasExpired()) acc++;
                    v.tick();
                }
            }
        }
        final long ns = System.nanoTime() - t0;
        sinkInt = acc;
        return ns;
    }

    private static long forgetNew(final Reference2ReferenceOpenHashMap<Object, Optional<ExpirableValue<?>>> memories, final int ticks) {
        final long t0 = System.nanoTime();
        int acc = 0;
        for (int t = 0; t < ticks; t++) {
            for (final Map.Entry<Object, Optional<ExpirableValue<?>>> entry : memories.entrySet()) {
                if (entry.getValue().isPresent()) {
                    final ExpirableValue<?> v = entry.getValue().get();
                    if (v.getClass() != ExpirableValue.class || v.canExpire()) {
                        if (v.hasExpired()) acc++;
                        v.tick();
                    }
                }
            }
        }
        final long ns = System.nanoTime() - t0;
        sinkInt = acc;
        return ns;
    }

    // ---------- C: Sensing GenerationSet ----------

    private static void runSensing(final int warmup, final int samples, final int ticks, final boolean reverse) {
        for (final int size : new int[]{6, 16, 64, 256}) {
            senseAt(warmup, samples, ticks, reverse, size);
        }
    }

    private static void senseAt(final int warmup, final int samples, final int ticks, final boolean reverse, final int size) {
        final int[] losBatch = buildLosBatch(size);
        final double[] oldNs = new double[samples];
        final double[] newNs = new double[samples];
        for (int i = 0; i < warmup; i++) { senseOld(losBatch, ticks); senseNew(losBatch, ticks); }
        for (int i = 0; i < samples; i++) {
            for (int order = 0; order < 2; order++) {
                final boolean isNew = ((order + i + (reverse ? 1 : 0)) & 1) == 1;
                final long ns = isNew ? senseNew(losBatch, ticks) : senseOld(losBatch, ticks);
                (isNew ? newNs : oldNs)[i] = (double) ns / ticks;
            }
        }
        Arrays.sort(oldNs); Arrays.sort(newNs);
        report("C.Sensing set~" + size, oldNs[samples / 2], newNs[samples / 2]);
    }

    private static long senseOld(final int[] losBatch, final int ticks) {
        final IntSet seen = new IntOpenHashSet();
        final IntSet unseen = new IntOpenHashSet();
        final long t0 = System.nanoTime();
        int acc = 0;
        for (int t = 0; t < ticks; t++) {
            seen.clear(); unseen.clear();
            for (final int id : losBatch) { if (!seen.add(id)) acc++; }
            for (final int id : losBatch) { if (unseen.contains(id)) acc++; }
        }
        final long ns = System.nanoTime() - t0;
        sinkInt = acc;
        return ns;
    }

    private static long senseNew(final int[] losBatch, final int ticks) {
        final GenerationSet seen = new GenerationSet();
        final GenerationSet unseen = new GenerationSet();
        final long t0 = System.nanoTime();
        int acc = 0;
        for (int t = 0; t < ticks; t++) {
            seen.clear(); unseen.clear();
            for (final int id : losBatch) { if (!seen.add(id)) acc++; }
            for (final int id : losBatch) { if (unseen.contains(id)) acc++; }
        }
        final long ns = System.nanoTime() - t0;
        sinkInt = acc;
        return ns;
    }

    // ---------- fixtures ----------

    private static Reference2ReferenceOpenHashMap<Object, Optional<ExpirableValue<?>>> buildMemories(final Object[] types) {
        final Reference2ReferenceOpenHashMap<Object, Optional<ExpirableValue<?>>> memories = new Reference2ReferenceOpenHashMap<>();
        for (int i = 0; i < MEMORY_TYPES; i++) {
            if (i < PRESENT) {
                memories.put(types[i], i < EXPIRING
                        ? Optional.of(ExpirableValue.of(i, 600L))
                        : Optional.of(ExpirableValue.of(i)));
            } else {
                memories.put(types[i], Optional.empty());
            }
        }
        return memories;
    }

    private static Object[] buildTypes() {
        final Object[] types = new Object[MEMORY_TYPES];
        for (int i = 0; i < MEMORY_TYPES; i++) types[i] = new Object();
        return types;
    }

    private static final class Condition {
        final Object type; final MemoryStatus status;
        Condition(final Object type, final MemoryStatus status) { this.type = type; this.status = status; }
    }

    private static Condition[][] buildBehaviors() {
        final Object[] types = buildTypes();
        final MemoryStatus[] statuses = MemoryStatus.values();
        final Condition[][] behaviors = new Condition[BEHAVIORS][];
        for (int b = 0; b < BEHAVIORS; b++) {
            final int count = 1 + (b % 3);
            final Condition[] conditions = new Condition[count];
            for (int c = 0; c < count; c++) {
                conditions[c] = new Condition(types[(b * 5 + c * 7) % MEMORY_TYPES], statuses[(b + c) % statuses.length]);
            }
            behaviors[b] = conditions;
        }
        return behaviors;
    }

    private static int[] buildLosBatch(final int size) {
        final int[] batch = new int[size];
        for (int i = 0; i < batch.length; i++) batch[i] = 1000 + i * 37;
        return batch;
    }

    // 复刻自 0130 补丁的 GenerationSet。
    private static final class GenerationSet {
        private int[] keys = new int[32];
        private int[] stamps = new int[32];
        private int generation = 1;
        private int size;

        boolean contains(final int key) {
            int mask = this.keys.length - 1;
            int slot = HashCommon.mix(key) & mask;
            while (this.stamps[slot] == this.generation) {
                if (this.keys[slot] == key) return true;
                slot = (slot + 1) & mask;
            }
            return false;
        }

        boolean add(final int key) {
            int mask = this.keys.length - 1;
            int slot = HashCommon.mix(key) & mask;
            while (this.stamps[slot] == this.generation) {
                if (this.keys[slot] == key) return false;
                slot = (slot + 1) & mask;
            }
            if (this.size >= this.keys.length - (this.keys.length >>> 2)) {
                grow();
                mask = this.keys.length - 1;
                slot = HashCommon.mix(key) & mask;
                while (this.stamps[slot] == this.generation) slot = (slot + 1) & mask;
            }
            this.keys[slot] = key;
            this.stamps[slot] = this.generation;
            ++this.size;
            return true;
        }

        void clear() {
            this.size = 0;
            if (this.generation == Integer.MAX_VALUE) {
                Arrays.fill(this.stamps, 0);
                this.generation = 1;
            } else {
                ++this.generation;
            }
        }

        private void grow() {
            final int[] oldKeys = this.keys;
            final int[] oldStamps = this.stamps;
            final int capacity = oldKeys.length * 2;
            this.keys = new int[capacity];
            this.stamps = new int[capacity];
            final int mask = capacity - 1;
            for (int i = 0; i < oldKeys.length; ++i) {
                if (oldStamps[i] != this.generation) continue;
                int s = HashCommon.mix(oldKeys[i]) & mask;
                while (this.stamps[s] == this.generation) s = (s + 1) & mask;
                this.keys[s] = oldKeys[i];
                this.stamps[s] = this.generation;
            }
        }
    }

    // 复刻 vanilla Brain.checkMemory 的开销（一次 map.get + 状态比较）。
    private static boolean checkMemory(final Map<Object, Optional<ExpirableValue<?>>> memories,
                                       final Object type, final MemoryStatus status) {
        final Optional<? extends ExpirableValue<?>> optional = memories.get(type);
        if (optional == null) return status == MemoryStatus.REGISTERED;
        final boolean present = optional.isPresent();
        return status == MemoryStatus.REGISTERED
                || status == MemoryStatus.VALUE_PRESENT && present
                || status == MemoryStatus.VALUE_ABSENT && !present;
    }

    /** 确定性伪随机：约 fraction 比例的 tick 发生一次写突发（模拟 scanRate=20/40、相位随机的传感器 + 行为写）。 */
    private static boolean tickWrites(final int t, final double fraction) {
        if (fraction >= 1.0) return true;
        long h = t * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return (h & 0xFFFFFFL) < fraction * 0x1000000L;
    }

    private static void report(final String label, final double oldNs, final double newNs) {
        final double delta = (newNs - oldNs) / oldNs * 100.0;
        System.out.printf("BRAIN_RESULT %-36s old=%9.3f new=%9.3f ns/tick  delta=%+6.1f%%%n",
                label, oldNs, newNs, delta);
    }
}
