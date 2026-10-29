import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * One-shot timer + hashtable callbacks -> doAfter() closures.
 *   caller:  t = CreateTimer(); Hash.saveXHandle(t.getHandleId(), K, EXPR)...; t.start(D, function cb)
 *   cb:      uses Hash.loadX(GetExpiredTimer().getHandleId(), K) + pause/flushChild/destr of the expired timer
 * becomes:   let uK = EXPR ...; doAfter(D) -> <cb body with uK>
 * Values are captured at the same moments they were saved, so behaviour is unchanged.
 *   args: <dir with .wurst files>
 */
public class Closurify {
    static Map<Path, List<String>> files = new LinkedHashMap<>();
    static final Pattern LOAD = Pattern.compile("Hash\\.load(\\w+?)(?:Handle)?\\(GetExpiredTimer\\(\\)\\.getHandleId\\(\\), (\\d+)\\)");
    static final Pattern SAVE = Pattern.compile("^(\\s*)Hash\\.save(\\w+?)(?:Handle)?\\((\\w+)\\.getHandleId\\(\\), (\\d+), (.*)\\)$");
    static final Pattern START = Pattern.compile("^(\\s*)(\\w+)\\.start\\((.*), function (\\w+)\\)$");
    static int converted = 0;

    public static void main(String[] a) throws Exception {
        try (Stream<Path> s = Files.walk(Paths.get(a[0]))) { for (Path p : s.filter(x -> x.toString().endsWith(".wurst")).collect(Collectors.toList())) files.put(p, new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8))); }
        String all = files.values().stream().flatMap(List::stream).collect(Collectors.joining("\n"));
        for (Path p : files.keySet()) {
            boolean changed = true;
            while (changed) { changed = false; List<String> lines = files.get(p);
                for (int i = 0; i < lines.size(); i++) { Matcher st = START.matcher(lines.get(i)); if (st.find() && tryConvert(p, i, st, all)) { changed = true; break; } } }
        }
        for (Map.Entry<Path, List<String>> e : files.entrySet()) Files.write(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
        System.out.println("timers converted to closures: " + converted);
    }

    static void dbg(String cb, int line) { if (cb.equals(System.getenv("CBDBG"))) System.out.println("reject " + cb + " at " + line); }
    static int countRefs(String all, String cb) { Matcher m = Pattern.compile("(?<!public )\\bfunction " + cb + "\\b").matcher(all); int c = 0; while (m.find()) c++; return c; }

    static boolean tryConvert(Path p, int startIdx, Matcher st, String all) {
        List<String> lines = files.get(p);
        String ind = st.group(1), tv = st.group(2), delay = st.group(3), cb = st.group(4);
        if (countRefs(all, cb) != 1) { dbg(cb, countRefs(all, cb)); return false; }
        // callback body (same file assumed)
        int cbHead = -1; for (int i = 0; i < lines.size(); i++) if (lines.get(i).equals("public function " + cb + "()")) cbHead = i;
        if (cbHead < 0) { dbg(cb, 1); return false; }
        int cbEnd = cbHead + 1; while (cbEnd < lines.size() && (lines.get(cbEnd).startsWith("    ") || lines.get(cbEnd).isBlank())) cbEnd++;
        List<String> body = new ArrayList<>(); for (int i = cbHead + 1; i < cbEnd; i++) if (!lines.get(i).isBlank()) body.add(lines.get(i));
        while (!body.isEmpty() && body.get(body.size() - 1).isBlank()) body.remove(body.size() - 1);
        // validate callback: every GetExpiredTimer use is a load / pause / destr / flushChild
        Map<Integer, String> loadTypes = new HashMap<>();
        List<String> newBody = new ArrayList<>();
        for (String l : body) {
            String s = l.strip();
            if (s.equals("GetExpiredTimer().pause()") || s.equals("GetExpiredTimer().destr()") || s.equals("Hash.flushChild(GetExpiredTimer().getHandleId())")) continue;
            Matcher lm = LOAD.matcher(l); StringBuffer sb = new StringBuffer();
            while (lm.find()) { int k = Integer.parseInt(lm.group(2)); String t = lm.group(1); if (loadTypes.containsKey(k) && !loadTypes.get(k).equals(t)) { dbg(cb, 2); return false; } loadTypes.put(k, t); lm.appendReplacement(sb, Matcher.quoteReplacement("" + k + "")); }
            lm.appendTail(sb); String r = sb.toString();
            if (r.contains("GetExpiredTimer")) { dbg(cb, 3); return false; }
            newBody.add(r);
        }
        if (newBody.isEmpty()) { dbg(cb, 4); return false; }
        // caller function range
        int fnStart = startIdx; while (fnStart > 0 && !lines.get(fnStart).startsWith("public function ")) fnStart--;
        int fnEnd = fnStart + 1; while (fnEnd < lines.size() && (lines.get(fnEnd).startsWith("    ") || lines.get(fnEnd).isBlank())) fnEnd++;
        // segment: from the CreateTimer of this timer var preceding the start line
        int seg = -1; for (int i = startIdx - 1; i >= fnStart; i--) { String s = lines.get(i).strip(); if (s.equals(tv + " = CreateTimer()") || s.equals("let " + tv + " = CreateTimer()") || s.equals("timer " + tv + " = CreateTimer()")) { seg = i; break; } if (s.matches(".*\\b" + tv + "\\b.*") && !SAVE.matcher(lines.get(i)).find()) { dbg(cb, 5); return false; } }
        if (seg < 0) { dbg(cb, 6); return false; }
        Map<Integer, String[]> saves = new LinkedHashMap<>(); // key -> {type, expr, lineIdx}
        Pattern tvUse = Pattern.compile("\\b" + tv + "\\b");
        for (int i = seg + 1; i < startIdx; i++) {
            String l = lines.get(i); if (!tvUse.matcher(l).find()) continue;
            Matcher sm = SAVE.matcher(l);
            if (sm.find() && sm.group(3).equals(tv)) { saves.put(Integer.parseInt(sm.group(4)), new String[]{sm.group(2), sm.group(5), String.valueOf(i)}); } else { dbg(cb, 7); return false; }
        }
        for (Map.Entry<Integer, String> e : loadTypes.entrySet()) { String[] sv = saves.get(e.getKey()); if (sv == null || !sv[0].equalsIgnoreCase(e.getValue())) { dbg(cb, 8); return false; } }
        // names for captured values
        String callerText = String.join("\n", lines.subList(fnStart, fnEnd));
        Map<Integer, String> names = new HashMap<>();
        for (Map.Entry<Integer, String[]> e : saves.entrySet()) {
            String t = e.getValue()[0]; String base = t.equals("Unit") ? "u" : t.equals("Effect") ? "fx" : t.equals("Int") ? "n" : t.equals("Real") ? "r" : t.equals("Trigger") ? "trg" : t.equals("Group") ? "g" : t.equals("Item") ? "it" : t.equals("Player") ? "p" : t.equals("Boolean") ? "b" : t.equals("String") ? "s" : "v";
            String nm = base + e.getKey(); while (callerText.matches("(?s).*\\b" + nm + "\\b.*")) nm = nm + "_";
            names.put(e.getKey(), nm);
        }
        // callback locals that collide with caller identifiers -> rename
        Map<String, String> localRen = new HashMap<>();
        for (String l : newBody) { Matcher dm = Pattern.compile("^\\s*(?:let|var|[a-z]\\w*) (\\w+)(?: =|$)").matcher(l); if (dm.find() && !dm.group(1).equals("skip") && callerText.matches("(?s).*\\b" + dm.group(1) + "\\b.*")) localRen.put(dm.group(1), dm.group(1) + "_cb"); }
        // build replacement
        List<String> repl = new ArrayList<>();
        for (Map.Entry<Integer, String[]> e : saves.entrySet()) repl.add(lines.get(Integer.parseInt(e.getValue()[2])).replaceAll("^(\\s*).*$", "$1") + "let " + names.get(e.getKey()) + " = " + e.getValue()[1]);
        List<String> closure = new ArrayList<>();
        closure.add(ind + "doAfter(" + delay + ") ->");
        for (String l : newBody) {
            String r = l;
            for (Map.Entry<Integer, String> e : names.entrySet()) r = r.replace("" + e.getKey() + "", e.getValue());
            for (Map.Entry<String, String> e : localRen.entrySet()) r = r.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
            closure.add(ind + r); // body lines already start with 4 spaces -> one level deeper than the doAfter line
        }
        // apply: replace start line, drop save lines and CreateTimer line, keep order of saves at their positions
        List<String> out = new ArrayList<>(lines);
        out.set(startIdx, String.join("\n", closure));
        int si = 0; for (Map.Entry<Integer, String[]> e : saves.entrySet()) out.set(Integer.parseInt(e.getValue()[2]), repl.get(si++));
        out.set(seg, null);
        // remove callback function (and blank lines after it)
        for (int i = cbHead; i < cbEnd; i++) out.set(i, null);
        List<String> flat = new ArrayList<>();
        for (String l : out) if (l != null) for (String x : l.split("\n", -1)) flat.add(x);
        // drop now-unused "timer tv" declaration
        int fs = flat.indexOf(lines.get(fnStart)); int fe = fs + 1; while (fe < flat.size() && (flat.get(fe).startsWith("    ") || flat.get(fe).isBlank())) fe++;
        String callerAfter = String.join("\n", flat.subList(fs, fe));
        int uses = 0; Matcher um = tvUse.matcher(callerAfter); while (um.find()) uses++;
        if (uses == 1) for (int i = fs; i < fe; i++) if (flat.get(i).strip().equals("timer " + tv) || flat.get(i).strip().equals("timer " + tv + " = null")) { flat.remove(i); break; }
        if (flat.stream().noneMatch(x -> x.equals("import ClosureTimers"))) { int at = 1; while (at < flat.size() && flat.get(at).startsWith("import ")) at++; flat.add(at, "import ClosureTimers"); }
        files.put(p, flat); converted++;
        return true;
    }
}
