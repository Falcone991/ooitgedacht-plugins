# OoitGedacht SMP — plugins overzicht

Gemaakt met behulp van CLaud ai,_

Zes losse Paper-plugins plus een losse Discord-bot, elk een eigen Maven-project (eigen
`pom.xml`). Bouwen gaat overal hetzelfde: `cd <map>` dan `mvn package`, de jar komt in
`<map>/target/<naam>.jar`.

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

## Discord-bot: ooitgedacht-bot + discordbridge
De bot bestaat uit twee delen, zodat hij 24/7 online blijft, ook als AMP de Minecraft-server
laat slapen:

```
 Discord  <-->  ooitgedacht-bot  <-- RCON -->  Minecraft-server + DiscordBridge-plugin
               (eigen AMP-instance,             (mag slapen; de bot merkt dat vanzelf)
                "Java App Runner", 24/7)
```

- **`ooitgedacht-bot`** — los Java-programma (`java -jar ooitgedacht-bot.jar`, leest
  `config.yml` + `data.json` uit de werkmap). Houdt de Discord-verbinding, en haalt elke paar
  seconden via RCON nieuwe logs op bij de plugin. Bevat de token; de plugin niet.
- **`discordbridge`** — kleine plugin op de server. Zet logs klaar in een wachtrij en beantwoordt
  verzoeken van de bot via het interne console-commando `discordbridge rpc <base64-JSON>`
  (spelers kunnen dat niet uitvoeren).

Wat het doet:
- **Profielstatus**: "Kijkt naar 3/20 spelers" als de server wakker is; "💤 Server slaapt" (en
  status "afwezig") als hij slaapt.
- **Logs** als embed (gekleurde rand, Minecraft-hoofd): server gestart / in slaap, joins, leaves,
  doden, /report, gamemode-wissels, items uit creative, "moved too quickly" (console meegelezen),
  commando's als /give (lijst in de plugin-config), en acties vanuit Discord. Chat naar een eigen
  kanaal. Rol-ping bij belangrijke logs (`logs.ping-on` in de bot-config, met cooldown).
- **Live statusbericht**: online/slapend, spelers, TPS, uptime, SurvivalTimeline-fase (week,
  hardcore, Nether/End met live aftellers, wereldgrens). Tijdens de slaap blijft de laatst bekende
  tijdlijn staan.
- **Altijd beschikbaar** (ook als de server slaapt): `/streak`, `/streaks`, `/goldnugget`,
  `/coppernugget [keuze]`, `/ironnugget`, `/embed` (beheer: formulier voor een bericht met
  gekleurde rand; met `bericht-id` bewerk je een eerder bericht).
- **Alleen als de server wakker is**: `/spelers`, `/deaths [speler]` (slapend: laatst bekende
  ranglijst), en voor beheer `/msg`, `/kick`, `/ban`, `/unban`.
- **`/discord` in-game**: klikbare uitnodigingslink (`discord-command.invite-link` in de plugin).
- Let op: StaffTools logt joins/leaves/reports ook al via de webhook. Zet daar
  `discord.webhook-url` leeg, anders krijg je alles dubbel.

### Instellen (eenmalig)
1. **Discord-app**: https://discord.com/developers/applications -> je app -> **Bot** ->
   **Reset Token**. Er hoeven géén "Privileged Gateway Intents" aan. Uitnodigen via **OAuth2** ->
   **URL Generator** met `bot` + `applications.commands`, en rechten `View Channels`,
   `Send Messages`, `Embed Links` (+ evt. `Mention All Roles` voor pings).
2. **RCON aan** op de Minecraft-server (`server.properties`): `enable-rcon=true`,
   `rcon.port=25575`, `rcon.password=<lang wachtwoord>`. Zet de RCON-poort **niet** open naar
   internet; de bot draait op dezelfde machine.
3. **Plugin**: `discordbridge/target/discordbridge.jar` in `plugins/`, server herstarten.
4. **Bot**: in AMP een **Java App Runner**-instance, `ooitgedacht-bot/target/ooitgedacht-bot.jar`
   erin, één keer starten (maakt `config.yml`), daarin token, guild-id, kanaal-ID's en
   `server.rcon-password` invullen, opnieuw starten. In de bot-console moet
   "Ingelogd op Discord als ..." en "Verbonden met de Minecraft-server." verschijnen.

---

## Nog openstaande losse punten uit het gesprek
- De build-protection straal stond op 100 blokken; er was een (nog niet opgehelderde) melding
  dat een speler toch buiten die straal al kon breken — waarschijnlijk gewoon correct gedrag
  (buiten de straal mag het weer), maar nooit concreet met F3-coördinaten geverifieerd.
- Server.properties `spawn-protection` moet op `0` staan, anders kan vanilla nog af en toe
  tussendoor komen vóór de plugin de kans krijgt.
