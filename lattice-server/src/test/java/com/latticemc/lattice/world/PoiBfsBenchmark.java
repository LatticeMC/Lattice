package com.latticemc.lattice.world;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;

/** 真实 POI BFS 微基准；仅 section 存储由内存 fixture 替代，不包含 IO 成本。 */
public final class PoiBfsBenchmark {
    private static volatile long sink;
    private record Scenario(boolean nearest, int radius, String density) {}

    public static void main(String[] args) {
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        List<Scenario> scenarios = new ArrayList<>();
        for (boolean nearest : new boolean[]{false, true}) {
            for (int radius : new int[]{0, 16, 48, 96, 256}) {
                for (String density : List.of("empty", "sparse", "near")) {
                    scenarios.add(new Scenario(nearest, radius, density));
                }
            }
        }
        if (Boolean.getBoolean("lattice.benchReverse")) Collections.reverse(scenarios);
        System.out.println("SOURCE " + io.papermc.paper.util.PoiAccess.class.getProtectionDomain().getCodeSource().getLocation());
        for (var scenario : scenarios) {
            var manager = PoiBfsTestSupport.manager();
            if (scenario.density.equals("sparse")) {
                PoiBfsTestSupport.add(manager, scenario.radius, 0, 0, PoiBfsTestSupport.TYPE, 2);
            } else if (scenario.density.equals("near")) {
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                    PoiBfsTestSupport.add(manager, x, 0, z, PoiBfsTestSupport.TYPE, 2);
                }
            }
            var output = new ArrayList<PoiRecord>();
            int count = scenario.radius >= 256 ? 100 : scenario.radius >= 96 ? 1000 : 10000;
            run(scenario, manager, output, count);
            long bytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            long checksum = run(scenario, manager, output, count);
            long elapsed = System.nanoTime() - start;
            bytes = bean.getThreadAllocatedBytes(thread) - bytes;
            System.out.printf(java.util.Locale.ROOT,
                "POI_RESULT nearest=%s radius=%d density=%s ns=%.3f bytes=%.3f checksum=%d count=%d%n",
                scenario.nearest, scenario.radius, scenario.density,
                (double) elapsed / count, (double) bytes / count, checksum, count);
        }
        org.apache.logging.log4j.LogManager.shutdown();
    }

    private static long run(Scenario scenario, PoiBfsTestSupport.Manager manager, ArrayList<PoiRecord> output, int count) {
        long checksum = 0;
        for (int i = 0; i < count; i++) {
            output.clear();
            PoiBfsTestSupport.query(manager, scenario.nearest, BlockPos.ZERO, scenario.radius,
                (double) scenario.radius * scenario.radius, PoiManager.Occupancy.ANY, false, 5,
                type -> true, null, output);
            checksum += output.size();
            for (PoiRecord record : output) checksum += record.getPos().asLong();
        }
        sink = checksum;
        return checksum;
    }
}
