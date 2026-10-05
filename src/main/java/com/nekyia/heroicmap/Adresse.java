package com.nekyia.heroicmap;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Prüft die Adresse aus einer {@code freigabe}, bevor der Mod sie anfragt.
 * Siehe docs/download.md, „Sicherheit“.
 */
final class Adresse {

    enum Urteil {
        GUT,
        /** Kein http oder https, oder kein Host. */
        SCHEMA,
        /** Das Ziel liegt im Heimnetz, der Spielserver nicht. */
        HEIMNETZ,
        /** Der Host lässt sich nicht auflösen. */
        UNBEKANNT
    }

    private Adresse() {
    }

    /**
     * Nur http und https. Ein Ziel im Heimnetz nur, wenn auch der Spielserver dort liegt;
     * sonst könnte ein Server Anfragen in das Netz des Spielers lenken.
     */
    static Urteil pruefe(URI url, String spielserverHost) {
        String schema = url.getScheme();
        if (schema == null || !(schema.equalsIgnoreCase("http") || schema.equalsIgnoreCase("https"))
                || url.getHost() == null || url.getHost().isEmpty()) {
            return Urteil.SCHEMA;
        }
        try {
            boolean zielNah = nah(InetAddress.getAllByName(url.getHost()));
            boolean serverNah = nah(InetAddress.getAllByName(spielserverHost));
            return zielNah && !serverNah ? Urteil.HEIMNETZ : Urteil.GUT;
        } catch (UnknownHostException e) {
            return Urteil.UNBEKANNT;
        }
    }

    /** Liegt eine der Adressen in loopback, link-local, einem privaten Netz oder fc00::/7? */
    static boolean nah(InetAddress[] adressen) {
        for (InetAddress a : adressen) {
            boolean eindeutigLokal = a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;
            if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                    || eindeutigLokal) {
                return true;
            }
        }
        return false;
    }
}
