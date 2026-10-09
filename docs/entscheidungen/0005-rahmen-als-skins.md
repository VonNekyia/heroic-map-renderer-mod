---
title: "0005: Rahmen als umschaltbare Skins"
description: Warum die Rahmen der Karte als Skins zur Wahl kommen, ohne Rahmen als Vorgabe, mit Bändern aus Code und Ornamenten als Bilder, ohne Nordmarke vor der Drehung, und wie Griff und Vollbildkarte damit umgehen.
status: gilt
date: 2026-10-09
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Skin.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
---

# 0005: Rahmen als umschaltbare Skins

## Anlass

Der User wünscht sich am 09.10. einen schlanken Rahmen um die Karte mit
Ornamenten. Der Designer hat zwei Runden Entwürfe gezeichnet: drei breitere
Bänder mit Licht und Schatten, drei feine Linien mit grossen Ornamenten.
Statt einen zu wählen, will der User alle zur Wahl.

## Entscheidung

- **Alle Entwürfe als Skins** zur Wahl im Untermenü „Einstellungen …“.
  **Vorgabe ohne Rahmen**, der Umriss wie bisher.
- **Bänder aus Code** in festen Farben aus `palette.txt`, eckig als
  Rechtecke, rund als Ring; die runde Karte mit derselben Rechnung als
  Maske. **Ornamente als Bilder** je Skin.
- **Keine Nordmarke ohne Drehung:** N und die Marken O, S, W wandern nur mit
  der drehenden Minimap am Rahmen; ohne Drehung gibt es sie nicht (User:
  „sonst weg“).
- **Der Griff** steht im Menü an seiner Ecke statt der zier, auch rund, bei
  45°.
- **Die Vollbildkarte** rückt den Rahmen um die halbe zier ein; Knöpfe,
  Zeile und Marken rücken nach innen, der Rahmen bleibt ganz.
- **Namen** in den Sprachdateien wie in Minecraft üblich, nicht in
  `info.txt`.

Entschieden vom User und vom Reviewer am 09.10.

## Verworfene Alternativen

- **Ein Rahmen für alle:** Der User wollte die Wahl.
- **`nine_slice` aus dem Atlas des GUI:** Kanten würden gekachelt, rund
  ginge es nicht; die Bänder aus Code sind in jeder Grösse genau.
- **Ornamente gespiegelt über die Pose:** Das GUI verwirft Rückseiten, siehe
  [Rahmen](../rahmen.md), „Dateien“.
- **Den Rahmen für die Knöpfe der Vollbildkarte aussparen:** Ein Rahmen mit
  Lücken sieht kaputt aus; die Knöpfe rücken nach innen.

## Folgen

- **Ein neuer Skin** braucht einen Ordner, einen Eintrag in `Skin.NAMEN` und
  zwei Schlüssel je Sprache.
- **Mit Rahmen** hält die Minimap mehr Abstand zum Rand, bis 8 Einheiten bei
  `uhr`.
- **Die Marken** aus dem Paket liegen bereit und kommen mit der PR zur
  Drehung ins Jar.
