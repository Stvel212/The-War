import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Translate the organised war3map.j (WE + cJass output) into Wurst packages.
 *   args: <war3map.j> <common.j> <blizzard.j> <objects dir> <out dir>
 * Output layout: MapGlobals, lib/{CjLib,MMD,SendResult}, one package per trigger folder, Autocast split by race.
 */
public class JassToWurst {
    // ---------- symbol tables ----------
    static Map<String, String> funcRet = new HashMap<>();          // function -> return type
    static Map<String, List<String>> funcParams = new HashMap<>(); // function -> param types
    static Map<String, String> globalType = new HashMap<>();       // global -> type
    static Set<String> globalArray = new HashSet<>();
    static Set<String> handleTypes = new HashSet<>();
    static Set<String> mapFuncs = new LinkedHashSet<>();
    static Map<String, String> rename = new HashMap<>();           // old global name -> new name
    static Map<String, String> localArrayTypes = new HashMap<>();  // hoisted local arrays: global name -> jass type
    static Map<String, String> localArrayGlobals = new LinkedHashMap<>(); // global name -> declaration text (pending for current package)
    static Set<String> stdNames = new HashSet<>();

    static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList("package","import","public","private","protected","readonly","static","override","abstract","constant","class","interface","module","use","extends","implements","enum","tuple","init","function","returns","native","nativetype","let","var","if","else","while","for","in","from","to","step","downto","break","continue","return","skip","switch","case","default","new","destroy","this","thistype","super","castTo","instanceof","it","and","or","not","null","true","false","begin","end","div","mod","endpackage","array","ondestroy","construct","int","real","bool","string","code","handle","nothing","integer","boolean"));

    static String wtype(String t) { switch (t) { case "integer": return "int"; case "boolean": return "bool"; default: return t; } }
    static String defaultOf(String t) { switch (t) { case "integer": return "0"; case "real": return "0."; case "boolean": return "false"; case "string": return "null"; default: return "null"; } }

    // ---------- data model ----------
    static class Fn { String name, ret = "nothing"; List<String[]> params = new ArrayList<>(); List<String> body = new ArrayList<>(); List<String> pre = new ArrayList<>(); String trigger; String section; }
    static class Global { String type, name, init; boolean array, constant; String lib; }
    static class Trigger { String name; List<String> desc = new ArrayList<>(); List<Fn> fns = new ArrayList<>(); String section; }

    static List<Global> globals = new ArrayList<>();
    static List<Fn> allFns = new ArrayList<>();
    static Map<String, Trigger> triggers = new LinkedHashMap<>();
    static Map<String, String> rectDefs = new LinkedHashMap<>();  // gg_rct_X -> Rect(...) expr
    static List<String> log = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        Path jass = Paths.get(a[0]), common = Paths.get(a[1]), bliz = Paths.get(a[2]), objDir = Paths.get(a[3]), out = Paths.get(a[4]);
        loadSignatures(common); loadSignatures(bliz);
        funcRet.put("UnitAlive", "boolean"); funcRet.put("GetUnitGoldCost", "integer"); funcRet.put("GetUnitWoodCost", "integer");
        stdNames.addAll(Files.readAllLines(Paths.get(a[5])));
        parseMap(jass);
        buildRenames(); buildFnRenames();
        Map<String, Integer> raceOf = loadRaces(objDir);
        emit(out, raceOf);
        emitSkeleton(out);
        Files.write(out.resolve("_translate.log"), log, StandardCharsets.UTF_8);
        System.out.println("done; log entries: " + log.size());
    }

    // ---------- common.j / blizzard.j signatures ----------
    static final Pattern SIG = Pattern.compile("^(?:constant\\s+)?(?:native|function)\\s+(\\w+)\\s+takes\\s+(.*?)\\s+returns\\s+(\\w+)");
    static final Pattern GDECL = Pattern.compile("^(constant\\s+)?(\\w+)\\s+(array\\s+)?(\\w+)(?:\\s*=\\s*(.*))?$");
    static void loadSignatures(Path p) throws Exception {
        boolean inGlobals = false;
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String l = raw.strip();
            if (l.startsWith("//") || l.isEmpty()) continue;
            if (l.equals("globals")) { inGlobals = true; continue; }
            if (l.equals("endglobals")) { inGlobals = false; continue; }
            if (l.startsWith("type ")) { handleTypes.add(l.split("\\s+")[1]); continue; }
            if (inGlobals) { Matcher m = GDECL.matcher(l); if (m.find()) { globalType.put(m.group(4), m.group(2)); if (m.group(3) != null) globalArray.add(m.group(4)); } continue; }
            Matcher m = SIG.matcher(l);
            if (m.find()) { funcRet.put(m.group(1), m.group(3)); funcParams.put(m.group(1), paramTypes(m.group(2))); }
        }
    }
    static List<String> paramTypes(String s) { List<String> r = new ArrayList<>(); if (s.strip().equals("nothing")) return r; for (String p : s.split(",")) r.add(p.strip().split("\\s+")[0]); return r; }

    // ---------- map script ----------
    static void parseMap(Path p) throws Exception {
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        int i = 0;
        // globals
        while (!lines.get(i).strip().equals("globals")) i++;
        i++; String lib = null;
        for (; !lines.get(i).strip().equals("endglobals"); i++) {
            String l = lines.get(i).strip();
            if (l.startsWith("//globals from ")) { lib = l.substring(15).replace(":", "").strip(); continue; }
            if (l.startsWith("//endglobals from")) { lib = null; continue; }
            if (l.startsWith("//") || l.isEmpty()) continue;
            Matcher m = GDECL.matcher(l);
            if (!m.find()) { log.add("global not parsed: " + l); continue; }
            Global g = new Global(); g.constant = m.group(1) != null; g.type = m.group(2); g.array = m.group(3) != null; g.name = m.group(4); g.init = m.group(5); g.lib = lib;
            if (g.lib == null && g.name.matches("(s__|si__|st__|sa__)?MMD.*|f__arg_this|jasshelper.*")) g.lib = "MMD";
            if (g.lib == null && g.name.startsWith("SendResult")) g.lib = "SendResult";
            if (g.lib == null && g.name.startsWith("cj")) g.lib = "cjLib";
            globals.add(g); globalType.put(g.name, g.type); if (g.array) globalArray.add(g.name);
        }
        // sections / triggers / functions
        String section = null; Trigger trig = null; List<String> pre = new ArrayList<>(); List<String> desc = new ArrayList<>();
        for (i++; i < lines.size(); i++) {
            String l = lines.get(i);
            String s = l.strip();
            if (s.startsWith("//  ") && i > 0 && lines.get(i - 1).startsWith("//====")) { section = s.substring(4).strip(); trig = null; continue; }
            if (s.startsWith("// Триггер: ")) { trig = new Trigger(); trig.name = s.substring(12).strip().replace(' ', '_'); trig.section = section; triggers.put(trig.name, trig); desc = trig.desc; continue; }
            if (s.startsWith("//   ") && trig != null && trig.fns.isEmpty()) { trig.desc.add(s.substring(5)); continue; }
            if (s.startsWith("//---") || s.startsWith("//===") || s.isEmpty()) continue;
            if (s.startsWith("native ")) continue;
            if (s.startsWith("//")) { pre.add(s); continue; }
            if (s.startsWith("function ")) {
                Matcher m = SIG.matcher(s);
                if (!m.find()) throw new RuntimeException("bad function header: " + s);
                Fn f = new Fn(); f.name = m.group(1); f.ret = m.group(3); f.pre = pre; pre = new ArrayList<>(); f.section = section; f.trigger = trig == null ? null : trig.name;
                if (!m.group(2).strip().equals("nothing")) for (String pp : m.group(2).split(",")) { String[] tn = pp.strip().split("\\s+"); f.params.add(new String[]{tn[0], tn[1]}); }
                for (i++; !lines.get(i).strip().equals("endfunction"); i++) f.body.add(lines.get(i).strip());
                mapFuncs.add(f.name); funcRet.put(f.name, f.ret); funcParams.put(f.name, f.params.stream().map(x -> x[0]).collect(Collectors.toList()));
                allFns.add(f); if (trig != null) trig.fns.add(f);
                if (f.name.equals("CreateRegions")) for (String b : f.body) { Matcher r = Pattern.compile("^set (gg_rct_\\w+)=(Rect\\(.*\\))$").matcher(b); if (r.find()) rectDefs.put(r.group(1), r.group(2)); }
                continue;
            }
            log.add("stray line: " + s);
        }
    }

    // ---------- renames ----------
    static void buildRenames() {
        Set<String> taken = new HashSet<>(stdNames); taken.addAll(mapFuncs); taken.addAll(KEYWORDS); taken.addAll(handleTypes); taken.addAll(funcRet.keySet());
        for (Global g : globals) {
            String n = g.name, nn = n;
            if (n.startsWith("udg_")) nn = n.substring(4);
            else if (n.startsWith("gg_trg_")) nn = "trg_" + n.substring(7);
            else if (n.startsWith("gg_rct_")) nn = "rct_" + n.substring(7);
            if (!nn.equals(n) && (taken.contains(nn) || Character.isDigit(nn.charAt(0)))) { log.add("rename kept prefix (collision): " + n); nn = n; }
            if (!nn.equals(n)) rename.put(n, nn);
        }
    }
    static String ren(String id) { return rename.getOrDefault(id, id); }
    static Map<String, String> fnRename = new HashMap<>();
    static Set<String> noUninit = new HashSet<>(Set.of("NaimAttack.g")); // "fn.local" pairs where Wurst cannot prove definite assignment
    /** Wurst style: function names start lower case. */
    static String fnName(String n) { return fnRename.getOrDefault(n, n); }
    static void buildFnRenames() {
        Set<String> taken = new HashSet<>(stdNames); taken.addAll(KEYWORDS); taken.addAll(funcRet.keySet()); taken.addAll(rename.values()); taken.addAll(handleTypes);
        for (String n : mapFuncs) { if (!Character.isUpperCase(n.charAt(0))) continue; String nn = n.equals(n.toUpperCase()) ? n.toLowerCase() : Character.toLowerCase(n.charAt(0)) + n.substring(1); if (taken.contains(nn)) nn = nn + "Fn"; taken.add(nn); fnRename.put(n, nn); }
    }

    // ---------- races of rawcodes ----------
    static Map<String, Integer> loadRaces(Path objDir) throws Exception {
        Map<String, Integer> r = new HashMap<>();
        String[] races = {"Human", "Orc", "Undead", "NightElf", "Naga", "Other", "Critters", "Heroes", "HeroUnits"};
        try (Stream<Path> s = Files.walk(objDir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".wurst")).collect(Collectors.toList())) {
                String fn = p.getFileName().toString().replace(".wurst", ""); int race = -1;
                for (int k = 0; k < races.length; k++) if (fn.endsWith(races[k]) || fn.equals(races[k])) race = k;
                if (race < 0) continue;
                Matcher m = Pattern.compile("(?:new \\w+Definition|createObjectDefinition\\(\"\\w+\",)\\s*\\('?([A-Za-z0-9]{4})'").matcher(Files.readString(p, StandardCharsets.UTF_8));
                Matcher m2 = Pattern.compile("'([A-Za-z0-9]{4})'").matcher(Files.readString(p, StandardCharsets.UTF_8));
                // first rawcode on each "new"/"createObjectDefinition" line is the object id
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.contains("Definition(") || line.contains("createObjectDefinition(")) { Matcher mm = Pattern.compile("'([A-Za-z0-9]{4})'").matcher(line); if (mm.find()) r.put(mm.group(1), race); }
                }
            }
        }
        return r;
    }
    static final String[] RACE_NAMES = {"Human", "Orc", "Undead", "NightElf", "Naga", "Other", "Critters", "Heroes", "HeroUnits"};

    // ---------- emit ----------
    static Set<String> SKIP_TRIGGERS = Set.of("Unit_Indexer", "Damage_Engine", "Damage_Engine_Config");
    static Map<String, String> SECTION_PKG = new LinkedHashMap<>();
    static {
        SECTION_PKG.put("ИНИЦИАЛИЗАЦИЯ (системы", "Initialization");
        SECTION_PKG.put("ТАБЛИЦА", "Scoreboard");
        SECTION_PKG.put("ГЛАВНОЕ", "Main");
        SECTION_PKG.put("КОМАНДЫ", "Commands");
        SECTION_PKG.put("СИНЕРГИЯ", "Synergy");
        SECTION_PKG.put("КОРОЛЬ", "King");
        SECTION_PKG.put("СТРОИТЕЛЬ", "Builder");
        SECTION_PKG.put("АВТОКАСТ", "Autocast");
        SECTION_PKG.put("ГЕРОИ", "HeroSpells");
        SECTION_PKG.put("АРТЕФАКТЫ", "Artifacts");
    }
    static String pkgOfSection(String section) { for (Map.Entry<String, String> e : SECTION_PKG.entrySet()) if (section != null && section.startsWith(e.getKey())) return e.getValue(); return null; }

    static Map<String, List<Object>> pkgItems = new LinkedHashMap<>(); // package -> list of Trigger or Fn
    static Map<String, String> fnPkg = new HashMap<>();

    static void emit(Path out, Map<String, Integer> raceOf) throws Exception {
        Files.createDirectories(out.resolve("lib")); Files.createDirectories(out.resolve("autocast"));
        // assign triggers to packages
        for (Trigger t : triggers.values()) {
            if (SKIP_TRIGGERS.contains(t.name)) continue;
            String pkg = pkgOfSection(t.section);
            if (pkg == null) { log.add("trigger without package: " + t.name + " / " + t.section); continue; }
            if (pkg.equals("Autocast")) pkg = "Autocast" + raceOfTrigger(t, raceOf);
            if (t.name.equals("DamageOne")) pkg = "DamageSystem";
            pkgItems.computeIfAbsent(pkg, k -> new ArrayList<>()).add(t);
            for (Fn f : t.fns) fnPkg.put(f.name, pkg);
        }
        // libraries + jasshelper tail
        for (Fn f : allFns) {
            if (f.trigger != null) continue;
            if (f.section == null) continue;
            String pkg = null;
            if (f.section.startsWith("БИБЛИОТЕКИ") || f.section.startsWith("JASSHELPER")) pkg = libOf(f.name);
            if (pkg == null) continue; // WE init functions stay in war3map.j
            pkgItems.computeIfAbsent("lib/" + pkg, k -> new ArrayList<>()).add(f); fnPkg.put(f.name, "lib/" + pkg);
        }
        // cross-package function references -> report
        Map<String, Set<String>> deps = new TreeMap<>();
        for (Map.Entry<String, List<Object>> e : pkgItems.entrySet()) {
            Set<String> refs = new TreeSet<>();
            for (Fn f : fnsOf(e.getValue())) for (String b : f.body) { Matcher m = Pattern.compile("\\b([A-Za-z_]\\w*)\\b").matcher(b); while (m.find()) { String p = fnPkg.get(m.group(1)); if (p != null && !p.equals(e.getKey())) refs.add(p + ":" + m.group(1)); } }
            deps.put(e.getKey(), refs);
        }
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) log.add("DEPS " + e.getKey() + " -> " + e.getValue());
        // write packages
        for (Map.Entry<String, List<Object>> e : pkgItems.entrySet()) {
            String pkgPath = e.getKey(); String pkg = pkgPath.contains("/") ? pkgPath.substring(pkgPath.indexOf('/') + 1) : pkgPath;
            StringBuilder sb = new StringBuilder("package " + pkg + "\n");
            Set<String> imports = new TreeSet<>();
            if (!pkgPath.startsWith("lib/") || usesMapGlobals(fnsOf(e.getValue()))) imports.add("MapGlobals");
            for (String d : deps.get(pkgPath)) { String p = d.substring(0, d.indexOf(':')); imports.add(p.contains("/") ? p.substring(p.indexOf('/') + 1) : p); }
            Set<String> usedLibGlobals = new HashSet<>();
            for (Fn f : fnsOf(e.getValue())) for (String b : f.body) for (Global g : globals) if (g.lib != null && b.contains(g.name)) usedLibGlobals.add(libOf(g.name + "___"));
            for (String lg : usedLibGlobals) if (!pkgPath.equals("lib/" + lg)) imports.add(lg);
            for (String imp : imports) sb.append("import ").append(imp).append("\n");
            int hoistPos = sb.length(); localArrayGlobals.clear();
            if (pkgPath.startsWith("lib/")) { sb.append("\n// ---- глобальные переменные библиотеки ----\n"); for (Global g : globals) if (g.lib != null && libOf(g.name + "___").equals(pkg)) sb.append(globalDecl(g)).append("\n"); }
            List<String> initCalls = new ArrayList<>();
            for (Object o : e.getValue()) {
                if (o instanceof Trigger) {
                    Trigger t = (Trigger) o;
                    sb.append("\n// ============================================================\n// Триггер: ").append(t.name.replace('_', ' ')).append("\n");
                    for (String d : t.desc) sb.append("//   ").append(d).append("\n");
                    sb.append("// ============================================================\n");
                    for (Fn f : t.fns) { sb.append(translateFn(f)); if (f.name.startsWith("InitTrig_")) initCalls.add(f.name); }
                } else { Fn f = (Fn) o; sb.append(translateFn(f)); }
            }
            if (pkgPath.equals("lib/MMD")) initCalls.addAll(List.of("jasshelper__initstructs1061119515", "MMD___init"));
            if (pkgPath.equals("lib/CjLib")) initCalls.add("cjLibw560nbs9b8nse46703948___init");
            if (pkgPath.equals("lib/SendResult")) initCalls.add("SendResult___Init");
            if (!initCalls.isEmpty()) { sb.append("\ninit\n"); for (String c : initCalls) sb.append("    ").append(fnName(c)).append("()\n"); }
            Path f = out.resolve(pkgPath.startsWith("Autocast") ? "autocast/" + pkgPath + ".wurst" : pkgPath + ".wurst");
            if (!localArrayGlobals.isEmpty()) sb.insert(hoistPos, "\n" + String.join("\n", localArrayGlobals.values()) + "\n");
            emittedTexts.add(sb.toString()); Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        }
        // MapGlobals
        StringBuilder sb = new StringBuilder("package MapGlobals\n\n// Глобальные переменные карты (бывшие udg_*, gg_trg_*), регионы из редактора и их инициализация (бывший InitGlobals).\n\n");
        // globals referenced by the translated packages only (Unit Indexer / Damage Engine leftovers and unused WE variables are dropped)
        Set<String> used = new HashSet<>();
        for (String txt : emittedTexts) { Matcher m = Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(txt); while (m.find()) used.add(m.group()); }
        int dropped = 0;
        for (Global g : globals) if (g.lib == null && !g.name.startsWith("gg_rct_")) { if (used.contains(ren(g.name))) sb.append(globalDecl(g)).append("\n"); else dropped++; }
        log.add("unused globals dropped: " + dropped);
        sb.append("\n// регионы из редактора (дубли WE-объектов из war3map.j, чтобы были видны из Wurst)\n");
        for (Map.Entry<String, String> r : rectDefs.entrySet()) sb.append("public rect ").append(ren(r.getKey())).append(" = ").append(translateExpr(r.getValue(), new HashMap<>())).append("\n");
        Fn ig = allFns.stream().filter(f -> f.name.equals("InitGlobals")).findFirst().orElse(null);
        if (ig != null) { Fn copy = new Fn(); copy.name = "initGlobals"; copy.body = pruneInitGlobals(ig.body, used); sb.append("\n// бывший InitGlobals: оставлены только присваивания не-значений-по-умолчанию (handles, числа != 0) для используемых переменных\n").append(translateFn(copy)); sb.append("\ninit\n    initGlobals()\n"); }
        Files.writeString(out.resolve("MapGlobals.wurst"), sb.toString(), StandardCharsets.UTF_8);
        // MapStart: map-initialization triggers (RunInitializationTriggers)
        Fn rit = allFns.stream().filter(f -> f.name.equals("RunInitializationTriggers")).findFirst().orElse(null);
        StringBuilder ms = new StringBuilder("package MapStart\nimport MapGlobals\n");
        Set<String> msImports = new TreeSet<>(); for (String k : pkgItems.keySet()) msImports.add(k.contains("/") ? k.substring(k.indexOf('/') + 1) : k); // import everything so MapStart initialises last
        List<String> calls = new ArrayList<>();
        if (rit != null) for (String b : rit.body) { Matcher m = Pattern.compile("ConditionalTriggerExecute\\((gg_trg_\\w+)\\)").matcher(b); if (m.find()) { String tn = m.group(1).substring(7); if (SKIP_TRIGGERS.contains(tn)) continue; calls.add(ren(m.group(1))); String pk = fnPkg.get("InitTrig_" + tn); if (pk != null) msImports.add(pk); } }
        for (String i : msImports) ms.append("import ").append(i).append("\n");
        ms.append("\n// Триггеры с событием «Map initialization» (бывший RunInitializationTriggers) — запускаются после инициализации всех пакетов.\ninit\n");
        for (String c : calls) ms.append("    ConditionalTriggerExecute(").append(c).append(")\n");
        Files.writeString(out.resolve("MapStart.wurst"), ms.toString(), StandardCharsets.UTF_8);
    }
    /** war3map.j that stays in the map: only World Editor parts (objects placed on the map, regions, players, main, config). */
    static void emitSkeleton(Path out) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("//===========================================================================\n");
        sb.append("// Скрипт карты: только часть, которую генерирует World Editor (юниты на карте, регионы, игроки, main, config).\n");
        sb.append("// Вся логика карты — в wurst/map/*.wurst. Wurst при сборке добавляет сюда свою инициализацию.\n");
        sb.append("//===========================================================================\n");
        sb.append("globals\n");
        for (Global g : globals) if (g.lib == null && g.name.startsWith("gg_") && !g.name.startsWith("gg_trg_"))
            sb.append("    ").append(g.constant ? "constant " : "").append(g.type).append(g.array ? " array " : " ").append(g.name).append(g.init == null ? "" : "=" + g.init).append("\n");
        sb.append("endglobals\n\n");
        Set<String> keep = Set.of("InitGlobals", "CreatePlayerBuildings", "CreatePlayerUnits", "CreateAllUnits", "CreateRegions", "InitCustomPlayerSlots", "InitCustomTeams", "InitAllyPriorities", "main", "config");
        for (Fn f : allFns) {
            if (!(keep.contains(f.name) || f.name.startsWith("CreateBuildingsForPlayer") || f.name.startsWith("CreateUnitsForPlayer") || f.name.startsWith("CreateNeutral"))) continue;
            sb.append("function ").append(f.name).append(" takes ");
            sb.append(f.params.isEmpty() ? "nothing" : f.params.stream().map(x -> x[0] + " " + x[1]).collect(Collectors.joining(", ")));
            sb.append(" returns ").append(f.ret).append("\n");
            int d = 1;
            for (String s : f.body) {
                if (f.name.equals("InitGlobals")) break;
                if (f.name.equals("main") && (s.contains("InitCustomTriggers") || s.contains("RunInitializationTriggers") || s.contains("ExecuteFunc"))) continue;
                String w = s.split("[\\s(]")[0];
                if (w.equals("endif") || w.equals("endloop") || w.equals("else") || w.equals("elseif")) d--;
                sb.append("    ".repeat(Math.max(1, d))).append(s).append("\n");
                if (w.equals("if") || w.equals("loop") || w.equals("else") || w.equals("elseif")) d++;
            }
            sb.append("endfunction\n\n");
        }
        Files.writeString(out.resolve("war3map.skeleton.j"), sb.toString(), StandardCharsets.UTF_8);
    }

    static boolean usesMapGlobals(List<Fn> fns) { for (Fn f : fns) for (String b : f.body) for (Global g : globals) if (g.lib == null && !g.name.startsWith("gg_rct_") && b.contains(g.name) && Pattern.compile("\\b" + g.name + "\\b").matcher(b).find()) return true; return false; }
    static List<String> emittedTexts = new ArrayList<>();
    static boolean isDefaultValue(String v) { v = v.strip(); return v.equals("0") || v.equals("0.0") || v.equals("0.") || v.equals("false") || v.equals("null") || v.equals("\"\""); }
    /** InitGlobals from WE: keep only assignments of non-default values to globals that are still used. */
    static List<String> pruneInitGlobals(List<String> body, Set<String> used) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            String s = body.get(i);
            Matcher sc = Pattern.compile("^set (\\w+)=(.*)$").matcher(s);
            if (s.equals("set i=0") && i + 5 < body.size() && body.get(i + 1).equals("loop")) {
                Matcher ar = Pattern.compile("^set (\\w+)\\[i\\]=(.*)$").matcher(body.get(i + 3));
                if (ar.find() && body.get(i + 5).equals("endloop")) {
                    if (used.contains(ren(ar.group(1))) && !isDefaultValue(ar.group(2))) for (int k = 0; k < 6; k++) r.add(body.get(i + k));
                    i += 5; continue;
                }
            }
            if (sc.find()) { if (used.contains(ren(sc.group(1))) && !isDefaultValue(sc.group(2))) r.add(s); continue; }
            if (s.startsWith("local integer i")) continue;
            r.add(s);
        }
        if (r.stream().anyMatch(x -> x.contains("[i]"))) r.add(0, "local integer i=0");
        return r;
    }
    static List<Fn> fnsOf(List<Object> items) { List<Fn> r = new ArrayList<>(); for (Object o : items) { if (o instanceof Trigger) r.addAll(((Trigger) o).fns); else r.add((Fn) o); } return r; }
    static String libOf(String n) { if (n.startsWith("cj")) return "CjLib"; if (n.contains("MMD") || n.startsWith("jasshelper")) return "MMD"; if (n.startsWith("SendResult")) return "SendResult"; return "CjLib"; }
    static String lastRace = "Other";
    static String raceOfTrigger(Trigger t, Map<String, Integer> raceOf) {
        int[] votes = new int[RACE_NAMES.length];
        for (Fn f : t.fns) for (String b : f.body) { Matcher m = Pattern.compile("'([A-Za-z0-9]{4})'").matcher(b); while (m.find()) { Integer r = raceOf.get(m.group(1)); if (r != null && r <= 5) votes[r]++; } }
        int best = 5; for (int k = 0; k < 6; k++) if (votes[k] > votes[best]) best = k;
        if (votes[best] == 0) { log.add("autocast race unknown, inherited " + lastRace + ": " + t.name); return lastRace; }
        lastRace = RACE_NAMES[best]; return lastRace;
    }
    static String globalDecl(Global g) {
        String t = wtype(g.type);
        if (g.array) return "public " + t + " array " + ren(g.name);
        String init = g.init == null ? defaultOf(g.type) : translateExpr(g.init, new HashMap<>());
        return "public " + (g.constant ? "constant " : "") + t + " " + ren(g.name) + " = " + init;
    }

    // ---------- function translation ----------
    static String translateFn(Fn f) {
        StringBuilder sb = new StringBuilder("\n");
        for (String p : f.pre) sb.append(p).append("\n");
        Map<String, String> locals = new HashMap<>();
        Set<String> assignedParams = new HashSet<>();
        for (String b : f.body) { Matcher m = Pattern.compile("^set (\\w+)\\s*=").matcher(b); if (m.find()) for (String[] p : f.params) if (p[1].equals(m.group(1))) assignedParams.add(p[1]); }
        String name = fnName(f.name);
        sb.append("public function ").append(name).append("(");
        List<String> ps = new ArrayList<>();
        for (String[] p : f.params) { String ln = localName(p[1]); locals.put(p[1], p[0]); ps.add(wtype(p[0]) + " " + (assignedParams.contains(p[1]) ? ln + "_" : ln)); }
        sb.append(String.join(", ", ps)).append(")");
        if (!f.ret.equals("nothing")) sb.append(" returns ").append(wtype(f.ret));
        sb.append("\n");
        List<String> body = new ArrayList<>(f.body);
        body = simplifyRegisterLoops(body);
        body = dropTrailingNulls(body, f, locals0(f));
        Set<String> reassigned = new HashSet<>(); for (String b : body) { Matcher m = Pattern.compile("^set (\\w+)\\s*=").matcher(b); if (m.find()) reassigned.add(m.group(1)); }
        List<String> outLines = new ArrayList<>();
        for (String p : assignedParams) outLines.add("    var " + localName(p) + " = " + localName(p) + "_");
        int depth = 1; boolean[] loopFirstExit = new boolean[64]; int loopDepth = 0;
        for (int i = 0; i < body.size(); i++) {
            String s = body.get(i);
            if (s.isEmpty()) continue;
            if (s.startsWith("//")) { outLines.add(ind(depth) + s); continue; }
            if (s.startsWith("debug ")) s = s.substring(6);
            String w = s.split("[\\s(]")[0];
            switch (w) {
                case "local": {
                    Matcher m = Pattern.compile("^local (\\w+)(?: (array))? (\\w+)(?:\\s*=\\s*(.*))?$").matcher(s);
                    if (!m.find()) throw new RuntimeException("bad local: " + s);
                    String t = m.group(1), n = m.group(3);
                    if (m.group(2) != null) { // JASS local array -> package-level array (Wurst has no local arrays)
                        String gn = f.name + "_" + n; locals.put(n, "@" + gn); localArrayTypes.put(gn, t); localArrayGlobals.put(gn, "// локальный массив из " + f.name + " (в Wurst локальных массивов нет)\n" + wtype(t) + " array " + gn);
                        break; }
                    locals.put(n, t);
                    String init = m.group(4);
                    boolean reassignedLater = reassigned.contains(n);
                    if (init == null && i + 1 < body.size()) { // "local T x" directly followed by "set x=..." -> one declaration
                        Matcher sm = Pattern.compile("^set " + n + "\\s*=\\s*(.*)$").matcher(body.get(i + 1));
                        if (sm.find()) { init = sm.group(1); i++; reassignedLater = body.subList(i + 1, body.size()).stream().anyMatch(x -> x.matches("^set " + n + "\\s*=.*")); }
                    }
                    boolean isLet = init != null && !reassignedLater && !init.strip().equals("null");
                    if (init == null && reassignedLater && handleTypes.contains(t) && !noUninit.contains(f.name + "." + n)) outLines.add(ind(depth) + wtype(t) + " " + localName(n)); // assigned later; Wurst checks definite assignment
                    else outLines.add(ind(depth) + (isLet ? "let" : wtype(t)) + " " + localName(n) + " = " + (init == null ? defaultOf(t) : t.equals("real") ? castReal(init, locals) : translateExpr(init, locals)));
                    break; }
                case "set": {
                    Matcher m = Pattern.compile("^set (\\w+)(\\[.*?\\])?\\s*=\\s*(.*)$").matcher(s);
                    // careful: array index may contain '=' inside; split at first '=' that is not inside brackets
                    int eq = findAssignEq(s); String lhs = s.substring(4, eq).strip(), rhs = s.substring(eq + 1).strip();
                    outLines.add(ind(depth) + translateExpr(lhs, locals) + " = " + castForAssign(lhs, rhs, locals));
                    break; }
                case "call": outLines.add(ind(depth) + translateExpr(s.substring(5).strip(), locals)); break;
                case "if": {
                    String c = s.substring(2).strip(); if (c.endsWith("then")) c = c.substring(0, c.length() - 4).strip();
                    outLines.add(ind(depth) + "if " + translateExpr(stripParens(c), locals)); depth++; break; }
                case "elseif": {
                    String c = s.substring(6).strip(); if (c.endsWith("then")) c = c.substring(0, c.length() - 4).strip();
                    depth--; outLines.add(ind(depth) + "else if " + translateExpr(stripParens(c), locals)); depth++; break; }
                case "else": depth--; outLines.add(ind(depth) + "else"); depth++; break;
                case "endif": depth--; break;
                case "loop": {
                    String next = i + 1 < body.size() ? body.get(i + 1) : "";
                    if (next.startsWith("exitwhen ")) { outLines.add(ind(depth) + "while not (" + translateExpr(next.substring(9).strip(), locals) + ")"); i++; }
                    else outLines.add(ind(depth) + "while true");
                    depth++; break; }
                case "exitwhen": outLines.add(ind(depth) + "if " + translateExpr(s.substring(9).strip(), locals)); outLines.add(ind(depth + 1) + "break"); break;
                case "endloop": depth--; break;
                case "return": {
                    String e = s.substring(6).strip();
                    if (e.isEmpty()) outLines.add(ind(depth) + "return");
                    else outLines.add(ind(depth) + "return " + (f.ret.equals("real") ? castReal(e, locals) : translateExpr(e, locals)));
                    break; }
                default: throw new RuntimeException("unknown statement in " + f.name + ": " + s);
            }
        }
        if (outLines.isEmpty()) outLines.add("    skip");
        for (String l : outLines) sb.append(l).append("\n");
        return sb.toString();
    }
    /** Types of the function's locals/params (jass names), for the pre-pass. */
    static Map<String, String> locals0(Fn f) {
        Map<String, String> r = new HashMap<>();
        for (String[] p : f.params) r.put(p[1], p[0]);
        for (String b : f.body) { Matcher m = Pattern.compile("^local (\\w+)(?: array)? (\\w+)").matcher(b); if (m.find()) r.put(m.group(2), m.group(1)); }
        return r;
    }
    /** Wurst nulls handle locals itself (NullSetter), so trailing "set x=null" of handle locals is redundant. */
    static List<String> dropTrailingNulls(List<String> body, Fn f, Map<String, String> locals) {
        List<String> r = new ArrayList<>(body);
        while (!r.isEmpty()) {
            String last = r.get(r.size() - 1);
            Matcher m = Pattern.compile("^set (\\w+)=null$").matcher(last);
            if (m.find() && locals.containsKey(m.group(1))) { r.remove(r.size() - 1); continue; }
            break;
        }
        return r;
    }
    static int findAssignEq(String s) { int d = 0; for (int i = 4; i < s.length(); i++) { char c = s.charAt(i); if (c == '[') d++; else if (c == ']') d--; else if (c == '=' && d == 0) return i; } throw new RuntimeException("no = in " + s); }
    static String stripParens(String c) { String t = c.strip(); if (t.startsWith("(") && t.endsWith(")")) { int d = 0; for (int i = 0; i < t.length(); i++) { if (t.charAt(i) == '(') d++; else if (t.charAt(i) == ')') { d--; if (d == 0 && i < t.length() - 1) return t; } } return t.substring(1, t.length() - 1).strip(); } return t; }
    static String ind(int d) { return "    ".repeat(d); }
    static String localName(String n) { return KEYWORDS.contains(n) || handleTypes.contains(n) ? n + "_" : n; }
    static String castForAssign(String lhs, String rhs, Map<String, String> locals) {
        String lt = typeOfLhs(lhs, locals);
        if (lt.equals("real")) return castReal(rhs, locals);
        return translateExpr(rhs, locals);
    }
    static String typeOfLhs(String lhs, Map<String, String> locals) { String n = lhs.contains("[") ? lhs.substring(0, lhs.indexOf('[')).strip() : lhs.strip(); if (locals.containsKey(n)) { String t = locals.get(n); return t.startsWith("@") ? localArrayTypes.get(t.substring(1)) : t; } return globalType.getOrDefault(n, "?"); }
    /** real context: int/int stays real division in JASS? No - JASS evaluates int/int as int first. Keep div semantics, just translate. */
    static String castReal(String e, Map<String, String> locals) { P p = new P(lex(e), locals); String[] r = orExpr(p); return coerceReal(r); }
    /** Wurst does not convert int to real implicitly in calls/assignments. */
    static String coerceReal(String[] e) { if (!e[1].equals("integer")) return e[0]; if (e[0].matches("-?[0-9]+")) return e[0] + ".0"; return "I2R(" + e[0] + ")"; }

    /** InitTrig loops over players -> TriggerRegisterAnyUnitEventBJ */
    static List<String> simplifyRegisterLoops(List<String> body) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            String s = body.get(i);
            if (s.equals("loop") && i + 4 < body.size()) {
                Matcher m = Pattern.compile("^call TriggerRegisterPlayerUnitEvent\\((\\w+), Player\\(index\\), (\\w+), null\\)$").matcher(body.get(i + 1));
                if (m.find() && body.get(i + 2).equals("set index=index + 1") && body.get(i + 3).equals("exitwhen index == bj_MAX_PLAYER_SLOTS") && body.get(i + 4).equals("endloop")) {
                    r.add("call TriggerRegisterAnyUnitEventBJ(" + m.group(1) + ", " + m.group(2) + ")"); i += 4; continue;
                }
            }
            r.add(s);
        }
        // drop now-unused "local integer index=0"
        boolean indexUsed = r.stream().anyMatch(x -> !x.startsWith("local integer index") && !x.equals("set index=0") && x.matches(".*\\bindex\\b.*"));
        if (!indexUsed) r.removeIf(x -> x.equals("local integer index=0") || x.equals("set index=0"));
        return r;
    }

    // ---------- expressions ----------
    static class Tok { String t, v; Tok(String t, String v) { this.t = t; this.v = v; } }
    static List<Tok> lex(String s) {
        List<Tok> r = new ArrayList<>(); int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '"') { int j = i + 1; while (j < n && s.charAt(j) != '"') { if (s.charAt(j) == '\\') j++; j++; } r.add(new Tok("str", s.substring(i, j + 1))); i = j + 1; continue; }
            if (c == '\'') { int j = s.indexOf('\'', i + 1); r.add(new Tok("raw", s.substring(i, j + 1))); i = j + 1; continue; }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i; boolean real = false;
                if (c == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) { j = i + 2; while (j < n && Character.isLetterOrDigit(s.charAt(j))) j++; r.add(new Tok("int", s.substring(i, j))); i = j; continue; }
                while (j < n && Character.isDigit(s.charAt(j))) j++;
                if (j < n && s.charAt(j) == '.') { real = true; j++; while (j < n && Character.isDigit(s.charAt(j))) j++; }
                String num = s.substring(i, j);
                if (real) { if (num.startsWith(".")) num = "0" + num; if (num.endsWith(".")) num = num + "0"; }
                r.add(new Tok(real ? "real" : "int", num)); i = j; continue;
            }
            if (Character.isLetter(c) || c == '_') { int j = i; while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++; r.add(new Tok("id", s.substring(i, j))); i = j; continue; }
            String two = i + 1 < n ? s.substring(i, i + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")) { r.add(new Tok("op", two)); i += 2; continue; }
            r.add(new Tok("op", String.valueOf(c))); i++;
        }
        return r;
    }
    static class P { List<Tok> t; int i = 0; Map<String, String> locals; P(List<Tok> t, Map<String, String> l) { this.t = t; locals = l; }
        Tok peek() { return i < t.size() ? t.get(i) : new Tok("eof", ""); }
        boolean is(String v) { return i < t.size() && t.get(i).v.equals(v) && !t.get(i).t.equals("str"); }
        Tok next() { return t.get(i++); }
        void expect(String v) { if (!is(v)) throw new RuntimeException("expected " + v + " at " + i + " in " + t.stream().map(x -> x.v).collect(Collectors.joining(" "))); i++; }
    }
    static String[] E(String text, String type) { return new String[]{text, type}; }
    static String translateExpr(String s, Map<String, String> locals) {
        P p = new P(lex(s), locals);
        String[] e = orExpr(p);
        if (p.i != p.t.size()) throw new RuntimeException("trailing tokens in expr: " + s);
        return e[0];
    }
    /** JASS: "and" and "or" have EQUAL precedence and evaluate left to right (a or b and c == (a or b) and c).
     *  Wurst binds "and" tighter, so mixed chains get explicit parentheses. */
    static String[] orExpr(P p) {
        String[] l = notExpr(p); String prevOp = null;
        while (p.is("and") || p.is("or")) {
            String op = p.next().v; String[] r = notExpr(p);
            String lt = (prevOp != null && !prevOp.equals(op)) ? "(" + l[0] + ")" : l[0];
            l = E(lt + " " + op + " " + r[0], "boolean"); prevOp = op;
        }
        return l;
    }
    static String[] notExpr(P p) { if (p.is("not")) { p.next(); String[] r = notExpr(p); return E("not " + r[0], "boolean"); } return cmpExpr(p); }
    static String[] cmpExpr(P p) { String[] l = addExpr(p); if (p.peek().t.equals("op") && Set.of("==", "!=", "<", "<=", ">", ">=").contains(p.peek().v)) { String op = p.next().v; String[] r = addExpr(p);
            if (l[1].equals("integer") && r[0].equals("null")) r = E("0", "integer"); if (r[1].equals("integer") && l[0].equals("null")) l = E("0", "integer"); // jasshelper: integer == null
            return E(l[0] + " " + op + " " + r[0], "boolean"); } return l; }
    static String[] addExpr(P p) { String[] l = mulExpr(p); while (p.is("+") || p.is("-")) { String op = p.next().v; String[] r = mulExpr(p); l = E(l[0] + " " + op + " " + r[0], arith(l[1], r[1], op)); } return l; }
    static String[] mulExpr(P p) {
        String[] l = unary(p);
        while (p.is("*") || p.is("/")) {
            String op = p.next().v; String[] r = unary(p);
            if (op.equals("/") && l[1].equals("integer") && r[1].equals("integer")) l = E(l[0] + " div " + r[0], "integer");
            else if (op.equals("/") && (l[1].equals("?") || r[1].equals("?"))) { log.add("division with unknown type: " + l[0] + " / " + r[0]); l = E(l[0] + " / " + r[0], "real"); }
            else l = E(l[0] + " " + op + " " + r[0], arith(l[1], r[1], op));
        }
        return l;
    }
    static String arith(String a, String b, String op) { if (op.equals("+") && (a.equals("string") || b.equals("string"))) return "string"; if (a.equals("real") || b.equals("real")) return "real"; if (a.equals("integer") && b.equals("integer")) return "integer"; return "?"; }
    static String[] unary(P p) { if (p.is("-")) { p.next(); String[] r = unary(p); return E("-" + r[0], r[1]); } if (p.is("+")) { p.next(); return unary(p); } return primary(p); }
    static String[] primary(P p) {
        Tok t = p.next();
        switch (t.t) {
            case "int": return E(t.v, "integer");
            case "real": return E(t.v, "real");
            case "raw": return E(t.v, "integer");
            case "str": return E(t.v, "string");
            case "op":
                if (t.v.equals("(")) { String[] e = orExpr(p); p.expect(")"); return E("(" + e[0] + ")", e[1]); }
                throw new RuntimeException("unexpected op " + t.v);
            case "id": {
                if (t.v.equals("null")) return E("null", "null");
                if (t.v.equals("true") || t.v.equals("false")) return E(t.v, "boolean");
                if (t.v.equals("function")) { Tok f = p.next(); return E("function " + fnName(f.v), "code"); }
                if (p.is("(")) { // call
                    p.next(); List<String> args = new ArrayList<>(); List<String> pts = funcParams.getOrDefault(t.v, null);
                    int k = 0;
                    while (!p.is(")")) { String[] a = orExpr(p); String pt = pts != null && k < pts.size() ? pts.get(k) : "?"; args.add(pt.equals("real") ? coerceReal(a) : a[0]); k++; if (p.is(",")) p.next(); }
                    p.expect(")");
                    String rt = funcRet.getOrDefault(t.v, "?"); if (rt.equals("?")) log.add("unknown function: " + t.v);
                    return E(fnName(t.v) + "(" + String.join(", ", args) + ")", rt);
                }
                String type; String name;
                if (p.locals.containsKey(t.v) && p.locals.get(t.v).startsWith("@")) { name = p.locals.get(t.v).substring(1); type = localArrayTypes.get(name); }
                else if (p.locals.containsKey(t.v)) { type = p.locals.get(t.v); name = localName(t.v); }
                else if (globalType.containsKey(t.v)) { type = globalType.get(t.v); name = ren(t.v); }
                else { type = "?"; name = t.v; log.add("unknown identifier: " + t.v); }
                if (p.is("[")) { p.next(); String[] idx = orExpr(p); p.expect("]"); return E(name + "[" + idx[0] + "]", type); }
                return E(name, type);
            }
        }
        throw new RuntimeException("bad token " + t.v);
    }
}
