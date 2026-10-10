---
title: "0012: Marken statt Verzierungen"
description: Warum der Rahmen nur noch die Marken N, O, S, W zeigt, genordet fest und gedreht starr mitdrehend, ohne zier in den Ecken, warum ein Schalter sie abstellt und warum der Abstand zum Rand mit der halben Diagonale der grössten Marke rechnet; löst 0005 in Ornamenten und Marken ab.
status: gilt
date: 2026-10-10
issues: [77]
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Skin.java
  - src/main/java/com/nekyia/heroicmap/Anzeige.java
---

# 0012: Marken statt Verzierungen

## Anlass

Rückmeldung des Users zu 0.2.13 (mod#77): An der drehenden Minimap stören
die Teile des Rahmens, die nicht mitdrehen, die zier in den Ecken und die
Marken N, O, S, W, deren Bilder aufrecht blieben. Am 10.10. hat der User
das zweimal nachgeschärft:

1. Nicht entfernen, sondern mitdrehen; nichts steht fest; ein Schalter
   stellt sie ab.
2. Die Verzierungen sind zu viel: nur noch die Marken, genordet wie
   gedreht; die zier in den Ecken fällt ganz weg.

## Entscheidung

- **Nur Marken:** Der Rahmen zeigt N, O, S und W auf der Mitte der Bänder,
  rund auf dem Kreis, eckig auf dem Quadrat. In den Ecken steht nichts
  mehr; die Bilder der zier sind aus dem Jar genommen, in den Quellen von
  Aseprite bleiben ihre Ebenen.
- **Genordet fest:** oben, rechts, unten und links, auf ganzen Einheiten.
- **Gedreht starr mit der Karte:** die Lage in ihrer Himmelsrichtung im
  Kartenbild, das Bild um seine Mitte um denselben Winkel. Die Mitte liegt
  auf ganzen Pixeln des Schirms, so zittert beim Laufen nichts. Die Kanten
  der Pixelkunst werden treppig wie bei der gedrehten Karte; so will es
  der User.
- **Ein Bild je Marke:** N `norden`, S `marke`, O und W `marke_quer`, fest
  je Richtung.
- **Der Schatten** bleibt auf dem Schirm um (+1, +1).
- **Der Griff** im Menü steht fest an seiner Ecke, zuoberst; er ist zum
  Ziehen da.
- **Schalter „Verzierungen“** im Untermenü, in der Zeile von „Drehen“,
  Vorgabe an. Er stellt die Marken ab; aus zeigt der Rahmen nur Bänder
  oder Ring.
- **Abstand zum Rand:** die halbe Diagonale der grössten Marke,
  aufgerundet, immer, auch genordet und mit dem Schalter aus. So bleibt
  jede Marke in jeder Drehung ganz auf dem Schirm, und die Minimap springt
  beim Umschalten nicht.

Entschieden vom User am 10.10., Pläne vom Reviewer freigegeben.

## Verworfene Alternativen

- **Gedreht nur die Bänder:** zuerst geplant und gebaut; der User wollte
  die Verzierungen behalten.
- **zier und Marken drehen mit:** gebaut, mit Messung; dem User waren es
  zu viele.
- **Marken nur gedreht:** Genordet hätte der Rahmen dann nichts ausser
  den Bändern; der User will die Marken in beiden Modi.
- **Nur die Lage dreht, das Bild bleibt aufrecht:** pixelgenau, aber
  genau das störte an den Marken.
- **Den Abstand nur gedreht vergrössern:** Die Minimap spränge beim
  Umschalten von Drehen um bis zu 6 Einheiten.

## Folgen

- **Die Minimap rückt mit Rahmen 1 bis 6 Einheiten weiter vom Rand:**
  `biom` 11 statt 6, `uhr` 13 statt 8, `kompass` und `kartograph` 13
  statt 7, `grau`, `holz` und `papier` 5 statt 4. Den Ausschlag gibt
  `norden`, bis 17 × 17 Pixel gross.
- **Je Frame 4 Bilder** für die Marken, mit Schatten 8, gedreht je mit
  einer gedrehten Pose. Gemessen ist die Fassung mit zier, gedreht 8
  Bilder, siehe [Minimap, Verzierungen drehen mit](../messungen/2026-10-10-minimap-verzierungen.md);
  sie bleibt als obere Schranke.
- **Die Beschreibungen der Skins** nennen keine Ecken mehr.
