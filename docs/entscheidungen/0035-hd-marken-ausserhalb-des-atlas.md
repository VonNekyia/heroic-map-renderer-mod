---
title: "0035: HD-Marken ausserhalb des Atlas, je Marke gewählt"
description: Warum die HD-Bilder der Marken nicht im Atlas des GUI liegen, sondern nur für die gezeigte Variante geladen und je GUI-Massstab nach Fläche gemittelt werden, wie die Wahl je Marke gespeichert ist und wann der Griff HD zeigt; ergänzt 0032 und 0034.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Skin.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/MinimapKlick.java
---

# 0035: HD-Marken ausserhalb des Atlas, je Marke gewählt

## Anlass

Der Designer hat zu jedem Bild der Marken und des Griffs eine HD-Fassung
mit 4 Texeln je Einheit gezeichnet. Der Plan der dritten PR der Rahmen lag
beim Reviewer und wurde am 24.10. freigegeben: HD je Marke, die mittlere
Taste reihum Pixel, HD, aus. Drei Punkte weichen vom Plan ab oder fehlten
dort; der Reviewer hat sie am selben Tag freigegeben.

## Entscheidung

- **Ausserhalb des Atlas:** Die HD-Bilder liegen unter `textures/rahmen/`.
  Geladen werden sie beim ersten Zeichnen, nur für die gezeigte Variante
  und den GUI-Massstab, in der Liste der Texturen aus
  [0034](0034-band-nach-der-formel.md); was 2 s nicht gezeichnet wurde,
  fällt. Der Plan sah sie als `_hd` im Atlas des GUI vor.
- **Nach Fläche gemittelt für jeden GUI-Massstab ab 2,** mit
  vormultipliziertem Alpha; bei 4 unverändert. Der Plan nannte nur 2 und 3.
  Bei GUI-Massstab 1 die Pixelfassung.
- **Gespeichert als `verzierungen_hd`** neben dem bestehenden
  `verzierungen_aus`, statt `marken=N:hd,…` wie im Plan. Alte Dateien
  gelten unverändert, ein Umzug ist nicht nötig.
- **Der Griff in HD,** sobald eine Marke HD zeigt; im Plan fehlte das.
- **Die mittlere Taste** reihum Pixel, HD, durchsichtig; Vorgabe Pixel.

## Verworfen

- **HD im Atlas des GUI:** Dauerhaft 1,35 MiB Texel statt 48 KiB, und der
  Atlas kann auf die nächste Zweierpotenz wachsen. Zu sehen ist aber je
  Rahmen nur eine Variante. Arbeitsspeicher ist knapp.
- **Nearest aus dem Atlas bei GUI-Massstab 2 und 3:** HD würde körnig, bei
  3 auch ungleichmässig.
- **HD bei GUI-Massstab 1:** Ein Pixel je Einheit, gemittelt wäre es Brei;
  die Pixelfassung ist dafür gezeichnet.
- **Ein eigener Schalter für den Griff:** Er ist nur im Menü zu sehen, ein
  viertes Ziel auf wenigen Einheiten lohnt nicht.

## Folgen

- Das Jar wächst um 569 KiB für 224 HD-Bilder, die Quellen im Repository
  um 526 KiB.
- Je Teil und GUI-Massstab eine kleine Textur, bei GUI-Massstab 4 für den
  Griff 28 × 28 Pixel.
- Ein HD-Bild, das fehlt oder nicht die 4-fache Grösse hat, lässt den Teil
  in Pixeln.
