package net.clanimg.plotsGUI.sync;

import net.clanimg.plotsGUI.messaging.RedisActionChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.logging.Logger;

/** Runs pending actions for players already on plotsquared_server-name, without waiting for a join event. */
public final class ActionExecutor {

    private final Plugin plugin;
    private final RedisActionChannel channel;
    private final PlotSquaredSyncModule syncModule;
    private final Logger log;
    private AutoCloseable subscription;

    public ActionExecutor(Plugin plugin, RedisActionChannel channel, PlotSquaredSyncModule syncModule, Logger log) {
        this.plugin = plugin;
        this.channel = channel;
        this.syncModule = syncModule;
        this.log = log;
    }

    public void start() {
        subscription = channel.subscribe(this::onAction);
    }

    public void stop() {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // best-effort shutdown
            }
        }
    }

    private void onAction(UUID playerId, RedisActionChannel.PendingAction ignoredHint) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return; // not here yet; PendingActionJoinListener will handle it once they join
            }
            channel.consumePending(playerId).ifPresent(action -> {
                ActionRunner.run(player, action, log);
                Bukkit.getScheduler().runTaskLater(plugin, () -> syncModule.refreshOwnerAsync(playerId), 40L);
            });
        });
    }
}
