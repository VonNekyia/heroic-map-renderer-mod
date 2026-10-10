---
title: "0015: Ein Ordner für Einzelspielerwelten"
description: Warum der Mod im Einzelspieler einen Ordner der Welt unter heroicmap/ anlegt, benannt nach dem Speicherordner und dem Hash des Seeds, warum nicht im Speicherordner der Welt selbst, und was dadurch im Einzelspieler geht; löst 0004 im Einzelspieler ab.
status: gilt
date: 2026-10-10
issues: [83]
code:
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/Selbst.java
---

# 0015: Ein Ordner für Einzelspielerwelten

## Anlass

Rückmeldung des Users zu 0.2.14 (mod#83): In einer Einzelspielerwelt
liess sich keine Vollbildkarte anlegen. Der Ordner einer Welt hing an der
Adresse des Servers; im Einzelspieler gab es keinen, und „Selbst“ ging dort
nicht ([0004](0004-karte-selbst-zeichnen.md), „Folgen“).

## Entscheidung

- **Im Einzelspieler** ist der Ordner der Welt
  `heroicmap/einzelspieler_<speicherordner>/welt-<hash>`, unabhängig von der
  Wahl „Ablage“, die nur Adressen betrifft (`Downloads.weltOrdner`).
  `<speicherordner>` ist der Name des Ordners unter `saves/`, bereinigt wie
  ein Host; `<hash>` der Hash des Seeds wie auf einem Server.
- **So tief wie eine Welt hinter einer Adresse,** damit die Kartenliste
  sie findet und löschen kann.
- **Ein Ordner für alles:** „Selbst“, die Vollbildkarte, die Wegpunkte und
  die Lage der Vollbildkarte; die beiden letzten lagen im Einzelspieler bis
  hier nur im Speicher.

Plan vom Reviewer freigegeben am 10.10.

## Verworfene Alternativen

- **Im Speicherordner der Welt,** `saves/<welt>/heroicmap/`: Er wanderte mit
  beim Umbenennen und ginge mit der Welt. Aber „Backup erstellen“ packt den
  ganzen Ordner als ZIP, und das Hochladen auf Realms nimmt ihn mit; eine
  eigene Karte wird schnell Hunderte MB gross. Die Kartenliste fände sie
  nicht, ohne je Welt unter `saves/` zu suchen. Vom Reviewer vorgeschlagen,
  nach diesen Gründen verworfen.
- **Nur „Selbst“ bekommt einen Ordner:** Dann fände die Vollbildkarte die
  eigene Karte über einen eigenen Weg, und Wegpunkte blieben im Speicher.

## Folgen

- **Umbenannt** beginnt die Karte einer Welt neu; die alte steht weiter in
  der Kartenliste.
- **Gelöscht** bleibt ihre Karte, bis der Spieler sie in der Kartenliste
  löscht. Eine neue Welt mit demselben Namen und anderem Seed bekommt einen
  eigenen Ordner, denn der Hash unterscheidet sie.
- **Gleicher Name nach dem Bereinigen,** etwa `a b` und `a_b`, mit gleichem
  Seed teilen sich einen Ordner.
