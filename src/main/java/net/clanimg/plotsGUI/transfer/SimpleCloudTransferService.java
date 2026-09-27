package net.clanimg.plotsGUI.transfer;

import net.clanimg.plotsGUI.config.Settings;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Transfers a player to another SimpleCloud-managed server, mirroring WorldsGUI's connectPlayerToWorldServer. */
public final class SimpleCloudTransferService {

    public record Result(boolean success, boolean alreadyOnTarget, String errorMessage) {
        static Result ok() {
            return new Result(true, false, null);
        }

        static Result alreadyThere() {
            return new Result(true, true, null);
        }

        static Result failed(String message) {
            return new Result(false, false, message);
        }
    }

    private static final Pattern SUCCESS_FIELD = Pattern.compile("\"success\"\\s*:\\s*true");
    private static final Pattern MESSAGE_FIELD = Pattern.compile("\"(?:message|error)\"\\s*:\\s*\"([^\"]*)\"");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final Settings.SimpleCloud config;

    public SimpleCloudTransferService(Settings.SimpleCloud config) {
        this.config = config;
    }

    public boolean isConfigured() {
        return !config.controllerUrl().isBlank() && !config.networkId().isBlank() && !config.networkSecret().isBlank();
    }

    /** Blocking HTTP call - always run off the main thread. */
    public Result transfer(UUID player, String targetServer) {
        if (!isConfigured()) {
            return Result.failed("simplecloud is not configured");
        }
        String controller = config.controllerUrl().endsWith("/")
                ? config.controllerUrl().substring(0, config.controllerUrl().length() - 1)
                : config.controllerUrl();
        String playerId = URLEncoder.encode(player.toString(), StandardCharsets.UTF_8);
        String endpoint = controller + "/v0/players/connect?player_id=" + playerId;
        String payload = "{\"server_id\":\"" + targetServer + "\"}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("X-Network-ID", config.networkId())
                    .header("X-Network-Secret", config.networkSecret())
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() / 100 == 2 && SUCCESS_FIELD.matcher(body).find()) {
                return Result.ok();
            }
            String detail = extractDetail(body);
            if (detail == null || detail.isBlank()) {
                detail = "HTTP " + response.statusCode();
            }
            String normalized = detail.toLowerCase(Locale.ROOT);
            if (normalized.contains("already connected") && normalized.contains("this server")) {
                return Result.alreadyThere();
            }
            return Result.failed(detail);
        } catch (Exception e) {
            return Result.failed(e.getMessage());
        }
    }

    private static String extractDetail(String body) {
        Matcher matcher = MESSAGE_FIELD.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }
}
