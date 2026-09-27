package net.clanimg.plotsGUI.config;

import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;

/** Immutable snapshot of config.yml. */
public record Settings(
        String plotsquaredServerName,
        String localServerNameOverride,
        Database database,
        Redis redis,
        SimpleCloud simpleCloud,
        String displayTimezone
) {

    public record Database(
            boolean enabled,
            String host,
            int port,
            String name,
            String username,
            String password,
            int poolSize,
            int timeoutMillis,
            String tablePrefix
    ) {
    }

    public record Redis(
            String host,
            int port,
            String username,
            String password,
            int database,
            boolean ssl,
            int timeoutMillis,
            int poolSize,
            String keyPrefix,
            int pendingActionTtlSeconds
    ) {
    }

    public record SimpleCloud(
            String controllerUrl,
            String networkId,
            String networkSecret
    ) {
    }

    public static Settings load(ConfigurationSection config) {
        return new Settings(
                config.getString("plotsquared_server-name", "").trim(),
                config.getString("local-server-name", "").trim(),
                database(section(config, "database")),
                redis(section(config, "redis")),
                simpleCloud(section(config, "simplecloud")),
                config.getString("display-timezone", "Europe/Berlin").trim()
        );
    }

    private static ConfigurationSection section(ConfigurationSection config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section == null ? config.createSection(path) : section;
    }

    private static Database database(ConfigurationSection section) {
        return new Database(
                section.getBoolean("enable", true),
                section.getString("host", "127.0.0.1"),
                section.getInt("port", 3306),
                section.getString("database", "pgui"),
                section.getString("username", "pgui"),
                section.getString("password", ""),
                Math.clamp(section.getInt("pool-size", 6), 1, 32),
                Math.max(500, section.getInt("timeout-ms", 3000)),
                section.getString("table-prefix", "plotsgui_")
        );
    }

    private static Redis redis(ConfigurationSection section) {
        int timeout = Math.max(500, section.getInt("timeout-ms", 3000));
        int pool = Math.clamp(section.getInt("pool-size", 6), 2, 64);
        int pendingTtl = Math.clamp(section.getInt("pending-action-ttl-seconds", 120), 10, 3600);
        String url = section.getString("url", "").trim();
        if (!url.isEmpty()) {
            return fromUrl(url, timeout, pool, pendingTtl, section.getString("key-prefix", "plotsgui"));
        }
        return new Redis(
                section.getString("host", "127.0.0.1"),
                section.getInt("port", 6379),
                section.getString("username", ""),
                section.getString("password", ""),
                Math.max(0, section.getInt("database", 0)),
                section.getBoolean("ssl", false),
                timeout,
                pool,
                section.getString("key-prefix", "plotsgui"),
                pendingTtl
        );
    }

    /** Accepts {@code redis://[user:password@]host[:port][/db]} and {@code rediss://} for TLS, as Coolify prints it. */
    private static Redis fromUrl(String url, int timeout, int pool, int pendingTtl, String keyPrefix) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("redis") || scheme.equals("rediss"))) {
            throw new IllegalArgumentException("redis.url must start with redis:// or rediss://");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("redis.url has no host");
        }

        String username = "";
        String password = "";
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                password = userInfo;
            } else {
                username = userInfo.substring(0, colon);
                password = userInfo.substring(colon + 1);
            }
        }

        int database = 0;
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            database = Integer.parseInt(path.substring(1));
        }

        return new Redis(
                uri.getHost(),
                uri.getPort() == -1 ? 6379 : uri.getPort(),
                username,
                password,
                database,
                scheme.equals("rediss"),
                timeout,
                pool,
                keyPrefix,
                pendingTtl
        );
    }

    private static SimpleCloud simpleCloud(ConfigurationSection section) {
        return new SimpleCloud(
                section.getString("controller-url", "").trim(),
                section.getString("network-id", "").trim(),
                section.getString("network-secret", "").trim()
        );
    }
}
