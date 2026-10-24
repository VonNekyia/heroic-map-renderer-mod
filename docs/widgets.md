---
title: Widgets an der Minimap
description: Die Widgets an der Minimap, je eine Zeile Text im Stapel unter ihr; heute die Koordinaten. Der Stapel mit dem Balken des Fortschritts, wie er gespeichert wird und wie er die alte Zeile der Koordinaten übernimmt.
code:
  - src/main/java/com/nekyia/heroicmap/Widgets.java
  - src/test/java/com/nekyia/heroicmap/WidgetsTest.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Einstellungen.java
---

# Widgets an der Minimap

Ein Widget ist eine Zeile Text an der Minimap, im Spiel nur Anzeige. Heute
gibt es eine Art, die Koordinaten des Spielers; sie sind das, was bis
0.2.68 die Zeile der Koordinaten war. Gewünscht vom User am 23.10., Plan
vom Reviewer freigegeben am 24.10., siehe
[0033](entscheidungen/0033-widgets-an-der-minimap.md). Bearbeiten im Menü,
weitere Arten und das Einrasten oben kommen in eigenen Schritten.

## Arten

| Art | Versionen | Text |
|---|---|---|
| Koordinaten | `xz`, `xyz` | wie bisher, siehe [Minimap](minimap.md), „Koordinaten“ |

## Stapel

- **Unter der Minimap** stehen die Widgets untereinander, in der Reihe
  ihrer Liste, je eine Zeile mit 2 Einheiten Abstand. Die erste beginnt
  dort, wo bisher die Zeile der Koordinaten stand; jede folgt denselben
  Regeln wie sie: mittig, zum Schirm hin verschoben, ganz auf dem Schirm,
  ohne Platz darunter darüber (`Minimap.koordinatenLage`, `Minimap.zeile`).
- **Der Balken des Fortschritts** steht über allen Widgets in der ersten
  Zeile, solange ein Download oder Abgleich läuft, siehe
  [Minimap](minimap.md), „Fortschritt“. Er ist kein Widget und lässt sich
  nicht löschen.
- **Das L im Menü** hängt am ganzen Stapel: Die Reihe mit Form, Auflösung
  und Raster steht hinter dem letzten Widget (`Minimap.stapelKasten`,
  `Einstellungen.aufloesungLage`).
- **Bei F3** wie die Minimap, siehe [Minimap](minimap.md), „Bei F3“.

## Bedienung

- **Im Menü** wechselt ein Klick auf die Koordinaten wie bisher reihum
  `xz`, `xyz` und aus (`Einstellungen.naechste`). Aus nimmt das Widget weg;
  im Menü steht dann grau „Koordinaten aus“ hinter dem Stapel, ein Klick
  darauf holt es unten zurück (`Minimap.setzeKoordinaten`).
- **Im Spiel** zeigen die Widgets nur.

## Gespeichert

- **In `heroicmap.properties`** als `widgets`, je Widget `art:version:anker`,
  getrennt durch `;`, etwa `widgets=koordinaten:xz:unten` (`Widgets.lies`,
  `Widgets.schreibe`). Höchstens 16 (`Widgets.MAX`); ein Eintrag mit
  unbekannter Art, Version oder Anker fehlt, die übrigen bleiben. Leer
  heisst ohne Widgets.
- **Übernahme:** Fehlt `widgets`, kommt die alte Einstellung `koordinaten`
  als Widget: `xz` und `xyz` je unten, `aus` keins, ohne Eintrag die
  Vorgabe `xz` (`Widgets.ausKoordinaten`). `koordinaten` schreibt der Mod
  danach nicht mehr; eine ältere Version zeigt dann die Vorgabe.
