---
title: "0012: Die Verzierungen drehen mit"
description: Warum zier und Marken des Rahmens beim Drehen starr mit der Karte drehen, Lage und Bild, statt fest zu stehen oder aufrecht zu wandern, warum ein Schalter sie abstellt und warum der Abstand zum Rand mit der halben Diagonale rechnet; löst 0005 in den Marken ab.
status: gilt
date: 2026-10-10
issues: [77]
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Skin.java
  - src/main/java/com/nekyia/heroicmap/Anzeige.java
---

# 0012: Die Verzierungen drehen mit

## Anlass

Rückmeldung des Users zu 0.2.13 (mod#77): An der drehenden Minimap stören
die Teile des Rahmens, die nicht mitdrehen. Die zier stand fest in den
Ecken, die Marken N, O, S, W wanderten am Rahmen, ihre Bilder blieben
aufrecht. Der User will sie nicht entfernt haben. Sie sollen mitdrehen,
nichts soll fest stehen, und ein Schalter soll sie abstellen.

## Entscheidung

- **Starr mit der Karte:** Gedreht liegen zier und Marken in ihrer
  Richtung im Kartenbild, die zier auf den Diagonalen, die Marken auf N,
  O, S, W, gedreht wie die Karte, auf der Mitte der Bänder. Rund liegen
  sie auf dem Kreis, eckig auf dem Quadrat; das Quadrat selbst bleibt
  achsparallel. Das Bild dreht um seine Mitte um denselben Winkel.
  Ungedreht sitzen sie wie bisher.
- **Ein Bild je Marke:** N `norden`, S `marke`, O und W `marke_quer`, fest
  je Richtung; früher wählte die Lage zwischen `marke` und `marke_quer`.
- **Nicht pixelgenau:** Gedreht rastert das Spiel die Pixelkunst mit
  Nearest. Die Kanten werden treppig, beim Drehen flimmern Texel am Rand,
  wie bei der gedrehten Karte. Die Mitte liegt auf ganzen Pixeln des
  Schirms, so zittert beim Laufen nichts. So will es der User.
- **Der Schatten** bleibt auf dem Schirm um (+1, +1), das Licht kommt
  weiter von links oben.
- **Der Griff** im Menü steht fest an seiner Ecke, obenauf; er ist zum
  Ziehen da.
- **Schalter „Verzierungen“** im Untermenü, Vorgabe an. Aus zeigt der
  Rahmen nur Bänder oder Ring, gedreht wie ungedreht.
- **Abstand zum Rand:** die halbe Diagonale der grössten Verzierung,
  aufgerundet, immer, auch ungedreht und mit dem Schalter aus. So bleibt
  jede Verzierung in jeder Drehung ganz auf dem Schirm, und die Minimap
  springt beim Umschalten nicht.

Entschieden vom User, Plan vom Reviewer freigegeben am 10.10.

## Verworfene Alternativen

- **Gedreht nur die Bänder:** Am 10.10. zuerst geplant und gebaut. Der
  User will die Verzierungen behalten.
- **Nur die Lage dreht, das Bild bleibt aufrecht:** pixelgenau, aber
  genau das störte an den Marken.
- **Den Abstand nur gedreht vergrössern:** Die Minimap spränge beim
  Umschalten von Drehen um bis zu 6 Einheiten.

## Folgen

- **Die Minimap rückt mit Rahmen 1 bis 6 Einheiten weiter vom Rand:**
  `biom` 11 statt 6, `uhr` 13 statt 8, `kompass` und `kartograph` 13
  statt 7, `grau`, `holz` und `papier` 5 statt 4. Den Ausschlag geben die
  Marken, `norden` ist bis 17 × 17 Pixel gross; im Plan waren es nach der
  zier geschätzt 2 bis 3 Einheiten.
- **Je Verzierung eine Drehung der Pose** beim Zeichnen; gedreht vier zier
  und vier Marken, mit Schatten doppelt so viele Bilder.
