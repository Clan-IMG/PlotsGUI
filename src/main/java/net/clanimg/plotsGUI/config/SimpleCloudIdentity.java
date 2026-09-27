package net.clanimg.plotsGUI.config;

import app.simplecloud.api.CloudApi;
import app.simplecloud.api.server.Server;

import java.util.concurrent.TimeUnit;

/**
 * Isolated in its own class so a missing or misbehaving simplecloud-api platform plugin - it's
 * compileOnly, and its own README calls the Paper integration "experimental and unstable" - can never
 * break PlotsGUI itself. {@link ServerIdentity} only ever calls {@link #resolveBlocking()} from inside
 * a {@code catch (Throwable)}, since a missing runtime class surfaces as a linkage error, not a checked
 * exception.
 */
final class SimpleCloudIdentity {

    private SimpleCloudIdentity() {
    }

    /** Blocks for up to 5 seconds; only ever called once, during plugin startup. */
    static String resolveBlocking() throws Exception {
        try (CloudApi api = CloudApi.create()) {
            Server current = api.server().getCurrentServer().get(5, TimeUnit.SECONDS);
            return current.getServerBase().getName();
        }
    }
}
