import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Reorganise a cJass/WE-compiled war3map.j:
 *  - functions grouped by trigger, triggers grouped by the author's WE categories (from trigcats.txt)
 *  - section / trigger headers, consistent 4-space indentation
 *  - 0x41303030-style rawcodes back to 'A000'
 *  - dead code removed ("//Function not found" lines)
 *  - a few handle leaks fixed (see FIXES)
 */
public class JassOrganize {
    static class Fn { String name; List<String> lines = new ArrayList<>(); List<String> pre = new ArrayList<>(); String group; }

    static final List<String> CAT_ORDER = List.of("Инсцилизация", "Таблица", "Главное (1)", "Команди", "Синегрия ", "Король", "Строитель", "Автокаст юнитов", "Герои", "Артефакти ");
    static final Map<String, String> CAT_TITLE = Map.of(
        "Инсцилизация", "ИНИЦИАЛИЗАЦИЯ (системы: MMD, Unit Indexer, Damage Engine, волны, победа)",
        "Таблица", "ТАБЛИЦА (мультиборд, выход игроков)",
        "Главное (1)", "ГЛАВНОЕ (постройка, бараки, запреты, камни, книга)",
        "Команди", "КОМАНДЫ (чат-команды: камера, ресурсы, контроль, кик AFK)",
        "Синегрия ", "СИНЕРГИЯ",
        "Король", "КОРОЛЬ",
        "Строитель", "СТРОИТЕЛЬ (контроль, репик, флаг, продажа)",
        "Автокаст юнитов", "АВТОКАСТ ЮНИТОВ (способности обычных юнитов)",
        "Герои", "ГЕРОИ (способности героев)",
        "Артефакти ", "АРТЕФАКТЫ");

    // exact-line fixes: old -> new (handle leaks / dead code)
    static final Map<String, String> FIXES = new LinkedHashMap<>();
    static {
        FIXES.put("call DestroyEffect(AddSpecialEffectLoc(\"Abilities\\\\Spells\\\\Human\\\\Thunderclap\\\\ThunderClapCaster.mdl\", GetUnitLoc(GetEnumUnit())))",
                  "call DestroyEffect(AddSpecialEffect(\"Abilities\\\\Spells\\\\Human\\\\Thunderclap\\\\ThunderClapCaster.mdl\", GetUnitX(GetEnumUnit()), GetUnitY(GetEnumUnit())))");
        FIXES.put("call DestroyEffect(AddSpecialEffectLoc(\"Abilities\\\\Spells\\\\Other\\\\Cleave\\\\CleaveDamageTarget.mdl\", GetUnitLoc(GetTriggerUnit())))",
                  "call DestroyEffect(AddSpecialEffect(\"Abilities\\\\Spells\\\\Other\\\\Cleave\\\\CleaveDamageTarget.mdl\", GetUnitX(GetTriggerUnit()), GetUnitY(GetTriggerUnit())))");
        FIXES.put("call DestroyEffect(AddSpecialEffectLoc(\"Abilities\\\\Spells\\\\Human\\\\FlakCannons\\\\FlakTarget.mdl\", GetUnitLoc(LoadUnitHandle(udg_Hash, GetHandleId(GetExpiredTimer()), 1))))",
                  "call DestroyEffect(AddSpecialEffect(\"Abilities\\\\Spells\\\\Human\\\\FlakCannons\\\\FlakTarget.mdl\", GetUnitX(LoadUnitHandle(udg_Hash, GetHandleId(GetExpiredTimer()), 1)), GetUnitY(LoadUnitHandle(udg_Hash, GetHandleId(GetExpiredTimer()), 1))))");
        FIXES.put("set u=CreateUnitAtLoc(GetOwningPlayer(GetTriggerUnit()), 0x6F30304B, GetSpellTargetLoc(), 0)",
                  "set u=CreateUnit(GetOwningPlayer(GetTriggerUnit()), 0x6F30304B, GetSpellTargetX(), GetSpellTargetY(), 0)");
        FIXES.put("set g=CreateGroup()\nset g=GetRandomSubGroup(2, udg_Group)\ncall ForGroup(g, function castPauk1)",
                  "set g=GetRandomSubGroup(2, udg_Group)\ncall ForGroup(g, function castPauk1)\ncall DestroyGroup(g)");
        // unreachable "set x=null" after return (pjass warnings): drop the unused local / null before returning
        FIXES.put("function Trig_ZapretHero_Conditions takes nothing returns boolean\nlocal unit u=GetTriggerUnit()\nlocal integer id=GetUnitTypeId(u)\n",
                  "function Trig_ZapretHero_Conditions takes nothing returns boolean\nlocal integer id=GetUnitTypeId(GetTriggerUnit())\n");
        FIXES.put("return false\nset u=null\nendfunction\nfunction Trig_ZapretHero_Actions",
                  "return false\nendfunction\nfunction Trig_ZapretHero_Actions");
        FIXES.put("local unit fi=GetFilterUnit()\nreturn IsUnitEnemy(fi, GetOwningPlayer(GetAttacker())) and UnitAlive(fi) and GetUnitAbilityLevel(fi, 0x41303057) == 1 and GetUnitAbilityLevel(fi, 0x426D6C74) < 1\nset fi=null\n",
                  "local unit fi=GetFilterUnit()\nlocal boolean ok=IsUnitEnemy(fi, GetOwningPlayer(GetAttacker())) and UnitAlive(fi) and GetUnitAbilityLevel(fi, 0x41303057) == 1 and GetUnitAbilityLevel(fi, 0x426D6C74) < 1\nset fi=null\nreturn ok\n");
    }
    static final Set<String> DEAD_FUNCS = Set.of();

