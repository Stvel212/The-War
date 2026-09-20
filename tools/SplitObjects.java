import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/** Split exported Wurst object files into categorised packages. */
public class SplitObjects {
    static class Block {
        String id, cls, text, section = "modified"; // section: modified | original
        Map<String, String> f = new HashMap<>();   // first value of selected setters
        String race;                                // effective race (units)
    }

    static Path dir; static Path mapDir;
    static Map<String, Block> units = new LinkedHashMap<>();
    static Map<String, Block> abils = new LinkedHashMap<>();
    static Map<String, Block> items = new LinkedHashMap<>();

    public static void main(String[] a) throws Exception {
        dir = Paths.get(a[0]); mapDir = Paths.get(a[1]);
        boolean apply = a.length > 2 && a[2].equals("--apply");

        List<Block> u = parse(dir.resolve("w3u.wurst"));
        List<Block> ab = parse(dir.resolve("w3a.wurst"));
        List<Block> it = parse(dir.resolve("w3t.wurst"));
        for (Block b : u) units.put(b.id, b);
        for (Block b : ab) abils.put(b.id, b);
        for (Block b : it) items.put(b.id, b);

        inferRaces();

        // ---- units -> files
        Map<String, List<Block>> uf = new TreeMap<>();
        for (Block b : u) {
            String file;
            if (b.section.equals("original")) file = "units/Original";
            else if (b.cls.equals("HeroDefinition")) file = "units/Heroes";
            else if (isHeroUnit(b)) file = "units/HeroUnits";
            else if (b.cls.equals("BuildingDefinition")) file = "units/Buildings" + b.race;
            else file = "units/Units" + b.race;
            uf.computeIfAbsent(file, k -> new ArrayList<>()).add(b);
        }

        // ---- abilities -> files, by who uses them
        Map<String, Set<String>> users = new HashMap<>(); // abilId -> categories
        for (Block b : u) {
            String cat = b.cls.equals("HeroDefinition") ? "Heroes" : isHeroUnit(b) ? "HeroUnits" : b.race;
            for (String key : new String[]{"setNormalAbilities", "setHeroAbilities"}) {
                String v = b.f.get(key); if (v == null) continue;
                for (String id : v.split(",")) { id = id.trim(); if (!id.isEmpty()) users.computeIfAbsent(id, k -> new TreeSet<>()).add(cat); }
            }
        }
        Set<String> itemAbil = new HashSet<>();
        for (Block b : it) { String v = b.f.get("setAbilities"); if (v != null) for (String id : v.split(",")) itemAbil.add(id.trim()); }
        String script = Files.readString(mapDir.resolve("war3map.j"), StandardCharsets.ISO_8859_1)
                      + new String(Files.readAllBytes(mapDir.resolve("war3map.wct")), StandardCharsets.ISO_8859_1);
        Map<String, List<Block>> af = new TreeMap<>();
        Map<String, Integer> stats = new TreeMap<>();
        for (Block b : ab) {
            String file;
            if (b.section.equals("original")) file = "abilities/Original";
            else if ("true".equals(b.f.get("setItemAbility")) || itemAbil.contains(b.id)) file = "abilities/Items";
            else {
                Set<String> c = users.getOrDefault(b.id, Set.of());
                if (c.size() == 1) file = "abilities/" + c.iterator().next();
                else if (c.size() > 1) file = "abilities/Shared";
                else if (script.contains("'" + b.id + "'")) file = "abilities/Script";
                else file = "abilities/Other";
            }
            af.computeIfAbsent(file, k -> new ArrayList<>()).add(b);
        }

        // ---- items
        Map<String, List<Block>> itf = new TreeMap<>();
        for (Block b : it) itf.computeIfAbsent(b.section.equals("original") ? "items/Original" : "items/Items", k -> new ArrayList<>()).add(b);

        Map<String, List<Block>> all = new TreeMap<>(); all.putAll(uf); all.putAll(af); all.putAll(itf);
        for (String t : new String[]{"w3h:Buffs", "w3q:Upgrades", "w3b:Destructables", "w3d:Doodads"}) {
            String[] p = t.split(":");
            List<Block> bl = parse(dir.resolve(p[0] + ".wurst"));
            for (Block b : bl) all.computeIfAbsent("misc/" + p[1] + (b.section.equals("original") ? "Original" : ""), k -> new ArrayList<>()).add(b);
        }
        for (Map.Entry<String, List<Block>> e : all.entrySet()) System.out.printf("%4d  %s%n", e.getValue().size(), e.getKey());
        System.out.println("race fallback (from id letter): " + fallbackCount);

        if (!apply) return;
        for (Map.Entry<String, List<Block>> e : all.entrySet()) {
            String pkg = "Obj" + e.getKey().substring(e.getKey().indexOf('/') + 1);
            StringBuilder sb = new StringBuilder("package " + pkg + "\n");
            String body = e.getValue().stream().map(b -> b.text).collect(Collectors.joining("\n"));
            Set<String> imports = new TreeSet<>();
            if (body.contains("UnitDefinition") || body.contains("BuildingDefinition") || body.contains("HeroDefinition")) imports.add("UnitObjEditing");
            if (body.contains("AbilityDefinition")) imports.add("AbilityObjEditing");
            if (body.contains("ItemDefinition")) imports.add("ItemObjEditing");
            if (body.contains("BuffDefinition")) imports.add("BuffObjEditing");
            if (body.contains("UpgradeDefinition")) imports.add("UpgradeObjEditing");
            if (body.contains("DestructableDefinition")) imports.add("DestructableObjEditing");
            if (body.contains("DoodadDefinition")) imports.add("DoodadObjEditing");
            if (body.contains("createObjectDefinition") || body.contains("setLvlData") || body.contains("Raw(")) imports.add("ObjEditingNatives");
            for (String i : imports) sb.append("import ").append(i).append("\n");
            if (body.contains("AbilityDefinitionRootAncientsRaw")) sb.append(RAW_ROOT);
            sb.append("\n").append(body);
            Path out = dir.resolve(e.getKey() + ".wurst");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        }
        for (String n : new String[]{"w3u", "w3a", "w3t", "w3h", "w3q", "w3b", "w3d"}) Files.delete(dir.resolve(n + ".wurst"));
        System.out.println("APPLIED");
    }

