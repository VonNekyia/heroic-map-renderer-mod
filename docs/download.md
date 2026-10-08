---
title: Download
description: Wie der Mod die Karte vom Plugin lädt, mit dem Kanal heroicmap:karte, den Befehlen, Zustimmung und Grösse, der Prüfung der Adresse, Manifest und Prüfsumme, Fristen, den harten Grenzen, der Reihe der Downloads, der Ablage je Welt nach dem Hash des Seeds, Baum und Massstab, der Kartenliste mit Löschen, Fortsetzen und Abgleich, und was der Offline-Modus heisst.
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
  - src/main/java/com/nekyia/heroicmap/Kartenliste.java
  - src/test/java/com/nekyia/heroicmap/AblageTest.java
  - src/main/resources/heroicmap.accesswidener
  - src/main/java/com/nekyia/heroicmap/Welten.java
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
  Uhrzeit. `spieler` nennt die Mitspieler, siehe [Minimap](minimap.md),
  „Mitspieler“. Nachrichten mit einem anderen `v` als 1 oder über 64 KiB
  verwirft er.
- **Senden:** `anfrage` und `show` nur, wenn `ClientPlayNetworking.canSend`
  wahr ist, also wenn das Plugin den Kanal angemeldet hat. Zu `show` siehe
  [Minimap](minimap.md), „Mitspieler“.
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
| Antwort | 200 | 401 und 403 heissen abgelehnt, 429 Budget erschöpft, alles andere Abbruch |

`z/x/y` wird ein Dateipfad; deshalb nur ganze Zahlen, auch beim Lesen des
eigenen Index, und doppelt zählt nach den Zahlen, `7/0/0` wie `007/0/0`.
Ein Abbruch meldet dem Spieler den Grund, übersetzt und ohne Pfad;
Einzelheiten stehen im Log.

## Ablage

- **Ordner:** `heroicmap/<welt>/<baum>/<massstab>/` im Spielordner, darin
  `map.json`, `etags.txt`, `z/x/y.webp` und `tmp/`. `<welt>` richtet sich
  nach der Wahl „Ablage der Karten“ im Menü (`Downloads.weltOrdner`),
  gespeichert als `ablage` in `heroicmap.properties`:

  | Wahl | `ablage` | `<welt>` |
  |---|---|---|
  | Hash | `hash` | `welt-<hash>` |
  | IP + Hash, die Vorgabe | `ip` | `<host>/welt-<hash>` |
  | IP:Port + Hash | `ip_port` | `<host>_<port>/welt-<hash>` |

  `<hash>` ist der Hash des Seeds, den der Server dem Client mit jeder Welt
  schickt (`BiomeManager.biomeZoomSeed`, per Access Widener), 16 Stellen
  hex. Den Seed selbst und den Namen der Welt kennt der Client nicht.
  Ohne Port gilt 25565. Die Vorgabe IP + Hash hat der User gewählt: Ein
  Netz hinter einem Proxy hat für alle Server dieselbe Adresse; eine Welt,
  etwa ein Mining-Realm, behält ihre Karte, und eine neue Welt überschreibt
  sie nicht. Eine andere Wahl gilt sofort für Downloads und die
  Vollbildkarte, für die Wegpunkte beim Schliessen des Menüs; sie zieht
  nichts um, die Karten unter der alten Wahl stehen weiter in der
  Kartenliste. `<host>` ist klein, andere Zeichen als Buchstaben, Ziffern, Punkt
  und Strich werden `_`. Ein Baum heisst nur `[a-z0-9_-]`, höchstens 64
  Zeichen. Namen, die Windows für Geräte hält (`con`, `nul`, `com1` …),
  lehnt der Mod als Baum ab und stellt dem Server ein `_` voran.
- **Hash je Dimension:** Paper hat einen Seed je Welt, belegt per javap an
  Paper 26.3 (`ServerLevel.getSeed` aus den `worldGenSettings` der Welt).
  Ein Baum liegt deshalb unter dem Hash seiner Dimension aus dem
  `angebot`, nicht unter dem der Welt, in der der Spieler gerade steht.
  Den Hash einer Dimension sieht der Client erst, wenn der Spieler sie
  betritt (`ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE`); der Mod merkt
  ihn je Server mit Port und Dimension in `heroicmap/welten.properties`
  (`Welten`, `Downloads.weltBetreten`). Solange er unbekannt ist:
  - `/hmap laden` und der Abgleich sagen „Betritt zuerst `<dimension>`“
    und fragen nichts an;
  - eine `freigabe`, etwa der Abgleich beim Beitritt, wartet und läuft,
    sobald der Spieler die Dimension betritt; beim Trennen fällt sie weg.
- **Gleiche Hashes:** Bei der Wahl Hash teilen sich zwei Server mit
  demselben Seed einen Ordner, ebenso Server, die statt des Hashes einen
  festen Wert wie 0 schicken. Der Download des einen löscht dann, was nicht
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
  drei Ebenen unter `heroicmap/`, in jeder Ablage oben und in der alten
  (`Laden.bestand`).
- **Zählen** ist ein Durchlauf über `heroicmap/` in einem eigenen Thread;
  bis er fertig ist, steht dort „Zähle …“. Ein Satz mit 450 000 Kacheln
  sind ebenso viele Dateien. Was sich nicht lesen lässt, fällt weg, die
  übrigen Bäume bleiben. Schliesst die Liste, auch für den Dialog, endet
  der Durchlauf; zurück in der Liste beginnt er neu, solange nichts
  gezählt ist.
- **Verbindungen:** Einem Symlink oder einer Junction folgen Zählen und
  Löschen nicht; Löschen entfernt nur die Verbindung, nie, worauf sie
  zeigt. Eine Junction ist für das JDK unter Windows ein Ordner und kein
  Link; der Mod erkennt sie daran, dass ihr echter Pfad nicht unter
  `heroicmap/` liegt (`Laden.verbindung`).
- **Löschen** fragt erst im Dialog nach. Nach dem Ja hält die Liste den
  Schlüssel des Baums in der Reihe, siehe „Reihe“; wartet oder läuft dort
  schon ein Download, löscht sie nichts. Gelöscht wird der Ordner des Baums
  samt Inhalt in einem eigenen Thread (`Laden.loesche`), danach gibt sie
  den Schlüssel frei und zählt neu. Eine selbst gezeichnete Karte löscht
  der Worker, der in sie zeichnet, siehe [Selbst gezeichnete Karte](selbst.md), „Wahl“.
- **Mausrad** blättert, wenn nicht alle Karten auf den Schirm passen.

## Was bleibt eine Näherung

- **Auflösen und Laden** sind zwei Schritte: Prüfung und Verbindung lösen
  den Namen getrennt auf. Beide gehen durch denselben Cache der JVM; läuft
  er genau dazwischen ab, kann die Verbindung eine andere Adresse bekommen
  als die geprüfte.
- **Ein naher Spielserver** darf sich selbst als Ziel nennen, also jeden
  Port auf seiner Adresse.
- **Plattenplatz** prüft der Mod nicht; der Dialog nennt nur die Grösse.
