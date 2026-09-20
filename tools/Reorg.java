import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;

/** Reorganise loose imports of a WC3 folder-map into models/ textures/ icons/ sounds/ ui/ and rewrite all references. */
public class Reorg {
    static final Set<String> KEEP_ROOT = Set.of("war3map.doo","war3map.imp","war3map.j","war3map.mmp","war3map.shd","war3map.w3a","war3map.w3b","war3map.w3c","war3map.w3d","war3map.w3e","war3map.w3h","war3map.w3i","war3map.w3q","war3map.w3r","war3map.w3t","war3map.w3u","war3map.wct","war3map.wpm","war3map.wtg","war3map.wts","war3mapmap.blp","war3mapmisc.txt","war3mappreview.tga","war3mapskin.txt","war3mapunits.doo");
    static final Set<String> SCAN_EXT = Set.of("j","wct","wtg","wts","w3i","txt","w3u","w3a","w3t","w3b","w3d","w3h","w3q","mdx");
    static final Set<String> UI = Set.of("loadingscreen.mdx","loadingscreen512x512.blp","1436792472_1czuqu4dvt711436217116016512x512.blp","fullscreen.blp");

    static Path root; static boolean apply;
    static Map<String,String> moves = new LinkedHashMap<>();   // oldRel(original case, '\') -> newRel
    static Map<String,String> lookup = new HashMap<>();        // lowercased key -> newRel
    static List<String> log = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        root = Paths.get(a[0]); apply = a.length > 1 && a[1].equals("--apply");
        planMoves();
        System.out.println("== planned moves: " + moves.size());
        Map<String,Integer> total = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String rel = root.relativize(p).toString().replace('/', '\\');
                String ext = ext(rel);
                if (!SCAN_EXT.contains(ext)) continue;
                if (rel.equalsIgnoreCase("war3map.imp")) continue;
                int n = rewrite(p, rel, ext);
                if (n > 0) total.put(rel, n);
            }
        }
        System.out.println("== replacements per file:"); total.forEach((k,v) -> System.out.println("  " + v + "\t" + k));
        System.out.println("== notes:"); log.forEach(l -> System.out.println("  " + l));
        if (apply) { doMoves(); writeImp(); System.out.println("== APPLIED"); }
    }

    static String ext(String n) { int i = n.lastIndexOf('.'); return i < 0 ? "" : n.substring(i+1).toLowerCase(); }
    static String base(String n) { int i = n.lastIndexOf('\\'); return i < 0 ? n : n.substring(i+1); }

    static String target(String base) {
        String l = base.toLowerCase(), e = ext(base);
        if (UI.contains(l)) return "ui\\" + base;
        if (e.equals("mdx") || e.equals("mdl")) return "models\\" + base;
        if (e.equals("mp3") || e.equals("wav")) return "sounds\\" + base;
        if (l.startsWith("btn") || l.startsWith("pas") || l.startsWith("disbtn")) return "icons\\" + base;
        return "textures\\" + base;
    }

    static void planMoves() throws Exception {
        List<String> olds = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) { for (Path p : s.collect(Collectors.toList())) {
            String n = p.getFileName().toString();
            if (Files.isRegularFile(p) && !KEEP_ROOT.contains(n.toLowerCase())) olds.add(n);
        } }
        Path imp = root.resolve("war3mapImported");
        if (Files.isDirectory(imp)) try (Stream<Path> s = Files.walk(imp)) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) olds.add(root.relativize(p).toString().replace('/', '\\'));
        }
        Map<String,String> used = new HashMap<>(); // newRel lower -> oldRel that claimed it
        for (String o : olds) {
            String t = target(base(o));
            String prev = used.get(t.toLowerCase());
            if (prev != null) {
                if (md5(root.resolve(prev)).equals(md5(root.resolve(o)))) { log.add("MERGE identical: " + o + " == " + prev + " -> " + t); }
                else { String e = ext(t); t = t.substring(0, t.length()-e.length()-1) + "2." + e; log.add("RENAME collision: " + o + " -> " + t); }
            }
            used.putIfAbsent(t.toLowerCase(), o);
            moves.put(o, t);
            lookup.put(o.toLowerCase(), t);
            if (ext(o).equals("mdx")) lookup.put(o.toLowerCase().replaceAll("\\.mdx$", ".mdl"), t.replaceAll("\\.mdx$", ".mdl"));
        }
        // warn about suffix ambiguity (name A ends with " "+B)
        for (String x : lookup.keySet()) for (String y : lookup.keySet()) if (!x.equals(y) && base(x).endsWith(" " + base(y))) log.add("WARN suffix: '" + y + "' is a suffix of '" + x + "'");
    }

    /** Fallback for broken references: unique move whose file name matches. */
    static String byBase(String b) {
        List<String> c = lookup.entrySet().stream().filter(e -> base(e.getKey()).equals(b)).map(Map.Entry::getValue).distinct().collect(Collectors.toList());
        return c.size() == 1 ? c.get(0) : null;
    }

    static String md5(Path p) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(Files.readAllBytes(p)); StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b)); return sb.toString();
    }

    static boolean pathChar(int c) { return c < 128 && Character.isLetterOrDigit(c) || c=='_' || c=='-' || c=='.' || c=='\\' || c=='/'; }

    /** Rewrite references in one file. Returns number of replacements. */
    static int rewrite(Path p, String rel, String ext) throws Exception {
        byte[] data = Files.readAllBytes(p);
        String s = new String(data, StandardCharsets.ISO_8859_1);
        String sl = s.toLowerCase();
        boolean jass = ext.equals("j") || ext.equals("wct");
        boolean fixed = ext.equals("mdx");
        // collect hits: [start, end, replacement]
        List<int[]> hits = new ArrayList<>(); List<String> reps = new ArrayList<>();
        Set<String> bases = lookup.keySet().stream().map(Reorg::base).collect(Collectors.toSet());
        for (String b : bases) {
            int from = 0;
            while ((from = sl.indexOf(b, from)) >= 0) {
                int st = from, en = from + b.length(); from = en;
                if (en < sl.length() && pathChar(sl.charAt(en)) && sl.charAt(en) != '\\' && sl.charAt(en) != '/') continue; // longer name
                String sep; String key;
                if (st >= 17 && sl.startsWith("war3mapimported\\\\", st-17)) { sep = "\\\\"; key = "war3mapimported\\" + b; st -= 17; }
                else if (st >= 16 && (sl.startsWith("war3mapimported\\", st-16) || sl.startsWith("war3mapimported/", st-16))) { sep = sl.charAt(st-1)=='/' ? "/" : "\\"; key = "war3mapimported\\" + b; st -= 16; }
                else if (st > 0 && pathChar(sl.charAt(st-1))) {
                    // inside some other path (e.g. ReplaceableTextures\...) - report only
                    int k = st; while (k > 0 && (pathChar(sl.charAt(k-1)) || sl.charAt(k-1)==' ') && sl.charAt(k-1) > 31) k--;
                    log.add("SKIP in " + rel + ": '" + s.substring(k, en) + "'"); continue;
                }
                else { sep = jass ? "\\\\" : "\\"; key = b; }
                String nw = lookup.get(key);
                if (nw == null) { nw = byBase(b); if (nw == null) { log.add("UNMAPPED in " + rel + ": '" + s.substring(st, en) + "'"); continue; } log.add("FIXED broken ref in " + rel + ": '" + s.substring(st, en) + "' -> " + nw); }
                hits.add(new int[]{st, en}); reps.add(nw.replace("\\", sep));
            }
        }
        if (hits.isEmpty()) return 0;
        // sort descending by start, apply
        Integer[] idx = IntStream.range(0, hits.size()).boxed().toArray(Integer[]::new);
        Arrays.sort(idx, (x, y) -> hits.get(y)[0] - hits.get(x)[0]);
        StringBuilder sb = new StringBuilder(s);
        int last = Integer.MAX_VALUE;
        for (int i : idx) {
            int st = hits.get(i)[0], en = hits.get(i)[1]; String r = reps.get(i);
            if (en > last) { log.add("OVERLAP in " + rel + " at " + st); continue; }
            last = st;
            if (fixed) {
                int d = r.length() - (en - st);
                if (d > 0) { // need d NULs after token, keep at least one terminator
                    for (int k = 0; k <= d; k++) if (en + k >= sb.length() || sb.charAt(en + k) != 0) throw new RuntimeException("no room in " + rel + " for " + r);
                    sb.replace(st, en + d, r);
                } else { sb.replace(st, en, r + "\0".repeat(-d)); }
            } else sb.replace(st, en, r);
        }
        if (apply) Files.write(p, sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        return hits.size();
    }

    static void doMoves() throws Exception {
        Set<String> done = new HashSet<>();
        for (Map.Entry<String,String> e : moves.entrySet()) {
            Path src = root.resolve(e.getKey()), dst = root.resolve(e.getValue().replace('\\', '/'));
            Files.createDirectories(dst.getParent());
            if (done.contains(e.getValue().toLowerCase())) { Files.delete(src); continue; } // merged duplicate
            Files.move(src, dst); done.add(e.getValue().toLowerCase());
        }
        Path imp = root.resolve("war3mapImported");
        if (Files.isDirectory(imp)) try (Stream<Path> s = Files.list(imp)) { if (s.findAny().isEmpty()) Files.delete(imp); }
    }

    static void writeImp() throws Exception {
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) { for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
            String rel = root.relativize(p).toString().replace('/', '\\');
            if (!rel.contains("\\") && KEEP_ROOT.contains(rel.toLowerCase())) continue;
            files.add(rel);
        } }
        Collections.sort(files, String.CASE_INSENSITIVE_ORDER);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(bo);
        o.writeInt(Integer.reverseBytes(1)); o.writeInt(Integer.reverseBytes(files.size()));
        for (String f : files) { o.writeByte(13); o.write(f.getBytes(StandardCharsets.UTF_8)); o.writeByte(0); }
        Files.write(root.resolve("war3map.imp"), bo.toByteArray());
        System.out.println("== war3map.imp: " + files.size() + " entries");
    }
}
