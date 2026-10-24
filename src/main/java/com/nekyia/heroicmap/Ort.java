package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Der Ort einer Welt unter {@code heroicmap/}: Adresse, Server und Hash, und der Umzug eines Ordners einer Welt oder eines
 * Baums an einen anderen Ort. Ein Umzug geschieht ganz oder gar nicht: Scheitert ein Schritt, gehen die vorigen zurück.
 * Siehe docs/download.md, „Ort ändern“.
 */
final class Ort {

    /** Der Hash einer Welt, wie der Mod ihn schreibt: genau 16 Stellen hex, klein. */
    static final Pattern HASH = Pattern.compile("[0-9a-f]{16}");
    static final String WELT = "welt-", KARTE = "karte-", EINZELSPIELER = "einzelspieler_";

    /**
     * Wo eine Welt liegt: die Adresse, leer bei der Ablage Hash, {@code einzelspieler_<speicherstand>} im Einzelspieler; der
     * Server, leer ohne Namen und im Einzelspieler; der Hash. Die Adresse ist der Name ihres Ordners, also schon bereinigt.
     */
    record Teile(String adresse, String server, String hash) {

        /** Der Schlüssel des Servers für zeigt.txt und eigene Karten: sein Name, ohne Namen und im Einzelspieler {@link Zeigt#OHNE}. */
        String wer() {
            return server.isEmpty() ? Zeigt.OHNE : server;
        }

        boolean einzelspieler() {
            return adresse.startsWith(EINZELSPIELER);
        }

        /** Im Einzelspieler der Speicherstand, wie sein Ordner unter {@code heroicmap/} ihn nennt; sonst leer. */
        String speicherstand() {
            return einzelspieler() ? adresse.substring(EINZELSPIELER.length()) : "";
        }
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Was der Standardport bei IP:Port vor der Regel 0040 an den Ordner hängte. */
    private static final String STANDARD = "_" + Downloads.STANDARDPORT;

    /** Ein Schritt eines Umzugs, den {@link #zurueck} wieder aufhebt. */
    private interface Schritt {
        void zurueck() throws IOException;
    }

    private Ort() {
    }

