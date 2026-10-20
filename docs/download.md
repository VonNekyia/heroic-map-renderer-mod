---
title: Download
description: Wie der Mod die Karte vom Plugin lädt, beim ersten Mal als ein Strom, mit dem Kanal heroicmap:karte, den Befehlen, Zustimmung und Grösse, der Prüfung der Adresse, Manifest und Prüfsumme, Fristen, den harten Grenzen, der Reihe der Downloads, der Ablage je Welt nach dem Hash des Seeds, Baum und Massstab, der Kartenliste mit Löschen, Fortsetzen und Abgleich, und was der Offline-Modus heisst.
code:
  - src/main/java/com/nekyia/heroicmap/Kanal.java
  - src/main/java/com/nekyia/heroicmap/Downloads.java
  - src/main/java/com/nekyia/heroicmap/Freigabe.java
  - src/main/java/com/nekyia/heroicmap/Reihe.java
  - src/main/java/com/nekyia/heroicmap/Servername.java
  - src/test/java/com/nekyia/heroicmap/ServernameTest.java
  - src/main/java/com/nekyia/heroicmap/Kartenname.java
  - src/test/java/com/nekyia/heroicmap/KartennameTest.java
  - src/main/java/com/nekyia/heroicmap/Laden.java
  - src/main/java/com/nekyia/heroicmap/Adresse.java
  - src/test/java/com/nekyia/heroicmap/LadenTest.java
  - src/test/java/com/nekyia/heroicmap/KanalTest.java
  - src/test/java/com/nekyia/heroicmap/FreigabeTest.java
  - src/test/java/com/nekyia/heroicmap/ReiheTest.java
  - src/test/java/com/nekyia/heroicmap/AdresseTest.java
  - src/main/java/com/nekyia/heroicmap/Kartenliste.java
  - src/test/java/com/nekyia/heroicmap/AblageTest.java
  - src/main/resources/heroicmap.accesswidener
  - src/gametest/java/com/nekyia/heroicmap/Anmeldung.java
  - src/gametest/java/com/nekyia/heroicmap/Strom.java
  - src/main/java/com/nekyia/heroicmap/Welten.java
---

# Download

