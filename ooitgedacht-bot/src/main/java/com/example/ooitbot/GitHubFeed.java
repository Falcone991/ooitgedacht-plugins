package com.example.ooitbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Plaatst nieuwe commits van een GitHub-repository in een Discord-kanaal.
 *
 * Kijkt elke paar minuten via de GitHub-API naar de laatste commits op één
 * branch. De laatst geziene commit staat in data.json, zodat er na een herstart
 * niets dubbel komt. Bij de allereerste keer wordt alleen onthouden waar we
 * zijn; er komt dan nog geen bericht.
 *
 * Zonder token mag je 60 API-verzoeken per uur doen. Antwoorden "niets
 * veranderd" (HTTP 304, via ETag) tellen daar niet voor mee.
 */
final class GitHubFeed {

    private static final int COLOR = 0x24292F;
    private static final int MAX_SHOWN = 10;

    private final Bot bot;
    private final String channelId;
    private final String repo;
    private final String branch;
    private final String token;
    private final int intervalSeconds;
    private final String feedKey;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Bot.daemon("ooitbot-github"));

    private String etag = null;      // alleen op de github-thread
    private String lastWarning = null;

    GitHubFeed(Bot bot, String channelId, String repo, String branch, String token, int intervalSeconds) {
        this.bot = bot;
        this.channelId = channelId;
        this.repo = repo;
        this.branch = branch;
        this.token = token;
        this.intervalSeconds = intervalSeconds;
        this.feedKey = repo + "@" + branch;
    }

    String channelId() {
        return channelId;
    }

    void start() {
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            Log.warn("github.repo moet de vorm 'eigenaar/naam' hebben (bv. Falcone991/ooitgedacht-plugins). GitHub-feed staat uit.");
            return;
        }
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                check();
            } catch (Throwable t) {
                warnOnce("Fout bij GitHub-feed: " + t);
            }
        }, 5, intervalSeconds, TimeUnit.SECONDS);
        Log.info("GitHub-feed actief voor " + feedKey + " (elke " + intervalSeconds + " s).");
    }

    void shutdown() {
        scheduler.shutdownNow();
    }

    private void check() throws IOException, InterruptedException {
        TextChannel channel = bot.channel(channelId);
        if (channel == null) return; // onReady waarschuwt al

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo
                        + "/commits?per_page=30&sha=" + URLEncoder.encode(branch, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ooitgedacht-bot");
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        if (etag != null) request.header("If-None-Match", etag);

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code == 304) return;
        if (code == 404 || code == 409) {
            warnOnce("GitHub-repository '" + repo + "' of branch '" + branch + "' niet gevonden (of leeg/privé zonder token).");
            return;
        }
        if (code == 401) {
            warnOnce("GitHub weigert de token (github.token). Klopt hij nog?");
            return;
        }
        if (code == 403 || code == 429) {
            warnOnce("GitHub-limiet bereikt; probeer later opnieuw. Tip: verhoog github.check-interval-seconds of vul github.token in.");
            return;
        }
        if (code != 200) {
            warnOnce("GitHub gaf HTTP " + code + " terug.");
            return;
        }
        lastWarning = null;
        etag = response.headers().firstValue("ETag").orElse(null);

        JsonNode commits = mapper.readTree(response.body());
        if (!commits.isArray() || commits.isEmpty()) return;
        String headSha = commits.get(0).path("sha").asText();

        String lastSha = feedKey.equals(bot.data.text("github-feed")) ? bot.data.text("github-last-sha") : null;
        if (headSha.equals(lastSha)) return;

        if (lastSha != null) {
            // Nieuwste staat bovenaan; verzamelen tot de laatst geziene commit.
            List<JsonNode> fresh = new ArrayList<>();
            boolean foundLast = false;
            for (JsonNode commit : commits) {
                if (commit.path("sha").asText().equals(lastSha)) {
                    foundLast = true;
                    break;
                }
                fresh.add(commit);
            }
            post(channel, fresh, foundLast ? lastSha : null);
        } else {
            Log.info("GitHub-feed: begonnen bij commit " + headSha.substring(0, 7) + " op " + feedKey + ".");
        }

        bot.data.putText("github-feed", feedKey);
        bot.data.putText("github-last-sha", headSha);
        bot.data.save();
    }

    /** @param previousSha vorige bekende commit (voor de vergelijk-link), of null als die niet meer te vinden is. */
    private void post(TextChannel channel, List<JsonNode> fresh, String previousSha) {
        if (fresh.isEmpty()) return;
        String headSha = fresh.get(0).path("sha").asText();
        Collections.reverse(fresh); // oud -> nieuw, zoals bij een push

        String repoName = repo.substring(repo.indexOf('/') + 1);
        String title = "[" + repoName + ":" + branch + "] " + fresh.size()
                + (fresh.size() == 1 ? " nieuwe commit" : " nieuwe commits");
        if (previousSha == null) title += " (of meer)";
        String url = fresh.size() == 1 || previousSha == null
                ? "https://github.com/" + repo + "/commit/" + headSha
                : "https://github.com/" + repo + "/compare/" + previousSha.substring(0, 12) + "..." + headSha.substring(0, 12);

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, fresh.size() - MAX_SHOWN);
        if (start > 0) sb.append("*… en ").append(start).append(" eerdere*\n");
        for (int i = start; i < fresh.size(); i++) {
            JsonNode c = fresh.get(i);
            String sha = c.path("sha").asText();
            String message = c.path("commit").path("message").asText("").split("\\R", 2)[0].trim();
            sb.append("[`").append(sha, 0, 7).append("`](").append(c.path("html_url").asText()).append(") ")
                    .append(Bot.cut(MarkdownSanitizer.escape(message), 120))
                    .append(" — ").append(MarkdownSanitizer.escape(authorName(c))).append("\n");
        }

        JsonNode newest = fresh.get(fresh.size() - 1);
        EmbedBuilder eb = bot.embed(COLOR, null, sb.toString());
        eb.setTitle(Bot.cut(title, 256), url);
        JsonNode author = newest.path("author");
        if (author.hasNonNull("login")) {
            eb.setAuthor(author.path("login").asText(), author.path("html_url").asText(null), author.path("avatar_url").asText(null));
        }
        String date = newest.path("commit").path("committer").path("date").asText(null);
        if (date != null) {
            try {
                eb.setTimestamp(Instant.parse(date));
            } catch (Exception ignored) {
                // tijdstip van nu blijft staan
            }
        }

        channel.sendMessageEmbeds(eb.build())
                .queue(null, err -> Log.warn("Kon GitHub-update niet naar Discord sturen: " + err.getMessage()));
    }

    private static String authorName(JsonNode commit) {
        JsonNode login = commit.path("author").get("login");
        if (login != null && !login.isNull()) return login.asText();
        return commit.path("commit").path("author").path("name").asText("onbekend");
    }

    /** Zelfde fout niet elke paar minuten opnieuw in de console. */
    private void warnOnce(String message) {
        if (message.equals(lastWarning)) return;
        lastWarning = message;
        Log.warn(message);
    }
}
