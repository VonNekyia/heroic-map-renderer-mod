---
title: "0010: Die Tafel im Mod 200 Einheiten breit"
description: Der Inhalt einer Infotafel ist im Mod höchstens 200 Einheiten des GUI breit, mit 6 Rand, nicht 320 wie auf der Webkarte.
status: gilt
date: 2026-10-10
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Tafel.java
---

# 0010: Die Tafel im Mod 200 Einheiten breit

Der Inhalt einer Infotafel ist auf der Vollbildkarte des Mods höchstens
200 Einheiten des GUI breit, der Innenabstand 6 (`Tafel.BREITE`,
`Tafel.INNEN`). Die Webkarte nimmt 320 Pixel und 8 Rand.

## Grund

- 320 Einheiten wären bei GUI-Massstab 2 auf einem Schirm mit 1920
  Pixeln Breite 640 Pixel, ein Drittel des Schirms; bei grösserem
  Massstab fast die ganze Breite.
- 200 Einheiten und 6 Rand passen zum 9-Slice des Designers, 16 × 16 mit
  5 Rand.

## Folgen

- Der Inhalt umbricht im Mod früher als auf der Webkarte; ein Bild über
  200 Einheiten verkleinert der Mod.
- Im Format steht dazu der Satz „im Mod höchstens 200 Einheiten“; den
  bringt der Reviewer zum Frontend.

Entschieden vom Reviewer im Review von #52, am 10.10.
