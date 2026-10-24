---
title: "0049: Pflanzen gekippt"
description: Warum der Mod Bodenpflanzen 45° nach Norden kippt statt flach zu legen, sie entlang der Kipprichtung in den eigenen Block einpasst und im Wasser unter die Oberfläche klemmt, warum Bambusblätter und die Stiele von Kürbis und Melone dazukommen, und welche Varianten verworfen sind.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
  - src/main/java/com/nekyia/heroicmap/Pflanzen.java
---

# 0049: Pflanzen gekippt

## Anlass

Bis 0.2.70 lag die erste Fläche einer Bodenpflanze flach über dem Block,
wie im Renderer nach dessen 0108. Von oben sah man so ein Stück Textur,
nicht die Pflanze. Der User wollte gekippte Pflanzen sehen; verglichen hat
er echte Bilder der Minimap aus Gametests in der CI, zuerst bei 4, 2 und
1 px, dann auf seinen Wunsch nah bei 16, 8 und 4 px, mit Feld, Seegras,
Kelp, Wiese und Bambus. Am 24.10. wählte er 45° nach Norden, eingepasst.
Der Versuch steht auf dem Tag `versuch/pflanzen-kippen`.

## Entscheidung

- **Kippen:** jede Fläche einer Bodenpflanze, die nicht liegt, starr 45° um
  die waagrechte Achse in x durch die Mitte des Blockbodens, oben nach
  Norden; danach auf die alte Tiefe. Gezeichnet wird, was nach oben zeigt.
  Hat das Modell eine liegende Fläche, etwa ein Blütenteppich, bleibt es,
  wie es ist.
- **Einpassen:** Nur die gezeichneten Flächen zählen. Der Mod schiebt sie
  entlang der Kipprichtung so wenig wie nötig in den Block und staucht sie
  auf 1, wenn sie über mehr als 1 Block reichen. Quer dazu bleibt alles.
  Ohne das schnitt die Spalte ab, was über die Nordkante ragt; reifer
  Weizen endete oben in einer geraden Linie.
- **Im Wasser:** Eine gekippte Fläche reiht sich höchstens 1/64 unter der
  Wasseroberfläche ihres Blocks ein. Sonst stand Seegras im flachen Wasser
  ungetönt über der Oberfläche.
- **Dazu:** Bambusblätter kippen, die Stange bleibt. Die Stiele von Kürbis
  und Melone (`stem_growth*`, `stem_fruit`) zählen als Bodenpflanze. Kelp
  und Seegras kippen mit, Wunsch des Users.
- **Pflanzen aus Paketen** nach [0042](0042-pflanzen-aus-paketen.md): Ob
  ein Block Pflanze ist und welche Fläche liegt, bleibt; statt flach zu
  liegen, kippen ihre Flächen.
- Der Renderer zieht dieselbe Regel nach, mit demselben Winkel, derselben
  Achse, demselben Einpassen und Klemmen.
- Siehe [Minimap](../minimap.md), „Pflanzen von oben“.

## Verworfene Alternativen

- **Flach wie bisher (V0):** zeigt von oben nur ein Stück Textur.
- **60° nach Norden (V5):** flacher, aber dem bisherigen ähnlich; der User
  zog 45° vor.
- **Eine Richtung je Block (V4):** wirkt unruhig.
- **Das ganze Modell 90° (V6):** legt die Pflanze ganz um, ähnlich flach.
- **Gegenläufig oder nach aussen gespreizt (V2, V3), ein Fächer (V7):** nur
  als Skizze, schon dort schwächer.
- **Die Ausdehnung über alle Flächen messen:** Flächen, die beim Kippen in
  sich drehen, stehen hochkant und sind unsichtbar; mit ihnen würde ein
  `crop` auf 0,71 statt 0,94 gestaucht.
- **Einen Wählschalter für den Spieler:** nicht gewünscht; Minimap und
  Kacheln sollen gleich aussehen.

## Folgen

- Ein volles Kreuz verliert beim Stauchen seinen Versatz entlang der
  Kipprichtung; der quer dazu bleibt.
- Je Pflanze werden statt einer Fläche zwei bis vier abgetastet. Mit n × n
  Proben je Pixel kostete das im Worker je Chunk rund 0,5 ms mehr, bei 4 px
  +18 %; darum nehmen gekippte Flächen das gemittelte Raster, eine Probe je
  Pixel, entschieden vom Reviewer am 24.10. Siehe
  [Pflanzen gekippt, Zeit je Chunk](../messungen/2026-10-24-pflanzen-gekippt.md).
- Die eigene Karte zeichnet mit demselben Maler und kippt also mit.