    public static void main(String[] a) throws Exception {
        String src = Files.readString(Paths.get(a[0]), StandardCharsets.UTF_8).replace("\r\n", "\n");
        Path out = Paths.get(a[1]);
        // trigger meta: category, name, desc (tab separated, first 2 lines are headers)
        List<String> trigOrder = new ArrayList<>(); Map<String, String> trigCat = new LinkedHashMap<>(); Map<String, String> trigDesc = new HashMap<>();
        for (String l : Files.readAllLines(Paths.get(a[2]), StandardCharsets.UTF_8).stream().skip(2).collect(Collectors.toList())) {
            if (l.isBlank() || !l.contains("\t")) continue;
            String[] p = l.split("\t", -1); String fnName = p[1].replace(' ', '_');
            trigOrder.add(fnName); trigCat.put(fnName, p[0]); trigDesc.put(fnName, p.length > 6 ? p[6] : "");
        }
        // apply exact fixes
        int fixed = 0;
        for (Map.Entry<String, String> e : FIXES.entrySet()) { if (!src.contains(e.getKey())) throw new RuntimeException("fix target not found: " + e.getKey()); src = src.replace(e.getKey(), e.getValue()); fixed++; }
        System.out.println("fixes applied: " + fixed);

        String[] lines = src.split("\n", -1);
        // globals block
        int gEnd = -1; for (int i = 0; i < lines.length; i++) if (lines[i].equals("endglobals")) { gEnd = i; break; }
        List<String> globals = new ArrayList<>(Arrays.asList(lines).subList(0, gEnd + 1));

        // functions
        List<Fn> fns = new ArrayList<>(); Fn cur = null; List<String> pending = new ArrayList<>();
        for (int i = gEnd + 1; i < lines.length; i++) {
            String l = lines[i];
            if (cur == null) {
                if (l.startsWith("function ")) { cur = new Fn(); cur.name = l.substring(9).split("\\s+")[0]; cur.pre = pending; pending = new ArrayList<>(); cur.lines.add(l); }
                else if (!l.isBlank()) pending.add(l);
            } else {
                if (l.startsWith("//Function not found:")) continue;      // dead WE call comments
                cur.lines.add(l);
                if (l.equals("endfunction")) { fns.add(cur); cur = null; }
            }
        }
        System.out.println("functions: " + fns.size());

        // assign groups
        Map<String, List<Fn>> byTrig = new LinkedHashMap<>();
        List<Fn> libs = new ArrayList<>(), initA = new ArrayList<>(), initB = new ArrayList<>(), tail = new ArrayList<>();
        Set<String> initANames = Set.of("InitGlobals", "CreatePlayerBuildings", "CreatePlayerUnits", "CreateAllUnits", "CreateRegions", "CreateNeutralHostile", "CreateNeutralPassive", "CreateNeutralPassiveBuildings", "CreateAllItems");
        Set<String> initBNames = Set.of("InitCustomTriggers", "RunInitializationTriggers", "InitCustomPlayerSlots", "InitCustomTeams", "InitAllyPriorities", "main", "config");
        List<Fn> pendingHelpers = new ArrayList<>(); boolean seenTrigger = false; boolean afterInitB = false;
        for (Fn f : fns) {
            if (DEAD_FUNCS.contains(f.name)) { System.out.println("removed dead: " + f.name); continue; }
            String trig = triggerOf(f.name, trigOrder);
            if (afterInitB) tail.add(f);
            else if (initBNames.contains(f.name)) { initB.add(f); if (f.name.equals("config")) afterInitB = true; }
            else if (initANames.contains(f.name) || f.name.startsWith("CreateBuildingsForPlayer") || f.name.startsWith("CreateUnitsForPlayer")) initA.add(f);
            else if (trig != null) { seenTrigger = true; List<Fn> l = byTrig.computeIfAbsent(trig, k -> new ArrayList<>()); l.addAll(pendingHelpers); pendingHelpers.clear(); l.add(f); }
            else if (!seenTrigger && initA.isEmpty()) libs.add(f);      // before InitGlobals: cJass/vJass libraries
            else pendingHelpers.add(f);                                   // custom-script helper, belongs to the next trigger's chunk
        }
        if (!pendingHelpers.isEmpty()) { System.out.println("WARN trailing helpers: " + pendingHelpers.stream().map(x -> x.name).collect(Collectors.toList())); byTrig.computeIfAbsent(trigOrder.get(trigOrder.size() - 1), k -> new ArrayList<>()).addAll(pendingHelpers); }
        System.out.println("libs=" + libs.size() + " initA=" + initA.size() + " triggers=" + byTrig.size() + " initB=" + initB.size() + " tail=" + tail.size());
        for (String t : trigOrder) if (!byTrig.containsKey(t)) System.out.println("trigger without functions: " + t);

        // ---- emit
        StringBuilder sb = new StringBuilder();
        sb.append("//============================================================================\n");
        sb.append("//  The War Reborn — скрипт карты (JASS)\n");
        sb.append("//  Собран из триггеров World Editor (cJass/vJass) и приведён в порядок:\n");
        sb.append("//  разделы = папки триггеров в редакторе, внутри — триггеры в исходном порядке.\n");
        sb.append("//  Wurst подхватывает этот файл при сборке (wurst/war3map.j, -noExtractMapScript).\n");
        sb.append("//============================================================================\n\n");
        for (String g : globals) sb.append(indentGlobals(g)).append("\n");
        sb.append("\n");
        banner(sb, "БИБЛИОТЕКИ (cJass / vJass: cjLib, MMD, SendResult)");
        String lastLib = "";
        for (Fn f : libs) { String lib = libOf(f.name); if (!lib.equals(lastLib)) { sb.append("//---- ").append(lib).append(" ----\n"); lastLib = lib; } emit(sb, f); }
        banner(sb, "ИНИЦИАЛИЗАЦИЯ КАРТЫ (World Editor: глобалы, юниты, регионы)");
        for (Fn f : initA) emit(sb, f);
        for (String cat : CAT_ORDER) {
            List<String> trigs = trigOrder.stream().filter(t -> cat.equals(trigCat.get(t)) && byTrig.containsKey(t)).collect(Collectors.toList());
            if (trigs.isEmpty()) continue;
            banner(sb, CAT_TITLE.getOrDefault(cat, cat.trim().toUpperCase()));
            for (String t : trigs) {
                sb.append("//---------------------------------------------------------------------------\n");
                sb.append("// Триггер: ").append(t.replace('_', ' ')).append("\n");
                String d = trigDesc.getOrDefault(t, "").trim();
                if (!d.isEmpty()) for (String w : wrap(d, 90)) sb.append("//   ").append(w).append("\n");
                sb.append("//---------------------------------------------------------------------------\n");
                for (Fn f : byTrig.get(t)) emit(sb, f);
            }
        }
        // triggers whose category is unknown
        for (String t : byTrig.keySet()) if (!CAT_ORDER.contains(trigCat.get(t))) { System.out.println("WARN uncategorised trigger: " + t + " cat=" + trigCat.get(t)); banner(sb, "ПРОЧЕЕ: " + t); for (Fn f : byTrig.get(t)) emit(sb, f); }
        banner(sb, "ЗАПУСК КАРТЫ (World Editor: InitCustomTriggers, игроки, команды, main, config)");
        for (Fn f : initB) emit(sb, f);
        if (!tail.isEmpty()) { banner(sb, "JASSHELPER: инициализация структур (вызывается через ExecuteFunc из main)"); for (Fn f : tail) emit(sb, f); }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("written " + out + " rawcodes converted: " + rawConverted);
    }

