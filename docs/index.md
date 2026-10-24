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
- [Vollbildkarte](vollbildkarte.md): Bedienung, der Hinweis auf eine neue Version, welcher Satz, Stufen und Lupe, Kacheln lesen und behalten, Farbindex hinter der Palette, Spieler und Koordinaten, das Bild aus dem Gametest und was fehlt.
- [Ebenen](ebenen.md): Ebenen vom Plugin empfangen, in Teilen je `version`, ihre Nadeln als Wappenschild mit Symbol und Namen, ihre Banner und ihre Flächen, Kreise, Linien und Kartenschrift auf Minimap und Vollbildkarte zeichnen, Knöpfe des Plugins in der Tafel, Grenzen und Kosten, und je Ebene im Menü umschalten.
- [API für Client-Mods](api.md): wie ein anderer Fabric-Mod eigene Ebenen anlegt, im Format der Ebenen vom Server, über den Entrypoint `heroicmap` und `HeroicMapClientApi`; Kennungen, Grenzen, Version, was v1 nicht kann.
- [Wegpunkte](wegpunkte.md): Wegpunkte setzen und löschen, auch an eingetippten Koordinaten, der Todespunkt, eigene Linien und Regionen mit eigenen Ecken, Namen und Farben, Ecken und Wegpunkte verbinden, eigene Ebenen und ihr Teilen als Code, Marken am Rand der Vollbildkarte, Klick zum Zentrieren, Doppelklick zum Anheften an die Minimap, auch für Regionen und Kreise vom Server, Grösse der Köpfe und Ablage in `wegpunkte.json`.
- [Selbst gezeichnete Karte](selbst.md): die Wahl „Selbst“, wie der Mod die geladenen Chunks in eine eigene Karte zeichnet, auch im Einzelspieler, Wahl und Beenden, Massstab 1, 2 oder 4 px, wann gezeichnet wird, Kacheln als PNG und Pyramide.
- [Rahmen](rahmen.md): die Rahmen der Minimap als Familien mit Varianten, der Wähler, die Vorgabe „biom“ nach dem Biom unter dem Spieler, Dateien, varianten.txt und Quellen, Bänder eckig und rund, die Marken N, O, S, W mit Stellung und Lage, genordet fest und gedreht mitdrehend, durchsichtig per mittlerer Taste, halbe Marken und der Griff im Menü, Abstand zum Rand.
- [Widgets](widgets.md): Zeilen Text im Stapel unter der Minimap, heute die Koordinaten; der Balken davor, Gespeichert als `widgets` und die Übernahme aus `koordinaten`.
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
- [0010](entscheidungen/0010-tafel-200-einheiten.md): Die Infotafel im Mod höchstens 200 Einheiten breit mit 6 Rand, nicht 320 wie auf der Webkarte; die Einheit ist seit 0020 die der Tafel.
- [0011](entscheidungen/0011-modrinth-mit-curl.md): Releases per Workflow mit curl auf Modrinth, ohne fremde Action mit dem Token.
- [0012](entscheidungen/0012-marken-statt-verzierungen.md): Der Rahmen zeigt nur die Marken N, O, S, W, genordet fest, gedreht starr mitdrehend, ohne zier; ein Schalter stellt sie ab, der Abstand zum Rand mit der halben Diagonale; löst 0005 in Ornamenten und Marken ab.
- [0013](entscheidungen/0013-groesse-als-anteil-des-schirms.md): Die Seite der Minimap als Anteil der kürzeren Seite des Schirms, sie folgt dem Fenster; alte Einstellungen ohne Sprung.
- [0016](entscheidungen/0016-zweiter-haken-an-der-welt.md): Ein zweiter Haken an `ClientLevel` neben dem an `LevelExtractor.setSectionDirty`, weil Sodium jenen umgeht; gesetzte Truhen erscheinen sofort.
- [0017](entscheidungen/0017-gametests-in-der-ci-auch-mit-sodium.md): Die Gametests laufen in der CI unter Xvfb, ohne und mit Sodium in der Version des Modpacks, fest mit Prüfsumme und nur für die Gametests.
- [0018](entscheidungen/0018-api-fuer-client-mods.md): Ebenen anderer Fabric-Mods über `HeroicMapClientApi`, im JSON der Ebenen vom Server, englische Namen, Entrypoint `heroicmap` und Modrinth Maven; bei gleicher Kennung gilt der Server.
- [0019](entscheidungen/0019-teleport-mit-fester-hoehe.md): „Hierher teleportieren“ mit fester Höhe, aus dem Chunk beim Client, sonst aus Boden oder Höhen der Karte per HTTP, sonst der alte Befehl mit Hinweis; nur die Ablage einer Wurzel, eine Region je Klick. Punkt 3 abgelöst durch 0023.
- [0020](entscheidungen/0020-tafel-mit-eigenem-massstab.md): Die Infotafel mit eigenem ganzem Massstab bis zum GUI-Massstab, höchstens etwa ein Fünftel der Fensterbreite, geeicht auf 1920 × 1080 bei GS 2.
- [0021](entscheidungen/0021-erster-download-als-strom.md): Der volle Download als ein Strom, ZIP ohne Kompression vom Renderer, früh sichtbar ab der gröbsten Stufe, Fortsetzen mit `nach`; Abgleich weiter einzeln.
- [0014](entscheidungen/0014-eigene-karte-mit-massstab.md): Die eigene Karte mit 1, 2 oder 4 Pixeln je Block, fest bis zum Löschen, der Baum wie ein Download; löst 0004 im Massstab ab.
- [0015](entscheidungen/0015-ordner-fuer-einzelspielerwelten.md): Ein Ordner der Welt auch im Einzelspieler, unter heroicmap/ nach Speicherordner und Hash, nicht im Speicherordner der Welt; löst 0004 im Einzelspieler ab.
- [0022](entscheidungen/0022-marken-nach-aussen.md): Bei „kompass“ und „biom“ zeigt das Bild jeder Marke von der Mitte weg, genordet wie gedreht; die anderen Skins bleiben starr.
- [0024](entscheidungen/0024-browser-ohne-bestaetigung.md): „Im Browser öffnen“ öffnet direkt nach dem Klick, ohne Bestätigungsschirm und nicht über Blaze3D.openUri, mit dem Link aus der eigenen Basis.
- [0025](entscheidungen/0025-neue-version-von-modrinth.md): Einmal je Start Modrinth nach einer neueren Version fragen, Vorabversionen nach Semver statt nach dem Kanal, das Repo als Kontakt, Vorgabe an, der feste Link hinter dem Bestätigungsschirm.
- [0029](entscheidungen/0029-was-ein-server-zeigt-gilt-je-server.md): Was ein Server je Dimension zeigt, gilt je Server, in `zeigt.txt`; die eigene Karte gehört einem Server, ohne Wahl mit Namen erst der Download; statt stillem Vorrang eine Frage mit drei Knöpfen; eine ruhende Karte bekommt keinen Abgleich; löst 0004 teilweise ab.
- [0033](entscheidungen/0033-widgets-an-der-minimap.md): Die Koordinaten werden ein Widget; Arten, Bedienung, Einrasten und Speichern der Widgets, in drei Schritten.
- [0030](entscheidungen/0030-koepfe-an-nadeln.md): Der Kopf einer Nadel fest 8 × 8 Einheiten mit Rand und pixelgenau, Köpfe je Name über alle Ebenen, höchstens 1024, ohne Hash über die Bytes.
- [0028](entscheidungen/0028-teleport-mit-tp.md): Der Teleport schickt den normalen `tp`; ohne Höhe erst in die Luft, sobald der Chunk beim Client ist auf den Boden, alle 10 Ticks zurück nach oben, nach 5 s an den Start, ohne Schaden.
- [0032](entscheidungen/0032-rahmen-familien-und-waehler.md): Die Rahmen als Familien mit Varianten (Recolors) in varianten.txt, Stellung und Lage der Marken in info.txt, `kante` für „biom“; ein Wähler mit Vorschau statt Durchschalten, die mittlere Taste für durchsichtige Marken, alte Wahlen ziehen um; ergänzt 0005, 0012 und 0022.
- [0034](entscheidungen/0034-band-nach-der-formel.md): Die Bänder der Rahmen nach der Formel des Designers, in Pixeln des Schirms gerechnet, eckig aus einer kleinen Textur der Ecken, rund als Ring; nur zu sehende Texturen bleiben.
- [0035](entscheidungen/0035-hd-marken-ausserhalb-des-atlas.md): Die HD-Bilder der Marken ausserhalb des Atlas, nur für die gezeigte Variante geladen und je GUI-Massstab nach Fläche gemittelt; Pixel, HD oder durchsichtig je Marke, gespeichert in `verzierungen_hd`.
- [0027](entscheidungen/0027-abgleich-bis-zur-haelfte.md): Ein Abgleich lädt still bis zur Hälfte des Satzes statt bis zu einem Zehntel, ohne Stand gemessen am Doppelten, und zeigt den Kasten des Fortschritts wie der Strom.
- [0026](entscheidungen/0026-gametests-und-gegenproben-in-der-ci.md): Gametests und Gegenproben laufen in der CI per Dispatch auf eigenen Branches, nebeneinander; lokal nur Unit-Tests, Gametests nur für Fehlersuche und Bilder unter der Sperre.
- [0023](entscheidungen/0023-teleport-mit-korrektur.md): „Hierher teleportieren“ immer im Menü; ohne Höhe zuerst der Server, nach 5 Ticks die Höhe des Spielers; danach einmal korrigieren, sobald der Chunk beim Client ist; löst 0019 in Punkt 3 ab. Abgelöst durch 0028.

