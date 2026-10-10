---
title: "0004: Die Karte selbst zeichnen"
description: Warum es neben den Karten des Servers die Wahl „Selbst“ gibt, eine Karte, die der Mod nur aus den Chunks zeichnet, die der Spieler lädt, und warum sie die Karte des Servers ersetzt, statt über ihr zu liegen.
status: gilt
date: 2026-10-08
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Selbst.java
  - src/main/java/com/nekyia/heroicmap/Kachelwerk.java
  - src/main/java/com/nekyia/heroicmap/Satz.java
---

# 0004: Die Karte selbst zeichnen

Teilweise abgelöst durch [0014](0014-eigene-karte-mit-massstab.md): Die
eigene Karte hat 1, 2 oder 4 Pixel je Block zur Wahl, nicht nur 4.

Teilweise abgelöst durch [0015](0015-ordner-fuer-einzelspielerwelten.md):
Auch im Einzelspieler gibt es einen Ordner der Welt und damit „Selbst“.

## Anlass

Der User wünscht sich am 08.10. neben den drei Massstäben des Servers eine
Wahl „myself“: Die Karte soll ganz selbst gezeichnet werden. Auf einem
Server bekommt der Client nur die Chunks in Sichtweite, nicht die ganze
Welt. Auf die Rückfrage hin hat der User gewählt: Der Mod zeichnet jeden
Chunk, den der Spieler lädt, in eine eigene Karte. Sie wächst beim Spielen,
ist eine eigene Wahl neben den Massstäben des Servers und wird nie mit
dessen Karte gemischt.

## Entscheidung

- **Wahl „Selbst“** je Welt und Dimension, über den Massstäben im Fenster
  „Karten dieses Servers“. Es geht auch auf einem Server ohne Plugin.
- **Ein eigener Baum** `selbst-<dimension>` im Ordner der Welt. Die
  Vollbildkarte zeigt ihn statt der Karte des Servers (`Satz.fuer`), bis
  der Spieler ihn in der Kartenliste löscht.
- **Gezeichnet** mit dem Maler der Minimap, 4 Pixel je Block, mit den
  Texturen und dem Biomübergang des Spielers. Abgelegt als Kacheln mit
  Pyramide, wie der Renderer sie verkleinert.
- **0002 gilt weiter für die Karte des Servers:** Über ihre Kacheln legt
  der Mod nie selbst gezeichnete Chunks. Teilweise abgelöst ist 0002 nur in
  dem Satz, dass der Mod selbst nur die Minimap zeichnet.

Entschieden vom Maintainer am 08.10.

## Verworfene Alternativen

- **Selbst Gezeichnetes über der Karte des Servers:** Das war die
  Live-Ebene, verworfen in
  [0002](0002-vollbildkarte-nur-vom-server.md) wegen der Flecken
  ausserhalb des gerenderten Gebiets.
- **Den Renderer im Einzelspieler auf den Ordner der Welt:** Das gäbe die
  ganze Welt, aber nur im Einzelspieler; auf einem Server hat der Client
  keine Dateien der Welt. Der User hat die erkundete Karte gewählt.
- **1, 2 und 4 Pixel je Block zur Wahl:** Es gibt einen Knopf mit 4 Pixeln,
  der feinsten Karte des Servers. Mehr Wahlen kommen, wenn der User sie
  will.
- **Kacheln als WebP:** Java hat keinen Encoder für WebP, auch TwelveMonkeys
  liest nur. Deshalb PNG; `Kacheln` liest jetzt beides.

## Folgen

- **Nur, was der Spieler geladen hat.** Der Rest bleibt dunkel. Dass dort
  Flecken sind, weiss der Spieler, denn er hat „Selbst“ gewählt.
- **Nicht unter einer Decke** wie im Nether, dort zeichnete der Mod das
  Dach. **Nicht im Einzelspieler**, dort fehlt ein Ordner der Welt.
- **Last:** ein eigener Worker mit niedriger Priorität. Die Minimap geht
  vor, siehe [Selbst gezeichnete Karte](../selbst.md), „Wann gezeichnet
  wird“.
- **Platz auf der Platte** wächst mit dem, was der Spieler erkundet. Die
  Kartenliste zeigt ihn.
