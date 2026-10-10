package org.purpurmc.testplugin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchedulingBenchmarkCommandTest {
    @TempDir Path directory;

    private SchedulingBenchmarkCommand command(Path output) {
        return command(output, new AtomicLong(100)::getAndIncrement);
    }

    private SchedulingBenchmarkCommand command(Path output, LongSupplier cpu) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(output.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        AtomicLong nanos = new AtomicLong(1000);
        return new SchedulingBenchmarkCommand(plugin, cpu, () -> nanos.addAndGet(10), () -> 1234);
    }

    private static void record(SchedulingBenchmarkCommand command, CommandSender sender) {
        assertTrue(command.execute(sender, "schedulingbench", new String[]{"start", "20"}));
        command.onTick(new ServerTickEndEvent(100, 999, 0)); // Command's tick is excluded.
        for (int i = 0; i < 20; i++) {
            // A deliberate event gap must survive in the CSV, never be synthesized away.
            command.onTickStart(new ServerTickStartEvent(101 + i * 2));
            command.onTick(new ServerTickEndEvent(101 + i * 2, i + 1, 0));
        }
    }

    @Test void recordsActualTicksAndDoesNotOverwriteAfterRestart() throws Exception {
        CommandSender sender = mock(CommandSender.class);
        SchedulingBenchmarkCommand first = command(directory);
        record(first, sender);
        first.execute(sender, "schedulingbench", new String[]{"status"});
        verify(sender).sendMessage(contains("complete ticks=20 first=101 last=139 mean=10.500000 median=10.500000 p95=19.000000 p99=20.000000"));
        record(command(directory), sender);
        try (var files = Files.list(directory)) {
            var outputs = files.toList();
            assertEquals(2, outputs.size());
            for (Path output : outputs) {
                var rows = Files.readAllLines(output);
                assertEquals(21, rows.size());
                assertEquals("101,1.0,measurement,1010,1020,1234,1010", rows.get(1));
                assertEquals("139,20.0,measurement,1390,1400,1234,1010", rows.get(20));
            }
        }
    }

    @Test void rejectsInvalidCountsAndConcurrentCensus() {
        CommandSender sender = mock(CommandSender.class);
        SchedulingBenchmarkCommand command = command(directory);
        for (String count : new String[]{"invalid", "19", "72001"}) {
            assertFalse(command.execute(sender, "schedulingbench", new String[]{"start", count}));
        }
        assertTrue(command.execute(sender, "schedulingbench", new String[]{"start", "20"}));
        assertFalse(command.execute(sender, "schedulingbench", new String[]{"start", "20"}));
        assertFalse(command.execute(sender, "schedulingbench", new String[]{"census"}));
        verify(sender).sendMessage("Census is unavailable while recording");
    }

    @Test void commandBetweenTicksIncludesTheNextFullTick() throws Exception {
        SchedulingBenchmarkCommand command = command(directory);
        CommandSender sender = mock(CommandSender.class);
        command.onTickStart(new ServerTickStartEvent(100));
        command.onTick(new ServerTickEndEvent(100, 999, 0));
        assertTrue(command.execute(sender, "schedulingbench", new String[]{"start", "20"}));
        for (int tick = 101; tick <= 120; tick++) {
            command.onTickStart(new ServerTickStartEvent(tick));
            command.onTick(new ServerTickEndEvent(tick, 1, 0));
        }
        command.execute(sender, "schedulingbench", new String[]{"status"});
        verify(sender).sendMessage(contains("complete ticks=20 first=101 last=120"));
    }

    @Test void reportsOutputFailureInsteadOfCompletion() throws Exception {
        Path file = Files.createFile(directory.resolve("not-a-directory"));
        SchedulingBenchmarkCommand command = command(file);
        CommandSender sender = mock(CommandSender.class);
        record(command, sender);
        command.execute(sender, "schedulingbench", new String[]{"status"});
        verify(sender).sendMessage(startsWith("failed java.nio.file.FileAlreadyExistsException"));
    }

    @Test void fixedSettlingRetainsRawTicksAndAlignsCpuWithMeasurement() throws Exception {
        LongSupplier cpu = mock(LongSupplier.class);
        when(cpu.getAsLong()).thenReturn(100L, 450L);
        SchedulingBenchmarkCommand command = command(directory, cpu);
        CommandSender sender = mock(CommandSender.class);
        assertTrue(command.execute(sender, "schedulingbench", new String[]{"start", "20", "2"}));
        command.onTick(new ServerTickEndEvent(100, 999, 0));
        for (int i = 0; i < 22; i++) {
            command.onTickStart(new ServerTickStartEvent(101 + i));
            if (i < 2) verifyNoInteractions(cpu);
            command.onTick(new ServerTickEndEvent(101 + i, i < 2 ? 100 : i - 1, 0));
            // A duplicate end event must not advance the measurement.
            command.onTick(new ServerTickEndEvent(101 + i, 999, 0));
        }
        verify(cpu, times(2)).getAsLong();
        command.execute(sender, "schedulingbench", new String[]{"status"});
        verify(sender).sendMessage(contains("complete ticks=20 first=103 last=122 mean=10.500000 median=10.500000 p95=19.000000 p99=20.000000"));
        verify(sender).sendMessage(contains("settleTicks=2 allTicks=22 allFirst=101 allmean=18.636364"));
        verify(sender).sendMessage(contains("processCpuNs=350 elapsedNs=390"));
        try (var files = Files.list(directory)) {
            var rows = Files.readAllLines(files.findFirst().orElseThrow());
            assertEquals(23, rows.size());
            assertEquals("101,100.0,settling,1010,1020,1234,1010", rows.get(1));
            assertEquals("103,1.0,measurement,1050,1060,1234,1010", rows.get(3));
            assertEquals("122,20.0,measurement,1430,1440,1234,1010", rows.get(22));
        }
    }

    @Test void rejectsInvalidSettlingAndReportsUnavailableCpu() {
        CommandSender sender = mock(CommandSender.class);
        SchedulingBenchmarkCommand command = command(directory, () -> -1);
        for (String settle : new String[]{"-1", "72001", "invalid"}) {
            assertFalse(command.execute(sender, "schedulingbench", new String[]{"start", "20", settle}));
        }
        record(command, sender);
        command.execute(sender, "schedulingbench", new String[]{"status"});
        verify(sender).sendMessage(contains("processCpuNs=NA"));
    }

    @Test void thresholdCountsAreStrictAndDoNotReplacePercentiles() {
        SchedulingBenchmarkCommand command = command(directory);
        CommandSender sender = mock(CommandSender.class);
        assertTrue(command.execute(sender, "schedulingbench", new String[]{"start", "20"}));
        double[] values = {15, 25, 50, 100, 101};
        for (int i = 0; i < 20; i++) {
            command.onTickStart(new ServerTickStartEvent(i));
            command.onTick(new ServerTickEndEvent(i, values[i % 5], 0));
        }
        command.execute(sender, "schedulingbench", new String[]{"status"});
        verify(sender).sendMessage(contains("p99=101.000000 gt15=16 gt25=12 gt50=8 gt100=4"));
    }

    @Test void backwardsCpuClockIsNotReportedAsNegativeCpuUsage() {
        LongSupplier cpu = mock(LongSupplier.class);
        when(cpu.getAsLong()).thenReturn(500L, 100L);
        CommandSender sender = mock(CommandSender.class);
        SchedulingBenchmarkCommand command = command(directory, cpu);
        record(command, sender);
        command.execute(sender, "schedulingbench", new String[]{"status"});
        verify(sender).sendMessage(contains("processCpuNs=NA"));
        verify(cpu, times(2)).getAsLong();
    }
}
