---
title: "0028: Teleport mit tp"
description: Warum „Hierher teleportieren“ den normalen tp schickt statt execute, ohne Höhe erst in die Luft und dann auf den Boden geht, alle 10 Ticks zurück nach oben und nach 5 s an den Start, und warum das ohne Schaden geht; löst 0023 ab.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Teleport.java
---

# 0028: Teleport mit tp

## Anlass

Wunsch des Users vom 24.10., über den Reviewer: Statt des
`execute`-Befehls soll der Teleport den normalen `tp` von Minecraft
schicken. Ohne Höhe soll er nach dem ersten `tp` gleich ein zweites auf
die richtige Höhe schicken, so schnell, dass man es nicht oder kaum
merkt.

Bis hier ging jeder Teleport als `execute in <dimension> run tp @s …`
([0019](0019-teleport-mit-fester-hoehe.md)). Ohne Höhe suchte erst der
Server den Boden mit `execute … positioned over
motion_blocking_no_leaves`, nach 5 Ticks folgte die Höhe des Spielers.
Dort konnte der Spieler im Block landen oder fallen, ½ bis wenige Herzen
([0023](0023-teleport-mit-korrektur.md)).

## Entscheidung

Entschieden vom Reviewer, 24.10.:

- **Der Befehl:** `tp <x+0,5> <y> <z+0,5>`, ohne `execute`.
  - Die Dimension ist immer die des Spielers: Die Karte zeigt nur den
    Satz seiner Dimension (`Satz.fuer`), und `execute in` nannte nie eine
    andere.
  - Der Server verlangt so nur das Recht für `tp`.
- **Ohne Höhe erst in die Luft:** ohne Decke zwei Blöcke unter dem
  obersten Block der Welt, mit Decke die Höhe des Spielers, mindestens
  der Meeresspiegel. Über der Decke läge das Dach.
- **Dann auf den Boden,** sobald der Client den Chunk hat; der Mod fragt
  jeden Tick.
- **Bis dahin alle 10 Ticks zurück nach oben.** Jedes `tp` setzt die
  Geschwindigkeit auf 0 (`Entity.teleportTo` mit `Vec3.ZERO`), so bleibt
  der Fall langsam.
- **Nach 5 s ohne Chunk zurück an den Start,** mit einer Zeile im Chat.
  Ebenso, wenn am Ziel kein Platz zum Stehen ist. Sonst hinge der Spieler
  oben und fiele rund 250 Blöcke, sobald der Server den Chunk lädt.
- **Mit Höhe** aus Client oder Karte bleibt alles wie in 0023: ein `tp`,
  dann höchstens eine Korrektur.
- **Eigenbewegung:** Geht der Spieler nach der Ankunft mehr als 4 Blöcke
  weg, etwa fliegend, hört der Mod auf, ohne ihn zurückzuholen.
- **Ohne Recht auf `tp`** schickt der Mod nichts und sagt es unten links
  auf der Karte. Ob er darf, steht im Befehlsbaum des Clients: Der Server
  schickt nur Befehle, die der Spieler darf (`Commands.fillUsableCommands`
  mit `canUse`, per javap). Sonst kämen ohne Höhe bis zu 12 Absagen und
  danach die falsche Zeile, das Ziel sei nicht gekommen. Befund des
  Reviewers in mod#192.

## Belege

Per javap im Client von 26.3, der den Server enthält:

- **`tp` setzt die Fallstrecke nicht zurück:**
  - Der Weg ist `TeleportCommand.performTeleport` → `Entity.teleportTo`
    → `ServerPlayer.teleport` → `ServerGamePacketListenerImpl.teleport`
    und `resetPosition`.
  - Keiner davon ruft `resetFallDistance`. `performTeleport` setzt nur die
    senkrechte Geschwindigkeit auf 0 und `setOnGround(true)`.
  - Zurück setzt der Server nur, wenn ein Bewegungspaket über `lastGoodY`
    steigt (`handlePlayerPositionChange`); `resetPosition` legt `lastGoodY`
    aber auf das Ziel.
- **In einem Chunk, den der Server nicht geladen hat, zählt kein Fall:**
  `Entity.doCheckFallDamage` kehrt sofort zurück, solange
  `touchingUnloadedChunk()` gilt, also `Level.hasChunksAt` um die
  Hitbox nein sagt.
  - Muss der Server den Chunk erst laden oder erzeugen, zählt die
    Fallstrecke erst danach.
  - Bis der Chunk beim Client ist und das zweite `tp` ankommt, vergehen
    wenige Ticks. Der Spieler fällt dann höchstens rund 0,7 Blöcke je Tick
    und bleibt unter den 3 Blöcken, ab denen eine Landung schadet
    (`checkFallDamage`).
  - Hat der Server den Chunk schon, zählt der Fall ab dem ersten `tp`.
    Dann schickt er den Chunk aber gleich, und der Fall ist ebenso kurz.
- **Grenze für Befehle:** `detectCommandRateSpam` zählt je Befehl 20 dazu
  (`TickThrottler`), je Tick 1 ab.
  - Ab `20 × command-spam-threshold-seconds` wirft der Server den Spieler
    hinaus (`disconnect.spam`), ausser Ops und dem Besitzer im
    Einzelspieler.
  - Der Standard (`DedicatedServerProperties`) ist 10 s, also 200; so
    auch auf den Servern von TerraNova.
  - Ein schwebender Teleport schickt in 5 s höchstens 11 Befehle: den
    ersten, 9 nach oben und den zurück. Der Zähler steigt dabei auf
    höchstens 120, mit drei eigenen Befehlen des Spielers davor auf 180
    (`TeleportTest.ohneHoeheUnterDerGrenzeFuerBefehle`).

## Verworfene Alternativen

- **Ohne Höhe weiter `execute … positioned over`:** wäre nicht der
  normale `tp`. Ohne das Recht für `execute` stünde zudem eine Absage im
  Chat.
- **Gleich die Höhe des Spielers, ohne Luft:** Ersticken oder Fall,
  ½ bis wenige Herzen, bis die Korrektur kommt.
- **10 s mit einem Befehl je 10 Ticks:** rund 21 Befehle. Ein Spieler
  ohne Op flöge nach rund 9 s hinaus.
- **10 s mit einem Befehl je 20 Ticks:** hielte den Zähler gleich. Die
  Geschwindigkeit vor dem nächsten `tp` stiege aber auf rund 1,2 Blöcke
  je Tick, und schon 3 Ticks zwischen Laden und zweitem `tp` kosteten
  Schaden.
- **`minecraft:tp`:** umginge ein Plugin, das `tp` überschreibt, etwa
  Essentials mit eigenem Recht. Es ist aber nicht der normale Befehl. Auf
  den Servern von TerraNova beansprucht kein Plugin `tp` oder `teleport`.

## Folgen

- [0023](0023-teleport-mit-korrektur.md) gilt damit nicht mehr. Von dort
  bleiben: immer im Menü und die eine Korrektur nach dem Landen. Von
  [0019](0019-teleport-mit-fester-hoehe.md) bleiben die Höhe aus Client
  und Karte und die Grenzen beim Holen.
- Ohne Daten kostet ein Teleport keinen Schaden mehr; mit veralteter Höhe
  aus der Karte kann der Spieler weiter kurz im Block landen.
- Siehe [Vollbildkarte](../vollbildkarte.md), „Teleportieren“.
