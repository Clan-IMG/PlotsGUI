package net.clanimg.plotsGUI.listener;

import net.clanimg.plotsGUI.messaging.RedisActionChannel;
import net.clanimg.plotsGUI.sync.ActionRunner;
import net.clanimg.plotsGUI.sync.PlotSquaredSyncModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/** Runs a pending GUI action (queued on another server) once the player actually arrives here. */
public final class PendingActionJoinListener implements Listener {

    private final Plugin plugin;
    private final RedisActionChannel channel;
    private final PlotSquaredSyncModule syncModule;
    private final Logger log;

    public PendingActionJoinListener(Plugin plugin, RedisActionChannel channel, PlotSquaredSyncModule syncModule,
                                      Logger log) {
        this.plugin = plugin;
        this.channel = channel;
        this.syncModule = syncModule;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Give PlotSquared a moment to finish its own join handling (world placement etc.) first.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            channel.consumePending(player.getUniqueId()).ifPresent(action -> {
                ActionRunner.run(player, action, log);
                Bukkit.getScheduler().runTaskLater(plugin, () -> syncModule.refreshOwnerAsync(player.getUniqueId()), 40L);
            });
        }, 20L);
    }
}
