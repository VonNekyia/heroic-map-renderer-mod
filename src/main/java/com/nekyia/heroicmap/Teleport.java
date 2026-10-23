package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * „Hierher teleportieren“ auf der Vollbildkarte: der Befehl, woher die Höhe kommt, und die Korrektur nach dem Landen.
 * Der Mod umgeht keine Rechte; die prüft der Server. Siehe docs/vollbildkarte.md, „Teleportieren“.
 */
final class Teleport {

    /** Ohne Block oder ohne Chunk steht dieser Wert in Boden und Höhen der Karte. */
    static final short OHNE = Short.MIN_VALUE;
    /** So gross ist die Datei einer Region höchstens, gepackt; ungepackt hat der Boden genau 512 KiB, die Höhen 32 KiB. */
    static final int MAX_GEPACKT = 1 << 20;
    /** So lange, 10 s, wartet ein Teleport mit Höhe höchstens auf Ankunft und Chunk. */
    static final int FRIST_TICKS = 200;
    /** So lange, 5 s, schwebt ein Teleport ohne Höhe höchstens, dann geht der Spieler zurück an den Start. */
    static final int SCHWEBEN_TICKS = 100;
    /** Alle so viele Ticks geht ein schwebender Teleport zurück auf die sichere Höhe; jedes {@code tp} setzt die Geschwindigkeit auf 0. */
    static final int NACH_OBEN_TICKS = 10;
    /** Bis {@code DA} Blöcke waagrecht vom Ziel ist der Spieler dort; nach der Ankunft weiter als {@code WEG} ist er weggegangen. */
    static final double DA = 1.5, WEG = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);

    /** Der offene Teleport aus dem Menü, oder null; nur im Thread des Clients. Ein neuer ersetzt ihn. */
    private static Offen offen;

    /** Was ein Tick mit dem offenen Teleport tut. */
    enum Schritt { WARTEN, NACH_OBEN, PRUEFEN, AUFGEBEN, ENDE }

    /**
     * Ein Teleport, bis er sitzt: Ziel, Start und Höhe des Spielers beim Klick, ob er ohne Höhe auf der sicheren Höhe
     * schwebt, ob der Spieler schon dort war.
     */
    private static final class Offen {
        final ClientPacketListener verbindung;
        final ClientLevel level;
        final int x, z, nahe, sicher;
        final double startX, startY, startZ;
        final boolean schwebt;
        boolean da;
        int ticks;

        Offen(ClientPacketListener verbindung, ClientLevel level, LocalPlayer p, int x, int z, boolean schwebt) {
            this.verbindung = verbindung;
            this.level = level;
            this.x = x;
            this.z = z;
            this.nahe = Mth.floor(p.getY());
            this.sicher = sicher(level.dimensionType().hasCeiling(), nahe, level.getSeaLevel(), level.getMinY(), level.getMaxY());
            this.startX = p.getX();
            this.startY = p.getY();
            this.startZ = p.getZ();
            this.schwebt = schwebt;
        }
    }

    /** Woher die Höhen einer Karte kommen: Ordner unter {@code /tiles}, Kante einer Zelle in Blöcken, unterster und oberster Block. */
    record Quelle(String ordner, int zelle, int minY, int maxY) {

        /** Werte je Seite einer Region. */
        int seite() {
            return 512 / zelle;
        }
    }

    private Teleport() {
    }

    /**
     * Auf (x, y, z) in der Dimension des Spielers, in der Mitte des Blocks: fest, auch in Chunks, die weder Client noch
     * Server geladen haben. Der normale {@code tp}, ohne {@code execute}; siehe docs/vollbildkarte.md, „Teleportieren“.
     */
    static String befehl(int x, int y, int z) {
        return String.format(Locale.ROOT, "tp %.1f %d %.1f", x + 0.5, y, z + 0.5);
    }

    /** Zurück auf (x, y, z), genau dorthin, wo der Spieler beim Klick stand. */
    static String befehl(double x, double y, double z) {
        return String.format(Locale.ROOT, "tp %.3f %.3f %.3f", x, y, z);
    }

    /**
     * Teleportiert nach (x, z) in {@code dimension}, der des Spielers. Hat der Client den Chunk, nimmt er die Höhe
     * daraus ({@link #frei(ClientLevel, LevelChunk, int, int, int)}); sonst aus Boden oder Höhen der Karte, eine Region
     * per HTTP; ohne beides auf die sichere Höhe ({@link #sicher}). Danach prüft {@link #tick} die Landung.
     */
    static void los(Minecraft mc, Satz satz, String dimension, int x, int z) {
        ClientPacketListener verbindung = mc.getConnection();
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        offen = null;
        LevelChunk chunk = chunk(level, x, z);
        Integer frei = chunk == null ? null : frei(level, chunk, x, z, Mth.floor(p.getY()));
        if (frei != null) {
            sende(new Offen(verbindung, level, p, x, z, false), befehl(x, frei, z));
            return;
        }
        Quelle q = satz != null && dimension.equals(satz.dimension()) ? quelle(satz) : null;
        URI uri = q == null ? null : uri(Symbole.INSTANZ.basis(), q, x, z);
        if (uri == null) {
            schwebe(new Offen(verbindung, level, p, x, z, true));
            return;
        }
        InetAddress server = Symbole.INSTANZ.spielserver();
        CompletableFuture.supplyAsync(() -> hole(Symbole.CLIENT, uri, server, Symbole.FRIST, q, x, z)).thenAccept(y -> mc.execute(() -> {
            if (mc.getConnection() != verbindung || mc.level != level || mc.player != p) {
                return;
            }
            if (y != null) {
                sende(new Offen(verbindung, level, p, x, z, false), befehl(x, y, z));
            } else {
                schwebe(new Offen(verbindung, level, p, x, z, true));
            }
        }));
    }

    /** Ohne Höhe auf die sichere Höhe in der Luft; sobald der Client den Chunk hat, setzt {@link #tick} den Spieler auf den Boden. */
    private static void schwebe(Offen o) {
        sende(o, befehl(o.x, o.sicher, o.z));
    }

    private static void sende(Offen o, String befehl) {
        offen = o;
        o.verbindung.sendCommand(befehl);
    }

    /**
     * Je Tick des Clients: Ist der Spieler da und der Chunk beim Client, einmal prüfen und wenn nötig ein zweiter
     * {@code tp} auf den freien Platz. Ohne Höhe bis dahin alle {@link #NACH_OBEN_TICKS} zurück auf die sichere Höhe und
     * nach {@link #SCHWEBEN_TICKS}, oder ohne Platz zum Stehen, zurück an den Start. Siehe docs/vollbildkarte.md,
     * „Teleportieren“.
     */
    static void tick(Minecraft mc) {
        Offen o = offen;
        LocalPlayer p = mc.player;
        if (o == null) {
            return;
        }
        if (p == null || mc.getConnection() != o.verbindung || mc.level != o.level) {
            offen = null;
            return;
        }
        double weg = Math.hypot(p.getX() - (o.x + 0.5), p.getZ() - (o.z + 0.5));
        LevelChunk chunk = chunk(o.level, o.x, o.z);
        Schritt schritt = schritt(++o.ticks, o.schwebt, o.da, weg, chunk != null);
        o.da |= weg <= DA;
        switch (schritt) {
            case ENDE -> offen = null;
            case NACH_OBEN -> o.verbindung.sendCommand(befehl(o.x, o.sicher, o.z));
            case AUFGEBEN -> zurueck(o, p, "heroicmap.teleport.frist");
            case PRUEFEN -> {
                offen = null;
                Integer frei = frei(o.level, chunk, o.x, o.z, o.nahe);
                if (frei == null) {
                    if (o.schwebt) {
                        zurueck(o, p, "heroicmap.teleport.kein_platz");
                    }
                    return;
                }
                boolean imBlock = !o.level.noBlockCollision(p, p.getBoundingBox().deflate(1.0E-3));
                boolean ueberDecke = o.level.dimensionType().hasCeiling() && p.getY() >= o.level.getMinY() + o.level.dimensionType().logicalHeight();
                if (korrigieren(p.getY(), frei, imBlock, ueberDecke)) {
                    o.verbindung.sendCommand(befehl(o.x, frei, o.z));
                }
            }
            case WARTEN -> {
            }
        }
    }

    /**
     * Was der Tick {@code ticks} nach dem Befehl tut, {@code weg} Blöcke waagrecht vom Ziel: nach der Ankunft weggegangen
     * aufhören; am Ziel mit dem Chunk beim Client prüfen. Ohne Höhe ({@code schwebt}) nach {@link #SCHWEBEN_TICKS}
     * aufgeben, sonst alle {@link #NACH_OBEN_TICKS} zurück nach oben; mit Höhe nach {@link #FRIST_TICKS} aufhören.
     */
    static Schritt schritt(int ticks, boolean schwebt, boolean warDa, double weg, boolean chunk) {
        if (warDa && weg > WEG || !schwebt && ticks > FRIST_TICKS) {
            return Schritt.ENDE;
        }
        if (weg <= DA && chunk) {
            return Schritt.PRUEFEN;
        }
        if (!schwebt) {
            return Schritt.WARTEN;
        }
        if (ticks >= SCHWEBEN_TICKS) {
            return Schritt.AUFGEBEN;
        }
        return ticks % NACH_OBEN_TICKS == 0 ? Schritt.NACH_OBEN : Schritt.WARTEN;
    }

    /** Gibt auf: zurück an den Start, mit einer Zeile im Chat. */
    private static void zurueck(Offen o, LocalPlayer p, String grund) {
        offen = null;
        o.verbindung.sendCommand(befehl(o.startX, o.startY, o.startZ));
        p.sendSystemMessage(Component.translatable(grund));
    }

    /**
     * Die Höhe der Füsse ohne Daten, {@code oben} der oberste Block der Welt: ohne Decke zwei darunter, in der Luft und nie
     * im Block; mit Decke die Höhe des Spielers, mindestens der Meeresspiegel, in der Welt, denn oben läge das Dach.
     */
    static int sicher(boolean decke, int spieler, int meer, int unten, int oben) {
        return decke ? Mth.clamp(Math.max(spieler, meer), unten, oben - 1) : oben - 2;
    }

    /**
     * Ob der Spieler mit den Füssen auf {@code y} neu muss: in einem Block, über der Decke, mehr als 3 Blöcke über dem
     * freien Platz {@code frei}, dann kostet der Fall Schaden, oder mehr als 1 darunter.
     */
    static boolean korrigieren(double y, int frei, boolean imBlock, boolean ueberDecke) {
        return imBlock || ueberDecke || y > frei + 3 || y < frei - 1;
    }

    /**
     * Die Höhe der Füsse am freien Platz über (x, z) im Chunk des Clients, oder null. Ohne Decke der erste freie Block über
     * dem obersten, der Bewegung oder Flüssigkeit hält, ohne Laub, über Wasser also die Oberfläche; mit Decke der Platz
     * unter ihr, der {@code nahe} am nächsten liegt, siehe {@link #frei(boolean[], boolean[], int, int)}.
     */
    static Integer frei(ClientLevel level, LevelChunk chunk, int x, int z, int nahe) {
        int unten = level.getMinY();
        if (!level.dimensionType().hasCeiling()) {
            // getHeight nennt den obersten Block, in einer leeren Säule unten - 1.
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
            return y > unten ? y : null;
        }
        int n = Math.min(level.dimensionType().logicalHeight(), level.getHeight());
        boolean[] fest = new boolean[n], leer = new boolean[n];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            BlockState s = chunk.getBlockState(pos.set(x, unten + i, z));
            boolean kollision = !s.getCollisionShape(level, pos).isEmpty(), fluessig = !s.getFluidState().isEmpty();
            fest[i] = kollision && !fluessig;
            leer[i] = !kollision && !fluessig;
        }
        return frei(fest, leer, unten, nahe);
    }

    /**
     * Die Höhe der Füsse am freien Platz, der {@code nahe} am nächsten liegt, bei gleichem Abstand der untere: darunter
     * ein Block mit Kollision ohne Flüssigkeit, Füsse und Kopf ohne beides. Index 0 ist die Höhe {@code unten}, die
     * Felder enden unter der Decke. Null ohne Platz.
     */
    static Integer frei(boolean[] fest, boolean[] leer, int unten, int nahe) {
        Integer best = null;
        for (int i = 1; i + 1 < fest.length; i++) {
            if (fest[i - 1] && leer[i] && leer[i + 1] && (best == null || Math.abs(unten + i - nahe) < Math.abs(best - nahe))) {
                best = unten + i;
            }
        }
        return best;
    }

    /** Der Chunk um (x, z), wenn der Client ihn hat, sonst null; {@code ClientLevel.hasChunk} sagt immer ja (per javap). */
    static LevelChunk chunk(ClientLevel level, int x, int z) {
        return level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
    }

    /** Die Quelle aus {@code map.json} des Satzes, oder null; siehe {@link #quelle(JsonObject)}. */
    static Quelle quelle(Satz satz) {
        try {
            return quelle(JsonParser.parseString(Files.readString(satz.ordner().resolve("map.json"), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Der Boden ohne Laub je Block, sonst die Höhen je 4 × 4, wie {@code map.json} sie nennt; nur in der Ablage einer
     * Wurzel ({@code ../ground/{x}.{z}.bin}), so liegen sie unter {@code /tiles/ground/}. Null ohne beides oder ohne
     * {@code minY} und {@code maxY}.
     */
    static Quelle quelle(JsonObject karte) {
        if (!karte.has("minY") || !karte.has("maxY")) {
            return null;
        }
        int min = karte.get("minY").getAsInt(), max = karte.get("maxY").getAsInt();
        if (muster(karte, "ground", "groundCell", 1)) {
            return new Quelle("ground", 1, min, max);
        }
        return muster(karte, "heights", "heightsCell", 4) ? new Quelle("heights", 4, min, max) : null;
    }

    private static boolean muster(JsonObject karte, String feld, String zelle, int kante) {
        return karte.has(feld) && ("../" + feld + "/{x}.{z}.bin").equals(karte.get(feld).getAsString())
                && karte.has(zelle) && karte.get(zelle).getAsInt() == kante;
    }

    /** Die Datei der Region um (x, z): {@code <basis>/<ordner>/<rx>.<rz>.bin}; null ohne Basis. */
    static URI uri(URI basis, Quelle q, int x, int z) {
        if (basis == null) {
            return null;
        }
        String b = basis.toString();
        return URI.create((b.endsWith("/") ? b : b + "/") + q.ordner() + "/" + Math.floorDiv(x, 512) + "." + Math.floorDiv(z, 512) + ".bin");
    }

    /**
     * Holt die Datei der Region wie ein Bild einer Ebene, Adresse geprüft, ohne Weiterleitung, höchstens
     * {@link #MAX_GEPACKT} Byte in {@code frist}, und gibt die Höhe zum Stehen über (x, z); null bei jedem Fehler.
     */
    static Integer hole(HttpClient client, URI uri, InetAddress spielserver, Duration frist, Quelle q, int x, int z) {
        try {
            Adresse.Urteil urteil = Adresse.pruefe(uri, spielserver);
            if (urteil != Adresse.Urteil.GUT) {
                throw new IOException(urteil.name());
            }
            HttpRequest anfrage = HttpRequest.newBuilder(uri).timeout(frist).GET().build();
            HttpResponse<byte[]> antwort = Laden.sende(client, anfrage, MAX_GEPACKT, Laden.Grund.NETZ, frist, uri.getPath());
            if (antwort.statusCode() != 200) {
                throw new IOException("HTTP " + antwort.statusCode());
            }
            return stehen(entpacke(antwort.body(), 2 * q.seite() * q.seite()), q, x, z);
        } catch (IOException | Laden.Fehler | RuntimeException e) {
            LOGGER.warn("Heroic Map: Höhe für den Teleport aus {} nicht geladen: {}", uri, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Entpackt den zlib-Strom auf genau {@code laenge} Byte; ein kürzerer oder längerer ist ein Fehler, so packt keine Datei mehr aus. */
    static byte[] entpacke(byte[] gepackt, int laenge) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(gepackt);
            byte[] aus = new byte[laenge];
            int n = 0;
            while (n < laenge && !inflater.finished()) {
                int k = inflater.inflate(aus, n, laenge - n);
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += k;
            }
            if (n != laenge || !inflater.finished() && inflater.inflate(new byte[1]) > 0) {
                throw new IOException("nicht " + laenge + " Byte");
            }
            return aus;
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    /**
     * Die Höhe zum Stehen über (x, z) aus den entpackten Werten der Region: i16 little-endian, zeilenweise nach z, der
     * oberste Block, also plus 1; null ohne Wert oder ausserhalb von {@code minY} bis {@code maxY}.
     */
    static Integer stehen(byte[] werte, Quelle q, int x, int z) {
        int i = 2 * (Math.floorMod(z, 512) / q.zelle() * q.seite() + Math.floorMod(x, 512) / q.zelle());
        short w = (short) ((werte[i] & 0xFF) | (werte[i + 1] << 8));
        return w == OHNE || w < q.minY() || w > q.maxY() ? null : w + 1;
    }
}
