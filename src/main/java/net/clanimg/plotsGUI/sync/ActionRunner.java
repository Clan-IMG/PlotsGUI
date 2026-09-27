package net.clanimg.plotsGUI.sync;

import net.clanimg.plotsGUI.messaging.RedisActionChannel.PendingAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/** Executes a queued GUI action as the actual PlotSquared command. Must run on the main thread. */
public final class ActionRunner {

    private ActionRunner() {
    }

    public static void run(Player player, PendingAction action, Logger log) {
        String command = switch (action.type()) {
            case CREATE_AUTO -> "plot auto";
            case JOIN_PLOT -> action.payload() == null || action.payload().isBlank() ? null : "plot visit " + action.payload();
            case NOOP, PAGE_NEXT, PAGE_BACK -> null;
        };
        if (command == null) {
            log.warning("PlotsGUI: unbekannte oder unvollstaendige Aktion " + action.type() + " fuer " + player.getName());
            return;
        }
        Bukkit.dispatchCommand(player, command);
    }
}