    static final String RAW_ROOT = "\n/* Roo2/Roo4 в карте хранятся как int, а stdlib ждёт строку — пишем сырые значения как было. */\n"
        + "class AbilityDefinitionRootAncientsRaw extends AbilityDefinitionRootAncients\n\tconstruct(int newAbilityId)\n\t\tsuper(newAbilityId)\n\n"
        + "\tfunction setUprootedWeaponsRaw(int level, int value)\n\t\tdef.setLvlDataInt(\"Roo2\", level, 2, value)\n\n"
        + "\tfunction setUprootedDefenseTypeRaw(int level, int value)\n\t\tdef.setLvlDataInt(\"Roo4\", level, 4, value)\n";

    static final Pattern HEAD = Pattern.compile("@compiletime function create_\\w+_(\\w+)\\(\\)");
    static final Pattern NEW = Pattern.compile("\\b(?:new (\\w+Definition)|createObjectDefinition)\\(");
    static final Pattern SET = Pattern.compile("\\.\\.(setName|setRace|setUnitsTrained|setNormalAbilities|setHeroAbilities|setItemAbility|setHeroAbility|setAbilities)\\((?:Race\\.)?\"?([^\")]*)\"?\\)");

    static List<Block> parse(Path p) throws Exception {
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        List<Block> res = new ArrayList<>(); Block cur = null; StringBuilder sb = null; String section = "modified";
        for (String l : lines) {
            if (l.startsWith("// Original Table")) section = "original";
            Matcher m = HEAD.matcher(l);
            if (m.find()) {
                if (cur != null) { cur.text = sb.toString().stripTrailing() + "\n"; res.add(cur); }
                cur = new Block(); cur.id = m.group(1); cur.section = section; sb = new StringBuilder();
            }
            if (cur == null) continue;
            sb.append(l).append("\n");
            Matcher n = NEW.matcher(l); if (n.find() && cur.cls == null) cur.cls = n.group(1) == null ? "raw" : n.group(1);
            Matcher s = SET.matcher(l); if (s.find()) cur.f.putIfAbsent(s.group(1), s.group(2));
        }
        if (cur != null) { cur.text = sb.toString().stripTrailing() + "\n"; res.add(cur); }
        return res;
    }

    /** Юниты с именем вида "[Герой] - Тралл" (в названии смешаны латиница/кириллица, поэтому по шаблону). */
    static boolean isHeroUnit(Block b) { String n = b.f.get("setName"); return n != null && n.matches(".*\\[Г.{4}\\].*"); }

    static int fallbackCount = 0;
    static String raceName(String r) {
        switch (r) { case "Nightelf": return "NightElf"; default: return r; }
    }
    static void inferRaces() {
        for (Block b : units.values()) { String r = b.f.get("setRace"); if (r != null && !r.isEmpty()) b.race = raceName(r); }
        for (int pass = 0; pass < 4; pass++) {
            for (Block b : units.values()) {
                if (b.race != null) continue;
                Map<String, Integer> votes = new HashMap<>();
                if (b.cls.equals("BuildingDefinition")) {
                    String v = b.f.get("setUnitsTrained");
                    if (v != null) for (String id : v.split(",")) { Block t = units.get(id.trim()); if (t != null && t.race != null) votes.merge(t.race, 1, Integer::sum); }
                } else {
                    for (Block o : units.values()) {
                        String v = o.f.get("setUnitsTrained");
                        if (o.race != null && v != null && Arrays.asList(v.split(",")).contains(b.id)) votes.merge(o.race, 1, Integer::sum);
                    }
                }
                if (!votes.isEmpty()) b.race = Collections.max(votes.entrySet(), Map.Entry.comparingByValue()).getKey();
            }
        }
        for (Block b : units.values()) if (b.race == null) {
            fallbackCount++;
            switch (b.id.charAt(0)) { case 'h': case 'H': b.race = "Human"; break; case 'o': case 'O': b.race = "Orc"; break; case 'u': case 'U': b.race = "Undead"; break; case 'e': case 'E': b.race = "NightElf"; break; default: b.race = "Other"; }
        }
    }
}
