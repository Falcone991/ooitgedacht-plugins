package com.example.ooitbot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.RichPresence;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * "Nu live": ziet via Discord wie er op Twitch streamt (Twitch gekoppeld aan
 * het Discord-account), stuurt dan één bericht (zonder pings) en geeft de
 * live-rol; na het stoppen gaat de rol weer weg.
 *
 * Werkt met een rondje langs alle leden elke paar seconden plus een snelle
 * check bij elke activiteit-wijziging. Wie even uit beeld valt (Discord laat de
 * stream soms kort wegvallen) telt pas na een korte wachttijd als gestopt, zodat
 * er geen dubbele berichten komen. Wie live is staat in data.json, dus na een
 * herstart van de bot komt er ook geen dubbel bericht.
 */
final class TwitchLive {

    private final Bot bot;
    private final String channelId;
    private final String roleId;
    private final String onlyRoleId;
    private final String message;
    private final long endGraceMs;

    private final Set<String> live = new LinkedHashSet<>();     // aangekondigd en nog live
    private final Map<String, Long> lastSeen = new HashMap<>(); // laatst gezien als streamend

    TwitchLive(Bot bot, String channelId, String roleId, String onlyRoleId, String message, int endGraceSeconds) {
        this.bot = bot;
        this.channelId = channelId;
        this.roleId = roleId;
        this.onlyRoleId = onlyRoleId;
        this.message = message;
        this.endGraceMs = endGraceSeconds * 1000L;
        String saved = bot.data.text("twitch-live");
        if (saved != null) {
            long now = System.currentTimeMillis();
            for (String id : saved.split(",")) {
                if (!id.isBlank()) {
                    live.add(id.trim());
                    lastSeen.put(id.trim(), now);
                }
            }
        }
    }

    String channelId() {
        return channelId;
    }

    String roleId() {
        return roleId;
    }

    /** Alle leden langs: nieuwe streams aankondigen, gestopte streams afronden, rollen rechtzetten. */
    synchronized void scan(JDA jda, String guildId) {
        Guild guild = guildId.isEmpty() ? null : jda.getGuildById(guildId);
        if (guild == null) return;
        long now = System.currentTimeMillis();

        for (Member member : guild.getMembers()) {
            Activity stream = twitchStream(member);
            if (stream != null) started(guild, member, stream, now);
        }

        boolean changed = false;
        for (String id : new LinkedHashSet<>(live)) {
            Member member = guild.getMemberById(id);
            if (member != null && twitchStream(member) != null) continue;
            if (now - lastSeen.getOrDefault(id, 0L) < endGraceMs) continue;
            live.remove(id);
            lastSeen.remove(id);
            changed = true;
            if (member != null) setRole(guild, member, false);
            Log.info("Twitch: " + (member != null ? member.getEffectiveName() : id) + " is gestopt met streamen.");
        }
        if (changed) save();

        // Opruimen: wie de live-rol heeft maar niet (meer) live is, bv. na een storing.
        Role role = role(guild);
        if (role != null) {
            for (Member member : guild.getMembersWithRoles(role)) {
                if (!live.contains(member.getId())) setRole(guild, member, false);
            }
        }
    }

    private void started(Guild guild, Member member, Activity stream, long now) {
        String id = member.getId();
        lastSeen.put(id, now);
        if (live.contains(id)) {
            if (!hasRole(member)) setRole(guild, member, true);
            return;
        }
        live.add(id);
        save();
        setRole(guild, member, true);
        announce(member, stream);
        Log.info("Twitch: " + member.getEffectiveName() + " is live: " + stream.getUrl());
    }

    private void announce(Member member, Activity stream) {
        TextChannel channel = bot.channel(channelId);
        if (channel == null) return;
        String title = "";
        String game = "";
        RichPresence rich = stream.asRichPresence();
        if (rich != null) {
            if (rich.getDetails() != null) title = rich.getDetails();
            if (rich.getState() != null) game = rich.getState();
        }
        if (title.isEmpty() && !stream.getName().equalsIgnoreCase("twitch")) title = stream.getName();

        String text = message
                .replace("{naam}", MarkdownSanitizer.escape(member.getEffectiveName()))
                .replace("{titel}", MarkdownSanitizer.escape(title))
                .replace("{spel}", MarkdownSanitizer.escape(game))
                .replace("{link}", stream.getUrl() == null ? "" : stream.getUrl())
                .replace("\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        // Nooit iemand pingen, ook niet als er @everyone in de tekst staat.
        channel.sendMessage(Bot.cut(text, 2000))
                .setAllowedMentions(Collections.emptyList())
                .queue(null, err -> Log.warn("Kon live-bericht niet sturen: " + err.getMessage()));
    }

    /** De Twitch-stream van dit lid, of null als die niet live is (of niet mee mag doen). */
    private Activity twitchStream(Member member) {
        if (member.getUser().isBot()) return null;
        if (!onlyRoleId.isEmpty() && member.getRoles().stream().noneMatch(r -> r.getId().equals(onlyRoleId))) return null;
        for (Activity activity : member.getActivities()) {
            if (activity.getType() == Activity.ActivityType.STREAMING
                    && activity.getUrl() != null && activity.getUrl().contains("twitch.tv")) {
                return activity;
            }
        }
        return null;
    }

    private Role role(Guild guild) {
        if (roleId.isEmpty()) return null;
        try {
            return guild.getRoleById(roleId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean hasRole(Member member) {
        return roleId.isEmpty() || member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId));
    }

    private void setRole(Guild guild, Member member, boolean give) {
        Role role = role(guild);
        if (role == null) return;
        boolean has = member.getRoles().contains(role);
        if (give == has) return;
        (give ? guild.addRoleToMember(member, role) : guild.removeRoleFromMember(member, role))
                .reason(give ? "Live op Twitch" : "Gestopt met streamen")
                .queue(null, err -> Log.warn("Kon live-rol niet " + (give ? "geven aan " : "weghalen bij ")
                        + member.getEffectiveName() + ": " + err.getMessage()
                        + " (staat de rol van de bot boven de live-rol en heeft hij 'Rollen beheren'?)"));
    }

    private void save() {
        bot.data.putText("twitch-live", String.join(",", live));
        bot.data.save();
    }
}
