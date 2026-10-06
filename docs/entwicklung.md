---
title: Bauen und testen
description: Versionen, Gradle, Tests, CI und die Prüfung der Doku.
code:
  - build.gradle.kts
  - src/gametest/resources/fabric.mod.json
  - gradle/libs.versions.toml
  - gradle/wrapper/gradle-wrapper.properties
  - .github/workflows/ci.yml
---

# Bauen und testen

Der Mod baut mit Gradle über den Wrapper und Fabric Loom. Er braucht
Java 25; Gradle holt es über die Toolchain in `build.gradle.kts`.

## Versionen

- **Minecraft, Fabric Loader, Fabric API, Loom und JUnit** stehen in
  `gradle/libs.versions.toml`. Minecraft folgt dem Server.
- **Gradle** steht in `gradle/wrapper/gradle-wrapper.properties`.

## Bauen

```bash
./gradlew build
```

Das baut das Jar nach `build/libs/` und lässt die Tests laufen.

## Tests

| Test | prüft |
|---|---|
| `ProjektionTest` | die Projektion gegen `projektion.json` des Renderers, braucht Netz, siehe [Projektion](projektion.md) |
| `LadenTest` | den Download gegen einen kleinen Server auf loopback: Fortsetzen, geänderte und gelöschte Kacheln, Stufen, Prüfsumme, Token, Heimnetz, alle harten Grenzen, Fristen, keine Weiterleitung, den Index während des Downloads, siehe [Download](download.md) |
| `FreigabeTest` | die `freigabe` lesen, reservierte Namen, und wann der Mod fragt, siehe [Download](download.md), „Zustimmung und Grösse“ |
| `ReiheTest` | Downloads nacheinander, je Baum höchstens einer, auch nach einem `Error`, siehe [Download](download.md), „Reihe“ |
| `AdresseTest` | die Prüfung der Adresse, siehe [Download](download.md), „Sicherheit“ |
| `KartenblickTest` | wie die Vollbildkarte Kacheln auf den Schirm legt, Zoom über Stufen und Lupe, Schieben, siehe [Vollbildkarte](vollbildkarte.md) |
| `KachelnTest` | WebP mit TwelveMonkeys lesen, samt Alpha, die Grösse aus dem Kopf vor dem Dekodieren, siehe [Vollbildkarte](vollbildkarte.md), „Kacheln“ |
| `PyramideTest` | Verkleinern wie die Pyramide des Renderers, siehe [Live-Ebene](live.md), „Raster“ |
| `EbeneTest` | die Bilder der Live-Ebene schreiben, lesen, beim Abgleich räumen, beim Wechsel des Massstabs anpassen und in die Kacheln legen, siehe [Live-Ebene](live.md) |
| `SatzTest` | den Satz zur Dimension finden, Grenzen für `map.json`, siehe [Vollbildkarte](vollbildkarte.md), „Welcher Satz“ |
| `MinimapTest` | den Bereich der Minimap, siehe [Minimap](minimap.md), „Neu zeichnen“ |
| `LichtTest` | die Lightmap gegen die Werte aus der Doku des Renderers, siehe [Minimap](minimap.md), „Licht“ |

## Gametests

Die Client-Gametests starten das Spiel mit einem Fenster und laufen nicht
in der CI:

```bash
./gradlew runClientGameTest
```

| Gametest | tut |
|---|---|
| `Bilder` | baut eine Szene und nimmt die Minimap auf, danach die Vollbildkarte aus einem Testsatz und die Live-Ebene darüber; mit `-Pbilder=<ordner>` landen die Bilder dort, siehe [Minimap](minimap.md), „Bilder“, [Vollbildkarte](vollbildkarte.md), „Bild“, und [Live-Ebene](live.md), „Bild“ |
| `Messung` | nur mit `-Pmessung=<datei>`: Zeit je Chunk und Frametime mit und ohne Minimap, siehe [Minimap](minimap.md), „Kosten“ |
| `Uebernahme` | nur mit `-Puebernahme=<datei>`: was eine Kachel der Vollbildkarte den Render-Thread kostet, siehe [Vollbildkarte](vollbildkarte.md), „Kacheln“ |

## CI

`.github/workflows/ci.yml` hat zwei Jobs:

- **Gradle:** `./gradlew build` unter Ubuntu mit Java 25.
  `gradle/actions/setup-gradle` prüft dabei auch, dass `gradle-wrapper.jar`
  ein Wrapper von Gradle ist.
- **Doku:** `pruefe-doku.sh` vom Branch `master` des Hauptrepositorys,
  dieselbe Prüfung wie dort. Der Schritt läuft mit `shell: bash`, also mit
  `pipefail`: Scheitert der Download, wird der Job rot.

## Doku prüfen

Lokal aus der Wurzel des Repositorys:

```bash
curl -fsSL https://raw.githubusercontent.com/VonNekyia/heroic-map-renderer/master/.github/pruefe-doku.sh | bash
```

Neue Seiten erst nach `git add`, die Prüfung sieht nur, was Git verfolgt.
