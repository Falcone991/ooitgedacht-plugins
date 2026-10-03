# OoitGedacht SMP — plugins overzicht

Gemaakt met behulp van CLAUD AI

Zes losse Paper-plugins, elk een eigen Maven-project (eigen `pom.xml`). Bouwen gaat overal
hetzelfde: `cd <map>` dan `mvn package`, de jar komt in `<map>/target/<naam>.jar`.

Check per project de `paper-api`-versie in `pom.xml` en zet die gelijk aan de Minecraft-versie
van de server (die blijkt nu 1.21.9 "The Copper Age" of nieuwer te zijn, zie funitems).

---

## survivaltimeline
Fasegewijze survival-tijdlijn, real-life weken geteld vanaf de eerste start:
- Week 0–3: normale respawns. Na 3 volledige weken: **hardcore** (alle werelden).
- Vanaf week 2 (dag 7): **Nether** open. Vanaf week 3 (dag 14): **End** open.
- Wereldgrens groeit elke **in-game dag** automatisch met een instelbaar aantal blokken.
- `/survivaltimeline` (alias `/stimeline`) toont de status.
- Config: `weeks-before-hardcore`, `nether-open-week`, `end-open-week`,
  `border-increment-per-ingame-day`, `border-start-size`, `main-world`, `announce`.

## lobbyspawn
Lobby/hub-systeem + spawn-bescherming:
- `/lobby`, `/setlobby` — teleport naar/instellen van de lobby, met warmup (annuleert bij
  bewegen/schade) en cooldown. Admin-bypass via `lobbyspawn.bypassdelay`.
- `/spawn` — zelfde systeem, maar naar het spawnpunt van de **survival**-wereld.
- `/survival` — terug naar je laatst bekende survival-locatie (hub-modus).
- `/rules` — toont het welkomstbericht opnieuw; wordt ook automatisch getoond bij de
  allereerste keer inloggen (`welcome-message` in config).
- **Hub-modus** (`hub.enabled`): iedereen start bij inloggen in de lobby; `/survival` brengt
  je terug naar precies waar je was. Werkt samen met een aparte lobby-wereld (via
  Multiverse-Core, los geïnstalleerd).
- **Exacte spawn** (`world-spawn.force-exact-spawn`): voorkomt vanilla's willekeurige
  verspreiding rond het wereld-spawnpunt, bij zowel eerste join als respawn zonder bed/anker.
- **Mob-spawnbescherming** (`/spawnprotection <blokken>`): blokkeert vijandige mobs binnen
  een straal rond het wereld-spawnpunt.
- **Bouw-bescherming** (`/buildprotection <blokken>`): blokkeert breken/plaatsen én
  deuren/luiken/knoppen/hendels/drukplaten binnen die straal, met een eigen Nederlandse
  actionbar-melding + geluid (vervangt vanilla's Engelse bericht — zet daarvoor wel
  `spawn-protection=0` in `server.properties`).

## playerstats
Klein scorebord + basic level-systeem:
- `/scoreboard` — zet je eigen scorebord aan/uit (onthouden per speler).
- `/levels` — bekijk je voortgang per categorie.
- Scorebord toont (config: `scoreboard.lines`, met plekhouders `{hours}` `{minutes}`
  `{deaths}` `{level}` `{mining_level}` etc.): speeltijd en doden (vanilla-statistieken),
  plus totaal-level.
- Leveling: mijnen (ertsen breken), boeren (volgroeide gewassen oogsten), vissen (succesvolle
  vangst), verkennen (elke X blokken lopen/rennen). Elke categorie eigen XP-teller, elke
  `xp-per-level` punten = 1 level, met titel-animatie + geluid + deeltjes + gratis
  Minecraft-XP bij een level-up.

## stafftools
Beheer-tools:
- OP's (permissie `stafftools.staff`, default `op`) krijgen automatisch een `[Beheer]`-label
  in zowel de tab-lijst als de chat.
- `/report <speler> <reden>` — voor alle spelers; alleen zichtbaar voor beheer (+ console-log).
- `/sgm <speler> <bericht>` — privébericht van beheer naar een speler (bv. als reactie op een
  report); andere beheer ziet het gesprek mee voor overzicht.
- **Discord-webhook** (`discord.webhook-url` in config, geen bot nodig): logt joins, leaves en
  reports naar een Discord-kanaal. `/sgm` wordt standaard NIET gelogd (privacy), aan te zetten
  via `discord.log-sgm`.

