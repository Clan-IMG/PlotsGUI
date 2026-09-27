package net.clanimg.plotsGUI.sync;

import com.google.common.eventbus.Subscribe;
import com.plotsquared.core.PlotAPI;
import com.plotsquared.core.events.PlotDeleteEvent;
import com.plotsquared.core.events.PlotEvent;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.util.query.PlotQuery;
import net.clanimg.plotsGUI.cache.PlotCacheStore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Only ever constructed on the server configured as plotsquared_server-name, once PlotSquared is
 * confirmed present. Keeps the shared MySQL cache in sync with PlotSquared's own plot data: a periodic
 * full rescan is the source of truth safety net, PlotSquared's events (all of which extend
 * {@link PlotEvent}) just make individual changes show up sooner.
 */
public final class PlotSquaredSyncModule {

    private final Plugin plugin;
    private final PlotCacheStore cache;
    private final Logger log;
    private final PlotAPI api = new PlotAPI();

    public PlotSquaredSyncModule(Plugin plugin, PlotCacheStore cache, Logger log) {
        this.plugin = plugin;
        this.cache = cache;
        this.log = log;
    }

    public void start() {
        api.registerListener(this);
        rescanAsync();
    }

    public void stop() {
        // PlotAPI/EventDispatcher exposes no unregister call; the listener goes away with this plugin's classloader.
    }

    public void rescanAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::rescan);
    }

    public void refreshOwnerAsync(UUID owner) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> refreshOwner(owner));
    }

    private void rescan() {
        try {
            Set<Plot> plots = api.getAllPlots();
            List<PlotCacheStore.PlotRecord> records = new ArrayList<>(plots.size());
            for (Plot plot : plots) {
                toRecord(plot).ifPresent(records::add);
            }
            cache.fullReplace(records);
            log.info("PlotsGUI: Cache neu aufgebaut (" + records.size() + " Plots)");
        } catch (SQLException e) {
            log.log(Level.SEVERE, "PlotsGUI: Voller Cache-Rescan fehlgeschlagen", e);
        }
    }

    private void refreshOwner(UUID owner) {
        try {
            List<Plot> plots = PlotQuery.newQuery().ownedBy(owner).whereBasePlot().asList();
            List<PlotCacheStore.PlotRecord> records = new ArrayList<>(plots.size());
            for (Plot plot : plots) {
                toRecord(plot).ifPresent(records::add);
            }
            cache.replaceOwner(owner, records);
        } catch (SQLException e) {
            log.log(Level.WARNING, "PlotsGUI: Konnte Plots von " + owner + " nicht aktualisieren", e);
        }
    }

    /** Catches every PlotSquared event and refreshes just the affected plot. */
    @Subscribe
    public void onPlotEvent(PlotEvent event) {
        Plot plot = event.getPlot();
        if (plot == null) {
            return;
        }
        String area = plot.getWorldName();
        String plotId = plot.getId().toString();
        // PlotDeleteEvent fires before the deletion (it is cancellable), so the plot still looks owned
        // at this point - treat it as gone explicitly instead of re-reading stale state.
        boolean deleted = event instanceof PlotDeleteEvent;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                cache.refresh(area, plotId, deleted ? null : toRecord(plot).orElse(null));
            } catch (SQLException e) {
                log.log(Level.WARNING, "PlotsGUI: Konnte Plot " + area + "/" + plotId + " nicht aktualisieren", e);
            }
        });
    }

    private Optional<PlotCacheStore.PlotRecord> toRecord(Plot plot) {
        UUID owner = plot.getOwnerAbs();
        if (owner == null) {
            return Optional.empty();
        }
        return Optional.of(new PlotCacheStore.PlotRecord(
                plot.getWorldName(),
                plot.getId().toString(),
                owner,
                names(plot.getTrusted()),
                names(plot.getMembers()),
                plot.getTimestamp()
        ));
    }

    private List<String> names(Set<UUID> uuids) {
        List<String> names = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            names.add(name != null ? name : uuid.toString());
        }
        return names;
    }
}
