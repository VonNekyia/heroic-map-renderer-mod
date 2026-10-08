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
- [Download](download.md): wie der Mod die Karte vom Plugin lädt, Kanal, Befehle, Zustimmung und Grösse, Sicherheit, Manifest, harte Grenzen, Ablage, Fortsetzen und Abgleich.
- [Vollbildkarte](vollbildkarte.md): Bedienung, welcher Satz, Stufen und Lupe, Kacheln lesen und behalten, Spieler und Koordinaten, das Bild aus dem Gametest und was fehlt.
- [Minimap](minimap.md): Bedienung, welcher Block oben liegt, Flächen aus dem Tesselator des Spiels, Pixel und Mittelung, Licht, Wasser, Blockentities, Decke, Neu zeichnen im Worker, Kosten, Bilder und was anders ist als `top-north`.

## Entwicklung

- [Bauen und testen](entwicklung.md): Versionen, Gradle, Tests, Gametests, Maustasten, CI und die Prüfung der Doku.

## Entscheidungen

- [0001](entscheidungen/0001-minimap-mit-dem-tesselator.md): Die Minimap mit dem Tesselator des Spiels.
- [0002](entscheidungen/0002-vollbildkarte-nur-vom-server.md): Die Vollbildkarte nur vom Server, ohne Live-Ebene.

## Messungen

- [2026-10-05, Minimap, Kosten](messungen/2026-10-05-minimap-kosten.md): was die Minimap je Chunk und je Frame kostet, auf dem Render-Thread und mit Worker.
- [2026-10-06, Minimap, rund gegen eckig](messungen/2026-10-06-minimap-rund.md): was die runde Minimap je Frame gegen die eckige kostet, im HUD-Element und in der Frametime.
- [2026-10-07, Minimap, 8 und 16 px](messungen/2026-10-07-minimap-8-16px.md): was die Minimap bei 8 und 16 Pixeln je Block kostet, je Chunk, je Region und je Frame.
- [2026-10-06, Vollbildkarte, Übernahme der Kacheln](messungen/2026-10-06-vollbildkarte-uebernahme.md): was eine Kachel den Render-Thread kostet, mit und ohne Kopieren der Pixel dort, und wie schnell der Dekoder liefert.
