package net.clanimg.plotsGUI.cache;

import java.util.List;
import java.util.UUID;

public record CachedPlot(
        UUID ownerUuid,
        String area,
        String plotId,
        int plotNumber,
        List<String> trusted,
        List<String> members,
        long createdAtMillis
) {
}
