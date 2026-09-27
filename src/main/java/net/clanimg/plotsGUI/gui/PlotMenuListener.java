package net.clanimg.plotsGUI.gui;

import net.clanimg.plotsGUI.config.GuiConfig;
import net.clanimg.plotsGUI.config.Settings;
import net.clanimg.plotsGUI.messaging.RedisActionChannel;
import net.clanimg.plotsGUI.transfer.SimpleCloudTransferService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class PlotMenuListener implements Listener {

    private final Plugin plugin;
    private final PlotMenuService menuService;
    private final GuiConfig guiConfig;
    private final Settings settings;
    private final RedisActionChannel actionChannel;
    private final SimpleCloudTransferService transferService;
    private final boolean localIsPlotsquaredServer;
    private final ItemBuilder itemBuilder;
    private final Logger log;

    public PlotMenuListener(Plugin plugin, PlotMenuService menuService, GuiConfig guiConfig, Settings settings,
                             RedisActionChannel actionChannel, SimpleCloudTransferService transferService,
                             boolean localIsPlotsquaredServer, ItemBuilder itemBuilder, Logger log) {
        this.plugin = plugin;
        this.menuService = menuService;
        this.guiConfig = guiConfig;
        this.settings = settings;
        this.actionChannel = actionChannel;
        this.transferService = transferService;
        this.localIsPlotsquaredServer = localIsPlotsquaredServer;
        this.itemBuilder = itemBuilder;
        this.log = log;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !(clicked.getHolder() instanceof PlotMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Resolved resolved = resolveSlot(holder.menu(), event.getSlot());
        if (resolved == null) {
            return;
        }

        GuiConfig.ClickAction action;
        if (event.isLeftClick()) {
            action = resolved.left();
        } else if (event.isRightClick()) {
            action = resolved.right();
        } else {
            return;
        }

        runAction(player, holder, action, event.getCurrentItem());

        if (!resolved.openGui().isEmpty()) {
            menuService.open(player, resolved.openGui());
        } else if (resolved.closeMenu()) {
            player.closeInventory();
        }
    }

    private void runAction(Player player, PlotMenuHolder holder, GuiConfig.ClickAction action, ItemStack item) {
        if (action instanceof GuiConfig.ClickAction.RawCommand raw) {
            Bukkit.dispatchCommand(player, raw.command());
            return;
        }
        GuiConfig.ActionType type = ((GuiConfig.ClickAction.Keyword) action).type();
        switch (type) {
            case NOOP -> {
            }
            case PAGE_NEXT -> menuService.open(player, holder.menu(), holder.page() + 1);
            case PAGE_BACK -> menuService.open(player, holder.menu(), holder.page() - 1);
            case JOIN_PLOT -> {
                String plotId = extractPlotId(item);
                if (plotId != null) {
                    triggerAction(player, GuiConfig.ActionType.JOIN_PLOT, plotId);
                }
            }
            case CREATE_AUTO -> triggerAction(player, GuiConfig.ActionType.CREATE_AUTO, "");
        }
    }

    private record Resolved(GuiConfig.ClickAction left, GuiConfig.ClickAction right, String openGui,
                             boolean closeMenu) {
    }

    private Resolved resolveSlot(String menuName, int slot) {
        GuiConfig.Menu menu = guiConfig.menu(menuName);
        if (menu == null) {
            return null;
        }
        for (GuiConfig.SlotDefinition definition : menu.fixedSlots()) {
            if (definition.slot() == slot) {
                return new Resolved(definition.leftClick(), definition.rightClick(), definition.openGui(),
                        definition.closeMenu());
            }
        }
        GuiConfig.PlotSlotRange range = menu.plotRange();
        if (range != null && slot >= range.from() && slot <= range.to()) {
            return new Resolved(range.leftClick(), range.rightClick(), range.openGui(), range.closeMenu());
        }
        return null;
    }

    private void triggerAction(Player player, GuiConfig.ActionType type, String payload) {
        String target = settings.plotsquaredServerName();
        if (target.isBlank()) {
            log.warning("PlotsGUI: plotsquared_server-name ist nicht gesetzt, Aktion " + type + " abgebrochen");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                actionChannel.queueAction(player.getUniqueId(), type, payload);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "PlotsGUI: Konnte Aktion " + type + " fuer " + player.getName()
                        + " nicht an Redis uebergeben: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage("§cAktion fehlgeschlagen (Redis nicht erreichbar). Bitte spaeter erneut versuchen."));
                return;
            }
            if (localIsPlotsquaredServer) {
                return; // ActionExecutor's own subscription already picks this up locally
            }
            SimpleCloudTransferService.Result result = transferService.transfer(player.getUniqueId(), target);
            if (!result.success()) {
                log.log(Level.WARNING, "PlotsGUI: Transfer von " + player.getName() + " nach " + target
                        + " fehlgeschlagen: " + result.errorMessage());
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage("§cServer-Wechsel fehlgeschlagen: " + result.errorMessage()));
            }
        });
    }

    private String extractPlotId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(itemBuilder.keys().plotId, PersistentDataType.STRING);
    }
}
