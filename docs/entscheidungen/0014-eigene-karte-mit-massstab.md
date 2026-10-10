---
title: "0014: Die eigene Karte mit 1, 2 oder 4 Pixeln je Block"
description: Warum die selbst gezeichnete Karte 1, 2 oder 4 Pixel je Block zur Wahl hat, wie die Karten des Servers, warum nicht 8 und 16, warum der Massstab bis zum Löschen fest steht und warum der Baum aussieht wie ein Download; löst 0004 im Massstab ab.
status: gilt
date: 2026-10-10
issues: [78]
code:
  - src/main/java/com/nekyia/heroicmap/Selbst.java
  - src/main/java/com/nekyia/heroicmap/Auswahl.java
---

# 0014: Die eigene Karte mit 1, 2 oder 4 Pixeln je Block

## Anlass

Rückmeldung des Users zu 0.2.13 (mod#78): Bei der Wahl „Selbst“ soll man
den Massstab wählen können, wie bei den Karten des Servers. Bisher zeichnete
der Mod sie immer mit 4 Pixeln je Block
([0004](0004-karte-selbst-zeichnen.md)).

## Entscheidung

- **1, 2 oder 4 Pixel je Block,** Vorgabe 4: Die Karten des Servers bieten
  dieselben drei.
- **Fest bis zum Löschen:** Der Massstab gilt für den Baum, bis der Spieler
  ihn in der Kartenliste löscht. Einen anderen gibt es nur mit einer neuen
  Karte.
- **Wie ein Download:** `satz.json` nennt den Massstab, die Kacheln liegen
  im Ordner des Massstabs, `map.json` ist für jeden gleich (`scale` 4,
  `maxZoom` 8). Die feinste Stufe ist 8, 7 oder 6 (`Satz.stufe`), Stufe 0
  deckt immer 16 384 Blöcke. Vollbildkarte, Satz und Kacheln brauchen
  dafür keine Änderung.
- **Die Wahl** steht als Umschalter links neben dem Knopf „Selbst“; die
  Rückfrage nennt den Massstab.

Plan vom Reviewer freigegeben am 10.10.

## Verworfene Alternativen

- **Dazu 8 und 16 Pixel:** Der Maler der Minimap kann sie, der Server
  bietet sie nicht. `map.json` bräuchte `scale` 16 und `maxZoom` 10, dazu
  eine andere Rechnung für die feinste Stufe und eine andere Prüfung in
  `Satz.lies`, also Änderungen am Weg der Karten des Servers. 16 Pixel
  kosten je Chunk 14,4 statt 2,6 ms und auf der Platte etwa das Quadrat
  der Pixel mehr; verlangt hat sie niemand.
- **Zwei Massstäbe im selben Baum:** Jeder Chunk würde zweimal gezeichnet
  und geschrieben.
- **Der Umschalter im Dialog der Rückfrage:** Der Dialog ist der des
  Spiels; daneben ist einfacher.

## Folgen

- **Kosten und Platz** hängen am Massstab, siehe
  [Selbst gezeichnete Karte](../selbst.md), „Kosten“.
- **Bestehende Karten** mit 4 Pixeln bleiben gültig.
