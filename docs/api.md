---
title: API für Client-Mods
description: Wie ein anderer Fabric-Mod beim Spieler eigene Ebenen anlegt, mit Nadeln, Kartenschrift, Flächen, Kreisen und Linien im Format der Ebenen vom Server; Einbinden über den Entrypoint heroicmap und Modrinth Maven, Kennungen, Grenzen, Version und was v1 nicht kann.
code:
  - src/main/java/com/nekyia/heroicmap/api/HeroicMapClientApi.java
  - src/main/java/com/nekyia/heroicmap/Ebenen.java
  - src/main/java/com/nekyia/heroicmap/HeroicMap.java
  - src/gametest/java/com/nekyia/heroicmap/ProbeEbene.java
  - src/gametest/java/com/nekyia/heroicmap/Api.java
---

# API für Client-Mods

Ein anderer Fabric-Mod legt beim Spieler eigene Ebenen an, etwa
`meinmod:staedte`, und füllt sie mit Nadeln, Kartenschrift, Flächen,
Kreisen und Linien. Der Mod zeichnet sie wie die Ebenen vom Server, auf
Minimap und Vollbildkarte, mit Schalter in der Liste. So will es der User
(mod#35). Die Namen der Schnittstelle sind englisch, weil wer gegen sie
entwickelt Englisch liest, wie bei der API des Plugins; entschieden in
[0018](entscheidungen/0018-api-fuer-client-mods.md).

## Schnittstelle

Die Klasse `com.nekyia.heroicmap.api.HeroicMapClientApi`:

| Name | tut |
|---|---|
| `VERSION` | die Version der API, heute 1; sie steigt mit jeder Änderung |
| `boolean put(String entry, String objects)` | legt die Ebene an oder ersetzt sie ganz; false, wenn `entry` oder `objects` nicht lesbar sind oder die Kennung nicht taugt |
| `void remove(String id)` | nimmt die Ebene weg; eine Ebene vom Server mit derselben Kennung bleibt |
| `Listener` mit `void ready()` | der Entrypoint `heroicmap`, siehe „Einbinden“ |

- **Format:** dasselbe wie vom Server, damit es nur eines gibt, siehe
  [Ebenen](https://github.com/VonNekyia/heroic-map-renderer/blob/master/docs/benutzung/ebenen.md)
  im Renderer. `entry` ist ein Eintrag der Liste `ebenen`: `id`,
  `name.de`, `name.en`, `visible`, `order`; `version` und `secret` gelten
  hier nicht. `objects` ist das Feld `objects` eines Teils.
- **Gelesen** mit demselben Code wie die Ebenen vom Server (`Ebenen.liste`,
  `Ebenen.formen`, `Ebenen.nadeln`), auf dem Thread des Aufrufs. Übernommen
  wird auf dem Render-Thread (`Minecraft.execute`), dem `Ebenen` gehört;
  `put` und `remove` gehen also von jedem Thread.
- **Version** der Daten vergibt die API selbst, `mod-1`, `mod-2` und so
  weiter; sie ist nie die einer Ebene vom Server.
- **Wahl** An oder Aus speichert der Mod je Welt unter der Kennung, wie
  bei den Ebenen vom Server, siehe [Ebenen](ebenen.md), „Umschalten“.

## Einbinden

In `fabric.mod.json` des anderen Mods:

```json
"entrypoints": {
  "heroicmap": ["com.example.meinmod.MeineEbenen"]
},
"suggests": {
  "heroicmap": "*"
}
```

```java
public final class MeineEbenen implements HeroicMapClientApi.Listener {
    @Override
    public void ready() {
        HeroicMapClientApi.put("""
                {"id":"meinmod:staedte","name":{"de":"Städte","en":"Towns"},"order":5}""", """
                [{"type":"pin","id":"hafen","at":[120,-40],"name":"Hafen","color":"#3070E0"},
                 {"type":"circle","center":[120,-40],"radius":24,"fill":"#3070E040"}]""");
    }
}
```

- **Entrypoint `heroicmap`:** Heroic Map ruft `ready()` einmal, sobald der
  Client läuft (`ClientLifecycleEvents.CLIENT_STARTED`). Die JVM lädt die
  Klassen des anderen Mods, die die API nutzen, erst dann; ohne Heroic Map
  ruft niemand den Entrypoint, und es braucht keine eigene Prüfung. Danach
  darf er `put` und `remove` jederzeit rufen.
- **Kompilieren** gegen das Jar aus dem Maven von Modrinth, nur zum
  Kompilieren; ein eigenes Maven oder JitPack braucht es nicht:

  ```kotlin
  repositories {
      exclusiveContent {
          forRepository { maven("https://api.modrinth.com/maven") }
          filter { includeGroup("maven.modrinth") }
      }
  }
  dependencies {
      compileOnly("maven.modrinth:heroic-map:<version>")
  }
  ```

- **Version prüfen:** `HeroicMapClientApi.VERSION` nennt die Version zur
  Laufzeit; was eine neuere Version mitbringt, steht hier.

## Kennungen

- **Mit Namensraum:** `namensraum:pfad` wie ein `Identifier` des Spiels,
  am besten die id des eigenen Mods vorn (`Ebenen.kennungEinesMods`).
  `heroicmap:` gehört diesem Mod, `put` lehnt es ab.
- **Gleiche Kennung wie vom Server:** Es gilt die vom Server. Die Ebene
  des Mods ist nicht zu sehen, solange der Server sie hat, und das Log
  nennt sie einmal; geht die vom Server weg, erscheint sie mit ihren Daten
  wieder. So kann kein Mod eine Ebene des Servers überdecken.
- **Beim Trennen** bleiben die Ebenen der Mods, denn sie hängen am Client;
  nur die vom Server sind weg (`Ebenen.leeren`).

## Grenzen

Dieselben wie für die Ebenen vom Server, siehe [Ebenen](ebenen.md),
„Grenzen“: höchstens 1 000 Nadeln, 10 000 Objekte und 200 000 Punkte je
Ebene, 500 000 Punkte über alle Ebenen und 64 Ebenen zusammen mit denen
vom Server. Eine zu grosse Ebene bleibt, wie sie war; über 64 fehlen die
Ebenen der Mods, die das Log einmal nennt.

## Was v1 nicht kann

- **Banner und Symbole von Nadeln:** Ihre Bilder kommen heute nur vom
  Server per HTTP. `put` übergeht Banner und das Feld `symbol`, sonst
  fragte der Mod den Server vergeblich. Nadeln stehen als Schild in ihrer
  Farbe ohne Symbol. Bilder aus den Ressourcen des anderen Mods kämen mit
  v2, wenn jemand sie braucht.

## Getestet

- **Gametest `Api`,** der erste der Reihe: `ProbeEbene` legt als
  Entrypoint `heroicmap` beim Start die Ebene `heroicmap-tests:probe` an,
  mit Nadel, Kreis, Linie, Schrift und einem Banner. Sie steht in der Liste
  der Vollbildkarte, mit einer Nadel und drei Formen, ohne Banner; sie
  übersteht eine Liste vom Server und das Trennen; eine Ebene vom Server
  mit derselben Kennung verdeckt sie, bis sie wieder geht; `remove` nimmt
  sie weg. Am Ende ist sie weg, die Bilder der Tests danach zeigen sie
  nicht.
- **Unit `EbenenTest.kennungEinesMods`:** mit Namensraum, nicht
  `heroicmap:`, keine Grossbuchstaben.
