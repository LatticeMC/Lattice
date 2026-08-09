package org.purpurmc.testplugin;

import java.util.Locale;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class TestPlugin extends JavaPlugin implements Listener {
    private PathfinderBenchmarkCommand pathfinderBenchmark;
    private ItemBenchmarkCommand itemBenchmark;
    private EntityActivationBenchmarkCommand entityActivationBenchmark;

    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);
        this.pathfinderBenchmark = new PathfinderBenchmarkCommand(this);
        this.itemBenchmark = new ItemBenchmarkCommand(this);
        this.entityActivationBenchmark = new EntityActivationBenchmarkCommand(this);
        this.getServer().getCommandMap().register(
            this.getName().toLowerCase(Locale.ROOT),
            this.pathfinderBenchmark
        );
        this.getServer().getCommandMap().register(
            this.getName().toLowerCase(Locale.ROOT),
            this.itemBenchmark
        );
        this.getServer().getCommandMap().register(
            this.getName().toLowerCase(Locale.ROOT),
            this.entityActivationBenchmark
        );
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        if (this.entityActivationBenchmark != null) {
            this.entityActivationBenchmark.onPlayerJoin(event);
        }
    }

    @Override
    public void onDisable() {
        if (this.pathfinderBenchmark != null) {
            this.pathfinderBenchmark.shutdown();
        }
        if (this.itemBenchmark != null) {
            this.itemBenchmark.shutdown();
        }
        if (this.entityActivationBenchmark != null) {
            this.entityActivationBenchmark.shutdown();
        }
    }
}
