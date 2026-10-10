package org.purpurmc.testplugin;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** 显式启用的验收工具；计时来自真实逐 tick 事件，写盘发生在采样完成之后。 */
final class SchedulingBenchmarkCommand extends Command implements Listener {
    private final JavaPlugin plugin;
    private final LongSupplier processCpuClock;
    private final LongSupplier nanoClock;
    private final LongSupplier epochClock;
    private double[] samples;
    private int[] sampleTicks;
    private long[] tickStarts;
    private long[] tickEnds;
    private int settleTicks;
    private long epochAnchor;
    private long nanoAnchor;
    private long cpuStart;
    private int count;
    private int firstTick;
    private int lastTick;
    private boolean waitingForNextTick;
    private String result = "idle";

    SchedulingBenchmarkCommand(JavaPlugin plugin) {
        this(plugin, () -> ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime(),
            System::nanoTime, System::currentTimeMillis);
    }

    SchedulingBenchmarkCommand(JavaPlugin plugin, LongSupplier processCpuClock, LongSupplier nanoClock, LongSupplier epochClock) {
        super("schedulingbench", "Records actual per-tick durations", "/schedulingbench <start ticks [settleTicks]|status|census>", List.of());
        this.plugin = plugin;
        this.processCpuClock = processCpuClock;
        this.nanoClock = nanoClock;
        this.epochClock = epochClock;
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
            sender.sendMessage(this.samples == null ? this.result : "running count=" + this.count + " target=" + this.samples.length
                + " settleTicks=" + this.settleTicks);
            return true;
        }
        if ((args.length != 2 && args.length != 3) || !args[0].equals("start")) {
            sender.sendMessage(this.getUsage());
            return false;
        }
        if (this.samples != null) {
            sender.sendMessage("Already recording");
            return false;
        }
        final int ticks;
        final int settle;
        try {
            ticks = Integer.parseInt(args[1]);
            settle = args.length == 3 ? Integer.parseInt(args[2]) : 0;
        } catch (NumberFormatException error) {
            sender.sendMessage("Invalid tick count: " + String.join(" ", args));
            return false;
        }
        if (ticks < 20 || ticks > 72_000) {
            sender.sendMessage("Tick count must be 20..72000");
            return false;
        }
        if (settle < 0 || settle > 72_000) {
            sender.sendMessage("Settle tick count must be 0..72000");
            return false;
        }
        this.settleTicks = settle;
        this.samples = new double[ticks + settle];
        this.sampleTicks = new int[ticks + settle];
        this.tickStarts = new long[ticks + settle];
        this.tickEnds = new long[ticks + settle];
        this.count = 0;
        this.waitingForNextTick = true;
        this.result = "running";
        sender.sendMessage("started ticks=" + ticks + " settleTicks=" + settle);
        return true;
    }

    @EventHandler
    public void onTickStart(ServerTickStartEvent event) {
        if (this.samples == null) return;
        this.waitingForNextTick = false;
        this.tickStarts[this.count] = this.nanoClock.getAsLong();
        if (this.count == 0) {
            this.nanoAnchor = this.tickStarts[0];
            this.epochAnchor = this.epochClock.getAsLong();
        }
        if (this.count == this.settleTicks) this.cpuStart = this.processCpuClock.getAsLong();
    }

    @EventHandler
    public void onTick(ServerTickEndEvent event) {
        if (this.samples == null || this.waitingForNextTick) return;
        this.waitingForNextTick = true;
        this.tickEnds[this.count] = this.nanoClock.getAsLong();
        if (this.count == this.settleTicks) this.firstTick = event.getTickNumber();
        this.lastTick = event.getTickNumber();
        this.sampleTicks[this.count] = event.getTickNumber();
        this.samples[this.count++] = event.getTickDuration();
        if (this.count != this.samples.length) return;
        long cpuEnd = this.processCpuClock.getAsLong();
        String processCpuNs = this.cpuStart < 0 || cpuEnd < this.cpuStart ? "NA" : Long.toString(cpuEnd - this.cpuStart);
        long elapsedNs = this.tickEnds[this.count - 1] - this.tickStarts[this.settleTicks];
        double[] completed = this.samples;
        int[] completedTicks = this.sampleTicks;
        this.samples = null;
        this.sampleTicks = null;
        final Path output;
        try {
            Path directory = this.plugin.getDataFolder().toPath();
            Files.createDirectories(directory);
            output = Files.createTempFile(directory, "scheduling-ticks-" + this.firstTick + "-", ".csv");
            StringBuilder csv = new StringBuilder("tick,milliseconds,phase,startNanos,endNanos,epochMillisAnchor,nanoTimeAnchor\n");
            for (int i = 0; i < completed.length; i++) {
                csv.append(completedTicks[i]).append(',').append(completed[i]).append(',')
                    .append(i < this.settleTicks ? "settling" : "measurement").append(',')
                    .append(this.tickStarts[i]).append(',').append(this.tickEnds[i]).append(',')
                    .append(this.epochAnchor).append(',').append(this.nanoAnchor).append('\n');
            }
            Files.writeString(output, csv, StandardOpenOption.WRITE);
        } catch (IOException error) {
            this.result = "failed " + error;
            this.plugin.getLogger().log(java.util.logging.Level.SEVERE, "Scheduling benchmark output failed", error);
            return;
        }
        Statistics measured = statistics(completed, this.settleTicks);
        Statistics all = this.settleTicks == 0 ? measured : statistics(completed, 0);
        this.result = String.format(Locale.ROOT,
            "complete ticks=%d first=%d last=%d %s file=%s settleTicks=%d allTicks=%d allFirst=%d %s processCpuNs=%s elapsedNs=%d",
            completed.length - this.settleTicks, this.firstTick, this.lastTick, measured.format(""), output,
            this.settleTicks, completed.length, completedTicks[0], all.format("all"), processCpuNs, elapsedNs);
        this.tickStarts = null;
        this.tickEnds = null;
        this.plugin.getLogger().info("SCHEDULING_BENCH " + this.result);
    }

    private record Statistics(double mean, double median, double p95, double p99, int gt15, int gt25, int gt50, int gt100) {
        String format(String prefix) {
            return String.format(Locale.ROOT,
                "%smean=%.6f %smedian=%.6f %sp95=%.6f %sp99=%.6f %sgt15=%d %sgt25=%d %sgt50=%d %sgt100=%d",
                prefix, mean, prefix, median, prefix, p95, prefix, p99,
                prefix, gt15, prefix, gt25, prefix, gt50, prefix, gt100);
        }
    }

    private static Statistics statistics(double[] raw, int start) {
        double[] sorted = Arrays.copyOfRange(raw, start, raw.length);
        double mean = Arrays.stream(sorted).average().orElseThrow();
        int gt15 = 0, gt25 = 0, gt50 = 0, gt100 = 0;
        for (double ms : sorted) {
            if (ms > 15) gt15++;
            if (ms > 25) gt25++;
            if (ms > 50) gt50++;
            if (ms > 100) gt100++;
        }
        Arrays.sort(sorted);
        return new Statistics(mean, median(sorted), percentile(sorted, .95), percentile(sorted, .99), gt15, gt25, gt50, gt100);
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
