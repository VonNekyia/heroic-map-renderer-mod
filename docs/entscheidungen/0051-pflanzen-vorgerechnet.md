---
title: "0051: Gekippte Pflanzen vorgerechnet"
description: Warum der Mod die gekippte Draufsicht einer Pflanze je Modell und Versatz einmal vorrechnet und je Block nur noch ins Licht legt, mit dem Licht im Mittel je Fläche, der Reihenfolge vor dem Einreihen unter das Wasser und einem begrenzten Vorrat, und welche Wege verworfen sind.
status: gilt
date: 2026-10-25
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
---

# 0051: Gekippte Pflanzen vorgerechnet

## Anlass

Mit [0049](0049-pflanzen-gekippt.md) tastet der Maler je Pflanze zwei bis
vier gekippte Flächen ab, je Pixel mit dem Test, ob er im Dreieck liegt,
und rechnet die Farbe nach sRGB um. Gegen die flache Fläche davor kostete
das im Worker je Chunk +6 bis +9 % bei 4 und 8 px, das Ziel waren
höchstens +5 %. Licht nur für gezeichnete Flächen brachte nichts
Messbares. Der Reviewer gab am 25.10. den Auftrag, die gekippte Draufsicht
je Zustand und Versatz vorzurechnen, mit einem Aufpreis nahe null.

## Entscheidung

- **Vorrechnen:** Je Satz Flächen aus dem Tesselator und Versatz des
  Blocks rechnet der Maler einmal, was gekippt in jedem Pixel liegt: je
  Fläche, die höchste zuerst, ihren Anteil am Pixel und ihre Farbe in sRGB,
  dazu die Deckung aller zusammen. Je Block legt er das nur noch in das
  Licht der Flächen.
- **Licht im Mittel je Fläche,** nicht je Ecke über das Dreieck wie bei
  einer Oberseite. Flach beleuchtet haben alle Ecken einer Fläche ohnehin
  dasselbe Licht; unter den Vorlagen des Spiels hat nur die von Seegras
  Umgebungsverdeckung. Die flache Fläche bis 0.2.70 nahm ebenso das
  Mittel.
- **Reihenfolge im Block** nach der Höhe vor dem Einreihen unter das
  Wasser. Vorher galt die geklemmte Höhe, und im Wasser lagen alle Flächen
  gleich hoch in der Reihenfolge des Modells.
- **Schlüssel:** die Flächen als dieselben Objekte, dazu der Versatz in x
  und y; der in z nur, wenn die Pflanze gekippt nicht über mehr als 1 Block
  reicht, sonst legt das Einpassen sie ohnehin an den Nordrand.
- **Vorrat begrenzt** je Maler auf höchstens 4096 und 2¹⁸ / scale²
  Draufsichten, die am längsten nicht gebrauchte fällt zuerst.
- Siehe [Minimap](../minimap.md), „Vorgerechnet je Modell und Versatz“.

## Verworfene Alternativen

- **So lassen (A):** Der Reviewer hat #217 so gemergt; der Aufpreis von
  +6 bis +9 % blieb dabei als Auftrag offen.
- **Je Pixel die Gewichte der Ecken mit vorrechnen,** um das Licht wie bei
  einer Oberseite zu verteilen: doppelt so viel Speicher, und es ändert nur
  Seegras.
- **Den Tesselator für bekannte Pflanzen überspringen:** Licht und Tönung
  müsste der Mod dann selbst nachbauen, für jede Art Modell; die flache
  Fläche bis 0.2.70 rief ihn ebenso.
- **Gleiche statt derselben Flächen als Schlüssel:** Die Flächen sind
  Records, gleich wären auch neu gebaute. Ihr Hash kostet je Block mehr,
  und ein Modell, das je Block neue Flächen baut, ist selten.
- **Ohne eigenen Fall für den Versatz in z:** Eine Wiese mit zehn Arten
  bräuchte bei 16 px über 2500 Draufsichten statt rund 160.

## Folgen

- Seegras und Pflanzen aus Paketen mit Umgebungsverdeckung bekommen das
  Licht je Fläche gemittelt statt je Ecke verteilt.
- Ein Modell, das je Block neue Flächen baut, rechnet jedes Mal neu und
  kostet so viel wie ohne Vorrat, dazu das Einfügen.
- **Kosten:** Messung folgt, unter denselben Bedingungen wie Reihe 2 und 3
  von [Pflanzen gekippt, Zeit je Chunk](../messungen/2026-10-24-pflanzen-gekippt.md).