Der Mod lädt die Karte, die das Plugin eines Servers anbietet, auf die
Platte: beim ersten Mal als einen Strom, siehe „Strom“, danach Kachel für
Kachel. Das Protokoll steht an einer Stelle, in
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
- **Name des Servers:** Das `angebot` kann oben ein Feld `server` tragen,
  etwa `"server": "play.example.net"`, den Namen, den der Server in seiner
  Konfiguration hat (heroic-map-renderer-plugin#67). Nur ein echter
  Hostname, wie das Plugin ihn schickt: Teile aus `a–z`, `0–9` und
  Bindestrich, nicht am Rand, je höchstens 63 Zeichen, durch einzelne Punkte
  getrennt, zusammen höchstens 253; nie `.`, `..` oder `a..b`
  (`Servername.gueltig`). Sonst übergeht der Mod ihn mit einer Zeile im
  Log; ein Server ohne das Plugin könnte so etwas schicken. Ohne Konfiguration fehlt das
  Feld. Wofür, steht unter „Ablage“.
- **Empfangen:** `angebot` merkt sich der Mod, `freigabe` reiht einen
  Download ein, `abgelehnt` zeigt er dem Spieler, mit `wieder` als
  Uhrzeit. `spieler` nennt die Mitspieler, siehe [Minimap](minimap.md),
  „Mitspieler“. `ebenen` und `ebene` bringen die Ebenen, `tafel` die Tafel
  eines Objekts, siehe [Ebenen](ebenen.md), „Infotafel“, `banner` das
  Sprite eines geheimen Banners, siehe [Ebenen](ebenen.md), „Geheime
  Banner“; `ebene`, `tafel` und `banner` liest schon der Thread des Netzes.
  `web` bringt das Token für die Webkarte, siehe „Webkarte“ unten. Nachrichten mit einem anderen
  `v` als 1 oder über 1 MiB verwirft er; so gross wird nur ein Teil einer
  Ebene.
- **Senden:** `anfrage`, `show`, `tafel`, `knopf`, `banner` und `web` nur, wenn
  `ClientPlayNetworking.canSend` wahr ist, also wenn das Plugin den Kanal
  angemeldet hat. Zu `show` siehe [Minimap](minimap.md), „Mitspieler“, zu
  `knopf` [Ebenen](ebenen.md), „Knöpfe“.
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

- **Webkarte** (heroic-map-renderer-plugin#85), siehe [Vollbildkarte](vollbildkarte.md),
  „Im Browser öffnen“:

  ```json
  {"v":1,"typ":"web"}
  {"v":1,"typ":"web","jetzt":1760000000,"erlaubt":true,"token":"…"}
  {"v":1,"typ":"web","jetzt":1760000000,"erlaubt":false,"grund":"rate","wieder":12}
  ```

  - `grund` ist `permission`, `webserver`, `rate` oder `sonst`; einen
    anderen liest der Mod wie `sonst`.
  - `wieder` sind bei `web` die Sekunden bis zum nächsten Link, anders als
    bei `abgelehnt`, wo es eine Uhrzeit ist.
  - Die Antwort auf `show` trägt `"web": true` oder `false`: ob der Spieler
    jetzt einen Link bekäme. Fehlt das Feld, ist das Plugin alt, und der
    Mod fragt nicht.
  - Die Antwort auf `show` trägt `karte`, den Namen der Karte für den
    Ordner, siehe „Ablage“. Fehlt das Feld, bleibt der Ordner,
    wie er ist.
  - Die Liste `ebenen` kommt mit `url` oder `port`, sobald der Webserver
    eine Adresse hat, auch leer, `"ebenen":[]`. Aus ihr nimmt der Mod die
    Basis, siehe [Ebenen](ebenen.md), „Symbole“.
  - Eine Antwort `web` ohne offene Frage übergeht der Mod.

## Befehle

| Befehl | tut |
|---|---|
| `/hmap angebot` | die angebotenen Karten mit Dimension und Grösse je Massstab |
| `/hmap laden <baum> <1, 2 oder 4>` | fragt erst im Dialog nach Grösse und Massstab, dann einen vollen Download an, siehe „Zustimmung und Grösse“ |
| `/hmap karte <x> <z>` | öffnet die Vollbildkarte der jetzigen Dimension mit dem Block (x, z) in der Mitte, Stufe und Lupe wie beim letzten Schliessen (`Karte.bei`); das ruft der Klick auf den Todespunkt im Chat, siehe [Wegpunkte](wegpunkte.md), „Todespunkt“ |
| `/hmap web` | öffnet die Webkarte im Browser und meldet dort an, wie der Knopf „Im Browser öffnen“, siehe [Vollbildkarte](vollbildkarte.md), „Im Browser öffnen“; was nicht geht, steht im Chat |
| `/hmap abgleich <baum>` | fragt einen Abgleich von Hand an, im gespeicherten Massstab; nach einer Ablehnung mit `wieder` erst ab dann wieder |

Dieselben Wege gehen über die Knöpfe der [Vollbildkarte](vollbildkarte.md), „Bedienung“.
Einen Namen, den der Mod nicht als Baum nimmt (`Freigabe.baum`), weisen
`laden` und `abgleich` vor jeder anderen Prüfung ab; es geht keine
`anfrage` hinaus, die das Plugin zählte.
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
  | `abgleich` | im gespeicherten Massstab, `bytes` höchstens 55 % der gespeicherten Grösse des Satzes, die Hälfte mit Spiel ([0027](entscheidungen/0027-abgleich-bis-zur-haelfte.md)) | still |
  | jede andere | | Dialog mit Spielserver, Host, Grösse und Massstab |

  Still heisst: nur mit Zustimmung zum Host; fehlt sie, fragt ein Dialog
  nach dem Host allein.
- **Gemessen wird nie an Zahlen aus dem `angebot` allein** (`Freigabe.mass`):
  Ein voller Download misst an dem, was der Spieler bestätigt hat, im
  Befehl oder im Dialog nach der `freigabe`. Beim Start legt der Mod Grösse
  und Kacheln dieses Satzes ab, siehe „Ablage“, und ein Abgleich misst
  daran. Fehlt der Stand, misst ein Abgleich nach dem Dialog am Doppelten
  der gezeigten `bytes`, denn sein Deckel ist die Hälfte des Satzes.
- **Die Zustimmung** gilt je Paar aus Spielserver und Host, gespeichert in
  `heroicmap/zustimmung.txt` im Spielordner.
- **Höchstens ein Dialog,** geplant oder offen. Eine `freigabe`, die einen
  zweiten bräuchte, verfällt mit einer Meldung. Sagt der Spieler Ja zu
  `/hmap laden`, während inzwischen ein Download für den Baum läuft,
  geht keine `anfrage` hinaus.
- **Der tägliche Abgleich:** Das Plugin schickt ihn von sich aus als
  `freigabe` mit `art` `abgleich`; mit Zustimmung lädt der Mod ihn im
  Hintergrund. Den Deckel setzt das Plugin, die Hälfte des Satzes; ein
  älteres mit 10 % lädt mit diesem Mod weiter still. Auf der Vollbildkarte
  zeigt der Abgleich den Kasten des Fortschritts, siehe
  [Vollbildkarte](vollbildkarte.md), „Fortschritt“.
- **Abgleich ohne Stand:** Hat der Mod im Ordner des Massstabs keinen
  Stand (`Laden.hatStand`), etwa nach dem Löschen der Karte oder in einem
  neuen Massstab, lädt er statt des Abgleichs voll
  (`Downloads.vollStattAbgleich`). Er fragt wie vor dem ersten Download
  und schickt dann `anfrage voll` mit `neu: true`. Sonst brächte der
  Abgleich je Tag nur die Hälfte der Karte, denn sein Deckel ist die
  Hälfte des Satzes. Den Abgleich dieses Tages verbraucht das trotzdem. Ebenso,
  solange ein Strom nicht fertig ist; dann geht die `anfrage` ohne `neu`,
  siehe „Strom“.
- **Im Einzelspieler** gibt es keinen Server und keinen Download, aber einen
  Ordner der Welt, siehe „Ablage“.

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
- **Namen im Strom** werden Pfade; welche der Mod nimmt, steht unter
  „Strom“.
- **Name des Servers** (`server` im `angebot`): Durch die erlaubten Zeichen
  und die Bereinigung wie bei einer Adresse wird er genau ein Ordner unter
  `heroicmap/`, darunter nur `welt-<hash>`; mehr kann ein Server so nicht
  anlegen. Ein Server kann einen fremden Namen melden. Den Ordner einer Welt
  teilte er mit dem echten Server aber nur bei gleichem Hash des Seeds, und
  auch dann könnte er nur Kacheln vorlegen, die der echte Server beim
  nächsten Abgleich über ihr ETag ersetzt. Eine Sperre, die einen Namen nur
  einer Adresse zugesteht, gibt es nicht: Sie bräche genau den Fall, dass
  Domain, IP und `0` im selben Ordner landen sollen (mod#113).

## Strom

Den vollen Download eines Satzes holt der Mod als einen Strom statt Kachel
für Kachel (heroic-map-renderer#289). Ein Baum mit 192 796 Kacheln und
4,8 GB brauchte einzeln rund 20 min, und hinter einem Proxy brachen die
vielen kurzen Verbindungen ab. Der Abgleich bleibt einzeln über das ETag,
siehe „Ablauf“. So hat es der Reviewer entschieden, siehe
[0021](entscheidungen/0021-erster-download-als-strom.md). Das Format
beschreibt der Renderer in seiner `docs/benutzung/server.md`, „Download
als Strom“; hier steht, was der Mod tut.

- **Wann:** jede `freigabe` mit `art` `voll` (`Downloads.lade`,
  `Laden.strom`). Antwortet der Kartenserver mit 404, ist es ein Renderer
  vor dem Strom; dann lädt der Mod einzeln wie unter „Ablauf“, mit
  demselben Token.
- **Anfrage:** `GET <url>.zip` mit demselben Header `Authorization:
  Bearer`. Nach einem Abbruch hängt der Mod `?nach=<z>/<x>/<y>` an, den
  letzten abgelegten Pfad aus `strom.txt`.
- **Antwort:**

  | Status | Mod |
  |---|---|
  | 200 | liest den Strom; `X-Heroic-Bytes` ist die erwartete Grösse für den Fortschritt |
  | 404 | einzeln, siehe oben |
  | 401, 403 | abgelehnt |
  | 429 | fragt nach 1,5 s neu an, höchstens dreimal (`Laden.VERSUCHE_429`), denn den Platz eines eben abgebrochenen Stroms gibt der Server erst frei, wenn er den Abbruch merkt; dann Budget erschöpft |
  | anderer | Abbruch mit dem Status |

- **Inhalt:** ein ZIP ohne Kompression, je Datei nur der lokale Kopf,
  ohne zentrales Verzeichnis. Der Mod liest es mit
  `java.util.zip.ZipInputStream`, das die CRC-32 jedes Eintrags prüft.
  Reihenfolge: `map.json`, `manifest`, die Kacheln von grob nach fein,
  zuletzt `ende` mit Grösse 0.
- **Prüfung:** Jeder Verstoss bricht ab, mit dem Grund `STROM`, ausser wo
  anders genannt.

  | Was | Regel |
  |---|---|
  | erster und zweiter Eintrag | `map.json`, dann `manifest`, mit den Grenzen und der Prüfsumme wie unter „Ablauf“ |
  | Name einer Kachel | `z/x/y.webp` aus ganzen Zahlen (`Laden.KACHEL`): nie `..`, nie absolut, kein `\`, kein anderer Typ; doppelt zählt nach den Zahlen |
  | Kachel | steht im Manifest bis zur Stufe des Massstabs, kommt nur einmal, laut Kopf höchstens 4 MiB (`KACHEL`) |
  | Methode | nur ohne Kompression, mit der Grösse im Kopf |
  | Summe | `bytes` plus 10 %, dazu je Zeile des Manifests 1 KiB für Kopf und Name (`Laden.KOPF_MAX`), dazu `map.json` und Manifest |
  | Ende | `ende` als letzter Eintrag, danach kein Byte; ohne `ende` ist der Strom abgebrochen, siehe „Abbruch“ |

  Links kennt ein ZIP ohne zentrales Verzeichnis nicht; der Mod legt jede
  Datei selbst als normale Datei an.
- **Ablegen:** Kachel für Kachel, ganz in den Speicher, ein `write` in die
  feste Zwischendatei `tmp/strom.tmp`, dann verschieben (`ATOMIC_MOVE`),
  ohne fsync; nie liegt eine halbe Kachel da. Danach eine Zeile
  `z/x/y etag` im Index, mit dem ETag aus dem Manifest im Strom. Was mit
  gleichem ETag schon daliegt, liest der Mod und schreibt es nicht neu. Hat
  sich eine Kachel während des Stroms geändert, holt sie der nächste
  Abgleich.
- **Früh sichtbar:** Eine Stufe liegt ganz da, sobald die erste Kachel der
  nächsten kommt, die letzte am Ende des Stroms. Mit der ersten schreibt der
  Mod `satz.json`, wenn noch kein Satz dasteht; ein vollständiger Satz in
  einem anderen Massstab bleibt bis zum Ende. Siehe
  [Vollbildkarte](vollbildkarte.md), „Welcher Satz“ und „Fortschritt“.
- **Unvollständig:** Solange `strom.txt` im Ordner des Massstabs liegt
  (`Laden.imStrom`), ist der Satz nicht vollständig: kein Abgleich, eine
  `freigabe` für einen Abgleich wird ein voller Download
  (`Downloads.vollStattAbgleich`), und der setzt den Strom fort. In
  `strom.txt` steht der letzte abgelegte Pfad; nach einem vollständigen
  Strom geht die Datei.
- **Fortsetzen:** Mit `nach` überspringt der Server alles bis einschliesslich
  dieser Kachel in derselben Reihenfolge, und der Deckel des Tokens zählt
  nur, was geht. Nach einem Abbruch setzt der Mod sofort fort, siehe
  „Abbruch“; ein späterer voller Download ebenso. Weil dann schon ein Index
  liegt, geht dessen `anfrage` ohne `neu`, und das Plugin gibt dasselbe
  Token, solange es gilt. Endet das Spiel hart, steht in `strom.txt` ein
  älterer Pfad oder keiner; dann kommt mehr noch einmal, geschrieben wird
  es nicht.
- **Fristen:** 2 min bis zum Header, danach keine Frist für den ganzen
  Strom; kommt 30 s lang kein Byte, beendet eine Wache die Verbindung.
- **Abbruch** (`Laden.Abbruch`): ein Fehler beim Lesen aus dem Netz, eine
  abgerissene Datei, 30 s ohne Byte, oder ein Körper, der sauber endet,
  aber ohne `ende`. So endet der Server am Deckel des Tokens oder nach einem
  Lesefehler. Dann fragt der Mod sofort mit `nach` weiter (`Laden.strom`),
  solange der letzte Versuch `strom.txt` weiterbrachte, höchstens 100
  Versuche (`Laden.VERSUCHE_STROM`). Bringen zwei Versuche hintereinander
  nichts Neues, hört er auf. Endete der Körper dabei sauber, ist es der
  Deckel; dasselbe Token bringt nichts mehr, und der Mod meldet „Budget
  erschöpft“ (`BUDGET`). Sonst meldet er den Fehler des Netzes (`NETZ`).
  Eine neue `freigabe` fragt er dafür nicht an, wie bei einem verbrauchten
  Token. So hat es der Reviewer entschieden, damit keine Schleife entsteht.
  Ein Fehler der Platte bleibt einer der Platte und bricht sofort ab.
- **Daten vor einem harten Abbruch:** Was vor dem Abbruch der Verbindung
  ankam, aber noch nicht gelesen war, verwirft der `HttpClient` mit dem
  Fehler; es kommt beim Fortsetzen noch einmal. Darum beendet der Server
  den Körper am Deckel sauber.
- **Tests:** `LadenTest` gegen einen Server auf loopback, auch mit einem
  echten Abriss im chunked-Körper; der Gametest `Strom`, siehe
  [Entwicklung](entwicklung.md), „Gametests“.

## Ablauf

Ein Abgleich, und ein voller Download, wenn der Renderer den Strom nicht
kennt: `Laden.lade`, im Thread der Reihe.

1. **Adresse** prüfen, vor jedem Abruf, siehe „Sicherheit“.
2. **`map.json`** holen; daraus `minZoom` und `maxZoom`. Die Stufe des
   Massstabs: 4 px ist `maxZoom`, 2 px eine gröber, 1 px zwei.
3. **Manifest** holen und SHA-256 über das gzip mit `manifest_sha256`
   vergleichen. Passt es nicht, endete inzwischen ein Lauf; der Mod fragt
   einmal neu an und bekommt dasselbe Token mit dem neuen Manifest.
4. **Entpacken** und die Zeilen bis zur Stufe des Massstabs nehmen.
5. **Löschen,** was lokal liegt und nicht mehr im Manifest steht.
6. **Laden,** was fehlt oder ein anderes ETag hat, über 8 Verbindungen mit
   Keep-Alive (`java.net.http.HttpClient`, HTTP/1.1, `Laden.VERBINDUNGEN`).
   Jede holt eine Kachel nach der anderen und wartet je Kachel einen Umlauf
   ab; bei 30 ms Ping deckelten 4 Verbindungen den Download rechnerisch bei
   rund 7,7 MB/s, 8 bei rund dem Doppelten (mod#114). Gespeichert wird das
   ETag aus der Antwort, denn eine Kachel kann neuer sein als das Manifest.
   Scheitert eine Kachel, bricht der Mod die laufenden Abrufe der anderen
   Verbindungen sofort ab.
7. **Der Index** `etags.txt` ist ein Protokoll: je Kachel eine Zeile ohne
   ETag vor dem Schreiben, eine mit danach; die letzte gilt, eine ohne ETag
   heisst neu laden. Endet das Spiel mitten im Download, gilt, was schon
   dasteht. Am Ende schreibt der Mod den Index neu, eine Zeile je Kachel.

**Fristen:** Eine Anfrage hat 2 min für Header und Körper zusammen; danach
bricht der Mod sie ab (`sendAsync`, `cancel`). Der Aufbau der Verbindung hat
10 s. Fehler der Verbindung und abgelaufene Fristen heissen `NETZ`. Für den
Strom siehe „Strom“.

## Reihe

- **Nacheinander:** Downloads laufen in einem Thread (`Reihe`). Eine
  `freigabe` für einen anderen Baum wartet, bis der laufende fertig ist.
- **Je Ordner eines Baums höchstens einer,** wartend oder laufend; der
  Schlüssel ist der Ordner des Baums, also je Welt und Baum. Solange die
  Kartenliste einen Baum löscht, hält sie denselben Schlüssel
  (`Reihe.halte`). Eine weitere `freigabe` für denselben
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
| Antwort | 200 | 401 und 403 heissen abgelehnt, 429 Budget erschöpft, alles andere Abbruch; beim Strom siehe „Strom“ |
| Strom | siehe „Strom“, „Prüfung“ | Abbruch |

`z/x/y` wird ein Dateipfad; deshalb nur ganze Zahlen, auch beim Lesen des
eigenen Index, und doppelt zählt nach den Zahlen, `7/0/0` wie `007/0/0`.
Ein Abbruch meldet dem Spieler den Grund, übersetzt und ohne Pfad;
Einzelheiten stehen im Log. Kam eine Antwort mit einem anderen Status als
200, steht er dabei, etwa „HTTP-Status 502“; so trennt sich ein Fehler
eines Proxys von einem des Netzes (`Laden.Fehler.status`).

## Ablage

- **Ordner:** `heroicmap/<welt>/<baum>/<massstab>/` im Spielordner, darin
  `map.json`, `etags.txt`, `z/x/y.webp` und `tmp/`, solange ein Strom
  nicht fertig ist `strom.txt`. `<welt>` richtet sich
  nach der Wahl „Ablage der Karten“ im Untermenü „Einstellungen …“
  (`Downloads.weltOrdner`),
  gespeichert als `ablage` in `heroicmap.properties`:

  | Wahl | `ablage` | `<welt>` |
  |---|---|---|
  | Hash | `hash` | `welt-<hash>` |
  | IP + Hash, die Vorgabe | `ip` | `<host>/welt-<hash>` |
  | IP:Port + Hash | `ip_port` | `<host>_<port>/welt-<hash>` |

  Mit einer Karte vom Plugin steht `karte-<karte>/` vor `welt-<hash>`,
  siehe unten, etwa `<host>/karte-main/welt-<hash>`.

  Meldet der Server einen Namen, siehe „Kanal“, steht er bei `ip` und
  `ip_port` statt `<host>`; der Port kommt weiter aus der eingetippten
  Adresse. So landet derselbe Server immer im selben Ordner, ob jemand
  `play.example.net`, die IP oder `0` eintippt, und die Kartenliste zeigt
  den Namen. So will es der User (mod#113).
  - **Gedächtnis:** `heroicmap/namen.txt`, je Zeile die eingetippte Adresse,
    klein, und der Name (`Servername`). Ein `angebot` mit Namen trägt ihn
    ein, eines ohne nimmt ihn heraus. Ohne `angebot`, etwa auf einem Server
    ohne Baum zum Download, bleibt der gemerkte Name.
  - **Einmal je Sitzung:** Den Namen liest der Mod beim ersten Betreten
    nach einem Login (`Downloads.weltBetreten` nach `neueSitzung`), nicht
    bei jedem Wechsel der Dimension und nicht beim Respawn. Wegpunkte,
    Ebenen, eigene Karte und ein laufender Download hängen am Ordner. Die
    erste Sitzung mit einem Namen bleibt deshalb im Ordner der Adresse.
  - **Je Welt fest bis zur nächsten Sitzung:** Beim ersten Betreten einer
    Welt in der Sitzung, je Hash, entscheidet `Servername.gilt`, ob der
    Name dort gilt. Ja, wenn es ihren Ordner nach der Adresse nicht gibt
    oder er jetzt umzieht. Nein, wenn ein Download in den alten läuft
    (`Reihe.belegtUnter`), wenn es den neuen schon gibt oder wenn der Umzug
    scheitert, etwa an einem offenen Handle unter Windows. Dann bleibt die
    Welt in dieser Sitzung im Ordner der Adresse, und das nächste Betreten
    nach einem Login versucht es wieder. So liegen Karte, Wegpunkte und
    Ebenen einer Welt nie in zwei Ordnern.
  - **Umzug:** Der Ordner der Welt nach der Adresse zieht mit allem darin
    zum Namen um, als ein Umbenennen (`Servername.zieheUm`,
    `ATOMIC_MOVE`), ohne neuen Download. Ein leerer Ordner der Adresse,
    etwa `heroicmap/0/`, geht mit. Gibt es den neuen schon, etwa weil
    derselbe Server unter zwei Adressen eingetragen ist, bleibt der alte
    in der Kartenliste zum Löschen; zusammengeführt wird nicht.

- **Karte** (heroic-map-renderer#318): Hinter einem Proxy haben alle
  Server dieselbe Adresse, und eine Kopie einer Welt hat denselben Hash.
  Darum nennt das Plugin an der Antwort auf `show` den Namen der Karte,
  `karte`, etwa `main`, siehe
  [Mehrere Server](https://github.com/VonNekyia/heroic-map-renderer-plugin/blob/main/docs/mehrere-server.md)
  im Plugin. Zeigt ein Server die Karte eines anderen, nennt er dessen
  Namen; dann teilen sich beide den Ordner.
  - **Gültig** nur `a–z 0–9 _ -`, höchstens 64 Zeichen
    (`Downloads.karteAus`, `Kartenname.gueltig`); sonst übergeht der Mod
    das Feld mit einer Zeile im Log. Mit `karte-` davor ist kein Name ein
    Gerät von Windows.
  - **Gedächtnis:** `heroicmap/karten.txt`, je Zeile die eingetippte
    Adresse, klein wie in `namen.txt`, der Hash und die zuletzt gemeldete
    Karte (`Kartenname.lies`, `Kartenname.merke`). Sie gilt gleich beim
    Betreten, vor der Antwort auf `show`; so stimmt der Ordner ab der
    zweiten Sitzung von Anfang an, auch für die eigene Karte und einen
    Wegpunkt der ersten Sekunden.
  - **Die erste Antwort der Sitzung** auf `show` gilt (`Kartenname`): mit
    `karte` oder ohne. Weicht sie vom Gedächtnis ab, korrigiert sie es, auch
    zu keiner Karte; spätere Antworten gelten erst nach dem nächsten Login.
    Eine ungültige übergeht der Mod, ohne zu korrigieren. Antwortet der
    Server gar nicht, etwa ohne Plugin, bleibt das Gedächtnis.
  - **Wechsel:** Ändert die Antwort die Karte, entscheidet der Mod für jede
    Welt, die er in der Sitzung betrat, ob sie dort gilt
    (`Downloads.karteGilt`), und liest neu: Minimap, eigene Karte,
    Wegpunkte, Ebenen und ihre Reihe (`HeroicMap.weltOrdnerGewechselt`).
    Eine Welt, die er später betritt, entscheidet beim Betreten, einmal je
    Sitzung, und kommt ins Gedächtnis.
  - **Ob sie gilt:**

    | Ordner ohne Karte | Ordner mit Karte | Ergebnis |
    |---|---|---|
    | fehlt | – | gilt |
    | da | da | gilt, der ohne Karte bleibt in der Kartenliste |
    | da | fehlt, ein Download läuft in den ohne Karte | gilt nicht in dieser Sitzung |
    | da | fehlt | Umzug wie beim Namen, siehe oben; scheitert er, gilt sie nicht |

    Gibt es den Ordner mit Karte schon, gilt er, anders als beim Namen:
    In der ersten Sitzung, ohne Gedächtnis, schreibt der Mod bis zur
    Antwort auf `show` in den Ordner ohne Karte; der bliebe sonst der
    Grund, dort zu bleiben. Zusammengeführt wird nicht.
  - **Was bleibt:** In der ersten Sitzung, und wenn die Antwort das
    Gedächtnis korrigiert, landen die ersten Sekunden noch im falschen
    Ordner, etwa wenn zwei Server mit derselben Welt verschiedene Karten
    haben und der Spieler zwischen ihnen wechselt.
  - **Zwei Server, ein alter Ordner:** Der zuerst betretene nimmt ihn mit,
    der andere beginnt leer. Ein laufender Download lädt weiter in seinen
    Ordner.

  Im Einzelspieler gilt die Wahl nicht, dort ist `<welt>`
  `einzelspieler_<speicherordner>/welt-<hash>`, `<speicherordner>` der
  Name des Ordners der Welt unter `saves/`, bereinigt wie ein Host
  (`Downloads.einzelspieler`). Er liegt so tief wie eine Welt hinter einer
  Adresse, so findet ihn die Kartenliste. Darin liegen die eigene Karte,
  Wegpunkte und Kartenlage, siehe
  [0015](entscheidungen/0015-ordner-fuer-einzelspielerwelten.md). Wird die
  Welt umbenannt, beginnt ein neuer Ordner; wird sie gelöscht, bleibt er,
  bis der Spieler ihn in der Kartenliste löscht.

  `<hash>` ist der Hash des Seeds, den der Server dem Client mit jeder Welt
  schickt (`BiomeManager.biomeZoomSeed`, per Access Widener), 16 Stellen
  hex. Den Seed selbst und den Namen der Welt kennt der Client nicht.
  Ohne Port gilt 25565. Die Vorgabe IP + Hash hat der User gewählt: Ein
  Netz hinter einem Proxy hat für alle Server dieselbe Adresse; eine Welt,
  etwa ein Mining-Realm, behält ihre Karte, und eine neue Welt überschreibt
  sie nicht. Eine andere Wahl gilt sofort für Downloads und die
  Vollbildkarte, für die Wegpunkte beim Schliessen des Untermenüs
  (`Anzeige.removed`); sie zieht
  nichts um, die Karten unter der alten Wahl stehen weiter in der
  Kartenliste. `<host>` ist klein, andere Zeichen als Buchstaben, Ziffern, Punkt
  und Strich werden `_`. Ein Baum heisst nur `[a-z0-9_-]`, höchstens 64
  Zeichen. Namen, die Windows für Geräte hält (`con`, `nul`, `com1` …),
  lehnt der Mod als Baum ab und stellt dem Server ein `_` voran.
- **Server beim Login:** Welcher Server es ist, liest der Mod aus der
  Verbindung des Levels (`Downloads.server`), nicht über
  `Minecraft.getCurrentServer`. Das geht über den Spieler, und beim Login
  entsteht das Level vor ihm: `handleLogin` ruft `setLevel`, worin der Mod
  die Welt merkt, bevor es den `LocalPlayer` anlegt, belegt per javap. Über
  den Spieler war der Server dort null; der Mod merkte die erste Welt
  nicht und meldete „Betritt erst …“, bis der Spieler die Dimension
  wechselte. Der Gametest `Anmeldung` prüft diese Reihenfolge.
- **Hash je Dimension:** Paper hat einen Seed je Welt, belegt per javap an
  Paper 26.3 (`ServerLevel.getSeed` aus den `worldGenSettings` der Welt).
  Ein Baum liegt deshalb unter dem Hash seiner Dimension aus dem
  `angebot`, nicht unter dem der Welt, in der der Spieler gerade steht.
  Den Hash einer Dimension sieht der Client erst, wenn der Spieler sie
  betritt (`ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE`); der Mod merkt
  ihn in `heroicmap/welten.properties` (`Welten`, `Downloads.weltBetreten`).
- **Backends hinter einem Proxy** haben dieselbe Adresse. Der Mod merkt
  die Hashes deshalb je Server mit Port und je Sitzung: Was der Spieler
  zwischen zwei Logins betritt, gehört zu einem Backend, eine Gruppe. Das
  erste Level nach einem Login wählt die gespeicherte Gruppe, die genau
  dieses Paar aus Dimension und Hash kennt, sonst entsteht eine neue. Eine
  Dimension, die der Spieler auf diesem Backend nie betrat, ist unbekannt,
  auch wenn ein anderes Backend sie kennt. Zwei Backends mit demselben
  Paar beim Login, also derselben Welt am Ort des Logins, teilen eine
  Gruppe; eine weitere Welt mit gleichem Schlüssel und anderem Seed behält
  dann den Hash des zuletzt besuchten Backends. Nether und End der
  Hauptwelt betrifft das nicht. Am Login sind solche Backends nicht zu
  unterscheiden. Je Server behält der Mod die 16 zuletzt benutzten
  Gruppen (`Welten.GRUPPEN`); sonst häuften Weltresets und Server, die den
  Hash würfeln, immer mehr an. Den Wechsel des Backends meldet
  Fabric nicht als `DISCONNECT`, sondern als neuen Login
  (`ClientPlayConnectionEvents.INIT`); dann vergisst der Mod Angebot,
  Bestätigungen und wartende `freigabe`n des alten Backends
  (`Downloads.neueSitzung`). Ein laufender Download lädt weiter in seinen
  Ordner; scheitert er an der Prüfsumme, fragt der Mod nur neu an, wenn er
  aus derselben Sitzung stammt (`Downloads.neuFragen`). So hat es der User entschieden: nur im Mod, ohne Änderung am
  Plugin.
- **Solange der Hash unbekannt ist:**
  - `/hmap laden` und der Abgleich sagen „Betritt zuerst `<dimension>`“
    und fragen nichts an;
  - eine `freigabe`, etwa der Abgleich beim Beitritt, wartet und läuft,
    sobald der Spieler die Dimension auf diesem Backend betritt; ein neuer
    Login oder das Trennen verwirft sie. Das Plugin zählt den Abgleich des
    Tages schon beim Ausstellen; eine `freigabe` für eine Dimension, die
    der Spieler an dem Tag nicht mehr betritt, verbraucht ihn ohne Wirkung.
  - Ein Token hat eine Frist, `ablauf`, 24 h beim Plugin; der Mod prüft sie
    nicht. Läuft eine wartende `freigabe` erst danach, lehnt der
    Kartenserver das Token ab, und der Mod meldet „abgelehnt“.
- **Gleiche Hashes:** Bei der Wahl Hash teilen sich zwei Server mit
  demselben Seed einen Ordner, ebenso Server, die statt des Hashes einen
  festen Wert wie 0 schicken; bei IP + Hash ebenso zwei Backends hinter
  derselben Adresse mit demselben Seed. Der Download des einen löscht dann, was nicht
  in seinem Manifest steht, also die Kacheln des anderen. Würfelt ein
  Server den Wert bei jedem Beitritt, entsteht jedes Mal ein neuer Ordner.
  Beides zeigt die Kartenliste; dort lässt sich aufräumen.
- **Schreiben** über eine Zwischendatei in `tmp/`, dann verschieben; nie
  liegt eine halbe Kachel da. `tmp/` leert der Mod zu Beginn jedes Downloads.
- **`satz.json`** je Baum gehört zur [Vollbildkarte](vollbildkarte.md),
  „Welcher Satz“.
- **Umzug aus der alten Ablage:** Vor dem Hash lag ein Baum unter
  `heroicmap/<adresse>/<baum>/`, `<adresse>` die Adresse aus der
  Serverliste. Nennt ein `angebot` oder eine `freigabe` den Baum und ist
  der Hash seiner Dimension bekannt, zieht der Mod ihn dorthin, wenn es ihn
  dort noch nicht gibt (`Downloads.zieheUm`, `Laden.zieheBaumUm`); sonst
  beim Betreten der Dimension. Ein übriger Ordner `overlay/` zieht nicht
  mit, er geht vorher. Ein Baum, den kein Server mehr anbietet, bleibt, wo
  er ist: Die Vollbildkarte findet ihn nicht mehr, die Kartenliste zeigt
  ihn zum Löschen.
- **`overlay/`** je Baum hielt die frühere Live-Ebene. Der Mod löscht den
  Ordner beim Start in jedem Baum (`Laden.loescheOverlays`), in einem
  eigenen Thread; was sich nicht löschen lässt, geht beim nächsten Start.
  Siehe [0002](entscheidungen/0002-vollbildkarte-nur-vom-server.md).
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

## Kartenliste

Der Knopf „Kartenliste …“ im Menü hinter `/hmap` zeigt alle Karten auf der
Platte (`Kartenliste`), auch die anderer Server und Welten:

- **Je Baum** der Pfad unter `heroicmap/`, darunter Name, Dimension und
  Massstab aus `satz.json`, oder „unvollständig“, und die Grösse.
- **Oben** die Summe aller Dateien unter `heroicmap/`, immer in GB, so will
  es der User, und die Zahl der Karten.
- **Ein Baum** ist ein Ordner mit `massstab.txt` oder `satz.json`, bis zu
  vier Ebenen unter `heroicmap/`, in jeder Ablage oben, mit Karte und in
  der alten (`Laden.bestand`).
- **Zählen** ist ein Durchlauf über `heroicmap/` in einem eigenen Thread;
  bis er fertig ist, steht dort „Zähle …“. Ein Satz mit 450 000 Kacheln
  sind ebenso viele Dateien. Was sich nicht lesen lässt, fällt weg, die
  übrigen Bäume bleiben. Schliesst die Liste, auch für den Dialog, endet
  der Durchlauf; zurück in der Liste beginnt er neu, solange nichts
  gezählt ist.
- **Verbindungen:** Einem Symlink oder einer Junction unter `heroicmap/`
  folgen Zählen und Löschen nicht; Löschen entfernt nur die Verbindung,
  nie, worauf sie zeigt. Ist `heroicmap/` selbst eine, etwa auf ein anderes
  Laufwerk verlegt, zählt die Liste über ihr Ziel und nennt die Pfade
  unter `heroicmap/`. Eine Junction ist für das JDK unter Windows ein Ordner und kein
  Link; der Mod erkennt sie daran, dass ihr echter Pfad nicht unter
  `heroicmap/` liegt (`Laden.verbindung`).
- **Löschen** fragt erst im Dialog nach. Nach dem Ja hält die Liste den
  Schlüssel des Baums in der Reihe, siehe „Reihe“; wartet oder läuft dort
  schon ein Download, löscht sie nichts. Gelöscht wird der Ordner des Baums
  samt Inhalt in einem eigenen Thread (`Laden.loesche`), danach gibt sie
  den Schlüssel frei und zählt neu. Eine selbst gezeichnete Karte löscht
  der Worker, der in sie zeichnet, siehe [Selbst gezeichnete Karte](selbst.md), „Wahl“.
  Scheitert das Löschen, nennt die Liste den Fehler; Einzelheiten stehen
  im Log.
- **Mausrad** blättert, wenn nicht alle Karten auf den Schirm passen.

## Was bleibt eine Näherung

- **Auflösen und Laden** sind zwei Schritte: Prüfung und Verbindung lösen
  den Namen getrennt auf. Beide gehen durch denselben Cache der JVM; läuft
  er genau dazwischen ab, kann die Verbindung eine andere Adresse bekommen
  als die geprüfte.
- **Ein naher Spielserver** darf sich selbst als Ziel nennen, also jeden
  Port auf seiner Adresse.
- **Plattenplatz** prüft der Mod nicht; der Dialog nennt nur die Grösse.
