---
title: Minimap, Vieleck auch ungedreht
description: Was die Minimap je Frame kostet, seit sie auch ungedreht mit dem Vieleck zeichnet, gegen main mit den Läufen, in einer Reihe; eckig und rund, mit und ohne Drehen, im Stand, im Flug und schräg im Flug, bei 4 Pixeln je Block und Zoom 4.
date: 2026-10-09
commits: [11e0f20, a2c96f1]
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Drehung.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Minimap, Vieleck auch ungedreht

Rund ohne Drehen sinkt die Frametime im Stand von 0,704 auf 0,327 ms im
p50, das HUD-Element von 0,165 auf 0,007 ms; rund kostet jetzt so viel wie
eckig. Auch rund gedreht wird billiger, im HUD-Element von 0,035 auf
0,008 ms, weil der Umriss ein Bild ist statt einer Füllung je Lauf. Eckig
bleibt gleich. Drehen kostet danach eckig und rund höchstens 0,011 ms im
p50, auch schräg im Flug.

## Aufbau

Wie [Minimap, Drehen](2026-10-09-minimap-drehen.md), „Aufbau“, mit diesen
Unterschieden:

- **Zwei Stände in einer Reihe:** zuerst main `11e0f20`, die runde Karte
  ungedreht mit Läufen, dann `a2c96f1`, mit dem Vieleck. Der Gametest ist
  in beiden derselbe, der des Branches.
- **Flug schräg:** Nach dem Flug mit Gier ±90 fliegt `Messung.drehen`
  dieselbe Strecke mit Gier 30 neben der Flugrichtung. So dreht die Karte
  auch im Flug schräg.
- **Ohne Rahmen,** wie in der Messung davor.

## Ablauf

09.10., 17:13:45 bis 17:37:45, unter der Sperrdatei; vorher `11e0f20` bis
17:25:46, danach `a2c96f1`. Vorher dreimal in Folge, je eine Minute
auseinander: keine fremde Sperre, kein Spiel, kein Renderer aus
`plugins\HeroicMap\bin`, Last unter 10 %, am Start 7 %. Während des Laufs
alle 5 s geprüft: in 214 Proben kein Renderer; die Last, im Median 23 %,
kommt vom gemessenen Client selbst. Die Zahlen stammen aus dem Bericht des
Gametests, ausgewertet als Median der drei Runden.

## Ergebnis

Frametime mit Minimap in ms, Median der drei Runden, in Klammern die
Spanne der Runden im p50; dazu die Zeit im HUD-Element. Vorher ist main,
nachher das Vieleck.

| Form, Lauf | Drehen | p50 vorher | p50 nachher | p95 vorher | p95 nachher | HUD p50 vorher | HUD p50 nachher |
|---|---|---|---|---|---|---|---|
| eckig, Stand | aus | 0,340 (0,328–0,349) | 0,318 (0,314–0,318) | 0,567 | 0,520 | 0,002 | 0,002 |
| eckig, Stand | an | 0,335 (0,334–0,347) | 0,329 (0,325–0,332) | 0,525 | 0,509 | 0,003 | 0,003 |
| eckig, Flug | aus | 0,265 (0,258–0,269) | 0,254 (0,249–0,264) | 0,551 | 0,438 | 0,002 | 0,002 |
| eckig, Flug | an | 0,260 (0,255–0,265) | 0,253 (0,251–0,258) | 0,444 | 0,438 | 0,002 | 0,002 |
| eckig, Flug schräg | aus | 0,264 (0,262–0,266) | 0,259 (0,258–0,261) | 0,434 | 0,412 | 0,002 | 0,002 |
| eckig, Flug schräg | an | 0,274 (0,270–0,277) | 0,267 (0,263–0,270) | 0,450 | 0,438 | 0,002 | 0,002 |
| rund, Stand | aus | 0,704 (0,703–0,717) | 0,327 (0,326–0,331) | 0,924 | 0,484 | 0,165 | 0,007 |
| rund, Stand | an | 0,383 (0,374–0,388) | 0,336 (0,333–0,337) | 0,551 | 0,494 | 0,035 | 0,008 |
| rund, Flug | aus | 0,443 (0,434–0,443) | 0,259 (0,259–0,261) | 0,699 | 0,425 | 0,077 | 0,007 |
| rund, Flug | an | 0,313 (0,310–0,315) | 0,261 (0,258–0,262) | 0,504 | 0,441 | 0,035 | 0,007 |
| rund, Flug schräg | aus | 0,443 (0,439–0,444) | 0,266 (0,265–0,268) | 0,687 | 0,423 | 0,076 | 0,006 |
| rund, Flug schräg | an | 0,324 (0,320–0,325) | 0,275 (0,271–0,276) | 0,512 | 0,446 | 0,035 | 0,007 |

Ohne Minimap lag die Frametime im Stand vorher bei 0,294 bis 0,310 ms im
p50, nachher bei 0,290 bis 0,297 ms, je Block der vier Formen.

## Schluss

- **Rund ungedreht** zeichnete je Frame einen Blit je Lauf und Region und
  davor den Umriss als Füllung je Lauf. Jetzt ist es ein Vieleck je
  Region und ein Bild für den Umriss. Die Minimap kostet im Stand
  0,03 ms Frametime über der ohne Minimap, vorher rund 0,4 ms.
- **Rund gedreht** zeichnete den Umriss ebenfalls je Lauf; das Bild spart
  im HUD-Element 0,027 ms.
- **Eckig** ändert sich nicht, im HUD-Element gleich. Vorher lag die
  Frametime auch ohne Minimap etwas höher; das ist die Streuung zwischen
  zwei Läufen, nicht der Code.
- **Drehen** kostet im p50 eckig 0,011 ms und rund 0,009 ms im Stand, im
  Flug nichts über der Streuung, schräg im Flug 0,008 und 0,009 ms.
- **Gegen die Messung davor** liegt das HUD-Element rund ungedreht auf main
  bei 0,165 statt 0,335 ms. Verglichen wird nur innerhalb dieser Reihe.
