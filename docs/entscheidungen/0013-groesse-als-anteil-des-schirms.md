---
title: "0013: Die Grösse der Minimap als Anteil des Schirms"
description: Warum die Seite der Minimap ein Anteil der kürzeren Seite des Schirms ist, so dass sie dem Fenster folgt und der GUI-Massstab sie nicht mehr ändert, und wie alte Einstellungen ohne Sprung übergehen.
status: gilt
date: 2026-10-10
issues: [77]
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Einstellungen.java
---

# 0013: Die Grösse der Minimap als Anteil des Schirms

## Anlass

Rückmeldung des Users zu 0.2.13 (mod#77): Wird das Fenster kleiner, soll
die Minimap kleiner werden. Bisher war ihre Seite eine feste Zahl von
Einheiten des GUI. Sie folgte also nur dem GUI-Massstab und belegte in
einem kleinen Fenster einen grösseren Teil.

## Entscheidung

- **Die Seite ist ein Anteil** der kürzeren Seite des Schirms, gespeichert
  als `groesse_anteil`. In jedem Fenster belegt die Minimap denselben Teil.
  Der GUI-Massstab ändert ihre Grösse in Pixeln nicht mehr.
- **Ziehen am Griff** setzt den Anteil im Fenster, in dem man zieht,
  zwischen 64 und 256 Einheiten. In einem kleineren Fenster kann die Seite
  unter 64 liegen.
- **Höchstens 256 Einheiten,** wie bisher, und höchstens so gross, wie der
  Schirm Platz hat. Darüber wächst sie nicht mit dem Fenster.
- **Alte Einstellungen** nennen `groesse` in Einheiten; eine frische
  Installation hat die Vorgabe 128. Beides gilt im ersten Schirm und wird
  dort zum Anteil. So springt nichts.
- **Der Zoom** bleibt in Einheiten je Block. Kleiner heisst also weniger
  Gegend, und jeder Texel bleibt ganz auf dem Schirm.

Weg (a) aus dem Plan zu mod#77, gewählt vom Reviewer am 10.10.

## Verworfene Alternativen

- **Seite mal GUI-Massstab mal Fenster durch das Fenster beim Einstellen:**
  Sie folgte Fenster und GUI-Massstab. Mit dem automatischen GUI-Massstab,
  der Vorgabe des Spiels, schrumpfte sie doppelt: von 1920 × 1080 auf
  1280 × 720 auf die Hälfte statt auf zwei Drittel.
- **Ohne Obergrenze:** Bei festem kleinem GUI-Massstab auf einem grossen
  Schirm hätte die Seite weit über 256 Einheiten, bei 3840 × 2160 und
  GUI-Massstab 1 in der Vorgabe 1152, gezogen bis rund 2090. Die
  Chunklinien kosteten bei Zoom 1× dann rund 1,1 Millionen Schritte je
  Frame statt höchstens 21 000, siehe [Minimap](../minimap.md),
  „Chunklinien“. Das Vieleck der runden Minimap ragte an seinen Ecken
  r · (1/cos(π/64) − 1), bei r = 1045 Pixeln 1,3 Pixel, über n/2 hinaus,
  weiter als der Umriss von einem Pixel reicht, siehe
  [Minimap](../minimap.md), „Form“. Mit dem automatischen GUI-Massstab
  hat der Schirm im Querformat 240 bis knapp 480 Einheiten auf der
  kürzeren Seite; dort greift die Grenze erst ab einem Anteil von gut der
  Hälfte.
- **Der Zoom mit der Seite:** Dieselbe Gegend auf kleinerem Raum hiesse
  Texel, die nicht ganz auf Pixel aufgehen, siehe [Minimap](../minimap.md),
  „Bedienung“: unscharf.

## Folgen

- **Kosten wie bisher:** Die Seite bleibt bei höchstens 256 Einheiten,
  alle Schranken in [Minimap](../minimap.md), „Kosten“ und „Chunklinien“,
  gelten weiter.
- **Grosse Fenster** bei festem GUI-Massstab: Die Minimap wächst bis 256
  Einheiten mit, darüber nicht mehr.
- **Ein anderes Fenster** ändert die Reichweite; die Minimap passt den
  Bereich im nächsten Frame an.
