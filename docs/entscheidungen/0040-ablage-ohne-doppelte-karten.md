---
title: "0040: Ablage ohne doppelte Karten"
description: Warum der Standardport bei IP:Port nichts an den Ordner hängt, eine Welt beim Wechsel der Ablage umzieht statt neu geladen zu werden, die Kartenliste doppelte Bäume mit Datum zeigt und ein Angebot ohne Namen den gemerkten Namen stehen lässt.
status: gilt
date: 2026-10-24
issues: []
code:
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/Ort.java
  - src/main/java/com/nekyia/heroicmap/Servername.java
  - src/main/java/com/nekyia/heroicmap/Kartenliste.java
  - src/main/java/com/nekyia/heroicmap/Anzeige.java
---

# 0040: Ablage ohne doppelte Karten

## Anlass

Der User sah am 24.10. in der Kartenliste dieselbe Karte zweimal, je
5,5 GB: einmal unter `<host>/karte-main/…`, einmal unter
`<host>_25565/karte-main/…`. Die Ablage war im Untermenü von IP auf
IP:Port gewechselt. Der Mod suchte danach im neuen Ordner, fand nichts und
lud alles neu; der alte Ordner blieb liegen. Dabei ist 25565 der
Standardport, beide meinen denselben Server. Den Plan schrieb Mod Code
Map 2, der Reviewer hat ihn freigegeben.

Beim Prüfen fiel ein zweiter Fehler auf: Ein Angebot ohne Namen nahm den
gemerkten Namen der Adresse heraus. Ein zweiter Server hinter derselben
Adresse mit Download und ohne `server-name` nähme dem ersten so bei jedem
Besuch seinen Ordner.

## Entscheidung

- **Der Standardport zählt nicht:** Bei IP:Port heisst der Ordner für Port
  25565 `<host>` wie bei IP; nur ein anderer Port hängt `_<port>` an.
- **Umziehen statt neu laden:** Beim ersten Betreten einer Welt je Sitzung
  zieht sie aus den Ordnern der anderen Ablagen in den der jetzigen, samt
  `karte-<k>/`; ebenso aus `<host>_25565` von vor dieser Regel. Nach einem
  Wechsel der Ablage im Menü zieht die Welt, in der der Spieler ist, gleich
  um. Ganz oder gar nicht, nie auf Bestehendes, nicht während eines
  Downloads in den alten Ordner.
- **„doppelt“ in der Kartenliste:** Gleicher Baum, gleicher Server und
  gleicher Hash unter Ordnern, die denselben Host meinen, sind doppelt. Der
  Baum, den der Mod nach der Ablage nicht nutzt, zeigt gelb „doppelt“. Der
  Tooltip nennt bei beiden die anderen und das Datum der jüngsten Datei,
  denn manchmal ist der ungenutzte der neuere. Gelöscht wird nur mit einem
  Klick des Spielers.
- **Ein Angebot ohne Namen** lässt den gemerkten Namen stehen; nur ein
  anderer Name ersetzt ihn.

## Verworfen

- **Beim Wechsel der Ablage alle Welten umziehen:** Seed und Adresse kennt
  der Mod nur von der Welt, in der der Spieler ist; die übrigen ziehen beim
  nächsten Betreten um.
- **Doppelte von selbst löschen oder zusammenführen:** Welcher Baum der
  neuere ist, entscheidet der Spieler am Datum.
- **`<host>` und `<host>_<anderer Port>` als doppelt zählen:** Ein anderer
  Port ist ein anderer Server.
- **Den Namen gleich in derselben Sitzung gelten lassen:** Er zöge unter der
  laufenden Anzeige um; bis zum Kartenausweis bleibt „ab der nächsten
  Sitzung“, so will es der Reviewer.

## Folgen

- Ein Server, der seinen Namen absichtlich entfernt, behält ihn bei
  Spielern, die ihn schon kannten. Hilft das nicht, ändert er den Namen,
  oder der Spieler zieht mit „Ort …“ um.
- Liegen eine Welt nach der alten und nach der neuen Ablage schon da, zieht
  nichts um; die Kartenliste zeigt dann „doppelt“.
- Ein Ordner `<host>_25565` von vor dieser Regel zieht beim nächsten
  Betreten nach `<host>`, wenn es dort die Welt noch nicht gibt.
- `heroicmap/welt-<hash>` aus der Ablage Hash hat keine Adresse; ihn holt
  der erste Server mit diesem Seed, den der Spieler betritt. Teilen sich
  zwei Server einen Seed, etwa eine Kopie oder ein Spiegel, gehört die
  Karte danach dem zuerst besuchten. Das ist vertretbar, denn wer Hash
  gewählt hatte, wollte eine Karte je Welt; „Ort …“ zieht sie um.
