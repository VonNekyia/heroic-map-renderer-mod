---
title: "0023: Teleport mit Korrektur"
description: Warum „Hierher teleportieren“ immer im Menü steht, ohne Höhe zuerst den Server suchen lässt und dann die Höhe des Spielers nimmt, und warum der Mod nach dem Landen einmal korrigiert; löst 0019 in Punkt 3 ab.
status: gilt
date: 2026-10-18
issues: [131]
code:
  - src/main/java/com/nekyia/heroicmap/Teleport.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
---

# 0023: Teleport mit Korrektur

## Anlass

Wünsche des Users vom 18.10. (mod#131): „Hierher teleportieren“ soll immer
im Menü stehen, in jeder Dimension. Ohne Höhe aus Client oder Karte soll
der Mod trotzdem teleportieren; bis dahin lehnte der Server ab, wo er den
Chunk nicht hatte. Landet der Spieler im Block oder hoch in der Luft, soll
der Mod gleich korrigieren, ohne Schaden, soweit das geht.

## Entscheidung

Entschieden vom Reviewer, 18.10.:

- **Immer im Menü,** sobald es eine Verbindung gibt. Die Rechte prüft der
  Server; seine Absage im Chat ist in Ordnung, so der User.
- **Ohne Höhe zuerst der Server,** mit `positioned over
  motion_blocking_no_leaves`. Hat er den Chunk, landet der Spieler ohne
  Schaden.
- **Steht der Spieler nach 5 Ticks nicht am Ziel,** die feste Höhe: die des
  Spielers, begrenzt auf die Welt, mindestens der Meeresspiegel der
  Dimension.
- **Danach eine Korrektur,** sobald der Chunk beim Client ist, für jeden
  Teleport aus dem Menü, auch mit Höhe aus der Karte, denn die kann
  veraltet sein. Höchstens eine je Teleport.
- **Die Doku nennt den möglichen Schaden,** ½ bis wenige Herzen, wenn der
  Server den Chunk erst erzeugen muss.

Punkt 3 aus [0019](0019-teleport-mit-fester-hoehe.md) gilt damit nicht
mehr; die Punkte 1 und 2 und die Grenzen beim Holen bleiben.

## Verworfene Alternativen

- **Gleich die feste Höhe, ohne den Server:** Ersticken oder ein Fall auch
  dort, wo der Server den Chunk hat und sicher setzen könnte.
- **Ganz oben in der Welt:** nie im Block, aber bleibt der Chunk aus, fällt
  der Spieler über 200 Blöcke tief.
- **`spreadplayers <x> <z> 0 1 false @s`:** Der Server lädt den Chunk
  selbst und setzt auf einen sicheren Block. Über Wasser oder Lava ist aber
  kein Platz sicher, dann schlägt der Befehl fehl
  (`SpreadPlayersCommand$Position.isSafe`, per javap in 26.3), und er setzt
  zufällig bis 1 Block neben das Ziel.

## Folgen

- Muss der Server den Chunk erst laden oder erzeugen, kann der Teleport
  Schaden kosten, siehe [Vollbildkarte](../vollbildkarte.md),
  „Teleportieren“.
- Ohne Daten zeigt der Chat die Absage des Servers vor dem zweiten Befehl.
- Im Nether landet der Spieler ohne Daten erst auf dem Dach, dann unter der
  Decke.
