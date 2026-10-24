---
title: "0038: Ort einer Karte ändern"
description: Warum „Ort …“ in der Kartenliste als Vorgabe den ganzen Ordner einer Welt umzieht, nur zwischen Adressen, ganz oder gar nicht, nie zusammenführt und grau bleibt, solange ein Download, „Selbst zeichnen“ oder bei „Ganzer Ordner“ der Spieler in der Welt ist.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Ort.java
  - src/main/java/com/nekyia/heroicmap/OrtFenster.java
  - src/main/java/com/nekyia/heroicmap/Kartenliste.java
---

# 0038: Ort einer Karte ändern

## Anlass

Der User am 24.10.: In der Kartenliste sollen sich Teile einer Karte ändern
lassen, Adresse, Hash und Server. Hinter einem Proxy ohne Namen teilen sich
alle Server einen Ordner je Welt, und nach einem Reset hat eine Welt einen
neuen Hash. Den Plan „Ort und Erkennen“ schrieb Mod Code Map 2; der
Reviewer hat ihn freigegeben und die Vorgabe und Einzelspieler entschieden.

## Entscheidung

- **„Ganzer Ordner“ ist die Vorgabe:** Der Ordner der Welt zieht mit
  Wegpunkten, Kartenlage, `zeigt.txt` und `zeiger.txt` um. Eine eigene Karte
  des alten Servers gehört danach dem neuen, wie bei „übernehmen“.
- **„Nur diese Karte“** zieht einen Baum in einen anderen Ordner; Wegpunkte
  und Kartenlage bleiben.
- **Nur zwischen Adressen** und der Ablage nach dem Hash, nie zwischen
  Einzelspieler und Server.
- **Ganz oder gar nicht:** Ordner und Bäume ziehen als Umbenennen mit
  `ATOMIC_MOVE` um, `satz.json` und `zeigt.txt` werden über eine
  Zwischendatei ersetzt. Scheitert ein Schritt, gehen die vorigen zurück.
- **Nie zusammenführen:** Liegt am Ziel etwas, ist „Umziehen“ grau.
- **Grau** auch, solange ein Download in einen der Bäume wartet oder läuft,
  solange „Selbst zeichnen“ in den alten oder neuen Ordner zeichnet,
  solange Minimap und Vollbildkarte einen Baum zeigen, der umzöge, und
  bei „Ganzer Ordner“, wenn der Spieler in der Welt des alten oder neuen
  Ordners ist.
- **Der Hash** hat genau 16 Stellen hex, klein, wie der Mod ihn schreibt.

## Verworfen

- **„Nur diese Karte“ als Vorgabe:** Bei zwei Ordnern derselben Welt will
  der Spieler sagen, welcher Ordner welchem Server gehört, und Wegpunkte
  gehören zum Ordner.
- **Zusammenführen** zweier Ordner oder Bäume: Kacheln, Wegpunkte und
  `zeigt.txt` zweier Stände liessen sich nicht ohne Verlust mischen.
- **Kopieren statt Umbenennen:** Es kostet Zeit und Platz in der Grösse
  des Baums, und ein Abbruch liesse zwei halbe Stände.
- **Einzelspieler ↔ Server:** Der Ordner einer Einzelspielerwelt heisst
  nach ihrem Speicherordner, nicht nach einer Adresse; der Reviewer hat es
  ausgeschlossen.
- **„Ort …“ ganz grau in der Welt, in der der Spieler ist,** wie im Plan:
  „Nur diese Karte“ geht dort, ausser für den Baum, den Minimap und
  Vollbildkarte zeigen. Grau ist nur „Umziehen“, mit dem Grund in Rot; der
  Reviewer hat es angenommen.
- **Die Anzeige nach dem Umzug neu laden** statt „Umziehen“ grau für den
  gezeigten Baum: mehr Code für einen seltenen Fall; der Reviewer wählte
  das Graue.

## Folgen

- Ein Zeiger, der danach auf keinen Ordner zeigt, steht in der Kartenliste
  als „(fehlt)“, bis der Spieler ihn löst.
- Was der Server meldet, ändert „Ort …“ nicht. Beim nächsten Betreten gilt
  die Ablage nach seiner Meldung.
- Die Gründe „Welt, in der du bist“, „Selbst zeichnen“ und „gezeigt“
  prüft nur `OrtTest`: Im Gametest ist der Spieler in einer
  Einzelspielerwelt, deren Ordner nicht umzieht.
