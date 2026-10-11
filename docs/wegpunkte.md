---
title: Wegpunkte
description: Wegpunkte auf der Vollbildkarte setzen und löschen, eigene Linien und Regionen mit eigenen Ecken, alte Rechtecke, Marken für Wegpunkte, Spieler und Mitspieler am Rand, Klick zum Zentrieren, Doppelklick zum Anheften an die Minimap, auch für Regionen, Kreise, Nadeln und Banner vom Server, höchstens 64 angeheftete Regionen und 64 Nadeln, der Strahl über angehefteten Wegpunkten und der Schleier am Rand angehefteter Regionen in der Welt, Grösse der Köpfe, Ablage in wegpunkte.json je Welt und was fehlt.
code:
  - src/main/java/com/nekyia/heroicmap/Wegpunkte.java
  - src/main/java/com/nekyia/heroicmap/Karte.java
  - src/main/java/com/nekyia/heroicmap/Minimap.java
  - src/main/java/com/nekyia/heroicmap/Mitspieler.java
  - src/main/java/com/nekyia/heroicmap/Kanal.java
  - src/main/java/com/nekyia/heroicmap/Strahlen.java
  - src/main/java/com/nekyia/heroicmap/Schleier.java
  - src/test/java/com/nekyia/heroicmap/SchleierTest.java
  - src/test/java/com/nekyia/heroicmap/StrahlenTest.java
  - src/gametest/java/com/nekyia/heroicmap/Messung.java
  - src/main/java/com/nekyia/heroicmap/Tafeln.java
  - src/test/java/com/nekyia/heroicmap/WegpunkteTest.java
  - src/test/java/com/nekyia/heroicmap/MinimapTest.java
  - src/main/java/com/nekyia/heroicmap/Kartenblick.java
  - src/test/java/com/nekyia/heroicmap/KartenblickTest.java
  - src/gametest/java/com/nekyia/heroicmap/Bedienung.java
---

# Wegpunkte

