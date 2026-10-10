package net.minecraft.world.entity.ai;

import com.google.common.collect.ImmutableList;
import com.sun.management.ThreadMXBean;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

/** 独立 JVM 微基准；校验和必须与冻结 server jar 相同，不能替代整服 ABBA。 */
public final class BrainMemoryBenchmark {
    private static volatile long blackhole;
    private static MethodHandle forget;
    private static final ThreadMXBean CPU = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args) throws Throwable {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        forget = MethodHandles.privateLookupIn(Brain.class, MethodHandles.lookup())
                .findVirtual(Brain.class, "forgetOutdatedMemories", MethodType.methodType(void.class));
        List<MemoryModuleType<?>> types = new ArrayList<>();
        BuiltInRegistries.MEMORY_MODULE_TYPE.forEach(types::add);
        types = types.subList(0, 40);
        Brain<?>[] brains = new Brain<?>[256];
        long allocationBefore = CPU.getThreadAllocatedBytes(Thread.currentThread().threadId());
        for (int i = 0; i < brains.length; i++) {
            brains[i] = new Brain<>(types, List.of(), ImmutableList.of(), () -> null);
        }
        long constructionBytes = CPU.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocationBefore;
        System.out.println("BRAIN_CONSTRUCTION bytesPerBrain=" + constructionBytes / brains.length);
        for (String scenario : List.of("read", "write", "expiry0", "expiry4", "expiry16")) {
            int expiring = scenario.equals("expiry16") ? 16 : scenario.equals("expiry4") ? 4 : 0;
            for (Brain<?> brain : brains) {
                for (int i = 0; i < types.size(); i++) {
                    if (i < expiring) brain.setMemoryWithExpiry((MemoryModuleType) types.get(i), i, 100000000L);
                    else brain.setMemory((MemoryModuleType) types.get(i), i);
                }
            }
            for (int i = 0; i < 12; i++) blackhole = run(scenario, brains, types, 100);
            long bytes = CPU.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long cpu = CPU.getCurrentThreadCpuTime();
            long start = System.nanoTime();
            long checksum = run(scenario, brains, types, 1500);
            long elapsed = System.nanoTime() - start;
            cpu = CPU.getCurrentThreadCpuTime() - cpu;
            bytes = CPU.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes;
            blackhole = checksum;
            long operations = 1500L * brains.length * (scenario.startsWith("expiry") ? 1 : types.size());
            System.out.printf(java.util.Locale.ROOT, "BRAIN_RESULT scenario=%s ns=%.3f cpuNs=%.3f bytes=%.3f checksum=%d%n",
                    scenario, (double) elapsed / operations, (double) cpu / operations, (double) bytes / operations, checksum);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static long run(String scenario, Brain<?>[] brains, List<MemoryModuleType<?>> types, int rounds) throws Throwable {
        long sum = 0;
        for (int round = 0; round < rounds; round++) {
            for (Brain<?> brain : brains) {
                if (scenario.startsWith("expiry")) {
                    forget.invokeExact((Brain) brain);
                    sum += brain.getTimeUntilExpiry(types.getFirst());
                } else {
                    for (int i = 0; i < types.size(); i++) {
                        MemoryModuleType type = types.get(i);
                        if (scenario.equals("write")) brain.setMemory(type, round & 127);
                        if (brain.checkMemory(type, MemoryStatus.VALUE_PRESENT)) sum += (Integer) brain.getMemory(type).orElseThrow();
                    }
                }
            }
        }
        return sum;
    }
}
