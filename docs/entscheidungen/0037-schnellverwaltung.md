---
title: "0037: Die Schnellverwaltung als Panel auf der Vollbildkarte"
description: Warum die Schnellverwaltung ein Panel ohne Hintergrund links der Spalte der Vollbildkarte ist, geöffnet über einen Knopf und eine Taste statt über das Rechtsklick-Menü, mit einer Zeile je eigenem Objekt, jeder Klick gespeichert, ohne Schleier und Schraffur je Objekt; ergänzt 0035.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Verwaltung.java
  - src/main/java/com/nekyia/heroicmap/VerwaltungsPanel.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
---

# 0037: Die Schnellverwaltung als Panel auf der Vollbildkarte

## Anlass

Der User am 24.10.: Regionen und Wegpunkte sollen sich in einem
Schnellverwaltungsmenü verwalten lassen, alle Einstellungen kompakt
nebeneinander. Bis dahin erreichte man jedes Objekt nur über sein Menü auf
der Karte. Den Plan schrieb Mod Code Map, der Reviewer hat ihn mit E3 und
E4 freigegeben; die Ergänzungen dieser Seite ebenso.

## Entscheidung

- **Ein Panel auf der Karte,** links neben der Spalte der Knöpfe, ohne
  Hintergrund: Jede Änderung ist gleich auf der Karte zu sehen.
- **Öffnen** über den Knopf „Verwalten“ unter „Ebenen“ und eine Taste ohne
  Belegung (E3).
- **Eine Zeile je Objekt** mit Farbe, Name, Schrift, Biegung, Schriftart,
  Ebene, Anheften, Hinspringen und Löschen; Löschen erst mit dem zweiten
  Klick. Todespunkte grau, nur Hinspringen und Löschen.
- **Nur eigene Objekte** der Dimension des Spielers, auch alte Rechtecke;
  Objekte vom Server nicht.
- **Jeder Klick speichert** gleich, auch Schrift und Biegung, die im Menü
  der Form erst mit dem Namen gespeichert werden.
- **Zwei Zeilen je Objekt,** wenn 314 Einheiten links der Spalte nicht
  passen.
- **Neu gebaut im Tick** der Karte, nie mitten in einem Klick.
- **Wegpunkte und alte Rechtecke** wechseln ihre Farbe reihum durch die 8
  Farben; dafür gibt es `Wegpunkte.faerbe` für beide.

## Verworfen

- **Ein Eintrag im Rechtsklick-Menü:** Das Menü gehört zum Ort unter der
  Maus (E3).
- **Schleier und Schraffur je Objekt:** Der Schleier ist für alle gleich
  und hat seinen Regler, eine Schraffur gibt es nicht. Will der User sie je
  Region, ist das ein eigener Wunsch mit eigenem Plan (E4).
- **Ein eigener Bildschirm wie das Untermenü der Ebenen:** Er deckte die
  Karte, und man sähe eine Änderung erst nach dem Schliessen.
- **Speichern erst beim Schliessen des Panels:** Ein Absturz oder das
  Schliessen des Spiels nähme alles mit; ein Klick schreibt nur eine kleine
  Datei.
- **Alles in einer Zeile, auch wenn es nicht passt:** Bei 1280 × 720 und
  GUI-Massstab 4 ragte das Panel über den Schirm.

## Folgen

- Jede Zeile hat bis zu 12 Knöpfe; gebaut werden nur die einer Seite.
- Die Liste der Ebenen zeigt eine Zeile weniger; die eine braucht
  „Verwalten“.
- Der Platz unter „Zum Spieler“ ist mit „Verwalten“ belegt, auch ohne
  Ebenen.
