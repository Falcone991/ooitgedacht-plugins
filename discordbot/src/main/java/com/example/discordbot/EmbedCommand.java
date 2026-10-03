package com.example.discordbot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;

/**
 * /embed voor beheer: opent een formulier in Discord (titel, tekst, kleur,
 * afbeelding, voettekst) en plaatst daarna een bericht met gekleurde rand,
 * bv. voor de regels of een aankondiging. Met "bericht-id" bewerk je een
 * eerder geplaatst bericht; het formulier is dan al ingevuld.
 */
class EmbedCommand {

    private static final int DEFAULT_COLOR = 0x5865F2;
    private static final String MODAL_PREFIX = "embed";

    private final DiscordBot bot;

    EmbedCommand(DiscordBot bot) {
        this.bot = bot;
    }

    static SlashCommandData command(DefaultMemberPermissions permissions) {
        return Commands.slash("embed", "Plaats of bewerk een bericht met gekleurde rand (bv. de regels).")
                .addOptions(
                        new OptionData(OptionType.CHANNEL, "kanaal", "Waar moet het bericht komen? (leeg = dit kanaal)", false)
                                .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS),
                        new OptionData(OptionType.STRING, "bericht-id",
                                "Alleen om te bewerken: ID of link van een eerder bericht van de bot", false))
                .setDefaultPermissions(permissions);
    }

    void handle(SlashCommandInteractionEvent event) {
        GuildChannelUnion chosen = event.getOption("kanaal", OptionMapping::getAsChannel);
        String channelId = chosen != null ? chosen.getId() : event.getChannel().getId();
        GuildMessageChannel channel = event.getGuild() == null ? null
                : event.getGuild().getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            event.reply(":x: In dat kanaal kan ik geen berichten plaatsen.").setEphemeral(true).queue();
            return;
        }

        String rawMessageId = event.getOption("bericht-id", OptionMapping::getAsString);
        if (rawMessageId == null) {
            event.replyModal(buildModal(MODAL_PREFIX + ":new:" + channelId, null)).queue();
            return;
        }

        // Een link naar een bericht eindigt op het bericht-ID: .../kanaal-id/bericht-id
        String messageId = rawMessageId.trim();
        if (messageId.contains("/")) messageId = messageId.substring(messageId.lastIndexOf('/') + 1);
        if (!messageId.matches("\\d{5,25}")) {
            event.reply(":x: Dat is geen geldig bericht-ID.").setEphemeral(true).queue();
            return;
        }

        String id = messageId;
        channel.retrieveMessageById(id).queue(
                message -> {
                    if (!message.getAuthor().equals(event.getJDA().getSelfUser()) || message.getEmbeds().isEmpty()) {
                        event.reply(":x: Ik kan alleen mijn eigen berichten met een embed bewerken.").setEphemeral(true).queue();
                        return;
                    }
                    event.replyModal(buildModal(MODAL_PREFIX + ":edit:" + channelId + ":" + id,
                            message.getEmbeds().get(0))).queue();
                },
                err -> event.reply(":x: Bericht niet gevonden in " + channel.getAsMention()
                        + ". Klopt het kanaal?").setEphemeral(true).queue());
    }

    private Modal buildModal(String modalId, MessageEmbed existing) {
        String title = existing != null ? existing.getTitle() : null;
        String text = existing != null ? existing.getDescription() : null;
        String color = existing != null && existing.getColor() != null
                ? String.format("#%06X", existing.getColorRaw() & 0xFFFFFF) : null;
        String image = existing != null && existing.getImage() != null ? existing.getImage().getUrl() : null;
        String footer = existing != null && existing.getFooter() != null ? existing.getFooter().getText() : null;

        TextInput titleInput = TextInput.create("titel", "Titel", TextInputStyle.SHORT)
                .setPlaceholder("Bv. Serverregels")
                .setRequired(false).setMaxLength(256)
                .setValue(limit(title, 256)).build();
        TextInput textInput = TextInput.create("tekst", "Tekst", TextInputStyle.PARAGRAPH)
                .setPlaceholder("**vet**, *schuin*, - lijstjes, emoji's en enters werken allemaal.")
                .setRequired(true).setMaxLength(4000)
                .setValue(limit(text, 4000)).build();
        TextInput colorInput = TextInput.create("kleur", "Kleur van de rand (hex, bv. #E67E22)", TextInputStyle.SHORT)
                .setPlaceholder("#5865F2")
                .setRequired(false).setMaxLength(7)
                .setValue(color).build();
        TextInput imageInput = TextInput.create("afbeelding", "Afbeelding-URL (optioneel)", TextInputStyle.SHORT)
                .setPlaceholder("https://...")
                .setRequired(false).setMaxLength(1000)
                .setValue(image).build();
        TextInput footerInput = TextInput.create("voettekst", "Voettekst (optioneel)", TextInputStyle.SHORT)
                .setPlaceholder("Bv. OoitGedacht SMP")
                .setRequired(false).setMaxLength(2048)
                .setValue(limit(footer, 2048)).build();

        return Modal.create(modalId, existing == null ? "Nieuw bericht" : "Bericht bewerken")
                .addActionRow(titleInput)
                .addActionRow(textInput)
                .addActionRow(colorInput)
                .addActionRow(imageInput)
                .addActionRow(footerInput)
                .build();
    }

    /** @return true als dit formulier hier afgehandeld is. */
    boolean handleModal(ModalInteractionEvent event) {
        String[] parts = event.getModalId().split(":");
        if (!parts[0].equals(MODAL_PREFIX) || parts.length < 3) return false;

        if (!bot.isDiscordStaff(event.getMember())) {
            event.reply(":x: Alleen de eigenaar en beheerders mogen dit.").setEphemeral(true).queue();
            return true;
        }

        GuildMessageChannel channel = event.getGuild() == null ? null
                : event.getGuild().getChannelById(GuildMessageChannel.class, parts[2]);
        if (channel == null) {
            event.reply(":x: Het kanaal bestaat niet meer.").setEphemeral(true).queue();
            return true;
        }

        MessageEmbed embed;
        try {
            embed = buildEmbed(event);
        } catch (IllegalArgumentException e) {
            event.reply(":x: " + e.getMessage()).setEphemeral(true).queue();
            return true;
        }

        event.deferReply(true).queue();
        boolean edit = parts[1].equals("edit") && parts.length >= 4;

        if (edit) {
            channel.editMessageEmbedsById(parts[3], embed).queue(
                    msg -> event.getHook().editOriginal(":white_check_mark: Bericht bijgewerkt: " + msg.getJumpUrl()).queue(),
                    err -> event.getHook().editOriginal(":x: Bewerken mislukt: " + err.getMessage()).queue());
        } else {
            channel.sendMessageEmbeds(embed).queue(
                    msg -> event.getHook().editOriginal(":white_check_mark: Geplaatst in " + channel.getAsMention()
                            + ".\nWil je het later aanpassen? Gebruik `/embed` met bericht-id `" + msg.getId() + "`.").queue(),
                    err -> event.getHook().editOriginal(":x: Plaatsen mislukt (mag ik in dat kanaal schrijven?): "
                            + err.getMessage()).queue());
        }
        return true;
    }

    private static MessageEmbed buildEmbed(ModalInteractionEvent event) {
        String title = value(event, "titel");
        String text = value(event, "tekst");
        String color = value(event, "kleur");
        String image = value(event, "afbeelding");
        String footer = value(event, "voettekst");

        if (text.isEmpty()) throw new IllegalArgumentException("De tekst mag niet leeg zijn.");

        EmbedBuilder eb = new EmbedBuilder().setDescription(text).setColor(parseColor(color));
        if (!title.isEmpty()) eb.setTitle(title);
        if (!footer.isEmpty()) eb.setFooter(footer);
        if (!image.isEmpty()) {
            if (!image.startsWith("http://") && !image.startsWith("https://")) {
                throw new IllegalArgumentException("De afbeelding moet een link zijn die begint met https://");
            }
            eb.setImage(image);
        }
        return eb.build();
    }

    private static String value(ModalInteractionEvent event, String id) {
        ModalMapping mapping = event.getValue(id);
        return mapping == null ? "" : mapping.getAsString().trim();
    }

    private static int parseColor(String text) {
        if (text.isEmpty()) return DEFAULT_COLOR;
        String hex = text.startsWith("#") ? text.substring(1) : text;
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Ongeldige kleur '" + text + "'. Gebruik bv. #E67E22.");
        }
        return Integer.parseInt(hex, 16);
    }

    private static String limit(String text, int max) {
        if (text == null || text.isEmpty()) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
