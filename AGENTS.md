# AGENTS.md

Hier gelten die Regeln aus
[`AGENTS.md` von heroic-map-renderer](https://github.com/VonNekyia/heroic-map-renderer/blob/master/AGENTS.md),
samt Skills und Gliederung von `docs/`. Diese Datei ergänzt nur, was für
den Mod anders ist.

## Rolle und Ort

| Rolle | Aufgabe |
|---|---|
| Mod-Programmierer | der Mod in diesem Repository |

Maintainer und Reviewer sind dieselben wie im Hauptrepository.

## Schnittstellen

- **Projektion:** Die Minimap rechnet wie die Karte `top-north` und prüft
  das an
  [`renderer/tests/fixtures/projektion.json`](https://github.com/VonNekyia/heroic-map-renderer/blob/master/renderer/tests/fixtures/projektion.json).
- **Download:** Die Vollbildkarte kommt vom Plugin, siehe
  [heroic-map-renderer#154](https://github.com/VonNekyia/heroic-map-renderer/issues/154).
  Wer daran etwas ändert, spricht es vorher mit dem Plugin-Programmierer ab.

## Doku

Das Wissen über den Mod steht in `docs/` dieses Repositorys. Im
Hauptrepository steht nur ein kurzer Verweis hierher.
