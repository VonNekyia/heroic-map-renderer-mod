package com.nekyia.heroicmap;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Prüft die Adresse aus einer {@code freigabe}, vor jedem Abruf.
 * Siehe docs/download.md, „Sicherheit“.
 */
final class Adresse {

    enum Urteil {
        GUT,
        /** Kein http oder https, kein Host, oder Userinfo, Query oder Fragment. */
        SCHEMA,
        /** Ein Ziel im Heimnetz, oder bei einem Spielserver im Heimnetz ein anderes als er selbst. */
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
     * Liegt der Spielserver fern, darf keine Adresse des Ziels im Heimnetz liegen. Liegt er nah,
     * darf das Ziel nur er selbst sein; sonst könnte ein Server Anfragen in das Netz des Spielers
     * lenken. {@code spielserver} ist die Adresse der echten Verbindung, oder null. Löst den
     * Namen auf, also nicht im Render-Thread.
     */
    static Urteil pruefe(URI url, InetAddress spielserver) {
        if (!form(url)) {
            return Urteil.SCHEMA;
        }
        InetAddress[] ziele;
        try {
            ziele = InetAddress.getAllByName(url.getHost());
        } catch (UnknownHostException e) {
            return Urteil.UNBEKANNT;
        }
        boolean serverNah = spielserver != null && nah(spielserver);
        for (InetAddress ziel : ziele) {
            if (serverNah ? !gleich(ziel, spielserver) : nah(ziel)) {
                return Urteil.HEIMNETZ;
            }
        }
        return Urteil.GUT;
    }

    /** Dieselbe Adresse, oder beide loopback, also derselbe Rechner. */
    static boolean gleich(InetAddress a, InetAddress b) {
        InetAddress x = v4(a), y = v4(b);
        return x.equals(y) || (x.isLoopbackAddress() && y.isLoopbackAddress());
    }

    /** Liegt die Adresse in loopback, link-local, einem privaten Netz, CGNAT, 0.0.0.0/8 oder fc00::/7? */
    static boolean nah(InetAddress adresse) {
        InetAddress a = v4(adresse);
        byte[] b = a.getAddress();
        boolean eindeutigLokal = a instanceof Inet6Address && (b[0] & 0xFE) == 0xFC;
        boolean v4 = a instanceof Inet4Address;
        boolean cgnat = v4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
        return a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                || eindeutigLokal || cgnat || (v4 && b[0] == 0);
    }

    /** IPv4 in IPv6 ({@code ::ffff:0:0/96}) als IPv4, sonst die Adresse selbst; aus DNS bleibt sie sonst IPv6. */
    static InetAddress v4(InetAddress a) {
        byte[] b = a.getAddress();
        if (!(a instanceof Inet6Address) || b[10] != (byte) 0xFF || b[11] != (byte) 0xFF) {
            return a;
        }
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return a;
            }
        }
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16));
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
