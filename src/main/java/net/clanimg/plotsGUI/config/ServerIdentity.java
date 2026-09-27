package net.clanimg.plotsGUI.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.logging.Logger;

/** Resolves this server's SimpleCloud identity, mirroring WorldsGUI's local-server-name resolution. */
public final class ServerIdentity {

    private static final List<String> ENV_KEYS = List.of(
            "SIMPLECLOUD_SERVER_NAME",
            "SIMPLECLOUD_SERVER_ID",
            "SIMPLECLOUD_SERVICE_NAME",
            "SIMPLECLOUD_SERVICE_ID",
            "CLOUDNET_SERVICE_ID",
            "CLOUDNET_SERVICE_NAME"
    );

    private ServerIdentity() {
    }

    public static String resolve(String configuredOverride, Logger log) {
        if (configuredOverride != null && !configuredOverride.isBlank()) {
            return configuredOverride.trim();
        }
        try {
            String fromApi = SimpleCloudIdentity.resolveBlocking();
            if (fromApi != null && !fromApi.isBlank()) {
                return fromApi.trim();
            }
        } catch (Throwable e) {
            // Throwable on purpose: a missing simplecloud-api plugin surfaces as a linkage error
            // (NoClassDefFoundError), not a checked exception, and that API is compileOnly here.
            log.info("PlotsGUI: SimpleCloud-API-Erkennung nicht verfuegbar (" + e.getMessage()
                    + "), falle zurueck auf ENV/Hostname.");
        }
        for (String key : ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isBlank()) {
                log.warning("Konnte keine lokale Server-ID aus Config/ENV ermitteln, nutze Hostname '" + hostname
                        + "'. Setze local-server-name oder SIMPLECLOUD_SERVER_NAME/SIMPLECLOUD_SERVER_ID fuer"
                        + " servergenaue Erkennung.");
                return hostname.trim();
            }
        } catch (UnknownHostException ignored) {
            // fall through to the warning below
        }
        log.warning("Konnte keine lokale Server-ID ermitteln. Setze local-server-name oder"
                + " SIMPLECLOUD_SERVER_NAME/SIMPLECLOUD_SERVER_ID.");
        return "";
    }
}
