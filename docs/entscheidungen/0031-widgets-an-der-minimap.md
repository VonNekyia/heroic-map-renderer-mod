---
title: "0031: Widgets an der Minimap"
description: Warum die Zeile der Koordinaten ein Widget wird, welche Arten und Versionen es gibt, wie man sie im Menü hinzufügt, verschiebt, skaliert und löscht, wohin sie einrasten und wie sie gespeichert werden; in drei Schritten.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Widgets.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Einstellungen.java
---

# 0031: Widgets an der Minimap

## Anlass

Wunsch des Users vom 23.10., über den Reviewer: einen Knopf zum Hinzufügen,
die Koordinaten als Widget, dazu Zeit und Biom; skalierbar, per Rechtsklick
hinzufügen und Versionen wechseln, mit `Entf` und einem × beim Darüberfahren
löschen; einrasten oben und unten an der Minimap, aber auch frei.

## Entscheidung

Plan mit Skizzen, freigegeben vom Reviewer am 24.10.; die Entscheide als
Vorgabe, der User kann sie ändern:

- **Arten und Versionen:** Koordinaten `xz`, `xyz`; Zeit 24 h, 12 h,
  „Tag n“, echte Uhr; Biom als Name oder Name mit Farbpunkt. Die Zeit
  zeigt auch in Nether und End die Uhr der Oberwelt.
- **Bedienung im Menü:** Rechtsklick auf ein Widget schaltet die Version,
  auf freie Fläche öffnet er die Liste der Arten; ein Symbol „+“ im L tut
  dasselbe. Skalieren nur mit dem Mausrad, in ganzen Pixeln des Geräts je
  Pixel der Schrift. `Entf` oder das × löscht.
- **Einrasten:** unten im Stapel unter der Minimap, oben über ihr oder,
  ohne Platz, neben dem Nordpfeil; sonst frei, als Anteil des freien
  Platzes. Der Balken des Fortschritts steht im Stapel unten an erster
  Stelle und ist kein Widget.
- **Gespeichert** in `heroicmap.properties` als `widgets`; fehlt es, kommt
  die alte Einstellung `koordinaten` als Widget.
- **Drei Schritte, je eine PR:** A das Modell und die Koordinaten als
  Widget, B das Bearbeiten im Menü, C Hinzufügen, Zeit und Biom.

## Verworfene Alternativen

- **„+“ als Knopf in der Spalte des Menüs:** Bei GS 4 ist die Höhe dort
  schon knapp.
- **Ein Griff zum Skalieren:** Das Mausrad reicht, so entschieden.
- **Der Balken als Widget:** Er kommt und geht von selbst; löschen soll
  man ihn nicht.

## Folgen

- Schritt A ändert für den Spieler nichts; die Einstellung heisst danach
  `widgets`.
- Siehe [Widgets](../widgets.md).
