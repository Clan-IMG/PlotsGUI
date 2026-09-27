package net.clanimg.plotsGUI.gui;

import net.clanimg.plotsGUI.cache.CachedPlot;
import net.clanimg.plotsGUI.cache.PlotCacheStore;
import net.clanimg.plotsGUI.config.GuiConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads a player's cached plots (if the menu needs them) and renders a named menu from gui.yml. */
public final class PlotMenuService {

    /** The menu opened by /plots and by default when no other menu is named. */
    public static final String DEFAULT_MENU = "plotgui";

    private final Plugin plugin;
    private final GuiConfig guiConfig;
    private final PlotCacheStore cache;
    private final ItemBuilder itemBuilder;
    private final Logger log;

    public PlotMenuService(Plugin plugin, GuiConfig guiConfig, PlotCacheStore cache, ItemBuilder itemBuilder,
                            Logger log) {
        this.plugin = plugin;
        this.guiConfig = guiConfig;
        this.cache = cache;
        this.itemBuilder = itemBuilder;
        this.log = log;
    }

    public void open(Player player) {
        open(player, DEFAULT_MENU, 0);
    }

    public void open(Player player, String menuName) {
        open(player, menuName, 0);
    }

    public void open(Player player, String menuName, int page) {
        GuiConfig.Menu menu = guiConfig.menu(menuName);
        if (menu == null) {
            log.warning("PlotsGUI: Menu '" + menuName + "' ist in gui.yml nicht definiert");
            return;
        }

        if (menu.plotRange() == null) {
            render(player, menuName, menu, 0, List.of());
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<CachedPlot> plots;
            try {
                plots = cache.plotsOf(player.getUniqueId());
            } catch (SQLException e) {
                log.log(Level.WARNING, "PlotsGUI: Konnte Plots von " + player.getName() + " nicht laden", e);
                plots = List.of();
            }
            List<CachedPlot> finalPlots = plots;
            Bukkit.getScheduler().runTask(plugin, () -> render(player, menuName, menu, page, finalPlots));
        });
    }

    private void render(Player player, String menuName, GuiConfig.Menu menu, int page, List<CachedPlot> plots) {
        GuiConfig.PlotSlotRange range = menu.plotRange();
        int pageSize = range == null ? 0 : range.size();
        int totalPages = range == null ? 1 : Math.max(1, (plots.size() + pageSize - 1) / pageSize);
        int clampedPage = Math.max(0, Math.min(page, totalPages - 1));

        PlotMenuHolder holder = new PlotMenuHolder(player.getUniqueId(), menuName);
        holder.setPage(clampedPage);
        Inventory inventory = Bukkit.createInventory(holder, menu.size(),
                LegacyComponentSerializer.legacyAmpersand().deserialize(menu.title()));
        holder.setInventory(inventory);

        for (GuiConfig.SlotDefinition slot : menu.fixedSlots()) {
            if (slot.slot() < 0 || slot.slot() >= inventory.getSize()) {
                continue;
            }
            if (hasKeyword(slot.leftClick(), slot.rightClick(), GuiConfig.ActionType.PAGE_NEXT)
                    && clampedPage >= totalPages - 1) {
                continue;
            }
            if (hasKeyword(slot.leftClick(), slot.rightClick(), GuiConfig.ActionType.PAGE_BACK) && clampedPage <= 0) {
                continue;
            }
            inventory.setItem(slot.slot(), itemBuilder.build(slot));
        }

        if (range != null) {
            int start = clampedPage * pageSize;
            for (int i = 0; i < pageSize; i++) {
                int slotIndex = range.from() + i;
                if (slotIndex > range.to() || slotIndex >= inventory.getSize()) {
                    continue;
                }
                int plotIndex = start + i;
                inventory.setItem(slotIndex,
                        plotIndex < plots.size() ? itemBuilder.buildForPlot(range, plots.get(plotIndex)) : null);
            }
        }

        player.openInventory(inventory);
    }

    private static boolean hasKeyword(GuiConfig.ClickAction left, GuiConfig.ClickAction right,
                                       GuiConfig.ActionType type) {
        return isKeyword(left, type) || isKeyword(right, type);
    }

    private static boolean isKeyword(GuiConfig.ClickAction action, GuiConfig.ActionType type) {
        return action instanceof GuiConfig.ClickAction.Keyword keyword && keyword.type() == type;
    }
}
