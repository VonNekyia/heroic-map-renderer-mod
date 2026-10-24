---
title: "0041: Ausrichtung der Marken"
description: Warum bei „biom“ die Blumen O, S und W aussen auf dem Rand stehen, warum „lettern“ den Kopf nach aussen dreht, warum es die Drehung innen gibt und jede Lage mit jeder Drehung geht, fest je Skin; und wie weit die Minimap dafür vom Rand rückt.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Skin.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
---

# 0041: Ausrichtung der Marken

Ergänzt [0022](0022-marken-nach-aussen.md) und
[0032](0032-rahmen-familien-und-waehler.md).

## Anlass

Der User schickte am 24.10. ein Bildschirmfoto der grünen Blume am Rand der
Minimap: „die blume vom rahmen ist genau in die falsche richtung bei diesem
skin soll sie aussen auf dem rand stehen. beim N E W S skin sollen die
himmelsrichtungen so ausgerichtet sein wie die blume jetzt also für skins
insgesamt 3 ausrichtungen aussen mitte und innen“.

Bis dahin lag bei `biom` die Blüte an der äusseren Kante der Bänder, der
Stiel reichte in die Karte; vom Rand aus stand die Blume kopfüber. Den Plan
mit Skizzen hat der Reviewer vorgelegt. Der User wählte „fest je Skin“, die
übrigen Fragen entschied der Reviewer; der User kann es danach ändern.

## Entscheidung

- **Blume bei `biom`:** O, S und W stehen aussen auf dem Rand, der Fuss eine
  Einheit ausserhalb der Minimap, die Blüte nach aussen: `drehung=aussen`,
  `lage=aussen`. N, das Bild `norden`, bleibt auf der Mitte der Bänder,
  `lage_norden=mitte`.
- **Buchstaben bei `lettern`:** mit dem Kopf nach aussen wie die Blume,
  `drehung=aussen`. S steht kopfüber, O und W liegen quer; die Lage bleibt
  je Variante innen oder aussen.
- **Drei Ausrichtungen als Drehung:** `drehung=aussen`, `innen` (neu, der
  Kopf zur Mitte) und `aufrecht`, das ist „mitte“. Dazu bleibt `karte`.
- **Jede Lage mit jeder Drehung:** Die Bindungen fallen, `kante` nur mit
  `aussen`, `innen` und `aussen` nur mit `aufrecht`. Gerechnet wird mit dem
  Kasten des Bilds, wie es auf dem Schirm gedreht steht (`Skin.kasten`).
- **Eigene Bilder je Richtung** (`osten`, `sueden`, `westen`) gelten in
  jeder Drehung, nicht nur aufrecht.
- **Fest je Skin** in `info.txt`; der Wähler bekommt keine Wahl dafür.

## Abstand zum Rand

Marken ganz aussen rücken die Minimap vom Rand des Schirms ab, in jeder
Drehung, eckig wie rund, samt Schatten (`Skin.einrueckung`). Die Familie
hält den grössten Abstand ihrer Kategorien und Varianten.

| Skin | Marke | vorher | neu |
|---|---|---|---|
| `biom/grasland` | 5 × 9 | 11 | 13 |
| `biom/feuchtgebiete` | 5 × 10 | 11 | 14 |
| `biom/gewaesser` | 11 × 7 | 11 | 15 |
| `biom/schnee`, `biom/tropen` | 9 × 9 | 11 | 15 |
| `biom/gebirge` | 11 × 8 | 11 | 16 |
| `biom/waelder`, `biom/wueste` | 9 × 10 | 11 | 16 |
| `biom`, die Familie | | 11 | 16 |
| `lettern` aussen | 7 × 9 | 11 | 14 |

Am weitesten reicht eine Marke, wenn die eckige Minimap um 45° gedreht ist
und sie in der Ecke steht.

## Verworfen

- **N bei `biom` auch aussen:** Das Bild `norden` ist 15 × 15; der Abstand
  zum Rand stiege auf 24 Einheiten, bei GUI-Massstab 2 48 px. Der User hatte
  nur nach der Blume gefragt. Vom Reviewer zurückgenommen.
- **Die Blume umgedreht, die Blüte zur Karte (B):** Der User wollte sie
  „aussen auf dem rand stehen“.
- **Drei Ausrichtungen als Lage:** „aussen, mitte, innen“ als Ort auf dem
  Rand, der Kopf immer nach aussen. Als Drehung lässt sich jede mit jeder
  Lage verbinden.
- **Eine Wahl des Spielers im Wähler:** mehr Arbeit; der User wählte fest
  je Skin.

## Folgen

- Mit Vierteldrehungen bleiben Pixel und HD genordet scharf (`Skin.blit`).
- Der Klick im Menü trifft die Marke dort, wo sie gezeichnet ist.
- Im Wähler ragen die Marken der Vorschau samt Schatten um bis zu zwei
  Einheiten über ihren Rand von 10 Einheiten; vorher bei `lettern` aussen
  um eine.
