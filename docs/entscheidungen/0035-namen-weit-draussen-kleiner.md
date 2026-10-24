---
title: "0035: Namen an Wegpunkten, weit draussen kleiner"
description: Warum Wegpunkte Namen bekommen wie Regionen, ohne Biegung, und warum die Namen von Wegpunkten, Nadeln, Köpfen und Bannern im Mod weit draussen stufenlos kleiner werden und ausblenden, nach 0119 des Renderers umgerechnet, aber ohne dessen Animation.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Ebenen.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Wegpunkte.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
---

# 0035: Namen an Wegpunkten, weit draussen kleiner

## Anlass

- Wunsch des Users vom 24.10., über den Reviewer: Wegpunkte benennen wie
  Regionen. Plan mit Skizzen vom Reviewer freigegeben am 24.10.
- Dazu der Reviewer am 24.10.: Die Grösse der Namen nicht fest, sondern
  wie auf der Webkarte nach
  [0119](https://github.com/VonNekyia/heroic-map-renderer/blob/master/docs/entscheidungen/0119-namen-stufenlos-icons-fest.md):
  Symbole fest, Namen mit dem Zoom stufenlos kleiner, weit draussen aus.
  Das gilt auch für die Namen von Nadeln, etwa Städten („Stadtnamen im Mod
  kleiner“). 0119 selbst sagt noch, der Mod behalte die feste Grösse aus
  0097; dieser Wunsch hebt das für den Mod auf.

## Entscheidung

- **Namen an Wegpunkten** wie an Regionen: Feld, „Schrift“ ¼ bis 4,
  „Schriftart“ Karte oder Minecraft; keine „Biegung“, ein Punkt hat keinen
  Bogen. Unter der Raute in der Farbe des Wegpunkts mit dunkler Kontur.
  Der Todespunkt behält seine Zeit und bekommt keinen Namen.
- **Symbole fest:** Rauten, Nadeln, Köpfe und Banner bleiben auf jeder
  Stufe gleich gross, wie bisher.
- **Namen weit draussen kleiner,** umgerechnet aus 0119 mit dem Verhältnis,
  mit dem der Mod die Namen der Nadeln zeichnet: 10 Einheiten im Mod sind
  16 px auf der Webkarte (`Ebenen.NAME_GROESSE`).
  - Faktor t = min(1, Länge eines Blocks in Einheiten / (10 / 256)); das ist
    die Grenze „16 Blöcke je px“ aus 0119.
  - Ausblenden zwischen 5 und 3,125 Einheiten Schrift, den 8 und 5 px aus
    0119; ausgeblendet kein Ziel für den Zeiger.
  - An Wegpunkten gilt ihre „Schrift“ obendrauf: Ein grosser Name blendet
    später aus.
  - Gerade Namen um ihre Oberkante, Namen im Bogen um den Fuss.
- **Wo:** Namen von Wegpunkten, Nadeln, Köpfen und Bannern, auf der
  Vollbildkarte und, an angehefteten, auf der Minimap in deren Massstab.
  Namen von Regionen und Linien bleiben, wie sie sind; sie wachsen schon
  mit den Blöcken.

## Abweichung von 0119

- **Keine Animation von 250 ms.** Auf der Webkarte zoomt Leaflet mit
  Animation, und der Name folgt ihr. Im Mod zoomt die Karte in Rasten ohne
  Animation; ein nachlaufender Name sähe falsch aus. Er springt mit der
  Karte. So freigegeben vom Reviewer am 24.10.

## Verworfene Alternativen

- **Feste Grösse nach 0097 auch für Namen an Wegpunkten,** der Vorschlag im
  Plan: Weit draussen lägen die Namen vieler Wegpunkte übereinander. Der
  User will die Regel der Webkarte.
- **Eigene Schwellen für den Mod:** Dann sähen Karte im Spiel und
  Webkarte bei gleichem Massstab verschieden aus.

## Folgen

- 0119 im Renderer bekommt einen Satz, dass der Mod ihr folgt; das macht
  das Frontend, sobald dieser Schritt gemergt ist.
- Siehe [Wegpunkte](../wegpunkte.md), „Namen an Wegpunkten“, und
  [Ebenen](../ebenen.md), „Namen weit draussen“.
