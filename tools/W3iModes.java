import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;

/**
 * Переводит war3map.w3i карты в формат Reforged (версия 31+) и выставляет «поддерживаемые режимы графики» = только SD
 * (классическая): игра тогда не даёт включить HD/Reforged-графику на этой карте. Версия 25 (TFT) такого поля не имеет,
 * а Wurst пишет w3i в исходной версии, поэтому это делается после сборки (build.cmd, после Localize).
 *
 *   java -cp "tools;%USERPROFILE%\.wurst\wurst-compiler\wurstscript.jar" W3iModes _build\TheWarReborn.w3x [sd|hd|both]
 *   java -cp tools W3iModes --dump war3map.w3i          -- распечатать поля (для проверки раскладки)
 */
public class W3iModes {
    static final int TARGET_VERSION = 31;
    static final int[] GAME_VERSION = {1, 32, 0, 0};   // major, minor, patch, build — минимум для формата 31

    public static void main(String[] a) throws Exception {
        if (a[0].equals("--dump")) { dump(Files.readAllBytes(Paths.get(a[1]))); return; }
        Path map = Paths.get(a[0]);
        int modes = a.length > 1 && a[1].equals("hd") ? 2 : a.length > 1 && a[1].equals("both") ? 3 : 1;
        Class<?> A = Class.forName("org.inwc3.jmpq.MpqArchive");
        Object ar = A.getMethod("open", Path.class).invoke(null, map);
        byte[] in = (byte[]) A.getMethod("read", String.class).invoke(ar, "war3map.w3i");
        byte[] out = convert(in, modes);
        Class<?> W = Class.forName("org.inwc3.jmpq.MpqArchiveWriter"), O = Class.forName("org.inwc3.jmpq.MpqWriteOptions");
        Object opts = O.getMethod("recompressed").invoke(null);
        opts = O.getMethod("withPrefix", boolean.class).invoke(opts, true);
        opts = O.getMethod("withListfile", boolean.class).invoke(opts, true);
        Object w = W.getMethod("from", A, O).invoke(null, ar, opts);
        W.getMethod("remove", String.class, short.class).invoke(w, "war3map.w3i", (short) 0);
        W.getMethod("put", String.class, short.class, byte[].class).invoke(w, "war3map.w3i", (short) 0, out);
        Path tmp = map.resolveSibling(map.getFileName() + ".w3i.tmp");
        W.getMethod("save", Path.class).invoke(w, tmp);
        ((AutoCloseable) ar).close();
        Files.move(tmp, map, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("w3i: version " + le(in, 0) + " -> " + TARGET_VERSION + ", supported modes = " + (modes == 1 ? "SD" : modes == 2 ? "HD" : "SD+HD") + " -> " + map);
    }

    /** Читает w3i версии 25/28/31/33 и пишет версию 31 с нужными режимами; остальные поля переносит как есть. */
    static byte[] convert(byte[] d, int modes) {
        R r = new R(d);
        Wr w = new Wr();
        int version = r.i();
        if (version != 25 && version != 28 && version != 31 && version != 33) throw new RuntimeException("unsupported w3i version " + version);
        w.i(TARGET_VERSION);
        w.i(r.i()); w.i(r.i());                                   // map version, editor version
        if (version >= 28) { for (int k = 0; k < 4; k++) w.i(r.i()); } else for (int v : GAME_VERSION) w.i(v);
        for (int k = 0; k < 4; k++) w.s(r.s());                  // name, author, description, suggested players
        w.copy(r, 8 * 4 + 4 * 4 + 2 * 4);                          // camera bounds, complements, playable size
        w.i(r.i());                                               // flags
        w.b(r.b());                                               // tileset
        w.i(r.i());                                               // loading screen number
        for (int k = 0; k < 4; k++) w.s(r.s());                  // loading screen model, text, title, subtitle
        w.i(r.i());                                               // game data set
        for (int k = 0; k < 4; k++) w.s(r.s());                  // prologue model, text, title, subtitle
        w.copy(r, 4 + 3 * 4 + 4);                                 // fog style, start z, end z, density, color
        w.i(r.i());                                               // weather id
        w.s(r.s());                                               // sound environment
        w.b(r.b());                                               // tileset light
        w.copy(r, 4);                                             // water color
        w.i(version >= 28 ? r.i() : 0);                           // script mode: 0 = JASS
        int gameDataVersion = 1;                                  // 1 = TFT
        if (version >= 31) { r.i(); gameDataVersion = r.i(); }
        w.i(modes); w.i(gameDataVersion);
        int players = r.i(); w.i(players);
        for (int p = 0; p < players; p++) {
            w.i(r.i()); w.i(r.i()); w.i(r.i()); w.i(r.i());       // id, type, race, fixed start
            w.s(r.s());                                           // name
            w.copy(r, 4 * 4);                                     // start x, y, ally low, ally high
            if (version >= 31) w.copy(r, 8); else { w.i(0); w.i(0); }   // enemy low, high
        }
        w.copy(r, d.length - r.p);                                // forces, upgrades, techs, random tables — без изменений
        return w.bytes();
    }

    static void dump(byte[] d) {
        R r = new R(d);
        int version = r.i();
        System.out.println("version " + version + ", map version " + r.i() + ", editor " + r.i());
        if (version >= 28) System.out.println("game version " + r.i() + "." + r.i() + "." + r.i() + "." + r.i());
        System.out.println("name [" + r.s() + "] author [" + r.s() + "]"); r.s(); System.out.println("players [" + r.s() + "]");
        r.p += 8 * 4 + 4 * 4 + 2 * 4;
        System.out.println("flags 0x" + Integer.toHexString(r.i()) + " tileset " + (char) r.b());
        r.i(); for (int k = 0; k < 4; k++) r.s(); r.i(); for (int k = 0; k < 4; k++) r.s();
        r.p += 4 + 3 * 4 + 4; r.i(); r.s(); r.b(); r.p += 4;
        if (version >= 28) System.out.println("script mode " + r.i());
        if (version >= 31) System.out.println("supported modes " + r.i() + ", game data version " + r.i());
        System.out.println("players " + r.i() + " at offset " + (r.p - 4) + " of " + d.length);
    }

    static class R {
        byte[] d; int p;
        R(byte[] d) { this.d = d; }
        int i() { int v = le(d, p); p += 4; return v; }
        byte b() { return d[p++]; }
        String s() { int e = p; while (d[e] != 0) e++; String v = new String(d, p, e - p, StandardCharsets.UTF_8); p = e + 1; return v; }
    }

    static class Wr {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        void i(int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
        void b(byte v) { o.write(v); }
        void s(String v) { byte[] b = v.getBytes(StandardCharsets.UTF_8); o.write(b, 0, b.length); o.write(0); }
        void copy(R r, int n) { o.write(r.d, r.p, n); r.p += n; }
        byte[] bytes() { return o.toByteArray(); }
    }

    static int le(byte[] d, int p) { return (d[p] & 255) | (d[p + 1] & 255) << 8 | (d[p + 2] & 255) << 16 | (d[p + 3] & 255) << 24; }
}
