import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Reforged shows/charges "Upgrades To" as (target cost - source cost); classic used the target's own cost.
 * Make the game behave like the original: target.gold := target.ownGold + source.gold (cumulative along chains),
 * and emit wurst/map/UpgradeCosts.wurst so the script can still get a unit type's OWN cost.
 *   args: <wurst/objects/units dir> <out .wurst> [--apply]
 */
public class UpgradeCosts {
    static class U { String id, file; int gold, lumber, goldLine, lumberLine; List<String> up = new ArrayList<>(); }
    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a[0]); boolean apply = a.length > 2 && a[2].equals("--apply"); boolean tableOnly = a.length > 2 && a[2].equals("--table-only");
        Map<String, U> units = new LinkedHashMap<>(); Map<Path, List<String>> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(dir)) { for (Path p : s.filter(x -> x.toString().endsWith(".wurst")).collect(Collectors.toList())) {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8); files.put(p, lines); U cur = null;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i); Matcher m;
                if ((m = Pattern.compile("new \\w+Definition\\('(\\w{4})'").matcher(l)).find()) { cur = new U(); cur.id = m.group(1); cur.file = p.toString(); units.put(cur.id, cur); }
                else if (l.contains("createObjectDefinition(\"w3u\"") && (m = Pattern.compile("'(\\w{4})'").matcher(l)).find()) { cur = new U(); cur.id = m.group(1); cur.file = p.toString(); units.put(cur.id, cur); }
                else if (cur != null && (m = Pattern.compile("\\.\\.setGoldCost\\((-?\\d+)\\)").matcher(l)).find()) { cur.gold = Integer.parseInt(m.group(1)); cur.goldLine = i; }
                else if (cur != null && (m = Pattern.compile("\\.\\.setLumberCost\\((-?\\d+)\\)").matcher(l)).find()) { cur.lumber = Integer.parseInt(m.group(1)); cur.lumberLine = i; }
                else if (cur != null && (m = Pattern.compile("\\.\\.setUpgradesTo\\(\"([^\"]*)\"\\)").matcher(l)).find()) { for (String t : m.group(1).split(",")) if (!t.isBlank()) cur.up.add(t.trim()); }
            }
        } }
        // sources per target
        Map<String, List<U>> sources = new LinkedHashMap<>();
        for (U u : units.values()) for (String t : u.up) sources.computeIfAbsent(t, k -> new ArrayList<>()).add(u);
        System.out.println("upgrade targets: " + sources.size());
        // cumulative cost: new(T) = own(T) + new(S); process in dependency order
        Map<String, int[]> add = new LinkedHashMap<>(); // target -> {goldAdd, lumberAdd}
        boolean progress = true; Set<String> done = new HashSet<>();
        while (progress) { progress = false;
            for (Map.Entry<String, List<U>> e : sources.entrySet()) {
                String t = e.getKey(); if (done.contains(t)) continue;
                boolean ready = true; Set<String> costs = new TreeSet<>();
                for (U s : e.getValue()) { if (sources.containsKey(s.id) && !done.contains(s.id)) { ready = false; break; } int[] sa = add.getOrDefault(s.id, new int[]{0, 0}); costs.add((s.gold + sa[0]) + "/" + (s.lumber + sa[1])); }
                if (!ready) continue;
                if (costs.size() != 1) System.out.println("AMBIGUOUS " + t + " (own " + units.get(t).gold + "): sources " + e.getValue().stream().map(s -> s.id + " own " + s.gold + " cumulative " + (s.gold + add.getOrDefault(s.id, new int[]{0, 0})[0])).collect(Collectors.toList()) + " -> taking the minimum");
                String[] c = costs.iterator().next().split("/"); // TreeSet is sorted as strings; pick numeric minimum below
                int bestG = Integer.MAX_VALUE, bestL = 0; for (String cs : costs) { String[] cc = cs.split("/"); int g = Integer.parseInt(cc[0]); if (g < bestG) { bestG = g; bestL = Integer.parseInt(cc[1]); } } c = new String[]{"" + bestG, "" + bestL};
                add.put(t, new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1])}); done.add(t); progress = true;
            }
        }
        for (String t : sources.keySet()) if (!done.contains(t)) System.out.println("CYCLE/unresolved " + t);
        StringBuilder w = new StringBuilder("package UpgradeCosts\nimport HashMap\n\n/*\n\tReforged показывает и списывает цену «Улучшить» как (цель − текущее здание), классика — цену самого улучшения.\n\tЧтобы игрок видел и платил столько же, сколько в оригинале, в объектах у целей улучшений стоит\n\t(своя цена + цена здания-источника). Здесь — обратная таблица, чтобы скрипт получал СВОЮ цену типа, как раньше.\n\tТаблица генерируется tools/UpgradeCosts.java из wurst/objects/units. */\n\nconstant sourceGold = new HashMap<int, int>()\nconstant sourceLumber = new HashMap<int, int>()\n\n/** цена типа юнита без учёта здания, из которого он улучшается (= GetUnitGoldCost из оригинала) */\npublic function unitOwnGoldCost(int unitId) returns int\n\treturn GetUnitGoldCost(unitId) - sourceGold.get(unitId)\n\npublic function unitOwnWoodCost(int unitId) returns int\n\treturn GetUnitWoodCost(unitId) - sourceLumber.get(unitId)\n\ninit\n");
        int changed = 0;
        for (Map.Entry<String, int[]> e : add.entrySet()) {
            U t = units.get(e.getKey()); if (t == null) { System.out.println("target not in objects: " + e.getKey()); continue; }
            int[] v = e.getValue();
            if (v[0] != 0) w.append("\tsourceGold.put('").append(t.id).append("', ").append(v[0]).append(")\n");
            if (v[1] != 0) w.append("\tsourceLumber.put('").append(t.id).append("', ").append(v[1]).append(")\n");
            List<String> lines = files.get(Paths.get(t.file));
            if (v[0] != 0) { if (t.goldLine == 0) { System.out.println("no setGoldCost line for " + t.id); } else lines.set(t.goldLine, "\t..setGoldCost(" + (t.gold + v[0]) + ") // " + t.gold + " + " + v[0] + " (здание-источник): Reforged показывает разницу, см. UpgradeCosts"); }
            if (v[1] != 0) { if (t.lumberLine == 0) { System.out.println("no setLumberCost line for " + t.id); } else lines.set(t.lumberLine, "\t..setLumberCost(" + (t.lumber + v[1]) + ") // " + t.lumber + " + " + v[1] + " (здание-источник)"); }
            changed++;
            System.out.println(t.id + ": gold " + t.gold + " -> " + (t.gold + v[0]) + ", lumber " + t.lumber + " -> " + (t.lumber + v[1]));
        }
        w.append("\tskip\n");
        System.out.println("targets adjusted: " + changed);
        if (tableOnly) { // objects already carry cumulative costs: table = current cost of the (cheapest) source
            StringBuilder w2 = new StringBuilder(w.substring(0, w.indexOf("init\n") + 5));
            for (Map.Entry<String, List<U>> e : sources.entrySet()) {
                int g = Integer.MAX_VALUE, l = 0; for (U s : e.getValue()) if (s.gold < g) { g = s.gold; l = s.lumber; }
                if (g != 0) w2.append("\tsourceGold.put('").append(e.getKey()).append("', ").append(g).append(")\n");
                if (l != 0) w2.append("\tsourceLumber.put('").append(e.getKey()).append("', ").append(l).append(")\n");
            }
            w2.append("\tskip\n"); Files.writeString(Paths.get(a[1]), w2.toString(), StandardCharsets.UTF_8); System.out.println("TABLE WRITTEN"); return;
        }
        if (apply) { for (Map.Entry<Path, List<String>> f : files.entrySet()) Files.write(f.getKey(), f.getValue(), StandardCharsets.UTF_8); Files.writeString(Paths.get(a[1]), w.toString(), StandardCharsets.UTF_8); System.out.println("APPLIED"); }
    }
}
