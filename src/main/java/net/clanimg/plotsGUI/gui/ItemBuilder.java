package net.clanimg.plotsGUI.gui;

import net.clanimg.plotsGUI.cache.CachedPlot;
import net.clanimg.plotsGUI.config.GuiConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

public final class ItemBuilder {

    /** NamespacedKeys tagged onto rendered plot items, read back by PlotMenuListener on click. */
    public static final class Keys {
        public final NamespacedKey plotId;

        Keys(Plugin plugin) {
            plotId = new NamespacedKey(plugin, "plot-id");
        }
    }

    private final PlaceholderService placeholders;
    private final Keys keys;

    public ItemBuilder(Plugin plugin, PlaceholderService placeholders) {
        this.placeholders = placeholders;
        this.keys = new Keys(plugin);
    }

    public Keys keys() {
        return keys;
    }

    public ItemStack build(GuiConfig.SlotDefinition definition) {
        return build(definition.material(), definition.name(), definition.lore(), null);
    }

    public ItemStack buildForPlot(GuiConfig.PlotSlotRange range, CachedPlot plot) {
        return build(range.material(), placeholders.apply(range.name(), plot), placeholders.apply(range.lore(), plot),
                plot.plotId());
    }

    private ItemStack build(Material material, String name, List<String> lore, String plotId) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name == null || name.isEmpty() ? Component.empty() : legacy(name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream().map(this::legacy).toList());
            }
            if (plotId != null) {
                meta.getPersistentDataContainer().set(keys.plotId, PersistentDataType.STRING, plotId);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