## Messungen

- [2026-10-05, Minimap, Kosten](messungen/2026-10-05-minimap-kosten.md): was die Minimap je Chunk und je Frame kostet, auf dem Render-Thread und mit Worker.
- [2026-10-06, Minimap, rund gegen eckig](messungen/2026-10-06-minimap-rund.md): was die runde Minimap je Frame gegen die eckige kostet, im HUD-Element und in der Frametime.
- [2026-10-07, Minimap, 8 und 16 px](messungen/2026-10-07-minimap-8-16px.md): was die Minimap bei 8 und 16 Pixeln je Block kostet, je Chunk, je Region und je Frame.
- [2026-10-09, Minimap, Drehen](messungen/2026-10-09-minimap-drehen.md): was die drehende Minimap je Frame kostet, eckig und rund, im Stand und im Flug; rund gedreht ist billiger als ungedreht.
- [2026-10-09, Minimap, Vieleck auch ungedreht](messungen/2026-10-09-minimap-vieleck.md): was die Minimap je Frame kostet, seit sie auch ungedreht mit dem Vieleck zeichnet, gegen main; rund so billig wie eckig.
- [2026-10-10, Minimap, Verzierungen drehen mit](messungen/2026-10-10-minimap-verzierungen.md): was die Minimap mit dem Rahmen „kompass“ je Frame kostet, seit die Verzierungen mitdrehen, gegen main im Wechsel A B A B unter Grundlast; unverändert.
- [2026-10-20, Minimap, Zoom 0,5](messungen/2026-10-20-minimap-zoom-halb.md): was Zoom 0,5 gegen 2 je Frame kostet und bis die Minimap nach dem Wechsel ganz gezeichnet ist; je Frame gleich, ganz gezeichnet nach rund 1 s statt 50 ms.
- [2026-10-20, Minimap, Zoom 1/32](messungen/2026-10-20-minimap-zoom-weit.md): was Zoom 1/32 aus Kacheln gegen 2 je Frame kostet und bis die Minimap nach dem Wechsel ganz gezeichnet ist; im Stand gleich, im Flug 0,06 ms mehr, ganz gezeichnet so schnell wie bei 2.
- [2026-10-11, Strahl über angehefteten Wegpunkten](messungen/2026-10-11-strahl.md): was 64 Strahlen je Frame kosten, mit und ohne „Effekte in der Welt“, drei Läufe im Wechsel mit dem Schleier.
- [2026-10-11, Schleier am Rand angehefteter Regionen](messungen/2026-10-11-schleier.md): was der Schleier an der Grenze von 20 000 Vierecken mit 64 Strahlen je Frame kostet und wie lange ein Bau dauert.
- [2026-10-19, Schleier, Auswahl statt Sortieren](messungen/2026-10-19-schleier-auswahl.md): Ein Bau mit 47 Regionen wird mit der Auswahl der 5 000 nächsten Stücke 20 % schneller, 3,44 statt 4,31 ms; je Frame unverändert.
- [2026-10-09, Selbst gezeichnete Karte, Schreiben der Kacheln](messungen/2026-10-09-selbst-schreiben.md): was `Kachelwerk.schreibe` je Durchlauf kostet und wie viele PNG es je Stunde schreibt, für eine Farm und einen Flug.
- [2026-10-10, Selbst gezeichnete Karte, Platz je Massstab](messungen/2026-10-10-selbst-platz-je-massstab.md): wie viele PNG die eigene Karte je Stunde schreibt und wie viel Platz sie braucht, bei 1, 2 und 4 px; Zeiten folgen.
- [2026-10-11, Selbst gezeichnete Karte, Schreiben je Massstab](messungen/2026-10-11-selbst-schreiben-je-massstab.md): was `Kachelwerk.schreibe` je Durchlauf bei 1, 2 und 4 px kostet, Farm und Flug, drei Läufe in Ruhe.
- [2026-10-06, Vollbildkarte, Übernahme der Kacheln](messungen/2026-10-06-vollbildkarte-uebernahme.md): was eine Kachel den Render-Thread kostet, mit und ohne Kopieren der Pixel dort, und wie schnell der Dekoder liefert.
