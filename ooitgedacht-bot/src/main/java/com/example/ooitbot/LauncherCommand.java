package com.example.ooitbot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.InputStream;

/**
 * /launcher: downloadlink van OoitGedacht.exe met uitleg, plus een plaatje van de
 * Windows-waarschuwing (SmartScreen) zodat spelers weten dat ze op "Meer info" en
 * "Toch uitvoeren" moeten klikken. Het antwoord is voor iedereen in het kanaal zichtbaar.
 */
final class LauncherCommand {

    private static final String DOWNLOAD_URL =
            "https://github.com/Falcone991/ooitgedacht-plugins/releases/latest/download/OoitGedacht.exe";
    private static final String IMAGE = "launcher-hulp.png";

    static SlashCommandData command() {
        return Commands.slash("launcher", "Download de OoitGedacht-launcher (alle mods in een klik).");
    }

    void handle(SlashCommandInteractionEvent event) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(0x3BA55D)
                .setTitle(":video_game: OoitGedacht launcher")
                .setDescription(String.join("\n",
                        "Met de launcher heb je in een klik alle mods, en de server staat er meteen in.",
                        "",
                        "**1.** Download **OoitGedacht.exe** met de knop hieronder.",
                        "**2.** Open hem. Krijg je een blauw scherm *\"Windows heeft uw pc beschermd\"*? "
                                + "Klik op **Meer info** en daarna op **Toch uitvoeren** (zie plaatje). "
                                + "Dit is normaal bij kleine programma's die nog niet bij Microsoft bekend zijn.",
                        "**3.** Druk op **Spelen**. De Minecraft Launcher gaat open: kies **OoitGedacht SMP** en speel!",
                        "",
                        "Je hebt de gewone Minecraft Launcher nodig (een keer gestart en ingelogd). "
                                + "Daarna start je voortaan de snelkoppeling **OoitGedacht SMP** op je bureaublad; "
                                + "die werkt de mods vanzelf bij.",
                        "",
                        ":flag_gb: *English Windows: click **More info**, then **Run anyway**.*"))
                .setFooter("Alleen voor Windows");

        InputStream image = LauncherCommand.class.getResourceAsStream("/" + IMAGE);
        if (image != null) eb.setImage("attachment://" + IMAGE);
        else Log.warn(IMAGE + " ontbreekt in de jar; /launcher zonder plaatje.");

        var reply = event.replyEmbeds(eb.build())
                .addActionRow(Button.link(DOWNLOAD_URL, "Download OoitGedacht.exe"));
        if (image != null) reply = reply.addFiles(FileUpload.fromData(image, IMAGE));
        reply.queue(null, err -> Log.warn("Kon /launcher niet sturen: " + err.getMessage()));
    }
}
