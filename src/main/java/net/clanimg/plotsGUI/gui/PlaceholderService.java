package net.clanimg.plotsGUI.gui;

import net.clanimg.plotsGUI.cache.CachedPlot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class PlaceholderService {

    private final DateTimeFormatter createdFormat;

    public PlaceholderService(String timezone) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (Exception e) {
            zone = ZoneId.of("Europe/Berlin");
        }
        this.createdFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy 'um' HH:mm").withZone(zone);
    }

    public String apply(String text, CachedPlot plot) {
        if (text == null) {
            return "";
        }
        return text
                .replace("%plot_number%", String.valueOf(plot.plotNumber()))
                .replace("%plot_id%", plot.plotId())
                .replace("%plot_trusted%", join(plot.trusted()))
                .replace("%plot_members%", join(plot.members()))
                .replace("%plot_created%", createdFormat.format(Instant.ofEpochMilli(plot.createdAtMillis())));
    }

    public List<String> apply(List<String> lines, CachedPlot plot) {
        return lines.stream().map(line -> apply(line, plot)).toList();
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "-" : String.join(", ", values);
    }
}
