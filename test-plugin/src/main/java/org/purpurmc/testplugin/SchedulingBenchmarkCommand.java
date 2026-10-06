package org.purpurmc.testplugin;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** 显式启用的验收工具；计时来自真实逐 tick 事件，写盘发生在采样完成之后。 */
final class SchedulingBenchmarkCommand extends Command implements Listener {
    private final JavaPlugin plugin;
    private double[] samples;
    private int[] sampleTicks;
    private int count;
    private int firstTick;
    private int lastTick;
    private boolean waitingForNextTick;
    private String result = "idle";

    SchedulingBenchmarkCommand(JavaPlugin plugin) {
        super("schedulingbench", "Records actual per-tick durations", "/schedulingbench <start ticks|status|census>", List.of());
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 1 && args[0].equals("census")) {
            if (this.samples != null) {
                sender.sendMessage("Census is unavailable while recording");
                return false;
            }
            try {
                sender.sendMessage(this.census());
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot inspect ticking sections", error);
            }
            return true;
        }
        if (args.length == 1 && args[0].equals("status")) {
            sender.sendMessage(this.samples == null ? this.result : "running count=" + this.count + " target=" + this.samples.length);
            return true;
        }
        if (args.length != 2 || !args[0].equals("start")) {
            sender.sendMessage(this.getUsage());
            return false;
        }
        if (this.samples != null) {
            sender.sendMessage("Already recording");
            return false;
        }
        final int ticks;
        try {
            ticks = Integer.parseInt(args[1]);
        } catch (NumberFormatException error) {
            sender.sendMessage("Invalid tick count: " + args[1]);
            return false;
        }
        if (ticks < 20 || ticks > 72_000) {
            sender.sendMessage("Tick count must be 20..72000");
            return false;
        }
        this.samples = new double[ticks];
        this.sampleTicks = new int[ticks];
        this.count = 0;
        this.waitingForNextTick = true;
        this.result = "running";
        sender.sendMessage("started ticks=" + ticks);
        return true;
    }

    @EventHandler
    public void onTickStart(ServerTickStartEvent event) {
        this.waitingForNextTick = false;
    }

    @EventHandler
    public void onTick(ServerTickEndEvent event) {
        if (this.samples == null || this.waitingForNextTick) return;
        if (this.count == 0) this.firstTick = event.getTickNumber();
        this.lastTick = event.getTickNumber();
        this.sampleTicks[this.count] = event.getTickNumber();
        this.samples[this.count++] = event.getTickDuration();
        if (this.count != this.samples.length) return;
        double[] completed = this.samples;
        int[] completedTicks = this.sampleTicks;
        this.samples = null;
        this.sampleTicks = null;
        final Path output;
        try {
            Path directory = this.plugin.getDataFolder().toPath();
            Files.createDirectories(directory);
            output = Files.createTempFile(directory, "scheduling-ticks-" + this.firstTick + "-", ".csv");
            StringBuilder csv = new StringBuilder("tick,milliseconds\n");
            for (int i = 0; i < completed.length; i++) csv.append(completedTicks[i]).append(',').append(completed[i]).append('\n');
            Files.writeString(output, csv, StandardOpenOption.WRITE);
        } catch (IOException error) {
            this.result = "failed " + error;
            this.plugin.getLogger().log(java.util.logging.Level.SEVERE, "Scheduling benchmark output failed", error);
            return;
        }
        double mean = Arrays.stream(completed).average().orElseThrow();
        Arrays.sort(completed);
        this.result = String.format(Locale.ROOT,
            "complete ticks=%d first=%d last=%d mean=%.6f median=%.6f p95=%.6f p99=%.6f file=%s",
            completed.length, this.firstTick, this.lastTick, mean, median(completed),
            percentile(completed, .95), percentile(completed, .99), output);
        this.plugin.getLogger().info("SCHEDULING_BENCH " + this.result);
    }

    private static double percentile(double[] sorted, double fraction) {
        return sorted[(int) Math.ceil(sorted.length * fraction) - 1];
    }

    private static double median(double[] sorted) {
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }

    // 仅在测量窗口外调用；反射避免测试插件依赖服务端内部类。
    private String census() throws ReflectiveOperationException {
        int chunks = 0, sections = 0, active = 0, mismatches = 0;
        long[] buckets = new long[5]; // 0, 1..16, 17..256, 257..4095, 4096
        boolean optimized = Boolean.getBoolean("lattice.optimizeRandomTick");
        for (var world : this.plugin.getServer().getWorlds()) {
            Object handle = world.getClass().getMethod("getHandle").invoke(world);
            Object ticking = handle.getClass().getMethod("moonrise$getEntityTickingChunks").invoke(handle);
            Object[] raw = (Object[]) ticking.getClass().getMethod("getRawDataUnchecked").invoke(ticking);
            int count = (int) ticking.getClass().getMethod("size").invoke(ticking);
            chunks += count;
            for (int i = 0; i < count; i++) {
                Object chunk = raw[i];
                Object[] entries = (Object[]) chunk.getClass().getMethod("getSections").invoke(chunk);
                long expected = 0L;
                for (int j = 0; j < entries.length; j++) {
                    Object list = entries[j].getClass().getMethod("moonrise$getTickingBlockList").invoke(entries[j]);
                    int size = (int) list.getClass().getMethod("size").invoke(list);
                    sections++;
                    if (size > 0) { active++; expected |= 1L << j; }
                    buckets[size == 0 ? 0 : size <= 16 ? 1 : size <= 256 ? 2 : size < 4096 ? 3 : 4]++;
                }
                if (optimized && entries.length <= 64) {
                    long actual = (long) chunk.getClass().getMethod("lattice$getRandomTickingSections").invoke(chunk);
                    if (actual != expected) mismatches++;
                }
            }
        }
        return "census chunks=" + chunks + " sections=" + sections + " active=" + active + " maskMismatches=" + mismatches
            + " buckets=" + Arrays.toString(buckets) + " cache=retired"
            + " random=" + optimized;
    }
}
