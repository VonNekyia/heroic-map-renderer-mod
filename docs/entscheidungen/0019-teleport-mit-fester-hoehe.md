---
title: "0019: Teleport mit fester Höhe"
description: Warum „Hierher teleportieren“ die Höhe selbst bestimmt, aus dem Chunk beim Client, sonst aus Boden oder Höhen der Karte per HTTP, und erst ohne beides den Server suchen lässt; warum nur die Ablage einer Wurzel und höchstens eine Region je Klick.
status: abgelöst durch 0023
date: 2026-10-11
issues: [111]
code:
  - src/main/java/com/nekyia/heroicmap/Teleport.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
---

# 0019: Teleport mit fester Höhe

## Anlass

„Hierher teleportieren“ ging nur dort, wo Chunks geladen waren (Rückmeldung
des Users, 11.10.). Der Befehl liess den Server die Höhe lesen
(`positioned over motion_blocking_no_leaves`). Hat er den Chunk nicht
geladen, weist er den Befehl ab, „That position is not loaded“, und nichts
geschieht; so in der Gegenprobe des Gametests `Bedienung`.

## Entscheidung

- **Feste Höhe:** `execute in <dimension> run tp @s <x+0,5> <y> <z+0,5>`.
  Die Dimension bleibt über `execute in`.
- **Die Höhe, in dieser Reihenfolge:**
  1. Hat der Client den Chunk, aus seiner Heightmap
     `MOTION_BLOCKING_NO_LEAVES`. Sie ist genau und zählt Flüssigkeit, über
     Wasser also die Oberfläche.
  2. Sonst aus der Karte: der Boden ohne Laub (`ground`), sonst die Höhen
     (`heights`), wie der Renderer sie ab 0.9.0 schreibt, plus 1. Die Datei
     der Region holt der Mod per HTTP vom Server.
  3. Ohne beides der alte Befehl, mit einem Hinweis im Chat. Er geht
     dort, wo der Server den Chunk hat; nichts zu tun wäre schlechter.
- **Nur die Ablage einer Wurzel** (`../ground/{x}.{z}.bin`): Dann liegt die
  Datei unter `/tiles/ground/`, neben den Ebenen, mit derselben Basis. Ein
  anderes Muster nimmt der Mod nicht, so baut er keine Pfade aus Text vom
  Server.
- **Dieselben Grenzen wie bei den Ebenen,** gewünscht vom Reviewer:
  höchstens eine Region je Klick, Adresse geprüft, keine Weiterleitung,
  Grösse und Zeit begrenzt. Schlägt das fehl, gilt 3.

## Verworfene Alternativen

- **Boden und Höhen mit dem Download ablegen:** Eine grosse Welt hat Hunderte
  Regionen; der Teleport braucht je Klick eine.
- **Die Datei lokal suchen:** Der Download legt sie nie ab, der Weg wäre tot.
- **Ohne Höhe nicht teleportieren:** Der alte Befehl geht in Chunks, die der
  Server hat, und das ist oft der Fall; der Hinweis sagt, was schiefgehen
  kann.
- **Den Stamm umgehen:** Über einem Stamm von 1 × 1 nennt der Boden durch
  seinen Median die Erde darunter, der Spieler landet im Stamm. Das ist
  selten; entschieden vom Reviewer, es so zu lassen.

## Folgen

- Ein Teleport in die Ferne wartet auf eine Antwort, höchstens 10 s.
- Die Teleports gehen im Gametest `Bedienung` in einen Chunk, den weder
  Client noch Server geladen haben, und über Wasser; siehe
  [Vollbildkarte](../vollbildkarte.md), „Teleportieren“.
