---
title: "0042: Pflanzen aus Paketen"
description: Warum der Mod aus der untersten Quelle liest, ob ein Block Pflanze ist, und warum bei Pflanzen nur eine liegende Fläche als oben zählt, damit Ressourcenpakete mit eigenen oder gekippten Pflanzenmodellen auf Minimap und eigener Karte flach liegen statt zu verschwinden oder als Streifen zu stehen.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Pflanzen.java
  - src/main/java/com/nekyia/heroicmap/ChunkMaler.java
---

# 0042: Pflanzen aus Paketen

## Anlass

Der User am 24.10. über den Reviewer: Mit einem Paket für Feldfrüchte sehen
die Felder auf der Minimap kaputt aus, ein Paket für Gras geht nur teilweise.
Gelesen in den beiden Paketen, ohne etwas davon ins Repo zu nehmen:

- **Eine ersetzte Vorlage:** Das Paket für Feldfrüchte ersetzt
  `minecraft:block/crop` selbst durch Flächen, die um ±22,5° gekippt sind.
  Die schräge Hälfte zeigte nach oben (n_y > 10⁻⁴ · |n|). Der Mod zeichnete
  sie als schmale Streifen, schnitt ab, was über die Spalte ragt, und legte
  die Pflanze nicht mehr flach.
- **Eigene Vorlagen ohne `parent`:** reifer Weizen als zwei Blöcke hohes
  Modell, Zuckerrohr, Gras aus bis zu 16 Varianten. Der Mod las die Kette
  aus dem obersten Pack; erbte nur eine Variante von keiner Vorlage des
  Spiels, fiel der ganze Zustand aus der Regel.

## Entscheidung

Freigegeben vom Reviewer am 24.10., als kleiner Fix vor der Wahl des Users
zu den gekippten Ansichten:

- **Ob ein Block Pflanze ist,** liest der Mod aus der untersten Quelle, dem
  Spiel oder dem Mod, der den Block bringt: Blockstate und `parent`-Kette.
  Ein Paket darüber zählt dafür nicht. Gezeichnet wird weiter das Modell
  des Pakets.
- **Bei Pflanzen zählt eine Fläche nur dann als oben,** wenn sie eher
  liegt als steht, n_y ≥ cos 45° · |n|. Gekippte Flächen eines Pakets legen
  die Pflanze so flach wie ohne Paket.
- Siehe [Minimap](../minimap.md), „Pflanzen aus Paketen“.

## Verworfene Alternativen

- **Blöcke über ihre Klasse erkennen,** etwa jede Vegetation: Das träfe
  Blöcke, deren Modell im Spiel keine Vorlage einer Bodenpflanze hat, und
  wiche vom Renderer ab.
- **Die Vorlagen bekannter Pakete in die Liste nehmen:** Jedes Paket hätte
  andere Namen, und der Mod hinge an fremden Dateien.
- **Die Grenze für alle Blöcke anheben:** Schräge Oberseiten wie
  Treppen-Formen anderer Mods sollen weiter als oben gelten; nur bei
  Pflanzen stehen gekippte Flächen für die Seitenansicht.

## Folgen

- Mit einem Paket liegt eine Pflanze flach, in der Textur ihrer ersten
  Fläche, die nicht liegt. Zeigt das Paket dort nur die halbe Textur, liegt
  diese Hälfte über dem Block.
- Der Renderer hat dieselbe Regel; Backend legt dafür einen eigenen Plan vor.
- Die gekippten Ansichten (V0 bis V7) sind davon getrennt und warten auf die
  Wahl des Users.
