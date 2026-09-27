package net.clanimg.plotsGUI.messaging;

import net.clanimg.plotsGUI.config.GuiConfig.ActionType;
import net.clanimg.plotsGUI.config.Settings;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Carries a GUI action (join a plot, auto-create a plot, ...) from wherever the player clicked to the
 * server running PlotSquared. The pending action is stored durably (a key with a TTL) so it survives
 * the player's transfer and is picked up once they join, and announced over pub/sub so it can also run
 * immediately if the player already happens to be on that server.
 */
public final class RedisActionChannel implements AutoCloseable {

    public record PendingAction(ActionType type, String payload) {
        String encode() {
            return type.name() + "|" + (payload == null ? "" : payload);
        }

        static PendingAction decode(String raw) {
            int bar = raw.indexOf('|');
            if (bar < 0) {
                return new PendingAction(ActionType.valueOf(raw), "");
            }
            return new PendingAction(ActionType.valueOf(raw.substring(0, bar)), raw.substring(bar + 1));
        }
    }

    private final JedisPool pool;
    private final HostAndPort address;
    private final Settings.Redis config;
    private final String pendingPrefix;
    private final String channel;
    private final int pendingTtlSeconds;
    private final Logger log;

    public RedisActionChannel(Settings.Redis config, Logger log) {
        this.config = config;
        this.log = log;
        this.pendingPrefix = config.keyPrefix() + ":pending:";
        this.channel = config.keyPrefix() + ":actions";
        this.pendingTtlSeconds = config.pendingActionTtlSeconds();
        this.address = new HostAndPort(config.host(), config.port());

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.poolSize());
        poolConfig.setMaxIdle(config.poolSize());
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        this.pool = new JedisPool(poolConfig, address, clientConfig(config.timeoutMillis()));
    }

    private JedisClientConfig clientConfig(int socketTimeoutMillis) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.timeoutMillis())
                .socketTimeoutMillis(socketTimeoutMillis)
                .database(config.database())
                .ssl(config.ssl());
        if (!config.username().isBlank()) {
            builder.user(config.username());
        }
        if (!config.password().isBlank()) {
            builder.password(config.password());
        }
        return builder.build();
    }

    /** Throws {@link RedisActionException} if Redis is unreachable or rejects the credentials. */
    public void ping() {
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
        } catch (JedisException e) {
            throw new RedisActionException("Cannot reach Redis at " + address, e);
        }
    }

    public void queueAction(UUID player, ActionType type, String payload) {
        String key = pendingPrefix + player;
        String encoded = new PendingAction(type, payload).encode();
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(key, pendingTtlSeconds, encoded);
            jedis.publish(channel, player + "|" + encoded);
        } catch (JedisException e) {
            throw new RedisActionException("Could not queue action for " + player, e);
        }
    }

    /** Atomically reads and clears the pending action for a player, if any. */
    public Optional<PendingAction> consumePending(UUID player) {
        String key = pendingPrefix + player;
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.getDel(key);
            return value == null ? Optional.empty() : Optional.of(PendingAction.decode(value));
        } catch (JedisException e) {
            throw new RedisActionException("Could not read pending action for " + player, e);
        }
    }

    public AutoCloseable subscribe(BiConsumer<UUID, PendingAction> listener) {
        Subscriber subscriber = new Subscriber(listener);
        subscriber.start();
        return subscriber;
    }

    @Override
    public void close() {
        pool.close();
    }

    /** Owns a dedicated connection and keeps re-subscribing until closed, mirroring CrossClipboard's RedisBackend. */
    private final class Subscriber extends JedisPubSub implements AutoCloseable {

        private final BiConsumer<UUID, PendingAction> listener;
        private final Thread thread = new Thread(this::run, "PlotsGUI-ActionSubscriber");
        private volatile boolean running = true;
        private volatile Jedis connection;

        Subscriber(BiConsumer<UUID, PendingAction> listener) {
            this.listener = listener;
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        private void run() {
            while (running) {
                try (Jedis jedis = new Jedis(address, clientConfig(30_000))) {
                    connection = jedis;
                    jedis.subscribe(this, channel);
                } catch (Exception e) {
                    if (running) {
                        log.log(Level.WARNING, "Redis-Subscription verloren, erneuter Versuch in 2s: " + e.getMessage());
                        pause(2000);
                    }
                } finally {
                    connection = null;
                }
            }
        }

        @Override
        public void onMessage(String ignoredChannel, String message) {
            int bar = message.indexOf('|');
            if (bar < 0) {
                return;
            }
            try {
                UUID player = UUID.fromString(message.substring(0, bar));
                PendingAction action = PendingAction.decode(message.substring(bar + 1));
                listener.accept(player, action);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Ungueltige Action-Nachricht ignoriert: " + message, e);
            }
        }

        private static void pause(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            running = false;
            Jedis current = connection;
            if (current != null) {
                current.close();
            }
            thread.interrupt();
        }
    }
}
