package com.example.ooitbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.utils.FileUpload;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Ticketsysteem.
 *
 *  - /ticket paneel (beheer): plaatst in dit kanaal een bericht met de knop "Ticket openen".
 *  - Speler klikt, vult kort in waar het over gaat, en krijgt een eigen privé-kanaal
 *    (alleen die speler, beheer en de bot zien het). Eén open ticket per speler.
 *  - Knop "Sluiten" (of /ticket sluiten): na bevestigen gaat er een kopie van het gesprek
 *    (tekstbestand) naar het log-kanaal en wordt het kanaal verwijderd.
 *
 * Open tickets staan in data.json onder "tickets" (kanaal-ID -> eigenaar, onderwerp, tijd).
 */
final class Tickets {

    private static final int COLOR = 0x5865F2;
    private static final int COLOR_CLOSED = 0x95A5A6;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(ZoneId.of("Europe/Amsterdam"));

    private static final EnumSet<Permission> MEMBER_ALLOW = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
            Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS);

    private final Bot bot;

    Tickets(Bot bot) {
        this.bot = bot;
    }

    static SlashCommandData command() {
        return Commands.slash("ticket", "Tickets: hulp vragen aan beheer.")
                .addSubcommands(
                        new SubcommandData("paneel", "Beheer: plaats in dit kanaal het bericht met de knop 'Ticket openen'."),
                        new SubcommandData("sluiten", "Sluit het ticket waar je nu in zit."));
    }

    // ---------- Commando's ----------

    void handle(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName() == null ? "" : event.getSubcommandName();
        if (sub.equals("paneel")) {
            if (!bot.isStaff(event.getMember())) {
                event.reply(":x: Alleen beheer kan het ticket-paneel plaatsen.").setEphemeral(true).queue();
                return;
            }
            EmbedBuilder eb = bot.embed(COLOR, ":ticket: Hulp nodig?",
                    "Heb je een vraag, een probleem of wil je iemand melden?\n"
                            + "Klik op de knop hieronder. Je krijgt dan een **eigen privé-kanaal** met het beheer.");
            event.getChannel().sendMessageEmbeds(eb.build())
                    .addActionRow(Button.primary("ticket:open", "Ticket openen").withEmoji(Emoji.fromUnicode("🎫")))
                    .queue(ok -> event.reply(":white_check_mark: Ticket-paneel geplaatst.").setEphemeral(true).queue(),
                            err -> event.reply(":x: Kon het paneel niet plaatsen: " + err.getMessage()).setEphemeral(true).queue());
        } else if (sub.equals("sluiten")) {
            askClose(event.getChannel().getId(), event.getMember(), event::reply);
        }
    }

    // ---------- Knoppen ----------

    /** @return true als dit een ticket-knop was. */
    boolean handleButton(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("ticket:")) return false;
        switch (id) {
            case "ticket:open":
                open(event);
                break;
            case "ticket:close":
                askClose(event.getChannel().getId(), event.getMember(), event::reply);
                break;
            case "ticket:confirm":
                close(event);
                break;
            default:
                break;
        }
        return true;
    }

    private void open(ButtonInteractionEvent event) {
        String existing = openTicketOf(event.getUser().getId(), event.getGuild());
        if (existing != null) {
            event.reply(":information_source: Je hebt al een open ticket: <#" + existing + ">").setEphemeral(true).queue();
            return;
        }
        TextInput subject = TextInput.create("onderwerp", "Waar gaat het over?", TextInputStyle.PARAGRAPH)
                .setPlaceholder("Bv. mijn spullen zijn weg, iemand griefde mijn huis, ik heb een vraag over ...")
                .setRequired(true).setMinLength(3).setMaxLength(1000).build();
        event.replyModal(Modal.create("ticket:new:" + event.getChannel().getId(), "Ticket openen")
                .addActionRow(subject).build()).queue();
    }

    /** @return true als dit het ticket-formulier was. */
    boolean handleModal(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("ticket:new:")) return false;
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) {
            event.reply(":x: Dit werkt alleen in de server.").setEphemeral(true).queue();
            return true;
        }
        if (openTicketOf(member.getId(), guild) != null) {
            event.reply(":information_source: Je hebt al een open ticket.").setEphemeral(true).queue();
            return true;
        }
        ModalMapping mapping = event.getValue("onderwerp");
        String subject = mapping == null ? "-" : mapping.getAsString().trim();

        // Categorie: uit de config, anders dezelfde als het kanaal met het paneel.
        Category category = null;
        String categoryId = bot.config.id("bot.ticket-category-id");
        if (!categoryId.isEmpty()) category = guild.getCategoryById(categoryId);
        if (category == null) {
            TextChannel panel = guild.getTextChannelById(event.getModalId().substring("ticket:new:".length()));
            if (panel != null) category = panel.getParentCategory();
        }

        String name = "ticket-" + member.getUser().getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (name.equals("ticket-")) name = "ticket-" + member.getId();

        event.deferReply(true).queue();
        var action = guild.createTextChannel(name.length() > 90 ? name.substring(0, 90) : name, category)
                .setTopic("Ticket van " + member.getUser().getName() + ": " + Bot.cut(subject, 900))
                .addRolePermissionOverride(guild.getPublicRole().getIdLong(), null, EnumSet.of(Permission.VIEW_CHANNEL))
                .addMemberPermissionOverride(member.getIdLong(), MEMBER_ALLOW, null)
                .addMemberPermissionOverride(guild.getSelfMember().getIdLong(),
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
                                Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ATTACH_FILES, Permission.MANAGE_CHANNEL), null);
        String staffRole = bot.staffRoleId();
        if (!staffRole.isEmpty()) action = action.addRolePermissionOverride(Long.parseLong(staffRole), MEMBER_ALLOW, null);

        action.queue(channel -> {
            synchronized (bot.data) {
                ObjectNode t = bot.data.object("tickets").putObject(channel.getId());
                t.put("user", member.getId());
                t.put("name", member.getUser().getName());
                t.put("subject", subject);
                t.put("opened", System.currentTimeMillis());
            }
            bot.data.save();

            EmbedBuilder eb = bot.embed(COLOR, ":ticket: Ticket van " + member.getUser().getName(),
                    "**Onderwerp:**\n" + Bot.cut(subject, 1000)
                            + "\n\nHet beheer kijkt zo snel mogelijk. Vertel hier gerust meer, en stuur screenshots als dat helpt."
                            + "\nKlaar? Klik op **Sluiten**.");
            String ping = member.getAsMention() + (staffRole.isEmpty() ? "" : " <@&" + staffRole + ">");
            channel.sendMessage(ping).setEmbeds(eb.build())
                    .addActionRow(Button.danger("ticket:close", "Sluiten").withEmoji(Emoji.fromUnicode("🔒")))
                    .queue();
            event.getHook().editOriginal(":white_check_mark: Je ticket is aangemaakt: " + channel.getAsMention()).queue();
            Log.info("Ticket geopend door " + member.getUser().getName() + ": #" + channel.getName());
        }, err -> {
            Log.warn("Kon ticket-kanaal niet maken: " + err.getMessage());
            event.getHook().editOriginal(":x: Kon je ticket niet aanmaken. Laat het beheer weten dat de bot de rechten"
                    + " 'Kanalen beheren' en 'Rollen beheren' nodig heeft.").queue();
        });
        return true;
    }

    // ---------- Sluiten ----------

    private interface Replier {
        net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction reply(String text);
    }

    private void askClose(String channelId, Member member, Replier replier) {
        JsonNode ticket = ticket(channelId);
        if (ticket == null) {
            replier.reply(":x: Dit is geen ticket-kanaal.").setEphemeral(true).queue();
            return;
        }
        if (!mayClose(ticket, member)) {
            replier.reply(":x: Alleen de maker van het ticket of beheer kan het sluiten.").setEphemeral(true).queue();
            return;
        }
        replier.reply("Weet je zeker dat je dit ticket wilt sluiten?").setEphemeral(true)
                .addActionRow(Button.danger("ticket:confirm", "Ja, sluiten")).queue();
    }

    private void close(ButtonInteractionEvent event) {
        JsonNode ticket = ticket(event.getChannel().getId());
        if (ticket == null || !mayClose(ticket, event.getMember())) {
            event.reply(":x: Dit ticket kan je niet sluiten.").setEphemeral(true).queue();
            return;
        }
        TextChannel channel = event.getGuild() == null ? null : event.getGuild().getTextChannelById(event.getChannel().getId());
        if (channel == null) return;
        event.reply(":lock: Ticket wordt gesloten...").queue();

        String closer = event.getUser().getName();
        channel.getIterableHistory().takeAsync(1000).thenAccept(messages -> {
            List<Message> list = new ArrayList<>(messages);
            Collections.reverse(list); // oud -> nieuw
            StringBuilder sb = new StringBuilder();
            sb.append("Ticket #").append(channel.getName()).append('\n')
                    .append("Van: ").append(ticket.path("name").asText()).append('\n')
                    .append("Onderwerp: ").append(ticket.path("subject").asText()).append('\n')
                    .append("Geopend: ").append(TIME.format(Instant.ofEpochMilli(ticket.path("opened").asLong()))).append('\n')
                    .append("Gesloten door: ").append(closer).append(" op ").append(TIME.format(Instant.now())).append("\n\n");
            for (Message m : list) {
                sb.append('[').append(TIME.format(m.getTimeCreated().toInstant())).append("] ")
                        .append(m.getAuthor().getName()).append(": ");
                String content = m.getContentDisplay();
                if (content.isEmpty() && !m.getEmbeds().isEmpty()) content = "(embed) " + nullToEmpty(m.getEmbeds().get(0).getTitle());
                sb.append(content);
                for (Message.Attachment a : m.getAttachments()) sb.append(" [bijlage: ").append(a.getUrl()).append(']');
                sb.append('\n');
            }

            TextChannel log = logChannel();
            if (log != null) {
                EmbedBuilder eb = bot.embed(COLOR_CLOSED, ":lock: Ticket gesloten: #" + channel.getName(), null)
                        .addField("Van", ticket.path("name").asText("-"), true)
                        .addField("Gesloten door", closer, true)
                        .addField("Onderwerp", Bot.cut(ticket.path("subject").asText("-"), 1024), false);
                log.sendMessageEmbeds(eb.build())
                        .addFiles(FileUpload.fromData(sb.toString().getBytes(StandardCharsets.UTF_8), channel.getName() + ".txt"))
                        .queue(null, err -> Log.warn("Kon ticket-kopie niet sturen: " + err.getMessage()));
            }
            removeTicket(channel.getId());
            channel.delete().queueAfter(5, TimeUnit.SECONDS, null,
                    err -> Log.warn("Kon ticket-kanaal niet verwijderen: " + err.getMessage()));
            Log.info("Ticket #" + channel.getName() + " gesloten door " + closer + ".");
        });
    }

    // ---------- Hulpjes ----------

    private boolean mayClose(JsonNode ticket, Member member) {
        return member != null && (ticket.path("user").asText().equals(member.getId()) || bot.isStaff(member));
    }

    private TextChannel logChannel() {
        String id = bot.config.id("bot.ticket-log-channel-id");
        if (id.isEmpty()) id = bot.config.id("bot.log-channel-id");
        return bot.channel(id);
    }

    private JsonNode ticket(String channelId) {
        JsonNode tickets = bot.data.node("tickets");
        return tickets == null ? null : tickets.get(channelId);
    }

    private void removeTicket(String channelId) {
        synchronized (bot.data) {
            bot.data.object("tickets").remove(channelId);
        }
        bot.data.save();
    }

    /** Open ticket van deze gebruiker (en ruimt tickets op waarvan het kanaal handmatig is verwijderd). */
    private String openTicketOf(String userId, Guild guild) {
        String found = null;
        boolean changed = false;
        synchronized (bot.data) {
            ObjectNode tickets = bot.data.object("tickets");
            Iterator<Map.Entry<String, JsonNode>> it = tickets.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (guild != null && guild.getTextChannelById(e.getKey()) == null) {
                    it.remove();
                    changed = true;
                    continue;
                }
                if (e.getValue().path("user").asText().equals(userId)) found = e.getKey();
            }
        }
        if (changed) bot.data.save();
        return found;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
