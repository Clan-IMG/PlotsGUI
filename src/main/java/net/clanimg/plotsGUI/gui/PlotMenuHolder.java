package net.clanimg.plotsGUI.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class PlotMenuHolder implements InventoryHolder {

    private final UUID viewer;
    private final String menu;
    private int page;
    private Inventory inventory;

    public PlotMenuHolder(UUID viewer, String menu) {
        this.viewer = viewer;
        this.menu = menu;
    }

    public UUID viewer() {
        return viewer;
    }

    public String menu() {
        return menu;
    }

    public int page() {
        return page;
    }

    void setPage(int page) {
        this.page = Math.max(0, page);
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
