package org.purpurmc.testplugin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchedulingBenchmarkCommandTest {
    @TempDir Path directory;

    private SchedulingBenchmarkCommand command(Path output) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(output.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return new SchedulingBenchmarkCommand(plugin);
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
                assertEquals("101,1.0", rows.get(1));
                assertEquals("139,20.0", rows.get(20));
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
}
