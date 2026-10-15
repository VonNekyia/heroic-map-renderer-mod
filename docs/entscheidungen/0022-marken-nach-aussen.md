---
title: "0022: Marken nach aussen"
description: Warum bei „kompass“ und „biom“ das Bild jeder Marke von der Mitte der Minimap weg zeigt, statt mit der Karte nach Norden, und warum die anderen Skins starr bleiben; ergänzt 0012.
status: gilt
date: 2026-10-15
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Skin.java
---

# 0022: Marken nach aussen

## Anlass

Nach [0012](0012-marken-statt-verzierungen.md) drehen die Marken starr mit
der Karte: Alle Bilder zeigen gleich nach Norden, mit Blick nach Süden
alle nach unten. Bei „biom“ sind O, S und W Bilder mit oben und unten,
Rohrkolben, Berg, Welle, Blume; dort fällt das auf. Der User am 15.10.:
Die Verzierungen des Kompass sollen nach aussen zeigen, nicht nach
Norden; „jetzt zeigt alles nach unten und nicht nach aussen“.

## Entscheidung

- **Nach aussen:** Das Bild jeder Marke zeigt von der Mitte weg; N wie
  bisher, O, S und W um 90°, 180° und 270° weiter, genordet wie gedreht.
- **Ein Bild für O, S und W:** `marke`, gezeichnet für oben; `marke_quer`
  bleibt bei diesen Skins ungenutzt.
- **Je Skin ein Schalter** `aussen=ja` in `info.txt`, gesetzt bei
  „kompass“ und in jeder Kategorie von „biom“.
- **Lage, Schatten und Abstand zum Rand** bleiben wie in 0012.

Gewünscht vom User am 15.10.

## Verworfene Alternativen

- **Eckig aufrecht, senkrecht zur Seite:** zuerst gebaut; für die Sterne
  von „kompass“ dasselbe, für die Bilder von „biom“ zeigte unten alles
  nach oben statt nach aussen.
- **Nach aussen für alle Skins:** Bei „grau“, „holz“ und „papier“ ist
  `norden` ein Buchstabe und `marke_quer` ein eigener Balken für die Seite;
  dort sieht starr mit der Karte richtig aus.

## Folgen

- 0012 gilt weiter für die anderen Skins.
- Siehe [Rahmen](../rahmen.md), „Nach aussen“.
