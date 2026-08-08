package org.purpurmc.testplugin;

import java.util.Locale;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class TestPlugin extends JavaPlugin implements Listener {
    private PathfinderBenchmarkCommand pathfinderBenchmark;
    private ItemBenchmarkCommand itemBenchmark;

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);
        this.pathfinderBenchmark = new PathfinderBenchmarkCommand(this);
        this.itemBenchmark = new ItemBenchmarkCommand(this);
        this.getServer().getCommandMap().register(
            this.getName().toLowerCase(Locale.ROOT),
            this.pathfinderBenchmark
        );
        this.getServer().getCommandMap().register(
            this.getName().toLowerCase(Locale.ROOT),
            this.itemBenchmark
        );
    }

    @Override
    public void onDisable() {
        if (this.pathfinderBenchmark != null) {
            this.pathfinderBenchmark.shutdown();
        }
        if (this.itemBenchmark != null) {
            this.itemBenchmark.shutdown();
        }
    }
}
