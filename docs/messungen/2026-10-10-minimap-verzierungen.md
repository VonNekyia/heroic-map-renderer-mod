---
title: Minimap, Verzierungen drehen mit
description: Was die Minimap mit dem Rahmen „kompass“ je Frame kostet, seit zier und Marken beim Drehen starr mitdrehen, gegen main, im Wechsel A B A B unter Grundlast; eckig und rund, mit und ohne Drehen, im Stand, im Flug und schräg im Flug, bei 4 Pixeln je Block und Zoom 4.
date: 2026-10-10
commits: [49e4f3c, e4765f0]
code:
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Skin.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
---

# Minimap, Verzierungen drehen mit

Mit dem Rahmen „kompass“ kostet die Minimap im HUD-Element auf dem Branch
so viel wie auf main: im Median 0,016 bis 0,021 ms eckig und 0,025 bis
0,033 ms rund, der Unterschied höchstens 0,003 ms. Das ist so viel, wie
zwei Läufe desselben Stands auseinanderliegen. In der Frametime ist kein
Unterschied über der Streuung zu sehen. Es gilt „unverändert“.

## Aufbau

Wie [Minimap, Vieleck auch ungedreht](2026-10-09-minimap-vieleck.md),
„Aufbau“, mit diesen Unterschieden:

- **Mit Rahmen:** `-PmessungRahmen=kompass`, neu in `Messung`. „kompass“
  hat Schatten, 13 × 13 zier und eine Nordmarke von 17 × 17, also die
  meisten Bilder je Frame. Die Verzierungen sind an, die Vorgabe.
- **Im Wechsel A B A B:** main `49e4f3c`, dann der Branch `e4765f0`,
  dann wieder beide, je ein Lauf von `runClientGameTest` mit
  `-PmessungDrehen=true`. Der Gametest `Messung` ist in allen vier Läufen
  derselbe, der des Branches.
- **Unter Grundlast:** Der Rechner lief mit anderen Programmen weiter, das
  hat der Reviewer angenommen. Verglichen wird nur innerhalb der Reihe.

## Ablauf

10.10., 20:02:11 bis 20:52:20, unter der Sperrdatei, Lauf 1 main
20:02–20:14, Lauf 2 Branch 20:14–20:27, Lauf 3 main 20:27–20:39, Lauf 4
Branch 20:39–20:52. Vorher keine fremde Sperre, kein Spiel, kein Renderer.
Alle 5 s geprüft: in 407 Proben kein Renderer. Die Last lag im Median bei
27, 26, 26 und 29 %, von 3 bis 84 %. Die Zahlen stammen aus den Berichten
des Gametests, je Lauf der Median der drei Runden.

## Ergebnis

Zeit im HUD-Element der Minimap, p50 in ms, Median der drei Runden; A ist
main, B der Branch:

| Form, Lauf | Drehen | A1 | A2 | B1 | B2 |
|---|---|---|---|---|---|
| eckig, Stand | aus | 0,018 | 0,017 | 0,017 | 0,016 |
| eckig, Flug | aus | 0,017 | 0,017 | 0,018 | 0,017 |
| eckig, Flug schräg | aus | 0,017 | 0,018 | 0,017 | 0,016 |
| eckig, Stand | an | 0,020 | 0,020 | 0,020 | 0,021 |
| eckig, Flug | an | 0,020 | 0,021 | 0,020 | 0,020 |
| eckig, Flug schräg | an | 0,020 | 0,021 | 0,020 | 0,020 |
| rund, Stand | aus | 0,027 | 0,029 | 0,029 | 0,028 |
| rund, Flug | aus | 0,026 | 0,028 | 0,026 | 0,026 |
| rund, Flug schräg | aus | 0,025 | 0,027 | 0,027 | 0,030 |
| rund, Stand | an | 0,031 | 0,032 | 0,033 | 0,033 |
| rund, Flug | an | 0,029 | 0,029 | 0,029 | 0,031 |
| rund, Flug schräg | an | 0,029 | 0,029 | 0,030 | 0,031 |

Frametime p50 mit Minimap minus ohne Minimap, in derselben Runde, in ms,
Median der drei Runden:

| Form, Lauf | Drehen | A1 | A2 | B1 | B2 |
|---|---|---|---|---|---|
| eckig, Stand | aus | +0,066 | +0,051 | +0,045 | +0,040 |
| eckig, Flug | aus | −0,038 | −0,041 | −0,022 | −0,030 |
| eckig, Flug schräg | aus | −0,057 | −0,027 | −0,044 | −0,040 |
| eckig, Stand | an | +0,041 | +0,054 | +0,051 | +0,073 |
| eckig, Flug | an | −0,033 | −0,014 | −0,032 | −0,034 |
| eckig, Flug schräg | an | −0,031 | −0,027 | −0,030 | −0,031 |
| rund, Stand | aus | +0,050 | +0,041 | +0,095 | +0,051 |
| rund, Flug | aus | −0,025 | −0,036 | −0,023 | +0,001 |
| rund, Flug schräg | aus | −0,027 | −0,021 | −0,035 | −0,057 |
| rund, Stand | an | +0,064 | +0,072 | +0,084 | +0,061 |
| rund, Flug | an | −0,020 | −0,027 | −0,030 | −0,013 |
| rund, Flug schräg | an | −0,015 | −0,012 | −0,008 | −0,034 |

## Schluss

- **HUD-Element:** B liegt in jeder Zeile innerhalb von 0,003 ms von A,
  so weit wie A1 und A2 auseinander. Dass gedreht vier zier und vier
  Marken je mit gedrehter Pose zeichnen, kostet nichts Messbares; ungedreht
  geht das Bild nun auch über die Pose, ebenso ohne messbaren Unterschied.
- **Frametime:** Die Differenzen streuen zwischen A1 und A2 um bis zu
  0,03 ms, zwischen B1 und B2 ebenso; ein Unterschied zwischen A und B
  ist darin nicht zu sehen.
- **Mit Rahmen gegen ohne:** Mit „kompass“ kostet das HUD-Element eckig
  rund 0,017 bis 0,021 ms, ohne Rahmen waren es am 09.10. 0,002 bis
  0,003 ms; das ist der Rahmen selbst, nicht diese Änderung.

## Grenzen

- **Grundlast:** Die Last schwankte in jedem Lauf zwischen wenigen und gut
  80 %. Die Frametime ohne Minimap lag in Lauf 4 rund im Flug bis 0,07 ms
  höher als in den anderen. Darum gilt der Vergleich nur in der Reihe und nur
  über die Differenz oder das HUD-Element.
- **Negative Differenzen im Flug:** Mit Minimap ist die Frametime im Flug
  im Median kleiner als ohne. Das kommt aus der Reihenfolge der Runden und
  der Last, nicht aus der Minimap; es trifft A und B gleich.
