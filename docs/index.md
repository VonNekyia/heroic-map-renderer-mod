---
title: Wegweiser
description: Jede Seite der Doku des Mods mit einer Zeile.
code: []
---

# Wegweiser

Das Wissen über den Mod: wie er rechnet, wie man ihn baut, was entschieden
und was gemessen ist. Die Regeln stehen in [`AGENTS.md`](../AGENTS.md). Zu
einer Datei findet `git grep -l "<pfad>" docs/` ihre Seiten.

## Karte

- [Projektion](projektion.md): wo ein Block auf Minimap und Vollbildkarte liegt, genordet von oben wie `top-north`, geprüft an `projektion.json` des Renderers.
- [Download](download.md): wie der Mod die Karte vom Plugin lädt, Kanal, Befehle, Zustimmung und Grösse, Sicherheit, Manifest, harte Grenzen, Ablage je Welt nach dem Hash des Seeds, Kartenliste mit Löschen, Fortsetzen und Abgleich.
- [Vollbildkarte](vollbildkarte.md): Bedienung, welcher Satz, Stufen und Lupe, Kacheln lesen und behalten, Farbindex hinter der Palette, Spieler und Koordinaten, das Bild aus dem Gametest und was fehlt.
- [Ebenen](ebenen.md): Ebenen vom Plugin empfangen, in Teilen je `version`, ihre Nadeln als Wappenschild mit Symbol und Namen, ihre Banner und ihre Flächen, Kreise, Linien und Kartenschrift auf Minimap und Vollbildkarte zeichnen, Grenzen und Kosten, und je Ebene im Menü umschalten.
- [API für Client-Mods](api.md): wie ein anderer Fabric-Mod eigene Ebenen anlegt, im Format der Ebenen vom Server, über den Entrypoint `heroicmap` und `HeroicMapClientApi`; Kennungen, Grenzen, Version, was v1 nicht kann.
- [Wegpunkte](wegpunkte.md): Wegpunkte setzen und löschen, eigene Linien und Regionen aus Wegpunkten, Marken am Rand der Vollbildkarte, Klick zum Zentrieren, Doppelklick zum Anheften an die Minimap, auch für Regionen und Kreise vom Server, Grösse der Köpfe und Ablage in `wegpunkte.json`.
- [Selbst gezeichnete Karte](selbst.md): die Wahl „Selbst“, wie der Mod die geladenen Chunks in eine eigene Karte zeichnet, auch im Einzelspieler, Wahl und Beenden, Massstab 1, 2 oder 4 px, wann gezeichnet wird, Kacheln als PNG und Pyramide.
- [Rahmen](rahmen.md): die Rahmen der Minimap als umschaltbare Skins, die Vorgabe „biom“ nach dem Biom unter dem Spieler, Dateien und Quellen, Bänder eckig und rund, die Marken N, O, S, W, genordet fest und gedreht mitdrehend, ihr Schalter, halbe Marken und der Griff im Menü, Abstand zum Rand.
- [Minimap](minimap.md): Bedienung mit Untermenü „Einstellungen …“, Aussehen per Klick auf Spieler und Marken, Chunklinien, Drehen mit der Blickrichtung, Bewegung zwischen zwei Ticks, welcher Block oben liegt, Flächen aus dem Tesselator des Spiels, Pixel und Mittelung, Licht, Wasser, Blockentities, Decke, Neu zeichnen im Worker, Kosten, Bilder und was anders ist als `top-north`.

## Entwicklung

- [Bauen und testen](entwicklung.md): Versionen, Gradle, Tests, Gametests, Maustasten, CI, Release auf GitHub und Modrinth und die Prüfung der Doku.

## Entscheidungen

