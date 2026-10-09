---
title: "0006: Ein Weg für die Minimap, Vieleck statt Läufen"
description: Warum die Minimap gedreht und ungedreht mit demselben Vieleck je Region zeichnet und ein Ring den Rand deckt, statt Läufen, einer Maske der Skins oder des Stencils; löst 0005 in der Maske ab.
status: gilt
date: 2026-10-09
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Drehung.java
  - src/main/java/com/nekyia/heroicmap/Gitter.java
  - src/main/java/com/nekyia/heroicmap/Skin.java
---

# 0006: Ein Weg für die Minimap, Vieleck statt Läufen

## Anlass

Mit der drehenden Minimap gab es zwei Wege für dasselbe Bild:

- **Gedreht** ein Vieleck je Region, mit der Form geschnitten.
- **Ungedreht** ein Blit je Lauf und Region; rund mit Rahmen schnitt eine
  Maske der Skins, dieselbe Rechnung wie der Ring je Einheit.

Die Messung zum Drehen zeigte rund gedreht billiger als ungedreht, siehe
[Minimap, Drehen](../messungen/2026-10-09-minimap-drehen.md).

## Entscheidung

- **Ein Weg:** Je Region schneidet die Minimap ihr Quadrat mit der Form,
  gedreht wie ungedreht. Ungedreht verschiebt die Lage nur, um ganze
  Pixel.
- **Der Ring deckt den Rand:** Das Vieleck reicht unter den Ring. Ohne
  Rahmen ist der Ring ein Bild in Pixeln nach Karte und Linien, mit Rahmen
  der Ring der Bänder; dafür braucht ein Skin mindestens zwei Bänder.
  Siehe [Minimap](../minimap.md), „Form“.
- **Rund bleibt rund:** Ohne Rahmen ist der Rand Pixel für Pixel wie
  vorher, gezeigt bei GUI-Massstab 1 und 3. Das war die Bedingung des
  Reviewers.

Entschieden vom Reviewer am 09.10.

## Verworfene Alternativen

- **Läufe ungedreht, Vieleck gedreht,** der Stand davor: zwei Wege, und
  der ungedrehte war der teurere. Im HUD-Element kostet rund ungedreht
  jetzt 0,007 statt 0,165 ms, siehe
  [Minimap, Vieleck auch ungedreht](../messungen/2026-10-09-minimap-vieleck.md).
- **Die Maske der Skins** (`Skin.maskeRund`): Sie schnitt die Karte genau
  am Ring. Das braucht es nicht, wenn der Ring den Überstand deckt.
- **Stencil oder Scissor:** Der Scissor des GUI schneidet nur Rechtecke.
  Ein Stencil bräuchte eine eigene Pipeline neben dem GUI; das Vieleck
  geht mit den Elementen des GUI, die schon da sind.

## Folgen

- **0005** ist im Satz „die runde Karte mit derselben Rechnung als Maske“
  abgelöst; der Rest gilt.
- **`Skin.lies`** weist eine Palette mit weniger als zwei Bändern ab.
- **Chunklinien** rechnet die Minimap über das ganze Quadrat und schneidet
  sie mit der Form; `Gitter.Linien` kennt nur noch volle Linien.
- **Der Ring ohne Rahmen** ist eine Textur; ihr Speicher steht in
  [Minimap](../minimap.md), „Kosten“.
