---
title: "0044: Kartenausweis je Weltordner"
description: Warum jeder Ordner einer Welt eine Datei ausweis.json bekommt, je Weltschlüssel mit Besuchen und Spielzeit, der Pfad massgeblich bleibt und der Mod sie nur in Ordner schreibt, die es schon gibt; Baustein 1 des Kartenausweises.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Ausweis.java
  - src/main/java/com/nekyia/heroicmap/Ausweise.java
  - src/main/java/com/nekyia/heroicmap/HeroicMap.java
---

# 0044: Kartenausweis je Weltordner

## Anlass

Der User will ein lückenloses Konzept dafür, zu welchem Server und welcher
Welt eine Karte gehört: auf TerraNova, auf grossen Netzwerken ohne Plugin,
mit mehreren Welten und mit Resets. Das Konzept „Kartenausweis“ des
Reviewers nennt sechs Teile: Netz, Server, Welt, Seed-Hash, Generation und
Inhalt. Heute steckt die ID nur im Pfad. Der Reviewer hat die Bausteine
freigegeben, in der Reihenfolge B1 → B4 → B3 → B5 → B6; dies ist B1.

## Entscheidung

- **Eine Datei je Ordner `welt-<hash>`,** `ausweis.json`, mit Netz,
  Server, Hash und je Weltschlüssel Besuchen und Spielzeit.
- **Je Weltschlüssel,** denn Oberwelt, Nether und End mit demselben Seed
  liegen im selben Ordner.
- **Der Pfad bleibt massgeblich;** die Ablage ändert sich nicht. Der
  Ausweis hält, was der Pfad nicht weiss: die eingetippte Adresse samt Port
  hinter einem Namen, die Weltschlüssel, Besuche und Spielzeit.
- **Nur in Ordner, die es gibt:** Ein Besuch allein legt keinen an.
- **Geschrieben** beim ersten Wert der Spielzeit je Besuch und beim
  Verlassen, über eine Zwischendatei; nicht in jedem Tick.

## Verworfen

- **Eine Datei für alle Welten unter `heroicmap/`:** Sie zöge bei „Ort …“
  und beim Wechsel der Ablage nicht mit und müsste mit dem Pfad abgeglichen
  werden.
- **Der Ausweis statt des Pfads als Wahrheit:** Das hiesse die Ablage
  umzubauen; das Konzept lässt sie, wie sie ist.
- **Für jede besuchte Welt einen Ordner anlegen:** Auf grossen Netzwerken
  entstünden Hunderte leere Ordner.

## Folgen

- Für den Spieler ändert sich nichts. Der Ausweis ist Grundlage für Reset,
  gleiche Welt unter anderem Netz und Runden- und Wegwerfkarten.
- Welten ohne Ordner haben keinen Ausweis. Braucht ein späterer Baustein
  Besuche auch ohne Karte, entscheidet er neu.
- Ob die Spielzeit bei jedem Wechsel kommt und in 26.x immer weiterläuft,
  ist noch zu prüfen; die Zeile im Log beim ersten Wert zeigt es.