## funitems
Leuke rechtermuisklik-spelletjes met nuggets (geen commando's):
- **Gouden nugget**: kop-of-munt, met geluidje.
- **Koperen nugget**: steen-papier-schaar tegen de server.
- **IJzeren nugget**: 1-op-10 gok — bij winst: titel, geluid, vuurwerk-deeltjes + 50 XP.
- Elk item heeft een eigen cooldown tegen spammen. Uitdelen via `/give <speler> gold_nugget 1`
  (en `copper_nugget` / `iron_nugget`).

## discordbot
Echte Discord-bot (JDA, draait ín de Minecraft-server — aan als de server aan is, offline als
de server uit is):
- **Status in het bot-profiel**: "Kijkt naar 3/20 spelers op OoitGedacht" (tekst/type in config).
- **Logs** naar het log-kanaal: server start/stop, joins, leaves, doden, /report, gamemode-wissels,
  items uit het creative-menu, "moved too quickly"-waarschuwingen (via de console meegelezen),
  gebruik van commando's als /give en /gamemode (lijst in config), en wie er vanuit Discord
  gemodereerd heeft.
- Alle logs als **embed** (gekleurde rand per soort, Minecraft-hoofd van de speler, tijdstip).
- **In-game chat** naar een apart kanaal (`bot.chat-channel-id`).
- **Live statusbericht** in een eigen kanaal (`bot.status-channel-id`): één bericht dat elke
  minuut bijgewerkt wordt met online/offline, spelers, TPS, uptime, en de SurvivalTimeline-fase
  (week, hardcore, Nether/End met live aftellers, wereldgrens). Leest SurvivalTimeline's
  `data.yml` rechtstreeks, die plugin hoeft niet aangepast. Bot heeft daar `Embed Links` nodig.
- **`/discord` in-game**: klikbare uitnodigingslink (`discord-command.invite-link`).
- **Slash-commando's in Discord**:
  - `/spelers` — voor iedereen: wie is er online.
  - `/msg <speler> <bericht>`, `/kick <speler> [reden]`, `/ban <speler> [reden]`,
    `/unban <speler>` — alleen voor de eigenaar, Administrators, en optioneel één extra rol
    (`bot.staff-role-id`). Spelersnamen worden automatisch aangevuld.
  - `/embed [kanaal] [bericht-id]` — beheer: formulier voor een bericht met gekleurde rand
    (titel, tekst, kleur, afbeelding, voettekst), bv. de regels. Met `bericht-id` bewerk je een
    eerder geplaatst bericht.
- **Fun-commando's voor iedereen** (`fun.enabled`):
  - `/deaths [speler]` — ranglijst meeste doden (vanilla-statistiek, ook offline spelers).
  - `/streak` — 1x per dag (middernacht, `fun.timezone`) typen om een streak op te bouwen;
    `/streaks` toont de langste lopende streaks. Opgeslagen in `plugins/DiscordBot/data.yml`.
  - `/goldnugget`, `/coppernugget [keuze]`, `/ironnugget` — zelfde spelletjes als FunItems,
    met de teksten/kansen/cooldowns uit FunItems' config.
- In-game ziet beheer (`discordbot.seemsg`, default op) mee wat er vanuit Discord gebeurt.
- Let op: StaffTools logt joins/leaves/reports ook al via de webhook. Gebruik je deze bot, zet
  dan in StaffTools `discord.webhook-url` leeg, anders krijg je alles dubbel.

### Bot instellen (eenmalig)
1. Ga naar https://discord.com/developers/applications -> **New Application** -> naam geven.
2. Tabblad **Bot** -> **Reset Token** -> kopieer de token naar `bot.token` in
   `plugins/DiscordBot/config.yml`. Deel deze token nooit. Er hoeven géén "Privileged Gateway
   Intents" aan.
3. Tabblad **OAuth2** -> **URL Generator**: vink `bot` en `applications.commands` aan, en bij
   Bot Permissions: `View Channels`, `Send Messages`. Open de gegenereerde link en nodig de bot
   uit op je server.
4. In Discord: Gebruikersinstellingen -> Geavanceerd -> **Ontwikkelaarsmodus** aan. Dan
   rechtermuisklik op je server -> "Server-ID kopiëren" -> `bot.guild-id`, en rechtermuisklik
   op het log-kanaal -> "Kanaal-ID kopiëren" -> `bot.log-channel-id`.
5. Jar in `plugins/`, server herstarten. In de console moet "Ingelogd op Discord als ..." en
   "Discord-commando's geregistreerd" verschijnen.

---

## Nog openstaande losse punten uit het gesprek
- De build-protection straal stond op 100 blokken; er was een (nog niet opgehelderde) melding
  dat een speler toch buiten die straal al kon breken — waarschijnlijk gewoon correct gedrag
  (buiten de straal mag het weer), maar nooit concreet met F3-coördinaten geverifieerd.
- Server.properties `spawn-protection` moet op `0` staan, anders kan vanilla nog af en toe
  tussendoor komen vóór de plugin de kans krijgt.
