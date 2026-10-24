---
title: "0033: Das Band nach der Formel, in Pixeln des Schirms"
description: Warum der Mod die Bänder der Rahmen nach der Formel des Designers in Pixeln des Schirms rechnet, eckig aus einer kleinen Textur der Ecken und rund als Ring, und nur die Texturen behält, die zu sehen sind; ergänzt 0032.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Skin.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Waehler.java
---

# 0033: Das Band nach der Formel, in Pixeln des Schirms

## Anlass

Der Designer hat zum neuen Satz der Rahmen eine Formel für die Bänder
geliefert: ein weicher Übergang zwischen den Bändern, eine Fase je Band,
ein Schatten auf die Karte und eine weiche Grenze zwischen Licht und
Schatten. Bis 0.2.68 war jedes Band eine Einheit in einer Farbe. Der Plan
lag beim Reviewer, er hat ihn am 24.10. als zweite von drei PRs der
Rahmen freigegeben, den Ring bis 4 MiB eingeschlossen.

## Entscheidung

- **Gerechnet in Pixeln des Schirms,** k je Einheit des GUI, k der
  GUI-Massstab. Die Formel rechnet in Texeln zu 4 je Einheit; ein Pixel
  ist 4/k Texel. Die Formel steht in [Rahmen](../rahmen.md), „Bänder“.
- **Eckig aus einer Textur der Ecken:** ein Quadrat der Seite 2c + 1
  Einheiten mit c = Bänder + 2, gezeichnet in 8 Stücken, die Ecken 1:1,
  die mittlere Einheit gestreckt. Bis c Einheiten vom Rand hängt die Farbe
  nur vom Abstand zur nächsten Kante ab; der Test vergleicht jeden Pixel
  mit der Formel für die ganze Seite.
- **Rund als Ring,** eine Textur von (s · k)² Pixeln, wie bisher nur mit k
  Pixeln je Einheit statt einem.
- **Der Schatten auf die Karte** liegt in derselben Textur unter den
  Bändern.
- **Nur, was zu sehen ist, bleibt:** Alle Texturen der Bänder liegen in
  einer Liste; eine neue gibt jede frei, die 2 s nicht gezeichnet wurde.

## Verworfen

- **Eine Textur in Texeln, 4 je Einheit, mit Nearest gezeichnet:** Bei
  GUI-Massstab 3 fiele jeder vierte Texel weg, ungleichmässig. So rät es
  auch der Designer.
- **Der Ring aus Vierecken mit Farben je Ecke, ohne Textur:** spart den
  Speicher, kostet aber jeden Frame mehr Arbeit, und die Minimap zeichnet
  jeden Frame.
- **Eckig eine Textur der ganzen Seite wie rund:** bis 4 MiB statt
  14 KiB, ohne dass ein Pixel anders aussähe.
- **Je Variante zwei Ringe wie bis 0.2.68:** Mit `biom` hätte jede
  gezeigte Kategorie ihren Ring behalten, bei 256 Einheiten und
  GUI-Massstab 4 bis 8 × 4 MiB.

## Folgen

- Bei 256 Einheiten und GUI-Massstab 4 braucht der Ring 4 MiB im
  Arbeitsspeicher und so viel auf der Grafikkarte, bis 0.2.68 256 KiB.
- Ein Wechsel des Bioms oder der Seite rechnet einen Ring neu, einmal je
  Seite und GUI-Massstab.
- Die runde Karte reicht weiter √2/2 Einheiten unter die Bänder; der Test
  prüft sie gegen den weichen Ring.
- Die HD-Bilder der Marken folgen in der dritten PR.
