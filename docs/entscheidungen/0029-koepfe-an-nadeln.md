---
title: "0029: Köpfe an Nadeln"
description: Wie der Mod den Kopf einer Nadel zeigt, fest 8 × 8 Einheiten mit Rand und pixelgenau, warum er Köpfe je Name über alle Ebenen hält, höchstens 1024, und warum er keinen Hash über die Bytes rechnet.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Symbole.java
  - src/main/java/com/nekyia/heroicmap/Ebenen.java
---

# 0029: Köpfe an Nadeln

## Anlass

Der User wünschte am 24.10., dass die NPCs des Servers als Ebene mit ihren
Köpfen statt Nadeln erscheinen, auf der Webkarte und im Mod. Format und
Plugin stehen in 0118 des Renderers; das Plugin schickt ab 0.17.0 das Feld
`head`. Teil 4 ist der Mod, Plan vom Reviewer freigegeben am 24.10.

## Entscheidung

Entschieden vom Reviewer, 24.10.:

- **Grösse:** das Gesicht fest 8 × 8 Einheiten, ein Pixel des Bilds eine
  Einheit, mit schwarzem Rand von einer Einheit; auf jeder Stufe gleich
  wie die Nadel (0097 des Renderers). Die Mitte auf dem Fuss, der Name
  darunter.
- **Prüfung wie die Webkarte:** 64 hex, höchstens 16 KiB, PNG genau
  8 × 8. Das Plugin legt genau das ab, geprüft in seinem `Koepfe`.
- **Speicher je Name** über alle Ebenen, höchstens 1024, der am längsten
  nicht gezeigte zuerst frei; holen erst beim Zeichnen, neu nach 1, 5 und
  15 Minuten wie die Symbole.

## Verworfene Alternativen

- **6 Einheiten wie die Mitspieler:** Ein Pixel des Gesichts wäre ¾
  Einheit, auf keinem GUI-Massstab ganze Pixel.
- **Kleiner weit draussen wie die Webkarte (0116):** Der Mod zeichnet
  Nadeln fest; Gesicht und Nadel sollen gleich wirken.
- **Je Ebene und `version` wie die Symbole:** Ein Kopf wäre je Ebene und
  nach jeder neuen `version` neu zu holen, obwohl sein Name sein Inhalt
  ist.
- **Den SHA-256 der Bytes prüfen:** Die Webkarte tut es nicht, und die
  Prüfung der Grösse und des Bilds fängt jede kaputte Datei.

## Folgen

- Bis der Kopf da ist, zeigt der Mod die Nadel; mit einem älteren Plugin
  bleibt alles wie vorher.
- Siehe [Ebenen](../ebenen.md), „Köpfe“.
