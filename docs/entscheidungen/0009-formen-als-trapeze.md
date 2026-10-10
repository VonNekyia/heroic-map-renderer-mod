---
title: "0009: Füllungen als Trapeze, Budget an Ecken"
description: Warum der Mod die Füllung einer Fläche als Trapeze in der Welt rechnet statt als Blöcke je Reihe, und warum die Formen ein Budget an Ecken je Neubau haben statt einer Zeit je Frame.
status: gilt
date: 2026-10-10
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Trapeze.java
  - src/main/java/com/nekyia/heroicmap/Formen.java
---

# 0009: Füllungen als Trapeze, Budget an Ecken

## Anlass

Die erste Fassung der Formen (#47) füllte eine Fläche mit Blöcken: je Reihe
die Blöcke, deren Mitte innen liegt, gleiche Spannen übereinander als
Rechteck. Das Review fand zwei Fehler mit derselben Wurzel. Der Speicher
wuchs mit der Fläche; ein Teil unter 1 MiB konnte Gigabytes an Rechtecken
erzeugen. Und schräge Kanten wurden Treppen, die bei starkem Zoom über
den Rand ragten. Dazu fehlte eine Grenze für die Arbeit je Frame.

## Entscheidung

- **Trapeze:** Die Füllung sind Trapeze zwischen den z der Ecken, an
  Kreuzungen von Kanten geteilt, gerade/ungerade über alle Ringe. Eine
  Spanne läuft weiter, solange sie dieselben zwei Kanten hat. Der Speicher
  wächst mit den Punkten, die Kanten sind genau. Die Grenzen stehen in
  [Ebenen](../ebenen.md), „Grenzen“.
- **Budget an Ecken:** Ein Neubau der Formen legt höchstens 1 000 000
  Ecken; was darüber geht, fehlt. Bei gleicher Ansicht fehlt immer
  dasselbe.

Vorgeschlagen vom Reviewer, entschieden in Runde 1 und 2 von #47 am 10.10.

## Verworfen

- **Blöcke je Reihe:** Speicher mit der Fläche, Treppen an schrägen
  Kanten, siehe Anlass.
- **Ein Budget an Zeit je Frame**, wie beim Neuzeichnen der Karte: Dort
  kann ein Abzug auf den nächsten Frame warten, und das Bild bleibt
  richtig. Eine halb gezeichnete Ebene flackerte, und was fehlt, hinge an
  der Last der Maschine.