    static String triggerOf(String fn, List<String> trigs) {
        String best = null;
        for (String t : trigs) {
            if (fn.equals("InitTrig_" + t)) return t;
            if (fn.startsWith("Trig_" + t + "_") && (best == null || t.length() > best.length())) best = t;
        }
        return best;
    }
    static String libOf(String n) {
        if (n.startsWith("cj") ) return "cjLib";
        if (n.contains("MMD")) return "MMD (Warcraft III Map Meta Data)";
        if (n.startsWith("SendResult")) return "SendResult";
        return "прочее";
    }
    static void banner(StringBuilder sb, String title) {
        sb.append("\n\n//============================================================================\n//  ").append(title).append("\n//============================================================================\n\n");
    }
    static void emit(StringBuilder sb, Fn f) {
        for (String p : f.pre) if (!p.startsWith("//library ") && !p.startsWith("//globals from") && !p.startsWith("//endglobals from")) sb.append(p).append("\n");
        int depth = 0;
        for (String raw : f.lines) {
            String l = raw.strip();
            if (l.isEmpty()) continue;
            String w = l.split("\\s+")[0];
            if (w.equals("endfunction") || w.equals("endif") || w.equals("endloop") || w.equals("else") || w.equals("elseif")) depth--;
            sb.append("    ".repeat(Math.max(0, depth))).append(rawcodes(l)).append("\n");
            if (w.equals("function") || w.equals("if") || w.equals("loop") || w.equals("else") || w.equals("elseif")) depth++;
        }
        sb.append("\n");
    }
    static String indentGlobals(String l) { String s = l.strip(); if (s.equals("globals") || s.equals("endglobals") || s.startsWith("//")) return s; return "    " + rawcodes(s); }

