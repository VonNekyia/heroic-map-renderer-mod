---
title: "0016: Ein zweiter Haken an der Welt"
description: Warum die Minimap Änderungen nicht nur an LevelExtractor.setSectionDirty abgreift, sondern auch an ClientLevel, seit Sodium jene Methoden ersetzt und gesetzte Truhen nicht mehr neu gezeichnet wurden.
status: gilt
date: 2026-10-10
issues: [84]
code:
  - src/main/java/com/nekyia/heroicmap/mixin/ClientLevelMixin.java
  - src/main/java/com/nekyia/heroicmap/mixin/LevelExtractorMixin.java
---

# 0016: Ein zweiter Haken an der Welt

## Anlass

Rückmeldung des Users zu 0.2.14 (mod#84): Eine gesetzte Truhe zeigte die
Minimap erst, wenn danach ein weiterer Block gesetzt wurde. Im Einzelspieler
ohne andere Mods trat das nicht auf. Mit Sodium 0.9.2 für 26.3, wie in den
Instanzen des Users, zeigt es der Gametest `Blockentities`: Sodium ersetzt
in `LevelExtractor` die Methoden, die zu `setSectionDirty` führen, per
`@Overwrite`, belegt per javap. Der einzige Haken des Mods hing dort.

## Entscheidung

- **Zwei Haken:** Der Mixin an `LevelExtractor.setSectionDirty` bleibt; er
  fängt in Vanilla jeden Weg, auch `handleChunksBiomes`. Dazu kommt einer
  an `ClientLevel`: `sendBlockUpdated`, `setBlocksDirty`,
  `setSectionDirtyWithNeighbors` und `setSectionRangeDirty`. Über diese
  Methoden ruft der Client den Renderer, belegt per javap am Client 26.3,
  und Sodium ersetzt sie nicht.
- **Markiert wie der Renderer:** ein Block mit seinen Nachbarn, ein
  Abschnitt mit seinen Nachbarn, ein Bereich ganz. Doppelt markiert
  schadet nicht.
- **Geprüft** mit Sodium über `-Pzusatzmods`; ohne den neuen Haken ist
  der Gametest dort rot.

Plan vom Reviewer: Gametest wie ein Spieler, dann die Ursache.

## Verworfene Alternativen

- **Nur der Haken am Renderer,** wie bisher: Er hängt an Methoden, die
  andere Mods ersetzen dürfen.
- **Nur der Haken an der Welt:** `handleChunksBiomes` geht direkt an den
  Renderer, ein geänderter Biom käme nicht an.
- **Eine Schnittstelle von Sodium:** eine Abhängigkeit für einen Mod, den
  nicht jeder hat; die Welt sieht jede Änderung ohnehin.

## Folgen

- **Je Blockänderung** markiert der Mod bis zu 9 Spalten zweimal; die
  Menge `offen` hält jede nur einmal.
- **Andere Render-Mods,** die `ClientLevel` nicht ersetzen, sind damit
  ebenso abgedeckt.
