# OoitGedacht-installer (launcher)

Een klein Windows-programma (`OoitGedacht.exe`) met één knop: **Spelen**. Het:
- downloadt het modpack `pack/ooitgedacht.mrpack` van GitHub (dus altijd de nieuwste versie);
- zet Fabric klaar en downloadt de mods (van Modrinth) naar een eigen map `%APPDATA%\.ooitgedacht`,
  zodat de gewone Minecraft-map van de speler niet wordt aangeraakt;
- zet de server (`play.ooitgedacht.nl`) in de serverlijst;
- maakt in de **officiële Minecraft Launcher** het profiel "OoitGedacht SMP" met het serverlogo en
  selecteert het; daarna gaat de launcher open;
- zet een snelkoppeling "OoitGedacht SMP" op het bureaublad. Via die snelkoppeling wordt alles bij
  elke start eerst bijgewerkt.

Inloggen en het spel starten doet de officiële launcher, dus geen Microsoft-goedkeuring nodig.
Spelers moeten de Minecraft Launcher wel een keer gestart hebben en ingelogd zijn.

## Mods aanpassen
1. Pas de instance **Ooitgedacht** aan in de Modrinth App en start hem een keer.
2. `powershell -ExecutionPolicy Bypass -File launcher\make-pack.ps1 -Version 1.0.1`
3. Commit en push `launcher/pack/ooitgedacht.mrpack`. Spelers krijgen het de volgende keer dat ze
   op Spelen drukken. De exe hoeft niet opnieuw.

Een `.mrpack` dat je zelf exporteert uit de Modrinth App werkt ook (Fabric-packs). Hernoem het dan naar
`ooitgedacht.mrpack`. Instellingen (`overrides/config`) worden alleen de eerste keer neergezet, zodat
spelers hun eigen instellingen houden.

## Exe bouwen
`powershell -ExecutionPolicy Bypass -File launcher\build.ps1`, daarna staat `launcher\target\OoitGedacht.exe` klaar.
Hiervoor hoef je niets te installeren: de C#-compiler zit in Windows.
Serveradres, naam en pack-link staan bovenaan in `src/OoitGedacht.cs` (`Config`).

Testen zonder je eigen Minecraft-map aan te raken:
`OoitGedacht.exe --test <lege map> --pack launcher\pack\ooitgedacht.mrpack`. De map speelt dan
`%APPDATA%`, met daarin `.minecraft\launcher_profiles.json`. Het verslag komt in `test-log.txt`.

Windows SmartScreen waarschuwt bij de eerste keer ("Windows heeft uw pc beschermd"): klik op
**Meer info**, daarna op **Toch uitvoeren**. Zonder betaald code-certificaat kan dat niet anders.