Wegpunkte setzt der Spieler auf der [Vollbildkarte](vollbildkarte.md). Dort
stehen sie als Raute in ihrer Farbe, wie Mitspieler und der eigene Spieler;
was ausserhalb des Schirms liegt, steht am Rand in seiner Richtung. Ein
Klick legt eine Marke in die Mitte, ein Doppelklick heftet einen Wegpunkt
oder Mitspieler an die [Minimap](minimap.md). So hat es der User am 08.10.
gewünscht. Ebenso heftet ein Doppelklick eine eigene Form oder eine
Fläche oder einen Kreis vom Server an, siehe „Anheften“ (mod#36). Aus
Wegpunkten baut der Spieler eigene Linien und Regionen, siehe „Eigene
Formen“ (mod#79, mod#112).

## Bedienung

| Eingabe auf der Vollbildkarte | tut |
|---|---|
| Rechtsklick, „Wegpunkt setzen“ | der Eintrag unter „Hierher teleportieren“; setzt einen Wegpunkt auf den Block unter der Maus, in der ersten der 8 Farben (`Wegpunkte.FARBEN`), die in der Dimension noch frei ist, sind alle vergeben, reihum. Steht dort schon einer, bleibt es einer. Den Eintrag gibt es auch ohne Recht zum Teleportieren und unter einer Decke |
| Rechtsklick auf einen Wegpunkt | das Menü für seinen Block: „Hierher teleportieren“, wenn erlaubt, und „Wegpunkt löschen“ |
| Klick auf eine Marke | legt sie in die Mitte, sobald kein zweiter Klick mehr folgen kann, 250 ms nach dem Loslassen: einen Wegpunkt, einen Mitspieler oder den eigenen Spieler. So kommt man vom Wegpunkt zum eigenen Spieler zurück. Wer auf einer Marke zu ziehen beginnt und sich weiter als 3 Einheiten vom Druckpunkt entfernt (`Karte.ZUG`), zieht nur die Karte |
| Doppelklick auf einen Wegpunkt oder Mitspieler | heftet ihn an die Minimap oder löst ihn wieder; die Karte bewegt sich dabei nicht, siehe „Doppelklick“ unten |
| Linke Taste 0,5 s still auf einem Wegpunkt halten | er hängt an der Maus, die Karte zieht nicht mit; beim Loslassen liegt er auf dem Block darunter, mit Farbe und Anheften (`Wegpunkte.verschiebe`). Liegt dort schon einer, bleibt er, wo er war; `Esc` bricht ab. Still heisst: nie weiter als 3 Einheiten vom Druckpunkt weg (`Karte.ZUG`); das Zittern der Hand hin und her zählt nicht, nur der Abstand. Bis 0.2.32 zählte der ganze Weg der Maus: Mit 1 bis 2 Pixeln Zittern fügte ein Linksklick in einer Form im Bau oft nichts an, und Halten ging kaum (mod#112). Unter 0,5 s ist es ein Klick oder ein Zug wie sonst (`Karte.HALTEN_MS`, mod#75). Bis 0.2.36 waren es 2 s, dem User zu lang; 0,5 s hat der Reviewer entschieden (mod#112). Ein Klick dauert kürzer. Nur Wegpunkte, nicht die Rauten eigener Regionen |
| Doppelklick in eine eigene Region, auf eine eigene Linie, auf die Raute eines alten Rechtecks, auf eine Fläche, einen Kreis, eine Nadel oder ein Banner vom Server | heftet sie an die Minimap oder löst sie wieder, siehe „Anheften“ |
| Rechtsklick, „Linie/Region von hier“ | beginnt eine eigene Form mit einer Ecke auf dem Block unter der Maus, auf einem Wegpunkt auf dessen Block, siehe „Eigene Formen“ |
| Linksklick auf die Karte, solange eine Form im Bau ist | setzt eine Ecke, auf einem Wegpunkt auf dessen Block, statt ihn in die Mitte zu legen, sonst auf den Block darunter; auf die erste Ecke schliesst er bei drei und mehr die Region. Ziehen verschiebt die Karte wie sonst |
| Rechtsklick, „Eckpunkt hinzufügen“ | im Bau: eine Ecke hier, wie ein Linksklick; auf einer Ecke einer fertigen Form: neue Ecken zwischen ihr und der nächsten |
| Rechtsklick auf eine Ecke, „Eckpunkt löschen“ | nimmt sie aus der Form; aus einer Region mit drei Ecken wird eine Linie, eine Linie mit zwei fällt weg |
| Linke Taste 0,5 s still auf einer Ecke halten | sie hängt an der Maus und liegt beim Loslassen auf dem Block darunter; `Esc` bricht ab |
| Rechtsklick, „Form fertig“ | speichert die Form im Bau, mit zwei Ecken eine Linie, mit mehr eine Region; den Eintrag gibt es ab zwei Ecken |
| Rechtsklick, „Form abbrechen“, oder `Esc` | verwirft die Form im Bau; eine geänderte bleibt, wie sie war |
| Rechtsklick in eine eigene Region oder auf eine eigene Linie | „Form löschen“ |
| Rechtsklick in ein altes Rechteck oder auf seine Raute | „Region löschen“ |

- **Doppelklick:** Das Spiel meldet einen Klick als doppelt, wenn derselbe
  Knopf im selben Schirm weniger als 250 ms nach dem letzten kommt, gleich
  wo, und nur, wenn der Schirm den letzten Klick angenommen hat
  (`mouseClicked` gab `true`; `MouseHandler.onButton`, belegt per javap am
  Client 26.3). Die Karte zählt ihn nur, wenn der zweite Druck höchstens
  3 Einheiten vom ersten weg ist (`Karte.ZUG`), für jedes Ziel, auch
  Flächen und Überschriften der Ebenen. Bis 0.2.36 heftete ein schneller
  Klick auf B kurz nach einem auf A den Wegpunkt A an, statt B in die
  Mitte zu legen (mod#112). Die Karte nimmt jeden Linksklick an, auch
  einen auf freie Karte, sonst zählte ein Doppelklick auf eine Fläche nie. Der erste
  Klick auf eine Marke legt sie erst in die Mitte, wenn 250 ms lang kein
  zweiter kam (`MouseHandler.DOUBLE_CLICK_THRESHOLD_MS`, `Karte.wartend`);
  so bewegt ein Doppelklick die Karte nicht, wie der User es will (mod#75).
  Der zweite zählt für die Marke des ersten (`Karte.letzte`), nicht für die
  unter der Maus, und hebt das Zentrieren auf. Jeder andere Klick vergisst
  sie; ein schneller Klick nach „Wegpunkt setzen“ heftet so nichts an.
- **Doppelklick auf ein Objekt vom Server:** Der erste Klick merkt sich
  beim Loslassen ohne Zug das Ziel unter der Maus (`Karte.letztesZiel`),
  eine Nadel, ein Banner, eine Fläche oder einen Kreis mit `id`; eine Nadel
  geht vor, wie bei der Tafel. Liegt dort eine eigene Form, merkt er sich
  sie statt der Fläche oder des Kreises vom Server darunter
  (`Karte.letzteEigene`), so wie sie über ihnen gezeichnet ist. Bis 0.2.17
  ging die Fläche vom Server vor, und eine eigene Region in einem Land vom
  Server liess sich nicht anheften. Der zweite heftet es an, wenn unter ihm
  dasselbe Ziel liegt; ein Knopf geht vor. Eine Tafel hält dabei keiner
  der Klicks, siehe [Ebenen](ebenen.md), „Infotafel“.
- **Treffer:** eine halbe Kopfseite und eine Einheit um die Mitte der
  Marke. Ein Wegpunkt oder Mitspieler geht dem eigenen Kopf vor, sonst
  liesse sich ein Wegpunkt am eigenen Standort nicht greifen; sonst die
  zuletzt gezeichnete zuerst. Die Knöpfe oben rechts gehen vor.
- **Ohne Namen:** Die Farbe unterscheidet die Wegpunkte; unten links stehen
  die Koordinaten unter der Maus, siehe [Vollbildkarte](vollbildkarte.md),
  „Bedienung“.

## Am Rand

- **Vollbildkarte:** Eine Marke, die nicht mindestens 14 Einheiten
  (`Karte.RAND`) vom Rand des Schirms liegt, rückt auf der Linie von der
  Mitte des Schirms zu ihr bis dorthin (`Kartenblick.marke`). Die Seite
  sagt so die Richtung, nicht die Entfernung. Die 14 Einheiten lassen Platz
  für den Namen über einem Kopf. Den Knöpfen rechts oben weicht eine Marke
  am Rand aus, oben nach links, rechts nach unten.
- **Auf der Karte:** Eine Marke steht auf dem Pixel, auf dem die Karte
  ihren Ort zeichnet, und wackelt beim Ziehen und Laufen nicht gegen sie.
  Auf der Vollbildkarte rechnen Kacheln, Marken und Klicks von derselben
  ganzzahligen Kante aus, der Kante des Pixels 0 der Basis
  (`Kartenblick.kanteX`): Kachel `tx` liegt bei `kante + tx · kachel ·
  lupe`, eine Marke bei `kante + B / teiler · lupe` (`Kartenblick.rasterX`).
  Umgekehrt nehmen „Wegpunkt setzen“, „Hierher teleportieren“ und die
  Koordinaten unten links den Block, den die Karte unter der Maus zeichnet
  (`Kartenblick.basisRasterX`). So stimmen sie auch in Doubles überein. Auf der
  Minimap rechnet die Marke von der Kante des Bildes aus `Minimap.ecke`
  (`Minimap.pixel`, `Minimap.marke`).
- **Namen** über Köpfen stehen ganz auf dem Schirm. Träfe der Name über
  dem Kopf die Knöpfe rechts oben, steht er unter dem Kopf; erst wenn auch
  das sie träfe, links neben ihnen (`Kartenblick.name`). Die
  Knöpfe misst die Karte an ihren eigenen Grenzen.
- **Minimap:** Angeheftete Wegpunkte und Mitspieler, die ausserhalb der
  Form liegen, stehen an ihrem Rand in ihrer Richtung, rund am Kreis, eckig
  am Quadrat, eine halbe Kopfseite und eine Einheit nach innen geklemmt. Mitspieler, die nicht angeheftet
  sind, stehen wie bisher nur in der Form; Wegpunkte, die nicht angeheftet
  sind, gar nicht.
- **Mitspieler** gibt es nur, solange der Server sie nennt, siehe
  [Minimap](minimap.md), „Mitspieler“. Ein angehefteter Mitspieler, den er
  nicht nennt, fehlt auch am Rand.
- **Angeheftet** zeigt die Vollbildkarte mit einem Ring, dessen Farbe
  einmal in 2 s durch alle Töne läuft (`Karte.BUNT_MS`); die Minimap zeigt
  keinen Ring. Gewünscht hat der User den Ring für Mitspieler; Wegpunkte
  haben ihn ebenso, sonst sähe man nicht, welche angeheftet sind.

## Strahl

Über jedem angehefteten Wegpunkt steht in der Welt der Strahl eines
Leuchtfeuers in seiner Farbe, so wünscht es der Maintainer (mod#36).

![Die Strahlen zweier angehefteter Wegpunkte über der Szene des Gametests, rot und blau](bilder/strahl.png)

- **Gezeichnet** mit dem Strahl des Spiels, `BeaconRenderer.submitBeaconBeam`,
  aus `LevelRenderEvents.COLLECT_SUBMITS` von Fabric (`Strahlen.zeichne`).
  Er sieht aus wie der eines Leuchtfeuers: dieselbe Textur, derselbe Lauf
  in 40 Ticks, innen 0,2 und aussen 0,25 Blöcke Radius. Ab 96 Blöcken
  waagrechtem Abstand wird er mit dem Abstand breiter, durchs Fernrohr
  nicht, wie beim Leuchtfeuer (`BeaconRenderer.extract`, belegt per javap am
  Client 26.3).
- **Unten** steht er auf dem Boden unter dem Laub, auf Wasser auf seiner
  Oberfläche: der Heightmap `MOTION_BLOCKING_NO_LEAVES` des Clients
  (`Level.getHeight`). Der Client bekommt `WORLD_SURFACE`, `MOTION_BLOCKING`
  und `MOTION_BLOCKING_NO_LEAVES` vom Server (`Heightmap.Types.sendToClient`,
  per javap). Ist der Chunk nicht geladen, beginnt der Strahl am Boden der
  Welt.
- **Oben** reicht er 2048 Blöcke hoch (`BeaconRenderer.MAX_RENDER_Y`), wie
  der Strahl eines Leuchtfeuers. Der Plan nannte die Bauhöhe; über ihr
  sähe man aber das Ende, wenn man höher fliegt.
- **Welche:** angeheftete Wegpunkte dieser Dimension, deren Block waagrecht
  höchstens die Sichtweite entfernt liegt, also Sichtweite × 16 Blöcke wie
  beim Leuchtfeuer (`Strahlen.waehle`). Höchstens 64 je Frame
  (`Strahlen.MAX_STRAHLEN`), die ersten in der Reihe der Wegpunkte.
- **Schalter** „Effekte in der Welt“ im Untermenü „Einstellungen …“, Vorgabe
  an, siehe [Minimap](minimap.md), „Bedienung“. Aus zeichnet der Mod
  keinen Strahl und keinen Schleier.
- **Kosten:** 64 Strahlen, die Grenze, kosten je Frame im Median 0,05 bis
  0,06 ms und im p95 0,07 bis 0,12 ms, gemessen am 11.10., siehe
  [Strahl, Kosten je Frame](messungen/2026-10-11-strahl.md).
- **Ohne Allokation im Mod je Frame:** Die Indizes der Gewählten liegen in
  einem festen Feld, die Liste der Wegpunkte ist eine feste Sicht, und den
  Namen der Dimension rechnet `Strahlen` nur beim Wechsel. Der Strahl des
  Spiels selbst legt je Aufruf zwei kleine Objekte an, wie bei jedem
  Leuchtfeuer.

## Schleier

Am Rand angehefteter Regionen und Kreise steht in der Welt ein Schleier in
ihrer Farbe, so wünscht es der Maintainer (mod#36): unten auf dem Gelände,
nach oben immer durchsichtiger.

![Der Schleier einer angehefteten Region am Hang und der Strahl eines angehefteten Wegpunkts; Szene `schleier` des Gametests](bilder/schleier.png)

- **Welche:** dieselben Formen wie auf der Minimap, siehe „Anheften“:
  angeheftete Flächen und Kreise der sichtbaren Ebenen, angeheftete eigene
  Regionen aus Wegpunkten und alte Rechtecke, in der Dimension des Spielers
  (`Wegpunkte.minimap`). Linien bekommen keinen Schleier, auch eigene
  nicht, entschieden vom Reviewer (mod#79).
- **Farbe:** der Rand der Form, ohne sichtbaren Rand ihre Füllung ohne
  Alpha; eine eigene Region in ihrer Farbe (`Schleier.farbe`).
- **Form:** je Stück der Kante ein senkrechtes Viereck. Die Kanten teilt
  der Mod, wo sie eine ganze Zahl in x oder z kreuzen, so liegt jedes Stück
  in einem Block (`Schleier.teile`). Ein Kreis wird ein Vieleck mit Sehnen
  von höchstens einem Block, nur über den Bogen beim Spieler
  (`Schleier.bogen`); so kostet ein Kreis von 100 000 Blöcken nur sein
  Stück in Sichtweite.
- **Höhe:** 4 Blöcke (`Schleier.HOEHE`). Unten ist der Schleier zur Hälfte
  so deckend wie seine Farbe (`Schleier.DECKUNG`), oben ganz durchsichtig,
  dazwischen linear.
- **Gelände:** Die Ecken unten stehen auf der Heightmap
  `MOTION_BLOCKING_NO_LEAVES` des Clients, also unter dem Laub und auf der
  Oberfläche von Wasser, wie der Strahl. Der Client hat `WORLD_SURFACE`,
  `MOTION_BLOCKING` und `MOTION_BLOCKING_NO_LEAVES`, die drei Heightmaps
  mit `Heightmap.Usage.CLIENT` (`Heightmap.Types.sendToClient`, belegt per
  javap am Client 26.3). Liegt eine Ecke auf der Grenze mehrerer Blöcke,
  nimmt der Mod den höchsten, so taucht der Schleier an keiner Stufe ein
  (`Schleier.hoehe`). So folgt er dem Hang.
- **Gezeichnet** mit `RenderTypes.debugQuads` aus
  `LevelRenderEvents.COLLECT_SUBMITS`: Ecken mit Farbe, gemischt wie
  Durchsichtiges, mit Tiefentest, aber ohne in die Tiefe zu schreiben, und
  ohne Culling, also von beiden Seiten zu sehen
  (`RenderPipelines.DEBUG_FILLED_SNIPPET`: `DepthStencilState` mit
  `GREATER_THAN_OR_EQUAL` und ohne Schreiben, `withCull(false)`; belegt per
  javap).
- **Gebaut** nur bei einer Änderung (`Schleier.baue`): andere Ebenen oder
  Wegpunkte (`Ebenen.stand`, `Wegpunkte.stand`), andere Dimension oder
  Sichtweite, der Spieler mehr als 16 Blöcke vom Ursprung des letzten Baus
  (`Schleier.NEU_AB`), oder ein Chunk unter dem Schleier ändert sich, über
  denselben Haken `setSectionDirty` wie die Minimap. Neu gebaut wird
  höchstens alle 500 ms (`Schleier.NEU_FRUEHESTENS_MS`); nur der erste Bau
  und eine andere Dimension warten nicht. Gebaut wird bis zur Sichtweite
  und 16 Blöcke darüber, so reicht der Schleier, bis der Spieler so weit
  gegangen ist. Über einem Chunk, der nicht geladen ist, fehlt er, bis der
  Chunk kommt.
- **Je Frame** reicht der Mod nur die fertigen Ecken weiter, relativ zum
  Ursprung des Baus; das Objekt, das sie schreibt, legt er einmal an.
- **Grenze:** höchstens 5 000 Vierecke (`Schleier.MAX_VIERECKE`), die
  nächsten zuerst, gemessen an der Mitte des Stücks. Darüber warnt das Log
  einmal, bis ein Bau wieder darunter liegt.
- **Kosten,** gemessen am 11.10., siehe
  [Schleier, Kosten je Frame](messungen/2026-10-11-schleier.md):

  | Fall | je Frame, Median | ein Bau, Median |
  |---|---|---|
  | 3 Regionen, 2 220 Vierecke | +0,16 bis +0,24 ms | 1,3 bis 1,4 ms |
  | 47 Regionen, an der Grenze von 5 000 | +0,32 bis +0,47 ms | 4,4 bis 4,7 ms |

  Je Frame mit 64 Strahlen. Ein Bau liest vor allem Höhen aus der Welt,
  70 bis 80 % seiner Zeit. Darum hat der Reviewer nach dem Profil die
  Grenze von 20 000 auf 5 000 gesenkt und den Bau auf höchstens alle
  500 ms gesetzt; bei 20 000 waren es 0,8 bis 2,1 ms je Frame und 5,2 ms je
  Bau. Sammeln und Sortieren gingen dabei über alle Stücke in Reichweite,
  nicht nur die nächsten 5 000; bei 47 Regionen waren das rund 2 ms des
  Baus.
- **Auswahl statt Sortieren:** Von allen Stücken in Reichweite wählt der
  Bau die 5 000 nächsten aus, ohne alle zu sortieren (`Schleier.waehle`),
  und sortiert nur diese (`Schleier.naechste`). Das ergibt dieselben in
  derselben Reihe wie volles Sortieren, denn jeder Schlüssel trägt seinen
  Index.
- **Nur im Kreis geteilt:** Eine Kante teilt der Bau nur, soweit sie im
  Kreis um den Spieler liegt, einen Block weiter als die Reichweite
  (`Schleier.imKreis`). Ein Stück mit der Mitte in der Reichweite liegt ganz
  darin, so fehlt keins; was ausserhalb liegt, entsteht gar nicht erst.
- **Geschätzt, noch nicht gemessen:** Beides nimmt von den rund 2 ms für
  Sammeln und Sortieren an der Spitze den grössten Teil; ein Bau mit
  47 Regionen läge dann eher bei 3 als bei 4,5 ms. Die Messung folgt in
  der nächsten Nacht.
- **Schalter** „Effekte in der Welt“, siehe „Strahl“.

## Grösse

- **Köpfe und Wegpunkte** sind 6 Einheiten des GUI gross (`Minimap.KOPF`),
  vorher waren es 8. Auf der Minimap wachsen sie mit deren Seite:
  6 × Seite / 128, mindestens 4, also 4 bei 64 Einheiten und 12 bei 256
  (`Minimap.kopf`). Mit dem GUI-Massstab wachsen sie wie alles im GUI.
- **Rand und Pfeil** wachsen mit: Kopf, Pfeil und Raute sind in Achteln
  oder Sechzehnteln gezeichnet und mit der Grösse skaliert
  (`Minimap.avatar`, `Mitspieler.kopf`, `Minimap.wegpunkt`).
- **Auf ganze Pixel** des Schirms gelegt, wie die Karte darunter.
- **Texel:** Gleich breit sind die 8 Texel eines Gesichts nur, wenn seine
  Seite in Pixeln ein Vielfaches von 8 ist. Bei 6 Einheiten und
  GUI-Massstab 2 sind es 12 Pixel, die Texel also abwechselnd 1 und 2
  Pixel breit. Ob 6 Einheiten gut aussehen, sieht der User im Spiel.

## Eigene Formen

Eigene Linien und Regionen baut der Spieler aus eigenen Ecken, je ein
Marker wie ein Wegpunkt (mod#79, mod#112). Zwei Ecken sind eine Linie,
drei und mehr eine Region, ein Vieleck in der Folge der Ecken. Eine Ecke
ist ein Block (x, z), kein Wegpunkt; so will es der User: „eine Linie
sollte dann ein Punkte sein genau wie eine Region mit Eckpunkten“.

- **Beginnen:** Rechtsklick, „Linie/Region von hier“ beginnt eine Form mit
  einer Ecke auf dem Block unter der Maus, auf einem Wegpunkt auf dessen
  Block (`Karte.beginne`). Den Eintrag gibt es nur, solange keine Form im
  Bau ist.
- **Bauen:** Solange eine Form im Bau ist, setzt ein Linksklick ohne Zug
  auf die Karte eine Ecke (`Karte.hinzu`): auf einem Wegpunkt auf dessen
  Block, statt ihn in die Mitte zu legen, sonst auf den Block darunter, und
  höchstens 3 Einheiten neben einer Ecke im Bau auf deren Block
  (`Karte.eckBlock`). Ebenso Rechtsklick, „Eckpunkt hinzufügen“. Ziehen
  über `Karte.ZUG` verschiebt die Karte wie sonst. Ein Wegpunkt entsteht
  dabei nicht. Ein Linksklick auf die erste Ecke schliesst eine neue Form
  ab drei Ecken zur Region; „Form fertig“ speichert sie, wie sie ist
  (`Karte.fertig`, `Wegpunkte.setzeForm`).
- **Im Bau** verbindet eine gepunktete weisse Vorschau die Ecken, mit
  einer weissen Raute auf jeder, und die Stelle der nächsten Ecke mit der
  Maus. Unten links steht „Linksklick: Eckpunkt hier oder auf einem
  Wegpunkt, auf den ersten: Region; Rechtsklick: Menü mit „Form fertig“;
  Esc bricht ab“. Ein Rechtsklick schliesst nie sofort ab; das Menü hat
  dann: „Hierher teleportieren“, wenn erlaubt; „Wegpunkt setzen“, auf einem
  Wegpunkt „Wegpunkt löschen“; „Eckpunkt hinzufügen“; „Form fertig“ ab zwei
  Ecken; „Form abbrechen“; „Form löschen“ oder „Region löschen“, wenn dort
  eine liegt.
- **Keine Tafel,** solange die Form im Bau ist (`Karte.tafel`). Sonst ging
  die Tafel einer Region vom Server dort auf, wo das Menü war, lag über den
  Stellen, die der Spieler anklickt, und nahm deren Klick; so fügte ein
  Linksklick bis 0.2.17 auf einem Server mit Ländern oft nichts an (Test
  des Users am 10.10.).
- **Nicht dazu** kommt eine Ecke, die schon dabei ist, ausser der ersten
  zum Schliessen, und keine über 64 (`Wegpunkte.MAX_PUNKTE_FORM`). Wer die
  Dimension wechselt, verliert die Form im Bau.
- **Ecken** stehen auf der Vollbildkarte als kleine Rauten in der Farbe
  ihrer Form, mit dunklem Rand, immer, unter den Marken (`Karte.ecken`).
  Ein Klick trifft die Ecke, deren Mitte am nächsten liegt, höchstens 3
  Einheiten weg (`Karte.eckeUnter`); liegen zwei eng, die nähere, nicht die
  zuletzt gezeichnete. Ein Wegpunkt auf derselben Stelle geht beim
  Linksklick vor; der Rechtsklick dort zeigt die Einträge für beide.
- **Eckpunkt hinzufügen** auf einer Ecke einer fertigen Form: Die Form kommt
  in den Bau (`Karte.bearbeite`), neue Ecken kommen zwischen diese und die
  nächste, jede weitere hinter die vorige neue. Nach der letzten Ecke einer
  Region ist die erste die nächste; an der letzten einer Linie hängt sie
  hinten an. „Form fertig“ speichert an derselben Stelle, mit Farbe und
  Anheften (`Wegpunkte.ersetze`); „Form abbrechen“ oder `Esc` lässt die Form,
  wie sie war. Gibt es sie beim Speichern so nicht mehr, etwa weil sie im
  Bau über „Form löschen“ ging, speichert „Form fertig“ nichts und sagt
  unten links „Die Form gibt es so nicht mehr; die neuen Ecken sind nicht
  gespeichert“, statt sie still zu verwerfen (Review von #122).
- **Eckpunkt löschen** auf einer Ecke nimmt sie heraus, die Form bleibt.
  Aus einer Region mit drei Ecken wird eine Linie; eine Linie mit zwei Ecken
  fällt weg, eine Ecke allein ist keine Form.
- **Verschieben:** Linke Taste 0,5 s still auf einer Ecke halten, wie bei
  einem Wegpunkt: Sie hängt an der Maus, beim Loslassen liegt sie auf dem
  Block darunter; `Esc` bricht ab. Liegt sie dann auf dem Block ihrer
  Nachbarin, werden beide eine.
- **Wegpunkte** und Formen sind getrennt. Wer einen Wegpunkt verschiebt
  oder löscht, ändert keine Form. Bis 0.2.37 nannte eine Form die ids ihrer
  Wegpunkte und ging mit ihnen; eine alte Datei zieht beim Lesen um, siehe
  „Ablage“.
- **Höchstens 256** Formen je Welt (`Wegpunkte.MAX_EIGENE_FORMEN`);
  darüber speichert „Form fertig“ nichts, und unten links steht „Höchstens
  256 eigene Formen“.
- **Farbe** wie ein Wegpunkt: die erste der 8 Farben, die in der Dimension
  unter Wegpunkten, Rechtecken und Formen noch frei ist.
- **Gezeichnet** auf der Vollbildkarte über den Formen der Ebenen
  (`Wegpunkte.form`, `Wegpunkte.karte`): eine Region als Fläche in ihrer
  Farbe zu 25 % mit 1 Einheit Rand, eine Linie 2 Einheiten breit, beide
  durch die Mitten der Blöcke ihrer Ecken.
- **Höhe:** Eine Ecke hat nur x und z. Der Schleier einer angehefteten
  Region nimmt die Höhe je Stück aus der Höhenkarte des Spiels und lässt
  Stücke in Chunks aus, die nicht geladen sind, bis sie es sind; siehe
  „Schleier“. Strahlen stehen nur über Wegpunkten.
- **Treffer:** in einer Region, oder höchstens 3 Einheiten des GUI neben
  einer Linie (`Karte.eigeneUnter`, `Tafeln.abstand`); überlappen zwei, die
  zuletzt gebaute. Ein Wegpunkt geht vor.
- **Löschen:** Rechtsklick in die Region oder auf die Linie, „Form
  löschen“.
- **Anheften:** Doppelklick, siehe „Anheften“. Auch Linien, entschieden
  vom Reviewer; den Schleier in der Welt bekommen nur Regionen.

## Regionen

Die Rechtecke aus „Region von hier“ bis 0.2.15 (mod#35, mod#73). Neue
gibt es nicht mehr, entschieden vom Reviewer (mod#79); vorhandene bleiben
ohne Umwandlung.

- **Aus der Datei** gelesen, höchstens 256 je Welt
  (`Wegpunkte.MAX_REGIONEN`).
- **Gezeichnet** auf der Vollbildkarte nach den Formen der Ebenen, vor
  Nadeln und Marken (`Karte.regionen`): die Fläche in ihrer Farbe zu 25 %,
  1 Einheit Rand deckend, auf ganzen Pixeln wie die Kacheln. In ihrer Mitte
  die Raute eines Wegpunkts; sie verhält sich wie eine Marke: Ein Klick legt
  sie in die Mitte, ausserhalb des Schirms steht sie am Rand.
- **Löschen:** Rechtsklick in die Region oder auf ihre Raute, „Region
  löschen“; überlappen zwei, die zuletzt gesetzte.
- **Anheften:** Doppelklick auf die Raute, siehe „Anheften“.
- **Minimap:** nur angeheftet, siehe „Anheften“.

## Anheften

Regionen und Kreise lassen sich anheften wie Wegpunkte, so wünscht es der
Maintainer (mod#36): eigene Formen und Rechtecke und Flächen und Kreise der
Ebenen vom Server. Linien vom Server nicht, entschieden vom Reviewer; eigene
Linien schon (mod#79). Ebenso Nadeln und Banner,
so will es der User (mod#71); auf der Minimap stehen sie nur angeheftet,
siehe [Ebenen](ebenen.md), „Nadeln“. Angeheftete Wegpunkte bekommen in der
Welt einen Strahl, siehe „Strahl“, angeheftete Regionen und Kreise einen
Schleier am Rand, siehe „Schleier“.

![Minimap genordet und gedreht: nur der angeheftete Kreis und die angeheftete eigene Region, die übrigen Formen fehlen; Szene `formen` des Gametests](bilder/formen.png)

- **Doppelklick** auf der Vollbildkarte in eine eigene Region, auf eine
  eigene Linie, auf die Raute eines Rechtecks oder auf eine Fläche oder
  einen Kreis vom Server, siehe „Bedienung“. Vom Server lässt sich nur
  anheften, was eine `id` hat; ohne `id` gibt es auch keine Tafel. Der
  erste Klick merkt sich die eigene Form unter der Maus
  (`Karte.letzteEigene`), wenn dort keine Nadel liegt, siehe „Bedienung“.
- **Ganze Ebene:** Ein Doppelklick auf ihren Schalter in der Liste der
  Vollbildkarte heftet alles von ihr an oder löst es, siehe
  [Vollbildkarte](vollbildkarte.md), „Ebenen“ (mod#76).
- **Höchstens 64** Formen, Rechtecke und Kreise je Welt, eigene und vom
  Server zusammen (`Wegpunkte.MAX_ANGEHEFTET`). Darüber heftet der Doppelklick
  nichts an, und unten links steht „Höchstens 64 Regionen angeheftet; erst
  eine lösen“. Lösen geht immer. Auch aus der Datei liest der Mod nicht
  mehr.
- **Höchstens 64 Nadeln und Banner** je Welt, eine eigene Grenze
  (`Wegpunkte.MAX_NADELN_ANGEHEFTET`), entschieden vom Reviewer: Nadeln sind
  Punkte und kosten auf der Minimap fast nichts, die 64 Regionen begrenzen
  später auch den Schleier in der Welt. Darüber steht unten links
  „Höchstens 64 Nadeln und Banner angeheftet; erst eins lösen“.
- **Vom Server** merkt sich der Mod die Kennungen `{ebene, id}`
  (`Wegpunkte.Anheftung`), nicht die `version`: So bleibt angeheftet, was
  eine neue `version` der Ebene noch hat. Flächen und Kreise stehen in
  `formen`, Nadeln und Banner in `nadeln`.
- **Tote Einträge:** Kommt eine Ebene ganz an, vergisst der Mod, was von ihr
  angeheftet ist und sie nicht mehr hat (`Wegpunkte.pruefe`, aus
  `Kanal.anmelden`); sonst füllten tote Einträge die 64. Einträge anderer
  Ebenen bleiben, auch wenn deren Daten gerade da sind: Nach dem Wechsel
  über einen Proxy können sie noch vom vorigen Server sein.
- **Minimap:** die angehefteten Flächen und Kreise der sichtbaren Ebenen,
  wie die Vollbildkarte sie zeichnet, darüber die angehefteten Rechtecke
  (`Wegpunkte.flaeche`) und eigenen Formen (`Wegpunkte.form`) wie auf der
  Vollbildkarte. Alle liegen unter der Kartenschrift und den
  Nadeln; eine ausgeblendete Ebene fehlt auch angeheftet.
- **Vollbildkarte:** Der Rand angehefteter Flächen, Kreise, Rechtecke und
  eigener Formen ist 2 Einheiten breiter (`Wegpunkte.BREITER`), höchstens 64 wie
  jeder Rand. Hat eine Form keinen Rand, bekommt sie einen von 2 Einheiten
  in der Füllung ohne Alpha, wie die Vorgabe des Formats. Die Raute einer
  angehefteten Region hat den bunten Ring wie ein Wegpunkt.
- **Kosten:** Die Listen für Karte und Minimap baut der Mod nur neu, wenn
  sich Ebenen (`Ebenen.stand`) oder Wegpunkte (`Wegpunkte.stand`) ändern
  (`Wegpunkte.karte`, `Wegpunkte.minimap`). Sonst sind es dieselben
  Listen, und der Speicher der Formen bleibt gültig, siehe
  [Ebenen](ebenen.md), „Flächen, Kreise und Linien“, „Neu gerechnet“. Eine
  Ebene ohne Angeheftetes gibt ihre eigene Liste weiter.

## Ablage

- **Je Welt** in `wegpunkte.json` im Ordner der Welt, `heroicmap/<welt>/`,
  siehe [Download](download.md), „Ablage“. Gelesen beim Wechsel der Welt
  (`ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE`, `Wegpunkte.wechsel`),
  wenn der Ordner ein anderer ist, und nach dem Schliessen des Menüs, falls
  die Ablage eine andere ist; vergessen beim Trennen. Ein Wechsel über einen
  Proxy bringt so die Wegpunkte der neuen Welt. Im Einzelspieler ist es der
  Ordner nach dem Speicherordner der Welt, siehe [Download](download.md),
  „Ablage“.
- **Format:**

  ```json
  {"wegpunkte":[{"id":1,"dimension":"minecraft:overworld","x":12,"z":-40,"farbe":0,"minimap":true}],
   "eigene_formen":[{"dimension":"minecraft:overworld","ecken":[[12,-40],[30,-40],[30,-20]],"farbe":2,"minimap":false}],
   "regionen":[{"dimension":"minecraft:overworld","x0":2,"z0":-5,"x1":10,"z1":3,"farbe":1,"minimap":false}],
   "spieler":["00000000-0000-0000-0000-000000000001"],
   "formen":[{"ebene":"b:staedte","id":"westmark"}],
   "nadeln":[{"ebene":"b:staedte","id":"nordhafen"}]}
  ```

  `farbe` ist ein Index in `Wegpunkte.FARBEN`, `minimap` heisst angeheftet,
  `eigene_formen` nennt ihre Dimension und die Blöcke ihrer Ecken in Reihenfolge,
  `spieler` sind die angehefteten Mitspieler, `formen` die angehefteten
  Flächen und Kreise vom Server, `nadeln` die angehefteten Nadeln und
  Banner. Eine Region nennt die Blöcke ihrer Ecken,
  `x0` ≤ `x1` und `z0` ≤ `z1`, beide samt. Eine Datei von vor mod#36 ohne
  `formen` oder von vor mod#71 ohne `nadeln` liest der Mod ohne Fehler.
  Wegpunkte von vor mod#79 ohne `id`, oder mit einer doppelten, bekommen
  beim Lesen eine neue (`Wegpunkte.vergibIds`). Eine Form von vor mod#112
  nennt statt `ecken` in `punkte` die ids von Wegpunkten: Beim Lesen werden
  sie zu Ecken auf den Blöcken, auf denen die Wegpunkte jetzt liegen, die
  Dimension die des ersten; fehlende ids und Wegpunkte einer anderen
  Dimension fallen weg, unter zwei Ecken die Form. Die Wegpunkte bleiben.
  Geschrieben wird nur das neue Format. Eine ältere Version des Mods liest
  Formen ohne `punkte` nicht und verliert sie beim nächsten Speichern.
- **Schreiben** nach jeder Änderung, über `wegpunkte.json.tmp`, dann
  verschieben; nie liegt eine halbe Datei da.
- **Lesen:** Ein unlesbarer Eintrag fällt weg, die übrigen bleiben. Ist die
  Datei unlesbar, gibt es keine Wegpunkte, und der Mod verschiebt sie nach
  `wegpunkte.json.kaputt`; die nächste Änderung legt eine neue an. Scheitert
  das Verschieben, bleiben neue Wegpunkte nur im Speicher.

## Tests

- `WegpunkteTest`: setzen, löschen, anheften, über den Neustart behalten,
  kaputte Einträge, eine kaputte Datei bleibt gesichert, Farben bleiben
  nach dem Löschen verschieden, nur im Speicher. Zum Anheften:
  `alteDateiOhneListeLaedt`, `anheftenUeberstehtDenNeustart`,
  `hoechstens64Angeheftet`, `toteEintraegeFallenWeg`,
  `listenFuerKarteUndMinimap` (dieselben Listen ohne Änderung, nur
  Angeheftetes auf der Minimap, breiterer Rand auf der Karte) und
  `breiterOhneRandNimmtDieFuellung`. Zu Nadeln: `nadelnAnheftenUndBehalten`,
  `hoechstens64NadelnEigeneGrenze` und `nadelnAufDerMinimapNurAngeheftet`.
  Zur ganzen Ebene: `ganzeEbeneAnheftenUndLoesen` und
  `ganzeEbeneHaeltDieGrenzen`. Zu eigenen Formen: `festeIdsAuchAusAltenDateien`,
  `formenMitEigenenEckenBleiben` (auch: Wegpunkte verschieben oder löschen
  ändert keine Form), `eckeLoeschenUndEinfuegen` (Region mit drei Ecken wird
  Linie, Linie mit zwei fällt weg, über 64 nicht) und
  `formenGrenzenUndAlteDatei` (Umzug von `punkte` zu `ecken`).
- `SchleierTest`: Teilen an jeder ganzen Zahl, auch rückwärts und durch
  eine Ecke; ein Rechteck ein Stück je Block, nur im Kasten; ein Kreis in
  Sehnen über den Bogen, ein Kreis von 100 000 Blöcken nur beim Kasten;
  die Höhe vom höchsten berührten Block; die nächsten zuerst; die Farbe.
- `StrahlenTest`: nur angeheftete Wegpunkte dieser Dimension in Sichtweite,
  genau an der Grenze, höchstens 64, die ersten der Reihe nach; breiter in
  der Ferne, durchs Fernrohr nicht.
- `MinimapTest`: `kopfWaechstMitDerSeite`, `amRandInSeinerRichtung` und
  `markeAufDemPixelDerKarte`; der Schalter „Effekte in der Welt“, Vorgabe
  an, gespeichert.
- `KartenblickTest`: `markenAufDemRasterDerKacheln`,
  `klickTrifftDenGezeichnetenBlock` (Lupe 1, 2, 4, grobe Stufe, links des
  Ursprungs, genau auf der Kante), `kachelnRasterUndKlickAufEinerKante`,
  `nameBleibtAufDemSchirmUndNebenDenKnoepfen` und
  `markeWeichtDenKnoepfenAus`.
- Gametest `Bedienung` mit echten Eingaben: Rechtsklick und „Wegpunkt
  setzen“; die Karte ziehen, bis der Wegpunkt am rechten Rand steht; auf
  der Marke ziehen zieht nur die Karte; ein Klick legt sie in die Mitte;
  ein schneller Klick nach „Wegpunkt setzen“ heftet nichts an; ein
  Doppelklick heftet an; am eigenen Standort holt ein Klick den Spieler
  zurück, und ein Rechtsklick bietet „Wegpunkt löschen“. Dazu ein Kreis
  vom Server: Ein Klick heftet nichts an, ein Doppelklick heftet an, ein
  zweiter löst; ein Doppelklick auf eine Nadel und einer auf die Raute
  einer eigenen Region heften sie an. Dazu „Zum Spieler“, „Optionen …“ und
  zurück, und ein Wegpunkt, nach 250 ms gehalten noch nicht, nach 700 ms
  an der Maus, nach der Uhr gemessen, verschoben, ein zweites Mal mit
  Escape abgebrochen (mod#75, mod#112). Formen wie ein Spieler
  (mod#79), über einer grossen Region vom Server mit Tafel, wie auf einem
  Server mit Ländern, jeder Klick von Hand: in acht Schritten hinfahren,
  ruhen, drücken, zwei Ticks halten und eine halbe Einheit zittern,
  loslassen (`Bedienung.spielerKlick`); zwei Ticks bleiben auch auf einem
  langsamen Runner unter den 0,5 s des Haltens. Fünf Wegpunkte über „Wegpunkt
  setzen“; auf dem ersten „Linie/Region von hier“, die Tafel der Region geht
  auf, wo das Menü war; Linksklicks auf den dritten, rechts unter dem
  Menü, den zweiten und den ersten schliessen eine Region; „Linie/Region
  von hier“, ein Linksklick und „Form fertig“ geben eine Linie; ein
  Doppelklick in die Region heftet sie an und nicht die Region vom Server;
  ein Doppelklick von Hand auf einen Wegpunkt in ihr heftet ihn an, und
  die Karte bleibt stehen; „Form löschen“ löscht die Region. Im Tempo
  eines Spielers (mod#112, `Bedienung.tempoKlick`: der Zeiger kommt einen
  Tick vor dem Drücken an, Drücken und Loslassen im selben Frame, die
  Pause nach der Uhr; kommt der Zeiger auf einem langsamen Rechner später an, drückt er gleich): auf einem Wegpunkt „Linie/Region von hier“, dann
  Linksklicks auf drei weitere, 300, 220 und 200 ms nach dem vorigen
  Drücken, der erste 12 Einheiten neben dem ersten Punkt; alle drei fügen
  an, auch die zwei, die das Spiel als Doppelklick meldet
  (`formImTempo`). Ein Klick auf B 220 ms nach einem auf A legt B in die
  Mitte und heftet A nicht an (`zweiMarkenImTempo`). Der Weg des Users
  (`formAufLeererKarte`): „Linie/Region von hier“ auf leerer Karte, drei
  Linksklicks auf leere Stellen, 300, 220 und 200 ms nach dem vorigen, und
  einer nah an der ersten Ecke geben eine Region mit vier Ecken, ohne
  neuen Wegpunkt. Ecken bearbeiten (`eckenBearbeiten`): zwei Ecken 4
  Einheiten auseinander, ein Rechtsklick eine Einheit neben der zweiten
  löscht die zweite; „Eckpunkt hinzufügen“ an der ersten setzt die neue an
  die zweite Stelle; 700 ms gehalten hängt eine Ecke an der Maus und liegt
  nach dem Loslassen 2 bis 3 Blöcke weiter; eine Linie mit zwei Ecken fällt
  beim Löschen einer weg; im Bau gelöscht, speichert „Form fertig“ nichts
  und zeigt den Hinweis.
- `AblageTest`: der Ordner der Welt je Wahl und je Dimension, siehe
  [Download](download.md), „Ablage“.
- Gametest `Bilder`: die Vollbildkarte mit einem angehefteten Wegpunkt und
  einem am Rand, siehe [Vollbildkarte](vollbildkarte.md), „Bild“; in der
  Szene `formen` ein angehefteter Kreis und eine angeheftete Region aus
  drei Wegpunkten
  auf Minimap und Vollbildkarte, siehe [Ebenen](ebenen.md), „Flächen,
  Kreise und Linien“; in der Szene `strahl` die Strahlen zweier
  angehefteter Wegpunkte (`strahl.png`); in der Szene `schleier` der
  Schleier einer angehefteten Region an einem Hang aus Stufen und ein Strahl
  (`schleier.png`).
- Gametest `Messung` mit `-PmessungEffekte=true`: was Strahlen und
  Schleier je Frame kosten und wie lange ein Bau des Schleiers dauert.

## Was fehlt

- **Namen, Farbe wählen:** Ein Wegpunkt hat nur Block und Farbe; die Farbe
  ändern heisst löschen und neu setzen. Verschieben geht, siehe „Bedienung“.
- **Formen ändern:** Einen Punkt in eine fertige Form einfügen oder ihre
  Farbe wählen geht nicht; dafür die Form löschen und neu bauen.
