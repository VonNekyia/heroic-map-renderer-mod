---
title: "0005: Rahmen als umschaltbare Skins"
description: Warum die Rahmen der Minimap als Skins zur Wahl kommen, ohne Rahmen als Vorgabe, nur um die Minimap, mit Bändern aus Code und Ornamenten im Atlas des GUI, ohne Nordmarke vor der Drehung, und wo der Griff sitzt.
status: gilt
date: 2026-10-09
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Skin.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
---

# 0005: Rahmen als umschaltbare Skins

Teilweise abgelöst durch [0006](0006-ein-weg-fuer-die-minimap.md): Die
runde Karte schneidet ein Vieleck, das unter den Ring reicht, keine Maske
mit derselben Rechnung wie der Ring.

## Anlass

Der User wünscht sich am 09.10. einen schlanken Rahmen um die Karte mit
Ornamenten. Der Designer hat zwei Runden Entwürfe gezeichnet: drei breitere
Bänder mit Licht und Schatten, drei feine Linien mit grossen Ornamenten.
Statt einen zu wählen, will der User alle zur Wahl („sind alle mega“).

## Entscheidung

- **Alle Entwürfe als Skins** zur Wahl im Untermenü „Einstellungen …“.
  **Vorgabe ohne Rahmen**, der Umriss wie bisher.
- **Nur um die Minimap,** im HUD und im Menü. Die Vollbildkarte bekommt
  keinen Rahmen (User).
- **Bänder aus Code** in festen Farben aus `palette.txt`, eckig als
  Rechtecke, rund als Ring; die runde Karte mit derselben Rechnung als
  Maske. **Ornamente als Sprites im Atlas des GUI**, gespiegelt über
  vertauschte Koordinaten im Atlas.
- **Keine Nordmarke ohne Drehung:** N und die Marken O, S, W wandern nur mit
  der drehenden Minimap am Rahmen; ohne Drehung gibt es sie nicht (User:
  „sonst weg“).
- **Der Griff** steht im Menü an seiner Ecke statt der zier, auch rund, bei
  45°; der weisse Umriss entfällt mit Rahmen.
- **Namen** in den Sprachdateien wie in Minecraft üblich, nicht in
  `info.txt`. **Die Quellen von Aseprite** liegen im Repo, ausserhalb der
  Ressourcen.

Entschieden vom User und vom Reviewer am 09.10.

## Verworfene Alternativen

- **Ein Rahmen für alle:** Der User wollte die Wahl.
- **Ein Rahmen auch um die Vollbildkarte,** eingerückt, mit Knöpfen und
  Marken nach innen: gebaut und verworfen; der User will ihn nur um die
  Minimap.
- **`nine_slice` aus dem Atlas des GUI:** Kanten würden gekachelt, rund
  ginge es nicht; die Bänder aus Code sind in jeder Grösse genau.
- **Ornamente gespiegelt über die Pose:** Das GUI verwirft Rückseiten,
  siehe [Rahmen](../rahmen.md), „Ornamente“.
- **Eigene, schon gespiegelte Texturen je Ecke:** 16 Texturen je Skin und
  ein eigener Lader, und F3+T lüde sie nicht neu; über die Koordinaten im
  Atlas geht es ohne.

## Folgen

- **Ein neuer Skin** braucht einen Ordner, einen Eintrag in `Skin.NAMEN` und
  zwei Schlüssel je Sprache.
- **Mit Rahmen** hält die Minimap mehr Abstand zum Rand, bis 8 Einheiten bei
  `uhr`.
- **Die Marken** aus dem Paket liegen bei den Quellen bereit und kommen mit
  der PR zur Drehung ins Jar.
