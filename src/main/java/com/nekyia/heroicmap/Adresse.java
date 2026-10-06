package com.nekyia.heroicmap;

import java.net.Inet4Address;
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
        /** Kein http oder https, kein Host, oder Userinfo, Query oder Fragment. */
        SCHEMA,
        /** Das Ziel liegt im Heimnetz, der Spielserver nicht. */
        HEIMNETZ,
        /** Der Host lässt sich nicht auflösen. */
        UNBEKANNT
    }

    private Adresse() {
    }

    /** Nur http und https mit Host, ohne Userinfo, Query und Fragment; der Mod hängt Pfade an. */
    static boolean form(URI url) {
        String schema = url.getScheme();
        return schema != null && (schema.equalsIgnoreCase("http") || schema.equalsIgnoreCase("https"))
                && url.getHost() != null && !url.getHost().isEmpty() && url.getRawUserInfo() == null
                && url.getRawQuery() == null && url.getRawFragment() == null;
    }

    /**
     * Ein Ziel im Heimnetz nur, wenn auch die Verbindung zum Spielserver dorthin geht; sonst
     * könnte ein Server Anfragen in das Netz des Spielers lenken. {@code spielserver} ist die
     * Adresse der echten Verbindung, oder null. Löst den Namen auf, also nicht im Render-Thread.
     */
    static Urteil pruefe(URI url, InetAddress spielserver) {
        if (!form(url)) {
            return Urteil.SCHEMA;
        }
        try {
            boolean zielNah = nah(InetAddress.getAllByName(url.getHost()));
            boolean serverNah = spielserver != null && nah(new InetAddress[] {spielserver});
            return zielNah && !serverNah ? Urteil.HEIMNETZ : Urteil.GUT;
        } catch (UnknownHostException e) {
            return Urteil.UNBEKANNT;
        }
    }

    /** Liegt eine der Adressen in loopback, link-local, einem privaten Netz, CGNAT, 0.0.0.0/8 oder fc00::/7? */
    static boolean nah(InetAddress[] adressen) {
        for (InetAddress a : adressen) {
            byte[] b = a.getAddress();
            boolean eindeutigLokal = a instanceof Inet6Address && (b[0] & 0xFE) == 0xFC;
            boolean v4 = a instanceof Inet4Address;
            boolean cgnat = v4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
            if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                    || eindeutigLokal || cgnat || (v4 && b[0] == 0)) {
                return true;
            }
        }
        return false;
    }
}
