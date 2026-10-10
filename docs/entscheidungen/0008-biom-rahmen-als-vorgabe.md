---
title: "0008: Der Rahmen „biom“ als Vorgabe"
description: Warum die Minimap als Vorgabe einen Rahmen nach dem Biom unter dem Spieler trägt, wie Tags und Namen in fester Rangfolge die Kategorie wählen, warum der Rahmen erst nach 2 s wechselt, dass Höhlen die letzte Kategorie behalten und wer seine Wahl behält; löst 0005 in der Vorgabe ab.
status: gilt
date: 2026-10-10
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Biom.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Skin.java
---

# 0008: Der Rahmen „biom“ als Vorgabe

## Anlass

Der Designer hat einen Rahmen in acht Kategorien gezeichnet, je eine für
eine Art Landschaft. Der User wünscht ihn als Vorgabe; bisher war die
Vorgabe ohne Rahmen ([0005](0005-rahmen-als-skins.md)). Weitergegeben
vom Reviewer am 10.10.

## Entscheidung

- **Vorgabe `biom`** statt „ohne“. Wer selbst einen Rahmen gewählt hat,
  behält ihn: gespeichert als `rahmen_wahl`, nur nach einer Wahl, wie
  `drehen_wahl`. Ein altes `rahmen=ohne` ist von der alten Vorgabe nicht
  zu unterscheiden und bekommt die neue.
- **Zuordnung über Tags und Namen** in fester Rangfolge, die erste
  passende Zeile gewinnt; danach ein Wort im Namen, sonst Grasland. Die
  Tabelle steht in [Rahmen](../rahmen.md), „Biom“.
- **Wechsel nach 2 s** in der neuen Kategorie, dann 0,3 s Überblendung.
- **Nether und End** nehmen den Rückfall Grasland.
- **Höhlen** behalten die letzte Kategorie: „Biom unter dem Spieler“ heisst
  die Landschaft, nicht die Höhle. `stony_shore` zählt wie die Strände.
  Entschieden vom Reviewer am 10.10., in der Nachtschicht.

## Verworfen

- **Nur Tags:** Der Server schickt die Tags des Spiels und die seiner
  Datapacks; `c:` gibt es nur mit Fabric API oder einem Datapack, der sie
  mitbringt. Die Tags des Spiels haben nichts für Schnee, Sumpf, Wüste
  oder Ebene; ohne `c:` wäre die verschneite Taiga Wald. Darum stehen
  diese Biome des Spiels mit Namen in den Regeln.
- **Nur Namen:** Biome aus Datapacks oder Mods mit Tags würden falsch
  eingeordnet, wenn ihr Name nichts sagt. Tags gehen darum vor.
- **Sofort wechseln:** An einer Grenze flackerte der Rahmen bei jedem
  Schritt. So hat es der Designer schon im Paket vermerkt.
- **Kategorien je Abstand zum Rand:** Die zier ist 9 bis 12 Pixel gross.
  Mit dem eigenen Abstand je Kategorie spränge die Minimap beim Wechsel um
  eine Einheit; sie nimmt den grössten.
