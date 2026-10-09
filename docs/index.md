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
- [Wegpunkte](wegpunkte.md): Wegpunkte setzen und löschen, Marken am Rand der Vollbildkarte, Klick zum Zentrieren, Doppelklick zum Anheften an die Minimap, Grösse der Köpfe und Ablage in `wegpunkte.json`.
- [Selbst gezeichnete Karte](selbst.md): die Wahl „Selbst“, wie der Mod die geladenen Chunks in eine eigene Karte zeichnet, Wahl und Beenden, wann gezeichnet wird, Kacheln als PNG und Pyramide.
- [Rahmen](rahmen.md): die Rahmen der Minimap als umschaltbare Skins, Dateien und Quellen, Bänder eckig und rund, Ornamente und Griff im Menü, wandernde Marken beim Drehen, Abstand zum Rand.
- [Minimap](minimap.md): Bedienung mit Untermenü „Einstellungen …“, Chunklinien, Drehen mit der Blickrichtung, Bewegung zwischen zwei Ticks, welcher Block oben liegt, Flächen aus dem Tesselator des Spiels, Pixel und Mittelung, Licht, Wasser, Blockentities, Decke, Neu zeichnen im Worker, Kosten, Bilder und was anders ist als `top-north`.

## Entwicklung

- [Bauen und testen](entwicklung.md): Versionen, Gradle, Tests, Gametests, Maustasten, CI und die Prüfung der Doku.

## Entscheidungen

- [0001](entscheidungen/0001-minimap-mit-dem-tesselator.md): Die Minimap mit dem Tesselator des Spiels.
- [0002](entscheidungen/0002-vollbildkarte-nur-vom-server.md): Die Vollbildkarte nur vom Server, ohne Live-Ebene; teilweise abgelöst durch 0004.
- [0003](entscheidungen/0003-kopie-des-vp8l-dekoders.md): Eine geänderte Kopie des VP8L-Dekoders von TwelveMonkeys, bis TwelveMonkeys den Farbindex hinter der Palette richtig liest.
- [0004](entscheidungen/0004-karte-selbst-zeichnen.md): Die Wahl „Selbst“, eine eigene Karte aus den geladenen Chunks, die die des Servers ersetzt; löst 0002 teilweise ab.
- [0005](entscheidungen/0005-rahmen-als-skins.md): Die Rahmen als umschaltbare Skins, nur um die Minimap, ohne Rahmen als Vorgabe, ohne Nordmarke vor der Drehung.

## Messungen

- [2026-10-05, Minimap, Kosten](messungen/2026-10-05-minimap-kosten.md): was die Minimap je Chunk und je Frame kostet, auf dem Render-Thread und mit Worker.
- [2026-10-06, Minimap, rund gegen eckig](messungen/2026-10-06-minimap-rund.md): was die runde Minimap je Frame gegen die eckige kostet, im HUD-Element und in der Frametime.
- [2026-10-07, Minimap, 8 und 16 px](messungen/2026-10-07-minimap-8-16px.md): was die Minimap bei 8 und 16 Pixeln je Block kostet, je Chunk, je Region und je Frame.
- [2026-10-09, Minimap, Drehen](messungen/2026-10-09-minimap-drehen.md): was die drehende Minimap je Frame kostet, eckig und rund, im Stand und im Flug; rund gedreht ist billiger als ungedreht.
- [2026-10-09, Selbst gezeichnete Karte, Schreiben der Kacheln](messungen/2026-10-09-selbst-schreiben.md): was `Kachelwerk.schreibe` je Durchlauf kostet und wie viele PNG es je Stunde schreibt, für eine Farm und einen Flug.
- [2026-10-06, Vollbildkarte, Übernahme der Kacheln](messungen/2026-10-06-vollbildkarte-uebernahme.md): was eine Kachel den Render-Thread kostet, mit und ohne Kopieren der Pixel dort, und wie schnell der Dekoder liefert.
