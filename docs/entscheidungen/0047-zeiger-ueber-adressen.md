---
title: "0047: Zeiger über Adressen"
description: Warum der Zeiger auch auf dieselbe Welt unter einer anderen Adresse zeigen kann, als `@<adresse>` mit dem eigenen Hash statt eines freien Pfads, warum der Mod ihn nur vorschlägt und wann nicht; Baustein 5 des Kartenausweises.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Zeiger.java
  - src/main/java/com/nekyia/heroicmap/Kartenliste.java
  - src/main/java/com/nekyia/heroicmap/Satz.java
  - src/main/java/com/nekyia/heroicmap/Ausweise.java
---

# 0047: Zeiger über Adressen

Löst [0031](0031-ein-zeiger-statt-geteilter-ordner.md) teilweise ab: im
Satz, dass im Zeiger nie ein Pfad oder eine Adresse steht.

## Anlass

Dieselbe Welt liegt manchmal unter zwei Adressen, etwa wenn ein Server
unter einem anderen Namen oder Port erreichbar ist. Unter der neuen Adresse
beginnt die Karte dann leer, obwohl die alte daliegt. Der User hat
entschieden: Der Mod schlägt die Karte von dort nur vor (2b). Baustein 5
des Konzepts „Kartenausweis“; den Plan hat der Reviewer freigegeben.

## Entscheidung

- **Form:** `@<adresse>` oder `@<adresse>/karte-<name>` in `zeiger.txt`;
  der Hash ist der eigene. `<adresse>` ist ein Ordner direkt unter
  `heroicmap/`, so geschrieben, wie der Mod Adressen ablegt, kein
  Einzelspieler und nicht derselbe Host, auch nicht mit `_25565`. Das
  gilt nur, wenn der eigene Ordner unter einer Adresse liegt.
- **Nur ein Vorschlag (2b)** in der Zeile des Zeigers, nach den Ordnern
  unter derselben Adresse. Kein Hinweis beim Betreten.
- **Vorgeschlagen** wird ein Ordner mit einem Download dieser Welt, der
  nicht ruht. Nicht, wenn sein Ausweis dieselbe eingetippte Adresse nennt
  wie der eigene, denn dann ist es derselbe Server in einem anderen
  Ordner; nicht, wenn beide Ausweise eine UID kennen und sie sich
  unterscheiden.
- **Wirkung wie in 0031:** nur lesen, die eigene Karte des Ziels nie, und
  „Selbst“ zeichnet nur in den eigenen Ordner. Ohne „Kopie“.
- **Der Einzelspieler** bekommt Vorschläge; ein Einzelspielerordner ist
  nie Ziel.

## Verworfen

- **Ein freier Pfad unter `heroicmap/`:** Er müsste gegen `..`, tiefe
  Pfade und einen anderen Hash geprüft werden. Mit dem eigenen Hash und
  höchstens zwei geprüften Namen zeigt der Zeiger nur auf dieselbe Welt.
- **Gleich nutzen, ohne Klick:** Derselbe Seed ist nicht dieselbe Welt.
  Zwei Server mit demselben Seed sähen sonst still die Karte des anderen.
- **Auch Ordner ohne Download vorschlagen,** wie unter derselben Adresse:
  Unter allen Adressen fänden sich leere Ordner, und die Zeile wäre voll.
- **„Kopie“ auch von dort:** [0045](0045-karte-kopieren-per-hardlink.md)
  gilt für dieselbe Adresse; bisher fragt niemand danach.
- **Einzelspielerordner als Ziel:** Ein Speicherstand mit demselben Seed
  ist meist eine eigene Welt.

## Folgen

- Ein gesetzter Zeiger über Adressen wird weiter geprüft (Review): Ein
  Reset im eigenen Ordner löst ihn, und mit verschiedener UID in den
  Ausweisen gilt sein Ziel nicht; sonst stünde nach einem Reset die alte
  oder die neue Karte über der falschen Welt. Siehe
  [Download](../download.md), „Reset“.
- Die Vorschläge zählt die Kartenliste einmal je Öffnen mit dem Bestand,
  im Hintergrund, nicht bei jedem Neuaufbau.
- Ein älterer Mod liest `@…` nicht als Namen einer Karte und lässt den
  Zeiger aus.
- Liegt der eigene Ordner später unter keiner Adresse, etwa mit der Ablage
  Hash, gilt der Zeiger nicht und steht nicht in der Kartenliste; zurück
  unter einer Adresse gilt er wieder.
