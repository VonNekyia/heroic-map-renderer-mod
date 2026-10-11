---
title: "0020: Die Tafel mit eigenem Massstab"
description: Warum die Infotafel einen eigenen ganzen Massstab bis zum GUI-Massstab bekommt, der sie auf etwa ein Fünftel der Fensterbreite hält, und warum er auf 1920 × 1080 bei GUI-Massstab 2 geeicht ist.
status: gilt
date: 2026-10-11
issues: [121]
code:
  - src/main/java/com/nekyia/heroicmap/Tafel.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
---

# 0020: Die Tafel mit eigenem Massstab

## Anlass

Der User fand die Tafel bei der Vorgabe Auto zu gross, bei seinem
GUI-Massstab 2 genau richtig (mod#121). Auto ist auf 1920 × 1080
GUI-Massstab 4; die Tafel mit bis zu 212 Einheiten nahm dort gut 40 % der
Breite ein. Sie folgte dem GUI-Massstab wie die Menüs, gemessen bei GS 2
und 3; das war das Problem, kein Fehler im Zeichnen.

## Entscheidung

- **Ein eigener, ganzer Massstab** g' bis zum GUI-Massstab: der grösste, bei
  dem die breiteste Tafel höchstens etwa 22 % der Breite und die Hälfte der
  Höhe des Fensters in Pixeln einnimmt. Gezeichnet mit der
  Pose g'/GUI-Massstab, so bleiben die Pixel der Schrift ganz und scharf.
- **Geeicht** so, dass 1920 × 1080 bei GUI-Massstab 2 unverändert bleibt;
  dafür steht im Code 23 % statt 22 %.
- **Untergrenze** g' ≥ min(GUI-Massstab, 2): Kleiner als bei GUI-Massstab 2
  wird die Tafel nur, wenn der Spieler selbst 1 gewählt hat. Ohne sie wäre
  die Tafel in Fenstern bis 1280 × 720 mit g' = 1 zu klein gewesen, die
  Schrift kleiner als auf den Knöpfen; nachgezogen vom Reviewer nach den
  Bildern.
- **Bilder** bleiben bei der Regel der Infotafel, in Pixeln von g' statt
  des GUI-Massstabs.
- **Treffer der Maus** auf die Tafel rechnen ihren Kasten zurück in
  Einheiten des GUI.
- Die 200 Einheiten aus [0010](0010-tafel-200-einheiten.md) bleiben,
  gemessen in Einheiten der Tafel.

Entschieden vom Reviewer am 11.10.

## Verworfene Alternativen

- **Die Pose genau auf eine Breite in Pixeln:** Ein Massstab, der kein
  ganzer ist, verteilt die Pixel der Schrift ungleich; die Tafel stünde
  unscharf neben scharfen Menüs.
- **Weniger Inhalt bei grossem GUI-Massstab:** Der Inhalt käme dann vom
  GUI-Massstab ab, nicht von der Tafel.

## Folgen

- In kleinen Fenstern bleibt die Tafel wie bei GUI-Massstab 2, auch wenn
  sie dann mehr als ein Fünftel der Breite einnimmt.
- Siehe [Ebenen](../ebenen.md), „Infotafel“.
