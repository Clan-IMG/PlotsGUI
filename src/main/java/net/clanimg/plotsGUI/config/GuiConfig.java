package net.clanimg.plotsGUI.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Typed view of gui.yml: a named set of menus (e.g. "plotgui", "editPlot"), each with its own slots. */
public final class GuiConfig {

    public enum ActionType {
        NOOP, JOIN_PLOT, CREATE_AUTO, PAGE_NEXT, PAGE_BACK
    }

    /** What a click on a slot does: one of the built-in keywords, or a literal command to dispatch. */
    public sealed interface ClickAction {
        record Keyword(ActionType type) implements ClickAction {
        }

        record RawCommand(String command) implements ClickAction {
        }
    }

    public record SlotDefinition(int slot, String name, List<String> lore, Material material, ClickAction leftClick,
                                  ClickAction rightClick, String openGui, boolean closeMenu) {
    }

    public record PlotSlotRange(int from, int to, String name, List<String> lore, Material material,
                                 ClickAction leftClick, ClickAction rightClick, String openGui, boolean closeMenu) {
        public int size() {
            return to - from + 1;
        }
    }

    /** One named menu, e.g. the plot list ("plotgui") or the per-plot editor ("editPlot"). */
    public record Menu(String title, int size, List<SlotDefinition> fixedSlots, PlotSlotRange plotRange) {
    }

    private final Map<String, Menu> menus;

    private GuiConfig(Map<String, Menu> menus) {
        this.menus = menus;
    }

    /** Null if no menu with that name is defined in gui.yml. */
    public Menu menu(String name) {
        return menus.get(name);
    }

    public static GuiConfig load(YamlConfiguration config, Logger log) {
        Map<String, Menu> menus = new LinkedHashMap<>();
        for (String menuName : config.getKeys(false)) {
            ConfigurationSection root = config.getConfigurationSection(menuName);
            if (root == null) {
                continue;
            }
            menus.put(menuName, loadMenu(menuName, root, log));
        }
        if (menus.isEmpty()) {
            throw new IllegalArgumentException("gui.yml defines no menus");
        }
        return new GuiConfig(menus);
    }

    private static Menu loadMenu(String menuName, ConfigurationSection root, Logger log) {
        String title = root.getString("titel", "&7Menu");
        int size = clampSize(root.getInt("size", 27), log);

        ConfigurationSection slots = root.getConfigurationSection("slots");
        if (slots == null) {
            throw new IllegalArgumentException("gui.yml menu '" + menuName + "' is missing 'slots'");
        }

        List<SlotDefinition> fixedSlots = new ArrayList<>();
        PlotSlotRange plotRange = null;
        for (String key : slots.getKeys(false)) {
            ConfigurationSection entry = slots.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            if (key.equals("slot-range-auto")) {
                plotRange = parseRange(entry, log);
                continue;
            }
            Integer slotIndex = parseSlotIndex(key, log);
            if (slotIndex == null) {
                continue;
            }
            fixedSlots.add(parseSlot(slotIndex, entry, log, menuName + "." + key));
        }

        return new Menu(title, size, List.copyOf(fixedSlots), plotRange);
    }

    private static SlotDefinition parseSlot(int slot, ConfigurationSection entry, Logger log, String context) {
        ConfigurationSection event = entry.getConfigurationSection("event");
        ConfigurationSection command = event == null ? null : event.getConfigurationSection("command");
        return new SlotDefinition(
                slot,
                entry.getString("name", ""),
                entry.getStringList("lore"),
                material(entry.getString("material", "STONE"), log, context),
                clickAction(command == null ? "" : command.getString("left_click", "")),
                clickAction(command == null ? "" : command.getString("right_click", "")),
                event == null ? "" : event.getString("open_gui", "").trim(),
                entry.getBoolean("close-menu", false)
        );
    }

    private static PlotSlotRange parseRange(ConfigurationSection entry, Logger log) {
        int from = entry.getInt("from", 0);
        int to = entry.getInt("to", from);
        if (to < from) {
            log.warning("gui.yml: slot-range-auto.to < from, tausche sie");
            int tmp = from;
            from = to;
            to = tmp;
        }
        ConfigurationSection event = entry.getConfigurationSection("event");
        ConfigurationSection command = event == null ? null : event.getConfigurationSection("command");
        return new PlotSlotRange(
                from,
                to,
                entry.getString("name", "&b%plot_number%. Plot"),
                entry.getStringList("lore"),
                material(entry.getString("material", "GRASS_BLOCK"), log, "slot-range-auto"),
                clickAction(command == null ? "JOIN_PLOT" : command.getString("left_click", "JOIN_PLOT")),
                clickAction(command == null ? "JOIN_PLOT" : command.getString("right_click", "JOIN_PLOT")),
                event == null ? "" : event.getString("open_gui", "").trim(),
                entry.getBoolean("close-menu", false)
        );
    }

    private static Integer parseSlotIndex(String key, Logger log) {
        if (!key.startsWith("slot-")) {
            return null;
        }
        try {
            return Integer.parseInt(key.substring("slot-".length()));
        } catch (NumberFormatException e) {
            log.warning("gui.yml: unbekannter Slot-Schluessel '" + key + "', wird ignoriert");
            return null;
        }
    }

    private static Material material(String name, Logger log, String context) {
        try {
            return Material.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("gui.yml: unbekanntes Material '" + name + "' bei " + context + ", nutze STONE");
            return Material.STONE;
        }
    }

    /** A blank value or one of the known keywords (NOOP, JOIN_PLOT, ...) is a Keyword; anything else is
     *  dispatched as a literal command (with or without a leading '/'). */
    private static ClickAction clickAction(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return new ClickAction.Keyword(ActionType.NOOP);
        }
        for (ActionType type : ActionType.values()) {
            if (type.name().equalsIgnoreCase(trimmed)) {
                return new ClickAction.Keyword(type);
            }
        }
        return new ClickAction.RawCommand(trimmed.startsWith("/") ? trimmed.substring(1) : trimmed);
    }

    private static int clampSize(int size, Logger log) {
        int rounded = Math.max(9, Math.min(54, (size + 8) / 9 * 9));
        if (rounded != size) {
            log.warning("gui.yml: size " + size + " ist kein Vielfaches von 9 (9-54), nutze " + rounded);
        }
        return rounded;
    }
}