    static int rawConverted = 0;
    static final Pattern HEX = Pattern.compile("0x([0-9A-Fa-f]{8})\\b");
    /** 0x41303030 -> 'A000' outside of string literals and comments; value-preserving (JASS 'xxxx' is an integer literal). */
    static String rawcodes(String l) {
        StringBuilder o = new StringBuilder(); int i = 0, n = l.length();
        while (i < n) {
            char c = l.charAt(i);
            if (c == '"') { int j = i + 1; while (j < n && l.charAt(j) != '"') { if (l.charAt(j) == '\\') j++; j++; } o.append(l, i, Math.min(n, j + 1)); i = j + 1; continue; }
            if (c == '/' && i + 1 < n && l.charAt(i + 1) == '/') { o.append(l.substring(i)); break; }
            if (c == '0' && i + 9 <= n && l.charAt(i + 1) == 'x') {
                Matcher m = HEX.matcher(l); if (m.find(i) && m.start() == i) {
                    long v = Long.parseLong(m.group(1), 16); StringBuilder r = new StringBuilder("'"); boolean ok = true;
                    for (int k = 3; k >= 0; k--) { int b = (int) ((v >> (8 * k)) & 0xFF); if (b < 0x20 || b > 0x7E || b == '\'' || b == '\\') { ok = false; break; } r.append((char) b); }
                    if (ok) { o.append(r).append("'"); i = m.end(); rawConverted++; continue; }
                }
            }
            o.append(c); i++;
        }
        return o.toString();
    }
    static List<String> wrap(String s, int w) { List<String> r = new ArrayList<>(); StringBuilder cur = new StringBuilder(); for (String word : s.split("\\s+")) { if (cur.length() + word.length() + 1 > w && cur.length() > 0) { r.add(cur.toString()); cur = new StringBuilder(); } if (cur.length() > 0) cur.append(' '); cur.append(word); } if (cur.length() > 0) r.add(cur.toString()); return r; }
}
