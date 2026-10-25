# OoitGedacht SMP — plugins overzicht

Gemaakt met behulp van CLaud ai,_

Tien losse Paper-plugins plus een losse Discord-bot, elk een eigen Maven-project (eigen
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
- **Finale** (vanaf `finale.start-week`, standaard week 4 = samen met hardcore): de wereldgrens
  groeit niet meer maar krimpt elke dag om `shrink-time` (20:00, echte tijd) in gelijke stappen,
  zodat hij na `days` (7) dagen op `min-size` (50) staat; 10 minuten vooraf een waarschuwing, het
  krimpen zelf duurt 30 minuten. Sliep de server, dan haalt hij de gemiste stappen in bij het
  opstarten. Nether en End gaan dicht; wie er is wordt teruggestuurd.
  Wie terugkomt en buiten de grens staat, krijgt `return-grace-minutes` (30) om naar de grens te
  lopen zonder grensschade (timer start bij bewegen; richting en coördinaten onderin beeld; de
  verkeerde kant op = schade zoals bij honger). Resterende tijd blijft bewaard bij uitloggen. Wie in hardcore sterft is uitgeschakeld; zodra er nog één speler over is (en er
  in de finale al iemand is afgevallen) wint die: titel voor iedereen en vuurwerk.
  Wie tijdens de finale `inactive-hours` (24) niet online is geweest, is ook uitgeschakeld (geteld
  vanaf de start van de finale); bij inloggen wordt die toeschouwer. Gaan de laatsten tegelijk
  dood, dan wint wie als laatste stierf.
- **Na de winst is het seizoen voorbij**: wereldgrens weg, hardcore uit, Nether/End weer open, en
  iedereen die dood was komt terug in survival (offline spelers bij de volgende keer inloggen).
- `/survivaltimeline` (alias `/stimeline`) toont de status. Beheer (`survivaltimeline.admin`, OP)
  kan fases testen: `/stimeline week <n>`, `/stimeline krimp`, `/stimeline winnaar <speler>` en
  `/stimeline reset bevestig` (alles terug naar week 1). Veilig testen op de echte server:
  `/stimeline test start` bewaart de stand (in `test-backup.yml`, overleeft een herstart),
  `/stimeline test stop` zet alles precies terug (week, fases, grens, wie er dood was).
  `/stimeline test eindstrijd` test het eindgevecht: alleen de spelers die nu online zijn doen mee,
  de grens springt naar `min-size` en ze worden in een kring in de arena gezet.
- Config: `weeks-before-hardcore`, `nether-open-week`, `end-open-week`,
  `border-increment-per-ingame-day`, `border-start-size`, `main-world`, `announce`.

## lobbyspawn
Lobby/hub-systeem + spawn-bescherming:
- **Serverlijst-tekst** (`motd:`): vaste eerste regel met kleurverloop, tweede regel elke ping een
  willekeurig grappig zinnetje met namen van echte spelers (`<speler>`, `<speler2>`), in
  MiniMessage-opmaak. Slaapt de server (AMP), dan toont AMP zijn eigen tekst.
- `/lobby`, `/setlobby` — teleport naar/instellen van de lobby, met warmup (annuleert bij
  bewegen/schade) en cooldown. Admin-bypass via `lobbyspawn.bypassdelay`.
- `/spawn` — zelfde systeem, maar naar het spawnpunt van de **survival**-wereld.
- `/survival` — terug naar je laatst bekende survival-locatie (hub-modus). Alleen de
  survival-wereld, Nether en End worden onthouden, nooit een lobby- of testwereld.
- `/survival opening [seconden] [npc]` (beheer) — feestelijke opening: aftellen in beeld voor
  iedereen (standaard 10 s), daarna gaat survival open met vuurwerk uit de NPC's in de lobby
  (of alleen uit de gekozen NPC; geen NPC's = bij het lobby-punt). Het vuurwerk doet geen
  schade. Afbreken: `/survival opening stop`.
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
- **Houthakken** (5e categorie): `woodcutting-per-log` (3) XP per houtblok van een echte boom
  (natuurlijke bladeren in de buurt; zelf neergezet hout telt niet). Met een bijl hak je elke
  10 levels één blok van dezelfde boom extra per hak (level 10: 2, level 20: 3, ..., max 10;
  `levels-per-extra-block`, `max-blocks`), en vanaf level 100 de hele boom (`big-boost-level`,
  `big-boost-blocks`, max 100); bukken = één blok. Via `player.breakBlock`, dus bijl slijt en bescherming telt mee.
  Instellingen onder `woodcutting:`. Plekhouder `{woodcutting_level}`.
- **Bonusvangst bij vissen** (`fishing-loot`): kans op een extra item naast de gewone vangst,
  groeiend met het vis-level (5% + 1%/level, max 25%), standaard één item per bonusvangst
  (`extra-item-levels` leeg) en kans op een dubbele gewone vangst (0,5%/level, max 8%).
  Grote lijst (~50 items); hogere levels openen betere loot (schatkaart, betoverde boeken (gemaakt
  vanuit een gewoon boek, zoals een enchanttafel), diamanten, heel zeldzaam netherite scrap vanaf
  level 15, zeldzame items met melding voor iedereen). Vis-XP per vangst x
  `xp-multiplier` (standaard 5). `/levels` toont kans, aantal items en dubbele-vangstkans.
  Items met `requires: nether`/`end` (kwarts, glowstone, blaze rod, Pigstep, netherite, shulker)
  vallen pas als die dimensie open is volgens SurvivalTimeline (`data.yml`, elke minuut gelezen).

## stafftools
Beheer-tools:
- OP's (permissie `stafftools.staff`, default `op`) krijgen automatisch een `[Beheer]`-label
  in zowel de tab-lijst als de chat.
- `/prefix set <speler> <prefix>` / `/prefix remove <speler>` / `/prefix list` — eigen prefix
  (met &-kleuren, bv. `&6[VIP]`) in tab, chat en boven het hoofd. Vervangt bij beheer de
  `[Beheer]`-tag. Werkt ook voor offline spelers die al eens online zijn geweest. Permissie
  `stafftools.prefix` (default `op`). Opgeslagen in `plugins/StaffTools/prefixes.yml`.
- `/staffchat <bericht>` (of `/sc`) — chat die alleen beheer ziet (+ console-log, niet naar
  Discord). Zonder bericht = aan/uit: dan gaat alles wat je typt naar de staffchat.
  Opmaak: `staff.staffchat-format`.
- `/staffmode` (`/sm`) — onzichtbaar meekijken als toeschouwer (ook weg uit de tab-lijst voor
  gewone spelers); plek en gamemode worden op de speler bewaard (overleeft crash/herstart) en bij
  uitzetten teruggezet. `/spectate <speler>` (`/spec`) = staffmode aan en naar die speler.
- `/staffcreative` (`/scr`) — tijdelijk creative met een lege inventory. Survival-spullen, XP,
  levens, honger en plek worden op de speler bewaard (overleeft crash/herstart); bij uitzetten
  verdwijnen de creative-spullen en komt alles terug. In staffcreative geen items droppen, geen
  kisten openen en niets laten vallen bij doodgaan, zodat er geen creative-spullen in survival komen.
- `/invsee <speler>` en `/endersee <speler>` — inventory/enderkist van een online speler openen
  (en aanpassen); wordt in de console gelogd.
- `/brig <speler> [tijd] [reden]` — speler naar de brig-plek (`/brig set`); niet lopen, wel
  rondkijken en chatten (+ /msg, /report). Tijd als `30s`, `5m`, `1h` (standaard 5m), aftellen
  onderin beeld, daarna naar de spawn. Geen schade, bouwen, droppen of teleporteren zolang hij
  erin zit; tijd loopt door bij uitloggen. `/brig vrij <speler>`, `/brig list`. Opslag: `brig.yml`.
- Grapjes (onschuldig, gelogd in de console): `/pumpkin <speler> [seconden]` (pompoen op het hoofd,
  niet af te zetten; eigen helm wordt bewaard en teruggezet, ook na uitloggen/crash),
  `/chickenrain <speler> [aantal]` (kippen die na 30 s weer verdwijnen),
  `/scare <speler> [creeper|guardian|ghast|warden]` (schrikgeluid/-beeld alleen voor hem).
- `/announce <bericht>` (`/mededeling`, `/bc`) — mededeling voor iedereen: chat, titel en geluid.
- `/restart [tijd] [reden]` (ook `/herstart`) — aftellen met een balk bovenin beeld en meldingen
  (laatste 10 s groot in beeld), daarna iedereen netjes gekickt en de server stopt; AMP moet hem
  weer starten. `/restart stop` breekt af. Neemt Spigots eigen `/restart` over.
- `/report <speler> <reden>` — voor alle spelers; alleen zichtbaar voor beheer (+ console-log).
- `/sgm <speler> <bericht>` — privébericht van beheer naar een speler (bv. als reactie op een
  report); andere beheer ziet het gesprek mee voor overzicht.
- **Discord-webhook** (`discord.webhook-url` in config, geen bot nodig): logt joins, leaves en
  reports naar een Discord-kanaal. `/sgm` wordt standaard NIET gelogd (privacy), aan te zetten
  via `discord.log-sgm`.

## survivalextras
Drie losse onderdelen, elk aan/uit te zetten in de config:
- **Nacht overslaan**: als `sleep.percentage` (standaard 50%) van de spelers in de wereld slaapt,
  wordt het na een paar seconden ochtend. De slaper krijgt "Dikke doei! Goeie nachtrust" in beeld
  plus een slaapliedje; iedereen ziet een chatbericht met de telling en hoort bij de ochtend een
  deuntje met een "haan". Toeschouwers (dood in hardcore) tellen niet mee. De dagteller loopt
  gewoon door, dus de wereldgrens van survivaltimeline groeit ook bij overgeslagen nachten.
- **TPA**: `/tpa <speler>`, `/tpahere <speler>`, `/tpaccept`, `/tpdeny`, `/tpacancel`. Klikbare
  [Accepteren]/[Weigeren], verzoek verloopt na 60 s, 5 s stilstaan (annuleert bij bewegen/schade),
  30 s cooldown. Toeschouwers kunnen niet meedoen.
- **Homes**: `/sethome [naam]`, `/home [naam]`, `/delhome <naam>`, `/homes` (klikbaar). Max. 2
  homes, 5 s stilstaan, 30 s cooldown. Geen homes in de lobby-wereld van lobbyspawn.
- **Uitgeschakelde wapens** (`banned-items`): standaard de mace en alle speren. Nooit te craften
  (ook niet via smeedtafel of crafter). Met `only-crafting: false` (standaard) ook niet te
  gebruiken of op te pakken, mobs krijgen ze niet, en bestaande exemplaren verdwijnen bij inloggen
  of na het sluiten van een kist. Uitzondering via `survivalextras.banneditems.bypass`.
- `survivalextras.bypassdelay` (meteen teleporteren) staat voor niemand aan, ook niet voor OP's.
- **Loot drop** (`/lootdrop start|hier|stop|info [tijd]`, OP): na een aftelling (standaard 5 min)
  landt er een kist (bliksem-effect, vuurwerk) met decoratie-hoofden (MHF_-hoofden en hoofden van
  online spelers) en handige spullen (`loot-drop.loot`). Iedereen ziet coördinaten, een balk bovenin
  beeld en een lichtstraal; wie hem als eerste opent wordt genoemd; leeg = kist weg, na 30 min
  verdwijnt hij. Willekeurige plek op land binnen de wereldgrens, niet bij spawn.
  Eenmalig event: `/lootdrop plan <UU:MM> [aftellen]` kondigt het meteen aan (game + Discord),
  herinnert in de game (30/10/5/1 min), en op het tijdstip komen de coördinaten (ook in Discord)
  en landt hij na het aftellen. Bewaard in `lootdrop.yml`; sliep de server, dan start hij bij
  wakker worden (tot 2 uur te laat). `/lootdrop plan annuleer`. Discord-meldingen gaan via
  `discordbridge announce` naar `bot.announce-channel-id` (optionele ping: `bot.announce-role-id`).

## ooitworlds
Eigen, simpele "Multiverse": extra werelden (zoals een aparte lobby) maken en laden, met regels
per wereld. Alles staat in `plugins/OoitWorlds/worlds.yml` en wordt bij elke start vanzelf geladen.
Alleen voor OP's (`ooitworlds.admin`).
- `/world create <naam> <void|flat|normal> [normal|nether|end]` — nieuwe wereld (void = leeg, met
  een klein platform bij de spawn).
- `/world import <naam> [void|flat|normal]` — geüploade wereldmap laden (los naast `world`, uit
  een oude of nieuwe Minecraft-versie; Paper zet hem zelf over naar
  `world/dimensions/minecraft/<naam>`). Voor een lobby: type `void`, dan blijft alles buiten de
  bouw leeg.
- `/world tp <naam> [speler]`, `/world list`, `/world setspawn`, `/world load|unload <naam>`.
- `/world info [naam]` en `/world set <naam> <instelling> <waarde>`: `gamemode`
  (survival/creative/adventure/geen), en aan/uit: `pvp`, `schade`, `honger`, `mobs`, `bouwen`,
  `portalen`, `altijd-dag`, `altijd-nacht`, `geen-regen`, `void-terug`.
- `/world preset <naam> lobby` zet alles in één keer goed voor een lobby; `normaal` = vanilla.
- Gamemode-wissel is hardcore-veilig: de oude gamemode wordt op de speler bewaard en teruggezet
  bij het verlaten; toeschouwers en OP's worden nooit aangepast.
- Let op (Minecraft 26+): extra werelden staan binnen de map `world`, onder `dimensions/minecraft/`.

## ooitnpcs
Speler-NPC's met skin (de "mannequin" uit Minecraft zelf, geen Citizens nodig) waar je op klikt
(links of rechts) om iets te doen. Alleen voor OP's (`ooitnpcs.admin`). **Let op:** deze plugin
bouwt tegen de 26.3-API (`paper-api 26.3.build.*`) en heeft Java 25 nodig.
- `/npc create <naam> [skin-speler]` — NPC waar je staat (vanuit de console: bij de spawn).
- `/npc create <naam> item` — **voorwerp-NPC**: het item uit je hand zweeft groot en draait
  langzaam rond (bv. een wereldbol-hoofd van minecraft-heads.com), met de naam erboven en een
  onzichtbare klikbox. `/npc size <naam> <0.25-6>`, `/npc spin <naam> <aan|uit>`,
  `/npc item <naam>` (ander item uit je hand).
- `/npc action <naam> speler <commando>` (bv. `survival`), `wereld <wereld>`,
  `console <commando>` (`{player}` = wie klikt) of `geen`.
- `/npc name|text|skin <naam> <...>` — naam boven het hoofd, tekst eronder (& voor kleuren,
  `geen` = weg), skin van een Minecraft-speler.
- `/npc move|tp|info|remove|respawn <naam>`, `/npc look <naam> <aan|uit>` (hoofd draait naar
  spelers in de buurt), `/npc list`.
- Dier-NPC: `/npc create <naam> dier <soort>` (elk levend wezen behalve speler/bazen, bv. fox, cat,
  parrot, villager, axolotl). `/npc dier <naam> <soort>`, `/npc variant <naam> <variant|geen>`
  (vos, kat, papegaai, axolotl, konijn, paard, lama, schaap-kleur, kikker, wolf, villager-beroep),
  `/npc baby <naam> aan|uit`, `/npc sit <naam> aan|uit` (kat, hond, papegaai, vos). Geen AI, stil,
  brandt niet in de zon, kijkt naar spelers (`/npc look`); naam/tekst als zwevende tekst erboven.
- Onkwetsbaar, beweegt niet, geen drops. Raakt een NPC kwijt (bv. `/kill`), dan wordt hij vanzelf
  opnieuw neergezet. Opgeslagen in `plugins/OoitNPCs/npcs.yml`.

## lobbygames
Spelletjes in de lobby:
- **Parkour**: beheer zet de punten waar ze staan: `/parkour setstart`, `/parkour addcheckpoint`
  (in volgorde), `/parkour setfinish` (+ `clearcheckpoints`, `setfall [auto]`, `resettimes`, `info`).
  Op de start stappen = timer aan (onderin beeld); checkpoints in volgorde; finish = tijd + record.
  Vallen onder het laagste punt (of `setfall`) = terug naar je checkpoint. Vliegen/creative/teleport
  stopt je poging. Spelers: `/parkour top`, `/parkour terug`, `/parkour stop`. Opslag: `parkour.yml`.
- **Duels** (1-tegen-1): `/duel <speler>` (klikbaar accepteren/weigeren) of `/duel wachtrij`
  (bv. via een NPC: `/npc action <naam> speler duel wachtrij`). Beheer: `/duel setspawn1|setspawn2`
  (startplekken), `/duel setkit` (= je huidige inventory) / `resetkit`, `info`. Eigen spullen, XP,
  levens, gamemode en plek worden opgeborgen (ook op schijf: `duel-saves.yml`) en daarna teruggegeven;
  niemand gaat echt dood (veilig in hardcore). Alleen schade van je tegenstander telt; geen commando's
  of droppen tijdens een duel; uitloggen = verliezen; na 5 minuten gelijkspel. Alleen vanuit de
  lobby. `/duel top` = ranglijst. Opslag: `duels.yml`.
- **Hot Potato** (tikkertje met een bom, eigen eiland): `/hotpotato join` (`/hp`, ook via een NPC:
  `/npc action <naam> speler hotpotato join`), `leave`, `top`. Vanaf `min-players` (3) telt het af,
  dan krijgt iemand de bom (TNT boven het hoofd, gloeit, sneller). Slaan = bom doorgeven (niet
  meteen terug). Na een geheime tijd (15-30 s) ontploft hij: die ligt eruit en gaat terug. Laatste
  over wint (melding, vuurwerk, `hotpotato.yml`). Eigen spullen en XP worden opgeborgen (ook op
  schijf, `hotpotato-saves.yml`, voor een crash) en daarna teruggegeven; in het spel een lege
  inventory, de bomhouder een hotbar vol TNT. Geen schade, niet droppen, van het eiland af = terug
  naar het midden, ergens anders heen = uit het spel. Beheer: `setspawn`,
  `pos1`/`pos2` (grens), `info`, `forcestop`. Niet tegelijk met een duel.
- **Launchpads**: beheer staat op een drukplaat en typt `/launchpad set [vooruit] [omhoog] [vrij]`
  (standaard 2 en 1; richting = waar je kijkt, of met `vrij` de kijkrichting van de speler). Alleen
  aangewezen platen lanceren; daarna even geen valschade. `/launchpad remove` (waar je op staat),
  `/launchpad list`. Opslag: `launchpads.yml`.

## bingo (OoitBingo)
Bingo in survival. Beheer start met `/bingo start [full|line]` (standaard `full` = hele kaart),
`/bingo stop`. Spelers: `/bingo join` (bingokaart = map in je hand), `/bingo kaart`, `/bingo top`,
`/bingo leave`. Kaart van 3x3 blokken (4 makkelijk, 3 middel, 2 moeilijk uit `blocks:`; met
`:nether`/`:end` pas als die dimensie open is volgens SurvivalTimeline). De map toont per speler
vakjes in de kleur van het blok met een groen vinkje als je hem hebt; rechtsklik = overzicht met de
echte blokken. Een blok telt zodra het in je inventory zit (elke 2 s gecontroleerd). Winnaar krijgt
`reward-levels` (30) XP-levels, melding, vuurwerk en (via DiscordBridge) een Discord-aankondiging.
Opslag in `bingo.yml` (overleeft een herstart); oude kaarten worden opgeruimd.

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
- **GitHub-updates** (`github:` in de bot-config): nieuwe commits op een branch van deze repo
  komen als embed in een eigen kanaal (link per commit, auteur met avatar). Kijkt elke 2 minuten
  via de GitHub-API; geen token nodig voor een publieke repo.
- **`/english`**: stuurt de Engelse uitleg over de server privé (DM) naar wie het typt; DM's dicht =
  antwoord dat alleen die persoon ziet. Tekst staat in `english.txt` naast de bot (aanpasbaar, `---` =
  nieuw bericht).
- **`/launcher`**: knop om `OoitGedacht.exe` te downloaden (nieuwste GitHub-release), met uitleg en
  een plaatje van de Windows-waarschuwing ("Meer info" → "Toch uitvoeren"). Zichtbaar voor iedereen
  in het kanaal. Plaatje: `launcher/make-help-image.ps1`.
- **Tickets**: `/ticket paneel` (beheer) plaatst een bericht met de knop "Ticket openen". Speler vult in
  waar het over gaat en krijgt een eigen privé-kanaal met beheer (staff-rol wordt gepingd); één open
  ticket per speler. "Sluiten" (of `/ticket sluiten`) stuurt een kopie van het gesprek als tekstbestand
  naar het log-kanaal (`bot.ticket-log-channel-id`) en verwijdert het kanaal. Categorie:
  `bot.ticket-category-id`. De bot heeft "Kanalen beheren" + "Rollen beheren" nodig, en voor de kopie
  de "Message Content Intent" in het Developer Portal.
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

## launcher (OoitGedacht.exe voor spelers)
Klein Windows-programma met één knop **Spelen**: installeert of werkt Fabric en het modpack
(`launcher/pack/ooitgedacht.mrpack`, gemaakt van de Modrinth-instance "Ooitgedacht") bij in
`%APPDATA%\.ooitgedacht`, zet de server in de lijst en maakt het profiel "OoitGedacht SMP" in de
officiële Minecraft Launcher. Zie [launcher/README.md](launcher/README.md).

---

## Nog openstaande losse punten uit het gesprek
- De build-protection straal stond op 100 blokken; er was een (nog niet opgehelderde) melding
  dat een speler toch buiten die straal al kon breken — waarschijnlijk gewoon correct gedrag
  (buiten de straal mag het weer), maar nooit concreet met F3-coördinaten geverifieerd.
- Server.properties `spawn-protection` moet op `0` staan, anders kan vanilla nog af en toe
  tussendoor komen vóór de plugin de kans krijgt.
