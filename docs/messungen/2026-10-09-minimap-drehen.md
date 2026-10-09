---
title: Minimap, Drehen
description: Was die drehende Minimap je Frame kostet, eckig und rund, im HUD-Element und in der Frametime, im Stand und im Flug, mit 4 Pixeln je Block und Zoom 4; rund gedreht ist billiger als rund ungedreht.
date: 2026-10-09
commits: [d046f22]
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Drehung.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Minimap, Drehen

Eckig kostet das Drehen im Stand, schräg bei Gier 30, 0,017 ms Frametime im
p50 und 0,036 ms im p95; im Flug, um eine Vierteldrehung, nichts über der
Streuung; im HUD-Element rund 0,001 ms. Rund
ist gedreht billiger als ungedreht: im Stand 0,410 statt 0,715 ms Frametime
im p50, im HUD-Element 0,077 statt 0,335 ms. Ungedreht zeichnet die runde
Minimap je Region einen Blit je Lauf, gedreht ein Vieleck je Region.

## Aufbau

Wie [Minimap, rund gegen eckig](2026-10-06-minimap-rund.md), „Aufbau“, mit
diesen Unterschieden:

- **Läufe:** nur freie Bildrate, 4 Pixel je Block, Zoom 4, GUI-Massstab 2,
  128 Einheiten Seite; eckig und rund, je erst ohne, dann mit Drehen
  (`-PmessungDrehen=true`, `Messung.drehen`).
- **Blick:** im Stand Gier 30, so dreht die Karte schräg. Im Flug setzt
  `Messung.flug` die Gier jeden Tick auf ±90, in Flugrichtung; dort dreht
  die Karte um eine Vierteldrehung, die Regionen liegen achsenparallel.
- **Ohne und mit Minimap** abwechselnd, je drei Runden im Stand und im Flug.
  Im Flug fliegt ohne Minimap immer hin und mit Minimap immer zurück; die
  Frametime ohne und mit Minimap vergleicht im Flug darum zwei Strecken.
  Verglichen wird das Drehen mit Minimap, aus und an auf derselben Strecke.

## Ablauf

09.10., 16:09:58 bis 16:17:42, `d046f22`, unter der Sperrdatei. Vorher
dreimal in Folge, je eine Minute auseinander: keine fremde Sperre, kein
Spiel, kein Renderer aus `plugins\HeroicMap\bin`, Last unter 10 %, am Start
5 %. Während des Laufs alle 5 s geprüft: in 69 Proben kein Renderer; die
Last, im Median 25 %, kommt vom gemessenen Client selbst. Jede Form wartete
auf freie Frames. Die Zahlen stammen aus dem Bericht des Gametests,
`messung-drehen.txt`, ausgewertet als Median der drei Runden.

## Ergebnis

Frametime mit Minimap in ms, Median der drei Runden, in Klammern die
Spanne der Runden im p50; dazu die Zeit im HUD-Element.

| Form, Lauf | Drehen | p50 | p95 | HUD p50 | HUD p95 |
|---|---|---|---|---|---|
| eckig, Stand | aus | 0,321 (0,319–0,324) | 0,557 | 0,004 | 0,005 |
| eckig, Stand | an | 0,338 (0,331–0,338) | 0,593 | 0,005 | 0,007 |
| eckig, Flug | aus | 0,257 (0,254–0,266) | 0,481 | 0,004 | 0,005 |
| eckig, Flug | an | 0,259 (0,253–0,273) | 0,491 | 0,004 | 0,006 |
| rund, Stand | aus | 0,715 (0,698–0,720) | 1,000 | 0,335 | 0,459 |
| rund, Stand | an | 0,410 (0,394–0,413) | 0,670 | 0,077 | 0,110 |
| rund, Flug | aus | 0,449 (0,447–0,454) | 0,761 | 0,157 | 0,330 |
| rund, Flug | an | 0,328 (0,325–0,332) | 0,581 | 0,075 | 0,114 |

Ohne Minimap lag die Frametime im Stand bei 0,301 bis 0,307 ms im p50, in
allen vier Blöcken gleich.

## Schluss

- **Eckig** kostet das Drehen fast nichts: im Stand, schräg, 0,017 ms im
  p50; im Flug, um eine Vierteldrehung, innerhalb der Streuung. Schräg im
  Flug ist nicht gemessen. Mehr Chunks zeichnet die Minimap eckig
  gedreht zwar vor (√2), das trifft den Worker, nicht den Frame.
- **Rund** ist gedreht billiger als ungedreht. Ungedreht schneidet die
  Minimap jede Region an den Läufen der Form, bei 256 Pixeln Seite 149
  Blits je Region; gedreht ist es ein Vieleck mit höchstens 68 Ecken je
  Region. Dasselbe Vieleck könnte auch die ungedrehte runde Minimap
  zeichnen; das ist ein eigener Schritt.
- **Nachgemessen,** auch schräg im Flug:
  [Minimap, Vieleck auch ungedreht](2026-10-09-minimap-vieleck.md).
