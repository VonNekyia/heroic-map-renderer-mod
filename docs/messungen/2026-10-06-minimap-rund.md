---
title: Minimap, rund gegen eckig
description: Was die runde Minimap je Frame gegen die eckige kostet, im HUD-Element und in der Frametime, bei freier Bildrate und 144 fps, mit 4 Pixeln je Block; dazu eckig nach dem Wechsel von Scissor auf Läufe.
date: 2026-10-06
commits: [25d0ec4]
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Minimap, rund gegen eckig

Rund kostet die Minimap im HUD-Element je Frame im Median 0,33 ms, eckig
0,003 ms. Bei freier Bildrate steigt die Frametime im p50 um 0,40 ms, im
p95 um 0,48 ms; bei 144 fps fängt die Grenze der Bildrate das auf, das p50
bleibt gleich. Eckig kostet nach dem Wechsel von Scissor auf Läufe so viel
wie vorher. Gemessen mit 4 Pixeln je Block, 128 Einheiten Seite, bei
GUI-Massstab 2, also 256 Pixeln Seite und 149 Läufen.

## Aufbau

Wie [Minimap, Kosten](2026-10-05-minimap-kosten.md), „Aufbau“, mit diesen
Unterschieden:

- **Läufe der Bildrate:** frei und 2 px eckig, frei und 4 px eckig, frei
  und 4 px rund, 144 fps und 4 px eckig, 144 fps und 4 px rund, 60 fps und
  4 px eckig; rund gleich nach eckig, damit sich die Maschine dazwischen
  nicht ändert.
- **Zeit im HUD:** Zwei Elemente des HUD direkt vor und nach dem der
  Minimap (`HudElementRegistry.attachElementBefore`, `attachElementAfter`)
  stoppen je Frame, was die Minimap dort kostet: `arbeite` und das
  Zeichnen in den Zustand des GUI. Das Zeichnen auf der Grafikkarte steckt
  nur in der Frametime.
- **Lauf:** 06.10., 13:32 bis 13:43, `25d0ec4`, unter der Sperrdatei; vorher
  keine fremde Sperre, kein Spiel, Last 7 %. Jede Bildrate wartete auf freie
  Frames, keine lief im Takt der Ticks.

## Ergebnis

Median der drei Läufe, Frametime ohne und mit Minimap, dazu die Zeit im
HUD-Element mit Minimap:

| Bildrate | Form | Lauf | p50 | p95 | HUD p50 | HUD p95 |
|---|---|---|---|---|---|---|
| frei | eckig | Stand | 0,296 → 0,316 ms | 0,470 → 0,511 ms | 0,003 ms | 0,004 ms |
| frei | eckig | Flug | 0,331 → 0,272 ms | 0,597 → 0,521 ms | 0,003 ms | 0,004 ms |
| frei | rund | Stand | 0,300 → 0,704 ms | 0,528 → 1,011 ms | 0,331 ms | 0,428 ms |
| frei | rund | Flug | 0,319 → 0,466 ms | 0,588 → 0,803 ms | 0,159 ms | 0,322 ms |
| 144 fps | eckig | Stand | 7,151 → 7,149 ms | 21,14 → 21,13 ms | 0,005 ms | 0,008 ms |
| 144 fps | eckig | Flug | 7,152 → 7,150 ms | 21,17 → 21,24 ms | 0,005 ms | 0,017 ms |
| 144 fps | rund | Stand | 7,155 → 7,143 ms | 21,16 → 20,76 ms | 0,331 ms | 0,421 ms |
| 144 fps | rund | Flug | 7,140 → 7,145 ms | 21,24 → 21,04 ms | 0,168 ms | 0,347 ms |

- **Eckig** liegt bei freier Bildrate im p95 höchstens 0,04 ms über „aus“,
  wie mit Scissor gemessen (höchstens 0,18 ms). Dass die Läufe im Flug mit
  Minimap schneller sind als ohne, ist Streuung zwischen den Läufen.
- **Rund** schickt je Frame etwa 450 Elemente an das GUI: 149 Läufe für den
  Rand, dazu je Lauf ein Rechteck je Region, die er schneidet, meist zwei.
  Das kostet etwa 0,7 µs je Element. Im Flug ist das HUD billiger;
  vermutlich fehlen dort zeitweise Regionen, die noch nicht gezeichnet
  sind, nicht nachgeprüft.
- **Bei 144 fps** ist ein Frame 6,9 ms lang; rund belegt davon 0,33 ms auf
  dem Render-Thread, etwa 5 %. Das p95 bei 144 fps streut zwischen den
  Läufen mehr, als die Minimap kostet.
- **Mit der Seite** wachsen die Läufe linear: bei 256 Einheiten und
  GUI-Massstab 2 etwa doppelt so viele.

## Schluss

- **Eckig bleibt die Vorgabe** und kostet so viel wie vorher.
- **Rund ist eine Option** für 0,33 ms je Frame. Billiger ginge es auf zwei
  Wegen, beide nicht gebaut: Läufe in Einheiten des GUI statt in Pixeln des
  Schirms, etwa halb so viele Elemente, mit Stufen von einer Einheit am
  Rand; oder eine Maske im Shader, ein Element je Region, mit eigener
  Pipeline.