    /**
     * Die Teile des Ordners einer Welt unter {@code wurzel}: {@code [adresse/][karte-<server>/]welt-<hash>}, im Einzelspieler
     * {@code einzelspieler_<speicherstand>/welt-<hash>}. Null für alles andere, auch für die Ablage vor dem Hash.
     */
    static Teile teile(Path wurzel, Path welt) {
        Path rel;
        try {
            rel = wurzel.relativize(welt);
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<String> n = new ArrayList<>();
        rel.forEach(p -> n.add(p.toString()));
        if (n.isEmpty() || n.size() > 3 || n.contains("..") || !n.getLast().startsWith(WELT)) {
            return null;
        }
        String hash = n.getLast().substring(WELT.length());
        if (!HASH.matcher(hash).matches()) {
            return null;
        }
        String server = "", adresse = "";
        int i = n.size() - 2;
        if (i >= 0 && n.get(i).startsWith(KARTE)) {
            server = n.get(i).substring(KARTE.length());
            if (Kartenname.gueltig(server) == null) {
                return null;
            }
            i--;
        }
        if (i >= 0) {
            adresse = n.get(i);
            // Im Einzelspieler gibt es keinen Server und damit kein karte-.
            boolean einzel = adresse.startsWith(EINZELSPIELER);
            if (einzel && (!server.isEmpty() || adresse.length() == EINZELSPIELER.length()) || adresse.startsWith(KARTE)
                    || adresse.startsWith(WELT)) {
                return null;
            }
            i--;
        }
        return i < 0 ? new Teile(adresse, server, hash) : null;
    }

    /**
     * Die Teile aus der Eingabe, bereinigt wie beim Anlegen: die Adresse wie ein Host ({@link Downloads#name}), der Server
     * wie ein Name einer Karte, der Hash klein. Null mit {@code fehler[0]}, dem Schlüssel der Meldung, wenn etwas nicht geht.
     */
    static Teile eingabe(String adresse, String server, String hash, String[] fehler) {
        String a = adresse.strip(), s = server.strip(), h = hash.strip();
        if (!HASH.matcher(h).matches()) {
            fehler[0] = "heroicmap.ort.fehler.hash";
            return null;
        }
        if (!s.isEmpty() && Kartenname.gueltig(s) == null) {
            fehler[0] = "heroicmap.ort.fehler.server";
            return null;
        }
        String ordner = a.isEmpty() ? "" : Downloads.name(a);
        // Einen Punkt am Ende nimmt Windows dem Namen eines Ordners; der Ordner hiesse dann anders.
        if (ordner.startsWith(EINZELSPIELER) || ordner.startsWith(KARTE) || ordner.startsWith(WELT) || ordner.endsWith(".")) {
            fehler[0] = "heroicmap.ort.fehler.adresse";
            return null;
        }
        return new Teile(ordner, s, h);
    }

    /**
     * Die Teile einer Welt im Einzelspieler aus der Eingabe: der Speicherstand, also der Name seines Ordners in {@code saves/},
     * bereinigt wie beim Anlegen ({@link #speicherordner}), und der Hash. Null mit {@code fehler[0]} wie {@link #eingabe}.
     */
    static Teile einzelspieler(String speicherstand, String hash, String[] fehler) {
        String s = speicherstand.strip(), h = hash.strip();
        if (!HASH.matcher(h).matches()) {
            fehler[0] = "heroicmap.ort.fehler.hash";
            return null;
        }
        String ordner = speicherordner(s);
        if (s.isEmpty() || ordner.endsWith(".")) {
            fehler[0] = "heroicmap.ort.fehler.speicherstand";
            return null;
        }
        return new Teile(ordner, "", h);
    }

    /** Der Ordner unter {@code heroicmap/} für den Speicherstand {@code speicherstand}, wie {@link Downloads#einzelspieler}. */
    static String speicherordner(String speicherstand) {
        return Downloads.name(EINZELSPIELER + speicherstand);
    }

    /**
     * Der Pfad {@code pfad} unter {@code heroicmap/} für eine Zeile von {@code breite}, gemessen mit {@code mass}. Passt er nicht,
     * fehlt ein Stück in der Mitte, „…“ an seiner Stelle: Vorn bleibt die Adresse, hinten {@code welt-…/<baum>}, so viel davon
     * passt. Reicht die Breite nicht einmal dafür, bleibt das Ende mit dem Namen des Baums. Siehe docs/download.md, „Kartenliste“.
     */
    static String kurz(String pfad, int breite, ToIntFunction<String> mass) {
        if (mass.applyAsInt(pfad) <= breite) {
            return pfad;
        }
        // Die letzten beiden Teile, der Ordner der Welt und der Baum.
        int hinten = pfad.lastIndexOf('/', pfad.lastIndexOf('/') - 1) + 1;
        String ende = pfad.substring(hinten);
        for (int vorn = hinten - 1; vorn >= 0; vorn--) {
            String s = pfad.substring(0, vorn) + "…" + ende;
            if (mass.applyAsInt(s) <= breite) {
                return s;
            }
        }
        for (int ab = hinten + 1; ab < pfad.length(); ab++) {
            String s = "…" + pfad.substring(ab);
            if (mass.applyAsInt(s) <= breite) {
                return s;
            }
        }
        return "…";
    }

    /** Der Ordner der Welt mit diesen Teilen unter {@code wurzel}. */
    static Path ordner(Path wurzel, Teile t) {
        Path p = t.adresse().isEmpty() ? wurzel : wurzel.resolve(t.adresse());
        if (!t.server().isEmpty()) {
            p = p.resolve(KARTE + t.server());
        }
        return p.resolve(WELT + t.hash());
    }

    /**
     * Was gegen den Umzug aus dem Ordner {@code welt} nach {@code neuWelt} spricht, als Schlüssel der Meldung, oder null.
     * {@code quelle} ist, was umzieht: bei „Ganzer Ordner“ {@code welt} selbst, sonst ein Baum darin; {@code ziel} der neue
     * Ordner oder der neue Baum. Dagegen sprechen: derselbe Ort; etwas am Ziel, denn zusammengeführt wird nie; bei „Ganzer
     * Ordner“ die Welt, in der der Spieler ist ({@code hier}); „Selbst“ zeichnet in eine der beiden Welten; Minimap und
     * Vollbildkarte zeigen einen Baum, der umzöge ({@code gezeigt}, oder null); ein Download in einen der Bäume ({@code belegt}).
     */
    static String grund(Path welt, Path quelle, Path neuWelt, Path ziel, Path hier, Predicate<Path> zeichnet, Path gezeigt,
            boolean belegt) {
        boolean ganz = quelle.equals(welt);
        if (neuWelt.equals(welt)) {
            return "heroicmap.ort.gleich";
        }
        if (Files.exists(ziel, LinkOption.NOFOLLOW_LINKS)) {
            return "heroicmap.ort.da";
        }
        if (ganz && (welt.equals(hier) || neuWelt.equals(hier))) {
            return "heroicmap.ort.hier";
        }
        if (zeichnet.test(welt) || zeichnet.test(neuWelt)) {
            return "heroicmap.ort.selbst";
        }
        // Die Anzeige läse sonst weiter aus den alten Pfaden.
        if (gezeigt != null && gezeigt.startsWith(quelle)) {
            return "heroicmap.ort.gezeigt";
        }
        return belegt ? "heroicmap.ort.belegt" : null;
    }

    /**
     * Zieht den Ordner der Welt {@code alt} nach {@code neu}, mit allem darin, als ein Umbenennen. Danach gehören eigene Karten
     * des alten Servers {@code werAlt} dem neuen {@code werNeu}: Name des Baums und {@code server} in {@code satz.json} neu,
     * ebenso seine Zeilen in {@code zeigt.txt}. Leere Ordner darüber bis {@code wurzel} gehen mit. Nie auf etwas, das schon da
     * ist.
     */
    static void zieheOrdner(Path wurzel, Path alt, Path neu, String werAlt, String werNeu) throws IOException {
        if (alt.equals(neu) || !Files.isDirectory(alt, LinkOption.NOFOLLOW_LINKS) || Files.exists(neu, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Ort: " + alt + " nach " + neu + " geht nicht");
        }
        List<Schritt> getan = new ArrayList<>();
        try {
            legeAn(neu.getParent(), getan);
            Files.move(alt, neu, StandardCopyOption.ATOMIC_MOVE);
            getan.add(() -> Files.move(neu, alt, StandardCopyOption.ATOMIC_MOVE));
            Map<String, String> namen = new HashMap<>();
            if (!werAlt.equals(werNeu)) {
                try (Stream<Path> baeume = Files.list(neu)) {
                    for (Path baum : baeume.toList()) {
                        if (Selbst.gehoert(baum, werAlt)) {
                            namen.put(baum.getFileName().toString(), uebergib(baum, werNeu, getan));
                        }
                    }
                }
            }
            schreibeZeigt(neu, werAlt, werNeu, namen, null, getan);
        } catch (IOException | RuntimeException e) {
            IOException fehler = e instanceof IOException io ? io : new IOException(e);
            zurueck(getan, fehler);
            throw fehler;
        }
        raeumeAuf(wurzel, alt.getParent());
    }

    /**
     * Zieht die Welt mit dem Ordnernamen {@code hash}, etwa {@code welt-<hash>}, aus den Ordnern {@code andere} anderer
     * Ablagen in den Ordner {@code ziel} der jetzigen: {@code welt-<hash>} und jedes {@code karte-<k>/welt-<hash>}, je ganz
     * oder gar nicht ({@link #zieheOrdner}). Nie auf Bestehendes, nicht aus einem Ordner, den {@code belegt} nennt, etwa für
     * einen Download. Gibt zurück, wohin umgezogen wurde. Siehe docs/download.md, „Ablage“.
     */
    static List<Path> zieheAblagen(Path wurzel, Path ziel, List<Path> andere, String hash, Predicate<Path> belegt) {
        List<Path> neu = new ArrayList<>();
        for (Path adresse : andere) {
            List<Path> welten = new ArrayList<>();
            welten.add(adresse.resolve(hash));
            try (Stream<Path> karten = Files.list(adresse)) {
                karten.filter(k -> k.getFileName().toString().startsWith(KARTE)).forEach(k -> welten.add(k.resolve(hash)));
            } catch (IOException e) {
                // Ohne Ordner nichts umzuziehen.
                continue;
            }
            for (Path alt : welten) {
                Path hin = ziel.resolve(adresse.relativize(alt));
                if (!Files.isDirectory(alt, LinkOption.NOFOLLOW_LINKS) || Files.exists(hin, LinkOption.NOFOLLOW_LINKS) || belegt.test(alt)) {
                    continue;
                }
                try {
                    // Derselbe Server: Eigene Karten und zeigt.txt bleiben, wie sie sind.
                    zieheOrdner(wurzel, alt, hin, Zeigt.OHNE, Zeigt.OHNE);
                    neu.add(hin);
                    LOGGER.info("Heroic Map: {} nach {} umgezogen, andere Ablage", alt, hin);
                } catch (IOException e) {
                    LOGGER.warn("Heroic Map: {} nicht nach {} umgezogen", alt, hin, e);
                }
            }
        }
        return neu;
    }

    /**
     * Die Bäume, die doppelt liegen: gleicher Name, gleicher Server ({@code karte-}) und gleicher Hash unter Ordnern, die
     * denselben Host meinen. Das sind {@code <host>} und {@code <host>_25565} von vor der Regel zum Standardport, und die
     * Ablage Hash ohne Adresse zu jeder Adresse. Je doppeltem Baum die anderen; Einzelspieler und die Ablage vor dem Hash
     * nie. Siehe docs/download.md, „Kartenliste“.
     */
    static Map<Path, List<Path>> doppelt(Path wurzel, List<Path> baeume) {
        Map<String, List<Path>> gruppen = new HashMap<>();
        for (Path b : baeume) {
            Teile t = teile(wurzel, b.getParent());
            if (t != null && !t.einzelspieler()) {
                gruppen.computeIfAbsent(t.server() + "/" + t.hash() + "/" + b.getFileName(), k -> new ArrayList<>()).add(b);
            }
        }
        Map<Path, List<Path>> aus = new HashMap<>();
        for (List<Path> gruppe : gruppen.values()) {
            for (Path a : gruppe) {
                for (Path b : gruppe) {
                    String ha = host(wurzel, a), hb = host(wurzel, b);
                    if (!a.equals(b) && (ha.isEmpty() || hb.isEmpty() || ha.equals(hb))) {
                        aus.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                    }
                }
            }
        }
        return aus;
    }

    /** Der Host des Ordners eines Baums, ohne {@code _25565}; leer bei der Ablage Hash. */
    private static String host(Path wurzel, Path baum) {
        String a = teile(wurzel, baum.getParent()).adresse();
        return a.endsWith(STANDARD) ? a.substring(0, a.length() - STANDARD.length()) : a;
    }

    /** Nutzt der Mod den Ordner dieses Baums nach {@code ablage}? Bei Hash der ohne Adresse, sonst der mit, ohne {@code _25565}. */
    static boolean genutzt(Path wurzel, Path baum, Downloads.Ablage ablage) {
        Teile t = teile(wurzel, baum.getParent());
        return t != null && (ablage == Downloads.Ablage.HASH ? t.adresse().isEmpty() : !t.adresse().isEmpty() && !t.adresse().endsWith(STANDARD));
    }

    /**
     * Nach „Ort …“ mit dem ganzen Ordner: Der Ausweis nennt den Server des neuen Orts, und das Netz ist unbekannt bis zum
     * nächsten Besuch, denn die eingetippte Adresse kennt nur der Login. Ohne Ausweis nichts. Siehe docs/download.md,
     * „Ausweis“.
     */
    static void ausweisNachUmzug(Path welt, Teile neu) throws IOException {
        Ausweis a = Ausweis.lies(welt);
        if (a != null) {
            a.mit("", neu.server()).schreibe(welt);
        }
    }

    /**
     * Zieht den Baum {@code baum} in den Ordner der Welt {@code neuWelt}, die dort {@code werNeu} gehört. Eine eigene Karte
     * gehört danach diesem Server. Die Zeilen des alten Ordners in {@code zeigt.txt}, die auf den Baum zeigen, fallen weg.
     * Nie auf einen Baum, der schon da ist.
     */
    static void zieheBaum(Path baum, Path neuWelt, String werNeu) throws IOException {
        Path altWelt = baum.getParent();
        String name = baum.getFileName().toString();
        boolean selbst = Selbst.selbst(baum);
        Satz satz = selbst ? Satz.lies(baum) : null;
        String neuName = selbst && satz != null ? Selbst.baum(satz.dimension(), werNeu) : name;
        Path ziel = neuWelt.resolve(neuName);
        if (altWelt.equals(neuWelt) || !Files.isDirectory(baum, LinkOption.NOFOLLOW_LINKS) || Files.exists(ziel, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Ort: " + baum + " nach " + ziel + " geht nicht");
        }
        List<Schritt> getan = new ArrayList<>();
        try {
            legeAn(neuWelt, getan);
            Files.move(baum, ziel, StandardCopyOption.ATOMIC_MOVE);
            getan.add(() -> Files.move(ziel, baum, StandardCopyOption.ATOMIC_MOVE));
            if (selbst && satz != null) {
                schreibeServer(ziel, werNeu, getan);
            }
            schreibeZeigt(altWelt, null, null, Map.of(), name, getan);
        } catch (IOException | RuntimeException e) {
            IOException fehler = e instanceof IOException io ? io : new IOException(e);
            zurueck(getan, fehler);
            throw fehler;
        }
    }

    /** Die eigene Karte {@code baum} gehört ab jetzt {@code wer}: neuer Name nach {@link Selbst#baum}, {@code server} neu. */
    private static String uebergib(Path baum, String wer, List<Schritt> getan) throws IOException {
        Satz satz = Satz.lies(baum);
        if (satz == null) {
            throw new IOException("Ort: " + baum + " ohne satz.json");
        }
        Path neu = baum.resolveSibling(Selbst.baum(satz.dimension(), wer));
        if (!neu.equals(baum)) {
            if (Files.exists(neu, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Ort: " + neu + " gibt es schon");
            }
            Files.move(baum, neu, StandardCopyOption.ATOMIC_MOVE);
            getan.add(() -> Files.move(neu, baum, StandardCopyOption.ATOMIC_MOVE));
        }
        schreibeServer(neu, wer, getan);
        return neu.getFileName().toString();
    }

    /** {@code server} in {@code satz.json} des Baums auf {@code wer}, ohne Namen ohne das Feld; die übrigen Felder bleiben. */
    private static void schreibeServer(Path baum, String wer, List<Schritt> getan) throws IOException {
        Path datei = baum.resolve("satz.json");
        byte[] vorher = Files.readAllBytes(datei);
        JsonObject json = JsonParser.parseString(new String(vorher, StandardCharsets.UTF_8)).getAsJsonObject();
        if (Zeigt.OHNE.equals(wer)) {
            json.remove("server");
        } else {
            json.addProperty("server", wer);
        }
        ersetze(datei, json.toString().getBytes(StandardCharsets.UTF_8), vorher, getan);
    }

    /**
     * Schreibt {@code zeigt.txt} im Ordner {@code welt} um: Zeilen von {@code werAlt} gelten für {@code werNeu}, umbenannte
     * Bäume heissen neu, Zeilen auf {@code weg} fallen weg. Ohne Datei nichts.
     */
    private static void schreibeZeigt(Path welt, String werAlt, String werNeu, Map<String, String> namen, String weg,
            List<Schritt> getan) throws IOException {
        Path datei = welt.resolve(Zeigt.DATEI);
        if (!Files.exists(datei, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        byte[] vorher = Files.readAllBytes(datei);
        List<String> neu = new ArrayList<>();
        for (String zeile : new String(vorher, StandardCharsets.UTF_8).split("\n")) {
            String[] t = zeile.strip().split(" ");
            if (t.length != 3) {
                if (!zeile.isBlank()) {
                    neu.add(zeile.strip());
                }
                continue;
            }
            if (t[2].equals(weg)) {
                continue;
            }
            String server = t[0].equals(werAlt) ? werNeu : t[0];
            neu.add(server + " " + t[1] + " " + namen.getOrDefault(t[2], t[2]));
        }
        ersetze(datei, (String.join("\n", neu) + (neu.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8), vorher, getan);
    }

    /** Ersetzt den Inhalt über eine Zwischendatei; zurück gibt den alten Inhalt wieder. */
    private static void ersetze(Path datei, byte[] neu, byte[] vorher, List<Schritt> getan) throws IOException {
        Path tmp = datei.resolveSibling(datei.getFileName() + ".tmp");
        Files.write(tmp, neu);
        Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        getan.add(() -> Files.write(datei, vorher));
    }

    /** Legt {@code ordner} samt fehlender Eltern an; zurück löscht, was neu war, von innen nach aussen. */
    private static void legeAn(Path ordner, List<Schritt> getan) throws IOException {
        List<Path> neu = new ArrayList<>();
        for (Path p = ordner; p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS); p = p.getParent()) {
            neu.add(p);
        }
        Files.createDirectories(ordner);
        for (Path p : neu.reversed()) {
            getan.add(() -> Files.deleteIfExists(p));
        }
    }

    /** Hebt die Schritte rückwärts auf; was dabei scheitert, hängt an {@code fehler}. */
    private static void zurueck(List<Schritt> getan, IOException fehler) {
        for (int i = getan.size() - 1; i >= 0; i--) {
            try {
                getan.get(i).zurueck();
            } catch (IOException | RuntimeException e) {
                fehler.addSuppressed(e);
            }
        }
    }

    /** Leere Ordner über dem alten Ort gehen mit, bis unter {@code wurzel}, wie beim Umzug zum Namen; scheitert es, bleiben sie. */
    private static void raeumeAuf(Path wurzel, Path ordner) {
        for (Path p = ordner; p != null && p.startsWith(wurzel) && !p.equals(wurzel); p = p.getParent()) {
            try (Stream<Path> rest = Files.list(p)) {
                if (rest.findAny().isPresent()) {
                    return;
                }
                Files.delete(p);
            } catch (IOException | RuntimeException e) {
                // Ein leerer Ordner stört nicht; die Kartenliste zählt ihn nicht.
                return;
            }
        }
    }
}