- [0001](entscheidungen/0001-minimap-mit-dem-tesselator.md): Die Minimap mit dem Tesselator des Spiels.
- [0002](entscheidungen/0002-vollbildkarte-nur-vom-server.md): Die Vollbildkarte nur vom Server, ohne Live-Ebene; teilweise abgelöst durch 0004.
- [0003](entscheidungen/0003-kopie-des-vp8l-dekoders.md): Eine geänderte Kopie des VP8L-Dekoders von TwelveMonkeys, bis TwelveMonkeys den Farbindex hinter der Palette richtig liest.
- [0004](entscheidungen/0004-karte-selbst-zeichnen.md): Die Wahl „Selbst“, eine eigene Karte aus den geladenen Chunks, die die des Servers ersetzt; löst 0002 teilweise ab; teilweise abgelöst durch 0014 und 0015.
- [0005](entscheidungen/0005-rahmen-als-skins.md): Die Rahmen als umschaltbare Skins, nur um die Minimap, ohne Rahmen als Vorgabe, ohne Nordmarke vor der Drehung; teilweise abgelöst durch 0006, 0008 und 0012.
- [0006](entscheidungen/0006-ein-weg-fuer-die-minimap.md): Ein Weg für die Minimap, gedreht wie ungedreht ein Vieleck je Region, der Ring deckt den Rand; löst 0005 in der Maske ab.
- [0007](entscheidungen/0007-toenung-auf-der-grafikkarte.md): Die Nadeln auf der Grafikkarte tönen, je Kanal höchstens eine Stufe anders als die Webkarte.
- [0008](entscheidungen/0008-biom-rahmen-als-vorgabe.md): Der Rahmen „biom“ als Vorgabe, Zuordnung über Tags und Namen in fester Rangfolge, Wechsel nach 2 s; löst 0005 in der Vorgabe ab.
- [0009](entscheidungen/0009-formen-als-trapeze.md): Füllungen als Trapeze statt Blöcken, ein Budget an Ecken je Neubau statt an Zeit je Frame.
- [0010](entscheidungen/0010-tafel-200-einheiten.md): Die Infotafel im Mod höchstens 200 Einheiten breit mit 6 Rand, nicht 320 wie auf der Webkarte.
- [0011](entscheidungen/0011-modrinth-mit-curl.md): Releases per Workflow mit curl auf Modrinth, ohne fremde Action mit dem Token.
- [0012](entscheidungen/0012-marken-statt-verzierungen.md): Der Rahmen zeigt nur die Marken N, O, S, W, genordet fest, gedreht starr mitdrehend, ohne zier; ein Schalter stellt sie ab, der Abstand zum Rand mit der halben Diagonale; löst 0005 in Ornamenten und Marken ab.
- [0013](entscheidungen/0013-groesse-als-anteil-des-schirms.md): Die Seite der Minimap als Anteil der kürzeren Seite des Schirms, sie folgt dem Fenster; alte Einstellungen ohne Sprung.
- [0016](entscheidungen/0016-zweiter-haken-an-der-welt.md): Ein zweiter Haken an `ClientLevel` neben dem an `LevelExtractor.setSectionDirty`, weil Sodium jenen umgeht; gesetzte Truhen erscheinen sofort.
- [0017](entscheidungen/0017-gametests-in-der-ci-auch-mit-sodium.md): Die Gametests laufen in der CI unter Xvfb, ohne und mit Sodium in der Version des Modpacks, fest mit Prüfsumme und nur für die Gametests.
- [0018](entscheidungen/0018-api-fuer-client-mods.md): Ebenen anderer Fabric-Mods über `HeroicMapClientApi`, im JSON der Ebenen vom Server, englische Namen, Entrypoint `heroicmap` und Modrinth Maven; bei gleicher Kennung gilt der Server.
- [0014](entscheidungen/0014-eigene-karte-mit-massstab.md): Die eigene Karte mit 1, 2 oder 4 Pixeln je Block, fest bis zum Löschen, der Baum wie ein Download; löst 0004 im Massstab ab.
- [0015](entscheidungen/0015-ordner-fuer-einzelspielerwelten.md): Ein Ordner der Welt auch im Einzelspieler, unter heroicmap/ nach Speicherordner und Hash, nicht im Speicherordner der Welt; löst 0004 im Einzelspieler ab.

## Messungen

- [2026-10-05, Minimap, Kosten](messungen/2026-10-05-minimap-kosten.md): was die Minimap je Chunk und je Frame kostet, auf dem Render-Thread und mit Worker.
- [2026-10-06, Minimap, rund gegen eckig](messungen/2026-10-06-minimap-rund.md): was die runde Minimap je Frame gegen die eckige kostet, im HUD-Element und in der Frametime.
- [2026-10-07, Minimap, 8 und 16 px](messungen/2026-10-07-minimap-8-16px.md): was die Minimap bei 8 und 16 Pixeln je Block kostet, je Chunk, je Region und je Frame.
- [2026-10-09, Minimap, Drehen](messungen/2026-10-09-minimap-drehen.md): was die drehende Minimap je Frame kostet, eckig und rund, im Stand und im Flug; rund gedreht ist billiger als ungedreht.
- [2026-10-09, Minimap, Vieleck auch ungedreht](messungen/2026-10-09-minimap-vieleck.md): was die Minimap je Frame kostet, seit sie auch ungedreht mit dem Vieleck zeichnet, gegen main; rund so billig wie eckig.
- [2026-10-10, Minimap, Verzierungen drehen mit](messungen/2026-10-10-minimap-verzierungen.md): was die Minimap mit dem Rahmen „kompass“ je Frame kostet, seit die Verzierungen mitdrehen, gegen main im Wechsel A B A B unter Grundlast; unverändert.
- [2026-10-11, Strahl über angehefteten Wegpunkten](messungen/2026-10-11-strahl.md): was 64 Strahlen je Frame kosten, mit und ohne „Effekte in der Welt“, drei Läufe im Wechsel mit dem Schleier.
- [2026-10-09, Selbst gezeichnete Karte, Schreiben der Kacheln](messungen/2026-10-09-selbst-schreiben.md): was `Kachelwerk.schreibe` je Durchlauf kostet und wie viele PNG es je Stunde schreibt, für eine Farm und einen Flug.
- [2026-10-10, Selbst gezeichnete Karte, Platz je Massstab](messungen/2026-10-10-selbst-platz-je-massstab.md): wie viele PNG die eigene Karte je Stunde schreibt und wie viel Platz sie braucht, bei 1, 2 und 4 px; Zeiten folgen.
- [2026-10-06, Vollbildkarte, Übernahme der Kacheln](messungen/2026-10-06-vollbildkarte-uebernahme.md): was eine Kachel den Render-Thread kostet, mit und ohne Kopieren der Pixel dort, und wie schnell der Dekoder liefert.
