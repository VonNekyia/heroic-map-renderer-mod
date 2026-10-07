---
title: Download
description: Wie der Mod die Karte vom Plugin lädt, mit dem Kanal heroicmap:karte, den Befehlen, Zustimmung und Grösse, der Prüfung der Adresse, Manifest und Prüfsumme, Fristen, den harten Grenzen, der Reihe der Downloads, der Ablage je Server, Baum und Massstab, Fortsetzen und Abgleich, und was der Offline-Modus heisst.
code:
  - src/main/java/com/nekyia/heroicmap/Kanal.java
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/Freigabe.java
  - src/main/java/com/nekyia/heroicmap/Reihe.java
  - src/main/java/com/nekyia/heroicmap/Laden.java
  - src/main/java/com/nekyia/heroicmap/Adresse.java
  - src/test/java/com/nekyia/heroicmap/LadenTest.java
  - src/test/java/com/nekyia/heroicmap/KanalTest.java
  - src/test/java/com/nekyia/heroicmap/FreigabeTest.java
  - src/test/java/com/nekyia/heroicmap/ReiheTest.java
  - src/test/java/com/nekyia/heroicmap/AdresseTest.java
---

# Download

Der Mod lädt die Karte, die das Plugin eines Servers anbietet, als einzelne
Kacheln auf die Platte. Das Protokoll steht an einer Stelle, in
[`docs/download.md` des Plugins](https://github.com/VonNekyia/heroic-map-renderer-plugin/blob/main/docs/download.md),
Plan und Entscheidungen an
[heroic-map-renderer#154](https://github.com/VonNekyia/heroic-map-renderer/issues/154).
Diese Seite sagt, was der Mod davon tut. Gegen einen echten Server läuft der
Download erst mit dem Server aus
[heroic-map-renderer#151](https://github.com/VonNekyia/heroic-map-renderer/issues/151);
bis dahin prüfen ihn die Tests gegen einen kleinen Server auf loopback. Was
der Mod mit den Kacheln zeigt, steht unter [Vollbildkarte](vollbildkarte.md).

## Kanal

- **`heroicmap:karte`** in beide Richtungen, UTF-8-JSON ohne Längenpräfix
  (`Kanal`). Der Mod meldet ihn für beide Richtungen an; Fabric sagt es dem
  Server mit `minecraft:register`, und das Plugin schickt dann sein
  `angebot`.
- **Empfangen:** `angebot` merkt sich der Mod, `freigabe` reiht einen
  Download ein, `abgelehnt` zeigt er dem Spieler, mit `wieder` als
  Uhrzeit. `spieler` nennt die Mitspieler, siehe
  [Minimap](minimap.md), „Mitspieler“. An `jetzt` jeder Nachricht misst er den Versatz der Uhren für die
  [Live-Ebene](live.md). Nachrichten mit einem anderen `v` als 1 oder über 64 KiB
  verwirft er.
- **Senden:** `anfrage` nur, wenn `ClientPlayNetworking.canSend` wahr ist,
  also wenn das Plugin den Kanal angemeldet hat.
- **`neu: true`** steht in der `anfrage` eines vollen Downloads, wenn es
  für den Massstab keinen Stand zum Fortsetzen gibt, also kein `etags.txt`
  im Ordner des Massstabs (`Laden.hatStand`), etwa nachdem die Karte
  gelöscht ist. Dann gibt das Plugin ein neues Token aus, das gegen die
  vollen Downloads je Woche zählt. Sonst fehlt das Feld, und das Plugin
  gibt zum Fortsetzen dasselbe Token, solange es noch gilt. So hat es der
  Reviewer entschieden: Ein altes Token kann seinen Deckel schon verbraucht
  haben.
- **`url` oder `port`** in der `freigabe`: Die `url` ist im Plugin
  optional. Fehlt sie, nennt die `freigabe` `port`, und der Mod baut die
  Adresse selbst: `http://<ip>:<port>/download/<baum>` (`Freigabe.url`).
  `<ip>` ist die entfernte Adresse der bestehenden Verbindung zum
  Spielserver, nicht der Name aus der Serverliste; so gehen auch
  SRV-Einträge, und es kommt kein neuer Weg über DNS dazu. IPv6 steht in
  eckigen Klammern. Steht `url` da, gilt sie. Fehlen beide, fehlt die
  Verbindung oder liegt `port` nicht zwischen 1 und 65535, ist die
  `freigabe` unlesbar. Die Prüfung unter „Sicherheit“ gilt unverändert; mit
  der IP der Verbindung ist das Ziel der Spielserver selbst. So hat es der
  Reviewer auf Wunsch des Users entschieden.

## Befehle

| Befehl | tut |
|---|---|
| `/hmap angebot` | die angebotenen Karten mit Dimension und Grösse je Massstab |
| `/hmap laden <baum> <1, 2 oder 4>` | fragt erst im Dialog nach Grösse und Massstab, dann einen vollen Download an, siehe „Zustimmung und Grösse“ |
| `/hmap abgleich <baum>` | fragt einen Abgleich von Hand an, im gespeicherten Massstab; nach einer Ablehnung mit `wieder` erst ab dann wieder |

Dieselben Wege gehen über die Knöpfe der [Vollbildkarte](vollbildkarte.md), „Bedienung“.
Ohne Unterbefehl öffnet `/hmap` das Menü, siehe [Minimap](minimap.md), „Bedienung“.

- **Höchstens ein Abgleich je Tag** und Spieler, automatisch oder von Hand,
  so hat es der Maintainer entschieden; die Grenzen stehen im Protokoll des
  Plugins. Eine Ablehnung nennt `baum` und `art`. Ist `art` `abgleich`,
  sperrt der Mod den Abgleich genau dieses Baums bis `wieder`
  (`Freigabe.sperre`); fehlt ein Feld, sperrt er nichts und zeigt nur die
  Meldung. Beim Trennen vergisst er die Sperren.

## Zustimmung und Grösse

- **Vor der `anfrage`** eines vollen Downloads fragt ein Dialog: Name der
  Karte, Grösse und Massstab aus dem `angebot`, und dass ein voller
  Download gegen die Grenze des Servers zählt. Ein Nein kostet nichts, denn
  das Plugin zählt erst, wenn es das Token ausstellt.
- **Nach der `freigabe`** entscheidet `Freigabe.weg`:

  | `freigabe` | Bedingung | Weg |
  |---|---|---|
  | `voll` | der Spieler bestätigte diesen Baum und Massstab vor der `anfrage`, `bytes` höchstens 10 % über der bestätigten Grösse | still |
  | `abgleich` | im gespeicherten Massstab, `bytes` höchstens 11 % der gespeicherten Grösse des Satzes | still |
  | jede andere | | Dialog mit Spielserver, Host, Grösse und Massstab |

  Still heisst: nur mit Zustimmung zum Host; fehlt sie, fragt ein Dialog
  nach dem Host allein.
- **Gemessen wird nie an Zahlen aus dem `angebot` allein** (`Freigabe.mass`):
  Ein voller Download misst an dem, was der Spieler bestätigt hat, im
  Befehl oder im Dialog nach der `freigabe`. Beim Start legt der Mod Grösse
  und Kacheln dieses Satzes ab, siehe „Ablage“, und ein Abgleich misst
  daran. Fehlt der Stand, misst ein Abgleich nach dem Dialog am Zehnfachen
  der gezeigten `bytes`, denn sein Deckel ist 10 % des Satzes.
- **Die Zustimmung** gilt je Paar aus Spielserver und Host, gespeichert in
  `heroicmap/zustimmung.txt` im Spielordner.
- **Höchstens ein Dialog,** geplant oder offen. Eine `freigabe`, die einen
  zweiten bräuchte, verfällt mit einer Meldung. Sagt der Spieler Ja zu
  `/hmap laden`, während inzwischen ein Download für den Baum läuft,
  geht keine `anfrage` hinaus.
- **Der tägliche Abgleich:** Das Plugin schickt ihn von sich aus als
  `freigabe` mit `art` `abgleich`; mit Zustimmung lädt der Mod ihn im
  Hintergrund.
- **Im Einzelspieler** gibt es keinen Server und keinen Download.

## Sicherheit

- **Adresse** (`Adresse.form`, beim Lesen der `freigabe`): nur `http` und
  `https` mit Host, ohne Userinfo, Query und Fragment; der Mod hängt Pfade
  an die Adresse an.
- **Heimnetz** (`Adresse.pruefe`, im Thread der Reihe vor jedem Abruf,
  denn jede neue Verbindung löst den Namen neu auf): Nah heisst loopback,
  link-local, ein privates Netz, `100.64.0.0/10`, `0.0.0.0/8` oder
  `fc00::/7`; IPv4 in IPv6 (`::ffff:0:0/96`) packt der Mod dafür aus.
  Massgeblich ist die Adresse der echten Verbindung zum Spielserver, nicht
  seine Namen; sonst könnte ein Server mit einem zusätzlichen DNS-Eintrag
  Anfragen in das Heimnetz des Spielers lenken.

  | Spielserver | erlaubtes Ziel |
  |---|---|
  | fern | jede Adresse, die nicht nah ist |
  | nah | nur der Spielserver selbst, gleiche Adresse oder beide loopback; dort läuft der Server aus heroic-map-renderer#151 |

  Jede Adresse, auf die der Host zeigt, muss erlaubt sein.
- **Kein Proxy** (`NO_PROXY`): Ein Proxy des Systems ginge an der Prüfung
  vorbei.
- **Keine Weiterleitungen** (`Redirect.NEVER`): So geht das Token nie an
  einen dritten Host.
- **Das Token** ist für den Mod undurchsichtig; er schickt es nur im Header
  `Authorization: Bearer`.
- **Nur angebotene Bäume:** Eine `freigabe` für einen Baum oder Massstab,
  der nicht im `angebot` steht, verwirft der Mod.
- **Offline-Modus:** Ein Server im Offline-Modus verschlüsselt die
  Spielverbindung nicht. Dann ist auch `manifest_sha256` über den Kanal
  nicht abgesichert, und ohne HTTPS kann ein Lauscher das Token mitlesen;
  der Deckel der Bytes je Token begrenzt, was er damit lädt.
- **Kacheln** prüft der Mod über ihre Grösse, nicht ihren Inhalt.

## Ablauf

`Laden.lade`, im Thread der Reihe:

1. **Adresse** prüfen, vor jedem Abruf, siehe „Sicherheit“.
2. **`map.json`** holen; daraus `minZoom` und `maxZoom`. Die Stufe des
   Massstabs: 4 px ist `maxZoom`, 2 px eine gröber, 1 px zwei.
3. **Manifest** holen und SHA-256 über das gzip mit `manifest_sha256`
   vergleichen. Passt es nicht, endete inzwischen ein Lauf; der Mod fragt
   einmal neu an und bekommt dasselbe Token mit dem neuen Manifest.
4. **Entpacken** und die Zeilen bis zur Stufe des Massstabs nehmen.
5. **Löschen,** was lokal liegt und nicht mehr im Manifest steht.
6. **Laden,** was fehlt oder ein anderes ETag hat, über 4 Verbindungen mit
   Keep-Alive (`java.net.http.HttpClient`, HTTP/1.1). Gespeichert wird das
   ETag aus der Antwort, denn eine Kachel kann neuer sein als das Manifest.
   Scheitert eine Kachel, bricht der Mod die laufenden Abrufe der anderen
   Verbindungen sofort ab.
7. **Der Index** `etags.txt` ist ein Protokoll: je Kachel eine Zeile ohne
   ETag vor dem Schreiben, eine mit danach; die letzte gilt, eine ohne ETag
   heisst neu laden. Endet das Spiel mitten im Download, gilt, was schon
   dasteht. Am Ende schreibt der Mod den Index neu, eine Zeile je Kachel.

**Fristen:** Eine Anfrage hat 2 min für Header und Körper zusammen; danach
bricht der Mod sie ab (`sendAsync`, `cancel`). Der Aufbau der Verbindung hat
10 s. Fehler der Verbindung und abgelaufene Fristen heissen `NETZ`.

## Reihe

- **Nacheinander:** Downloads laufen in einem Thread (`Reihe`). Eine
  `freigabe` für einen anderen Baum wartet, bis der laufende fertig ist.
- **Je Server und Baum höchstens einer,** wartend oder laufend; der
  Schlüssel ist der Ordner des Baums. Eine weitere `freigabe` für denselben
  Baum verfällt mit einer Meldung, und `/hmap laden` sagt vorher, dass
  schon einer läuft.
- **Beim Trennen** bricht der Mod den laufenden Download ab und verwirft,
  was wartet. Was schon geladen ist, steht im Index.
- **Das Ergebnis** meldet der Mod in jedem Fall, auch nach einem `Error` im
  Thread der Reihe.

## Harte Grenzen

| Grenze | Wert | bei Verstoss |
|---|---|---|
| `map.json` | 64 KiB | Abbruch |
| Manifest, gepackt und entpackt | je 64 MiB | Abbruch |
| Zeile des Manifests | `z/x/y` ganze Zahlen, `z` zwischen `minZoom` und `maxZoom`, Grösse bis 4 MiB, ETag ohne Leer- und Steuerzeichen | Abbruch |
| Zeilen bis zur Stufe | keine doppelte Kachel; höchstens `kacheln` plus 10 %, und höchstens eine je 4 KiB der Grösse des Satzes plus 10 %, beides aus dem Stand, an dem der Download misst | Abbruch |
| eine Kachel | 4 MiB; mit dem ETag des Manifests genau die Grösse aus dem Manifest | Abbruch |
| Summe des Geladenen | bei `voll` `bytes` plus 10 %, beim Abgleich `bytes`, der Deckel des Tokens | der Rest bleibt liegen, „zum Teil geladen“ |
| Antwort | 200 | 401 und 403 heissen abgelehnt, 429 Budget erschöpft, alles andere Abbruch |

`z/x/y` wird ein Dateipfad; deshalb nur ganze Zahlen, auch beim Lesen des
eigenen Index, und doppelt zählt nach den Zahlen, `7/0/0` wie `007/0/0`.
Ein Abbruch meldet dem Spieler den Grund, übersetzt und ohne Pfad;
Einzelheiten stehen im Log.

## Ablage

- **Ordner:** `heroicmap/<server>/<baum>/<massstab>/` im Spielordner, darin
  `map.json`, `etags.txt`, `z/x/y.webp` und `tmp/`. `<server>` ist die
  Adresse des Servers, klein, andere Zeichen als Buchstaben, Ziffern, Punkt
  und Strich werden `_`. Ein Baum heisst nur `[a-z0-9_-]`, höchstens 64
  Zeichen. Namen, die Windows für Geräte hält (`con`, `nul`, `com1` …),
  lehnt der Mod als Baum ab und stellt dem Server ein `_` voran.
- **Schreiben** über eine Zwischendatei in `tmp/`, dann verschieben; nie
  liegt eine halbe Kachel da. `tmp/` leert der Mod zu Beginn jedes Downloads.
- **`satz.json`** je Baum gehört zur [Vollbildkarte](vollbildkarte.md),
  „Welcher Satz“.
- **`overlay/`** je Baum hält die [Live-Ebene](live.md). Nach einem
  vollständigen Download räumt der Mod dort, was älter ist als
  `abdeckt_bis` aus der `freigabe`, siehe dort, „Abgleich“.
- **Der Stand** steht in `<baum>/massstab.txt`: Massstab, Grösse und
  Kacheln des Satzes, wie der Spieler ihn bestätigt hat, etwa `4 9000000000
  450000`. Der Mod schreibt ihn, wenn ein voller Download beginnt, denn dann
  speichert das Plugin den Massstab. Der Abgleich fragt in diesem Massstab
  an und misst an Grösse und Kacheln.
- **Eine Auflösung je Baum:** Ist ein Download vollständig, fallen die
  anderen Massstäbe dieses Baums weg. Bis dahin bleibt der alte Satz, damit
  der Spieler nicht ohne Karte dasteht; ein gekappter Download löscht nichts.
- **Fortsetzen:** Was mit gleichem ETag schon da ist, lädt der Mod nicht.
  Ein Abgleich ist derselbe Weg mit weniger Kacheln.

## Was bleibt eine Näherung

- **Auflösen und Laden** sind zwei Schritte: Prüfung und Verbindung lösen
  den Namen getrennt auf. Beide gehen durch denselben Cache der JVM; läuft
  er genau dazwischen ab, kann die Verbindung eine andere Adresse bekommen
  als die geprüfte.
- **Ein naher Spielserver** darf sich selbst als Ziel nennen, also jeden
  Port auf seiner Adresse.
- **Plattenplatz** prüft der Mod nicht; der Dialog nennt nur die Grösse.
