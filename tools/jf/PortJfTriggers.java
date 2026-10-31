import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Переносит триггеры юнитов Just Fight (war3map.j) в Wurst-пакет The War: триггеры событий юнитов (атака, каст, смерть),
 * которые касаются скопированных объектов Just Fight (их ID — в соответствиях CopyJfUnits), вместе со всеми функциями
 * и глобальными переменными, которые они используют. ID скопированных объектов заменяются на ID копий, ID остальных
 * объектов Just Fight — на 0 (в The War под тем же ID другой объект), стандартные ID игры остаются. Модели Just Fight
 * из корня архива — путь imports/models (если модель импортирована). Не переносятся триггеры наград и интерфейса
 * игроков Just Fight (таблицы, диалоги — в The War свои) и перечисленные в списке исключений (награда за убийство).
 *
 * Подписки на события любого юнита — через диспетчер PveJfSupport (jfRegister) с ключами из условий триггеров.
 *
 *   java PortJfTriggers <war3map.j> <соответствия copy_map.tsv> <каталог экспорта объектов> <imports/models> <выход .wurst> <исключения> [выход: ещё не скопированные объекты]
 */
public class PortJfTriggers {
    static final Pattern FUNC = Pattern.compile("^function ([A-Za-z0-9_]+) takes");
    static final Pattern REF = Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z_][A-Za-z0-9_]*)(?![A-Za-z0-9_])");
    static final Pattern RAW = Pattern.compile("'([A-Za-z0-9]{4})'");
    static final Pattern HEX = Pattern.compile("(?<![A-Za-z0-9_])0[xX]([0-9A-Fa-f]+)");
    static final Pattern REAL_TRAIL = Pattern.compile("(?<![A-Za-z0-9_.])(\\d+)\\.(?![0-9A-Za-z_])");
    static final Pattern REAL_LEAD = Pattern.compile("(?<![A-Za-z0-9_.])\\.(\\d+)");
    static final Pattern MODEL = Pattern.compile("\"([A-Za-z0-9_]+)\\.(?:mdl|mdx)\"", Pattern.CASE_INSENSITIVE);
    /** События юнитов; появление на карте — обёртка Just Fight LllLlI (TriggerRegisterEnterRegion) со всей картой
        (с областями Just Fight — нет: их в The War нет); урон — система Just Fight LllLII (EVENT_UNIT_DAMAGED всем) */
    static final Pattern UNIT_EVENT = Pattern.compile("TriggerRegister(?:AnyUnitEventBJ|PlayerUnitEvent(?:Simple)?)\\(|LllLlI\\([A-Za-z0-9_]+,GetPlayableMapRect\\(\\)\\)|LllLII\\([A-Za-z0-9_]+\\)");
    /** Интерфейс игроков Just Fight — в The War свой */
    static final Pattern PLAYER_STUFF = Pattern.compile("Multiboard|Leaderboard|Dialog|SetPlayerTech|DzAPI|Dz[A-Z]|YDWE");
    /** Вызовы, завязанные на области Just Fight, -> функции The War (PveJfSupport): LLILlI — юнит IIL1 снова в атаку
        по своей линии (областями Just Fight, их в The War нет) */
    static final Map<String, String> REPLACE = Map.of(
        "call TriggerExecute(LLILlI)", "call jfAttackLane(IIL1)",
        "call ConditionalTriggerExecute(LLILlI)", "call jfAttackLane(IIL1)");
    /** Глобальные Just Fight, которые здесь — значения The War (PveJfSupport): LIlLI — уровень PvE (1..5, 6 — бесконечный) */
    static final Map<String, String> GLOBAL_REPLACE = Map.of("LIlLI", "jfPveLevel()");
    /** Вспомогательный триггер, который вызывают через TriggerExecute: его создаёт своя функция инициализации */
    static final Pattern EXECUTE = Pattern.compile("Trigger(?:Execute|Evaluate)\\(([A-Za-z0-9_]+)\\)");

    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8);
        Map<String, String> copies = new HashMap<>();
        for (String l : Files.readAllLines(Paths.get(a[1]), StandardCharsets.UTF_8)) {
            String[] f = l.split("\t");
            if (f.length >= 2) copies.put(f[0], f[1]);
        }
        // объекты Just Fight; изменённые стандартные ('BHwe' на основе 'BHwe') — объекты игры, их ID остаются
        Set<String> jfObjects = new HashSet<>();
        Set<String> standard = new HashSet<>();
        Pattern created = Pattern.compile("create_w3[a-z]_([A-Za-z0-9]{4})\\(");
        Pattern sameBase = Pattern.compile("'([A-Za-z0-9]{4})', '\\1'\\)");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(a[2]), "*.wurst")) {
            for (Path p : ds) for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                Matcher m = created.matcher(l);
                if (m.find()) jfObjects.add(m.group(1));
                Matcher s = sameBase.matcher(l);
                if (s.find()) standard.add(s.group(1));
            }
        }
        jfObjects.removeAll(standard);
        Path models = Paths.get(a[3]);
        Set<String> exclude = new HashSet<>();
        for (String l : Files.readAllLines(Paths.get(a[5]), StandardCharsets.UTF_8)) {
            l = l.replaceAll("#.*", "").trim();
            if (!l.isEmpty()) exclude.add(l);
        }

        // глобальные и функции
        Map<String, String> globals = new LinkedHashMap<>();
        Map<String, List<String>> funcs = new LinkedHashMap<>();
        int i = 0;
        if (lines.get(0).trim().equals("globals")) {
            for (i = 1; !lines.get(i).trim().equals("endglobals"); i++) {
                String[] w = lines.get(i).trim().split("\\s+");
                if (w.length < 2) continue;
                String name = w[1].equals("array") ? w[2] : w[1].split("=")[0];
                globals.put(name, lines.get(i).trim());
            }
        }
        String cur = null;
        for (; i < lines.size(); i++) {
            String l = lines.get(i);
            Matcher m = FUNC.matcher(l);
            if (m.find()) { cur = m.group(1); funcs.put(cur, new ArrayList<>()); }
            for (Map.Entry<String, String> r : REPLACE.entrySet()) l = l.replace(r.getKey(), r.getValue());
            if (cur != null) funcs.get(cur).add(l);
            if (l.startsWith("endfunction")) cur = null;
        }

        // триггеры событий юнитов: функция-инициализатор -> её условия и действия
        List<String> port = new ArrayList<>();
        Set<String> need = new LinkedHashSet<>();
        StringBuilder report = new StringBuilder();
        for (Map.Entry<String, List<String>> e : funcs.entrySet()) {
            String body = String.join("\n", e.getValue());
            if (!UNIT_EVENT.matcher(body).find() || !body.contains("CreateTrigger()")) continue;
            Set<String> closure = closure(e.getKey(), funcs);
            String text = closureText(closure, funcs);
            boolean touches = false;
            Matcher r = RAW.matcher(text);
            while (r.find()) if (copies.containsKey(r.group(1))) { touches = true; break; }
            if (!touches) continue;
            if (exclude.contains(e.getKey())) { report.append("исключён: ").append(e.getKey()).append('\n'); continue; }
            // условие не про скопированные объекты (а про юниты/героев Just Fight, которых нет) — триггер не наш
            Matcher cm = Pattern.compile("TriggerAddCondition\\([^,]+,Condition\\(function ([A-Za-z0-9_]+)\\)\\)").matcher(body);
            if (cm.find()) {
                boolean own = false;
                Matcher cr = RAW.matcher(closureText(closure(cm.group(1), funcs), funcs));
                while (cr.find()) if (copies.containsKey(cr.group(1))) { own = true; break; }
                if (!own) { report.append("условие не про копии: ").append(e.getKey()).append('\n'); continue; }
            }
            Matcher ps = PLAYER_STUFF.matcher(text);
            if (ps.find()) { report.append("награды/интерфейс (").append(ps.group()).append("): ").append(e.getKey()).append('\n'); continue; }
            port.add(e.getKey());
            need.addAll(closure);
        }
        // вспомогательные триггеры (TriggerExecute): их инициализация — тоже в init, пока новых не найдётся
        Map<String, String> creator = new HashMap<>();
        Pattern create = Pattern.compile("^set ([A-Za-z0-9_]+)=CreateTrigger\\(\\)");
        for (Map.Entry<String, List<String>> e : funcs.entrySet()) for (String l : e.getValue()) {
            Matcher m = create.matcher(l.trim());
            if (m.find()) creator.putIfAbsent(m.group(1), e.getKey());
        }
        for (boolean grew = true; grew; ) {
            grew = false;
            Matcher x = EXECUTE.matcher(closureText(need, funcs));
            Set<String> add = new LinkedHashSet<>();
            while (x.find()) {
                String init = creator.get(x.group(1));
                if (init != null && !port.contains(init) && !add.contains(init)) add.add(init);
            }
            for (String init : add) {
                port.add(init);
                need.addAll(closure(init, funcs));
                grew = true;
            }
        }

        // нужные глобальные
        Set<String> usedGlobals = new TreeSet<>();
        for (String f : need) for (String l : funcs.get(f)) {
            Matcher m = REF.matcher(l.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\""));
            while (m.find()) if (globals.containsKey(m.group(1)) && !GLOBAL_REPLACE.containsKey(m.group(1))) usedGlobals.add(m.group(1));
        }

        StringBuilder out = new StringBuilder();
        out.append("package PveJfTriggers\n");
        out.append("import PveJfSupport\n");
        out.append("import UnitEvents\n");
        out.append("import ClosureEvents\n");
        out.append("import ClosureTimers\n");
        out.append("import Preloader\n");
        out.append("// Триггеры юнитов Just Fight 5.6.2 (war3map.j) для копий юнитов Just Fight (UnitsPveJf): способности, которые\n");
        out.append("// в Just Fight делает скрипт карты. Сгенерировано tools/jf/gen_pve_waves.sh (PortJfTriggers.java, JassToWurst.java);\n");
        out.append("// руками не править. Каждый триггер — функция с условием в начале, подписка в jfRegisterTriggers\n// (UnitEvents, EventListener.onCast, onUnitEnter) — её вызывают старты PvE и PvEvP.\n");
        out.append("globals\n");
        for (String g : usedGlobals) out.append(convert(global(globals.get(g)), copies, jfObjects, models)).append('\n');
        out.append("endglobals\n\n");
        Set<String> intGlobals = new HashSet<>();
        for (Map.Entry<String, String> g : globals.entrySet()) if (g.getValue().startsWith("integer ")) intGlobals.add(g.getKey());
        for (String f : need) {
            List<String> body = dropUnusedLocals(funcs.get(f));
            Set<String> ints = new HashSet<>(intGlobals);
            Matcher p = Pattern.compile("integer ([A-Za-z0-9_]+)").matcher(body.get(0).replaceAll(" returns .*", ""));
            while (p.find()) ints.add(p.group(1));
            for (String l : body) {
                Matcher lm = Pattern.compile("^local integer ([A-Za-z0-9_]+)").matcher(l.trim());
                if (lm.find()) ints.add(lm.group(1));
            }
            boolean returnsInt = body.get(0).endsWith("returns integer");
            for (String l : body) out.append(convert(intDivision(l, ints, returnsInt), copies, jfObjects, models)).append('\n');
        }
        out.append("\ninit\n");
        for (String f : port) out.append(convert("call " + f + "()", copies, jfObjects, models)).append('\n');
        out.append("endinit\nendpackage\n");
        Path names = Paths.get(a[5]).resolveSibling("names.txt");
        JassToWurst.readObjects(Paths.get(a[4]).getParent().getParent().resolve("objects/units/UnitsPveJf.wurst"));
        String wurst = JassToWurst.convert(dispatch(out.toString(), report), JassToWurst.readNames(names));
        // «не мёртв» у Just Fight — флаг UNIT_TYPE_DEAD; юнит, убитый тем же ударом, ещё не помечен мёртвым (HP уже 0):
        // его выбирали и удаляли посреди собственной смерти (змея 'nJ4G' «съедала» такого) — проверяем ещё и HP
        wurst = wurst.replaceAll("not ([A-Za-z0-9_]+(?:\\(\\))?)\\.isType\\(UNIT_TYPE_DEAD\\)", "not $1.isType(UNIT_TYPE_DEAD) and $1.getHP() > 0.405");
        wurst = poolDummies(wurst);
        Files.writeString(Paths.get(a[4]), wurst, StandardCharsets.UTF_8);
        // объекты Just Fight, которые нужны перенесённым функциям, но ещё не скопированы, — для следующего прохода CopyJfUnits
        if (a.length > 6) {
            Set<String> missing = new TreeSet<>();
            Matcher r = RAW.matcher(closureText(need, funcs).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\""));
            while (r.find()) if (jfObjects.contains(r.group(1)) && !copies.containsKey(r.group(1))) missing.add(r.group(1));
            Files.write(Paths.get(a[6]), missing, StandardCharsets.UTF_8);
        }
        System.err.print(report);
        System.out.println("ported " + port.size() + " triggers, " + need.size() + " functions, " + usedGlobals.size() + " globals");
    }

    // ---- диспетчер событий (PveJfSupport.jfRegister) ----

    static final Pattern REGISTER = Pattern.compile("^call (?:TriggerRegisterAnyUnitEventBJ\\(([A-Za-z0-9_]+),EVENT_PLAYER_UNIT_(ATTACKED|DEATH|SPELL_EFFECT|SPELL_FINISH|SPELL_CAST)\\)|jfLllLlI\\(([A-Za-z0-9_]+),GetPlayableMapRect\\(\\)\\)|jfLllLII\\(([A-Za-z0-9_]+)\\))$");
    static final String ROLE = "(GetAttacker|GetTriggerUnit|GetAttackedUnitBJ|GetDyingUnit|GetKillingUnitBJ|GetKillingUnit|GetSpellAbilityUnit|GetEnteringUnit|GetEventDamageSource)\\(\\)";
    static final String ID = "('[A-Za-z0-9]{4}'|0)";
    static final Pattern A_TYPE = Pattern.compile("^GetUnitTypeId\\(" + ROLE + "\\)==" + ID + "$");
    static final Pattern A_SPELL = Pattern.compile("^GetSpellAbilityId\\(\\)==" + ID + "$");
    static final Pattern A_LEVEL_SWAPPED = Pattern.compile("^GetUnitAbilityLevelSwapped\\(" + ID + "," + ROLE + "\\)(==|>=|>|!=)(\\d+)$");
    static final Pattern A_LEVEL = Pattern.compile("^GetUnitAbilityLevel\\(" + ROLE + "," + ID + "\\)(==|>=|>|!=)(\\d+)$");
    static final Pattern A_BUFF = Pattern.compile("^UnitHasBuffBJ\\(" + ROLE + "," + ID + "\\)(?:==true)?$");

    /**
     * Подписки триггеров Just Fight на события любого юнита -> регистрация в диспетчере: по условию триггера — без чего
     * оно не выполнится (ключи «вид,юнит,ID»; null — неизвестно, проверять всегда; пусто — не выполнится никогда:
     * объект Just Fight, которого нет)
     */
    static final Pattern DUMMY_CREATE = Pattern.compile("^(\\s*)(let )?([A-Za-z0-9_]+) = createUnit\\((.+?), 'eJ6O', (.+?), bj_UNIT_FACING\\.fromDeg\\(\\)\\)$");
    /** Даммик Just Fight 'eJ6O' короткого эффекта (живёт до DUMMY_MAX_LIFE с) — из пула The War (PveJfSupport.jfDummy):
        createUnit → jfDummy, setTimedLife → jfDummyLife, addAbility → jfDummyAbility, hide убирается. Долгие даммики
        (вселение, долгий поводок) и особые (выдают предмет, сами снимают способность) — как в Just Fight */
    static final double DUMMY_MAX_LIFE = 5.0;

    static String poolDummies(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null) continue;
            Matcher m = DUMMY_CREATE.matcher(lines[i]);
            if (!m.find()) continue;
            String indent = m.group(1), v = Pattern.quote(m.group(3));
            int end = i + 1;
            // блок даммика — до первой строки с отступом меньше, чем у его создания (конец ветки/функции)
            while (end < lines.length && (lines[end] == null || lines[end].isBlank() || lines[end].length() - lines[end].stripLeading().length() >= indent.length()))
                end++;
            double life = -1;
            boolean special = false;
            Pattern lifeP = Pattern.compile(v + "\\.setTimedLife\\(([0-9.]+), 'BHwe'\\)");
            for (int j = i + 1; j < end; j++) {
                if (lines[j] == null) continue;
                Matcher lm = lifeP.matcher(lines[j]);
                if (lm.find()) life = Double.parseDouble(lm.group(1));
                if (lines[j].contains(m.group(3) + ".removeAbility(") || lines[j].contains(m.group(3) + ".addItemById(")) special = true;
            }
            if (life < 0 || life > DUMMY_MAX_LIFE || special) continue;
            lines[i] = indent + (m.group(2) == null ? "" : "let ") + m.group(3) + " = jfDummy(" + m.group(4) + ", " + m.group(5) + ")";
            for (int j = i + 1; j < end; j++) {
                if (lines[j] == null) continue;
                lines[j] = lines[j].replaceAll(v + "\\.setTimedLife\\(([0-9.]+), 'BHwe'\\)", Matcher.quoteReplacement(m.group(3)) + ".jfDummyLife($1)")
                    .replaceAll(v + "\\.addAbility\\(", Matcher.quoteReplacement(m.group(3)) + ".jfDummyAbility(");
                if (lines[j].trim().equals(m.group(3) + ".hide()")) lines[j] = null;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) if (lines[i] != null) sb.append(lines[i]).append(i < lines.length - 1 ? "\n" : "");
        return sb.toString();
    }

    static String dispatch(String text, StringBuilder report) {
        Map<String, List<String>> funcs = new HashMap<>();
        String cur = null;
        for (String l : text.split("\n")) {
            Matcher m = FUNC.matcher(l);
            if (m.find()) { cur = m.group(1); funcs.put(cur, new ArrayList<>()); }
            if (cur != null) funcs.get(cur).add(l);
            if (l.startsWith("endfunction")) cur = null;
        }
        Map<String, List<String>> conditions = new HashMap<>();
        Matcher cm = Pattern.compile("call TriggerAddCondition\\(([A-Za-z0-9_]+),Condition\\(function ([A-Za-z0-9_]+)\\)\\)").matcher(text);
        while (cm.find()) conditions.computeIfAbsent(cm.group(1), k -> new ArrayList<>()).add(cm.group(2));
        StringBuilder out = new StringBuilder();
        int always = 0, never = 0, keyed = 0;
        for (String l : text.split("\n", -1)) {
            Matcher m = REGISTER.matcher(l);
            if (!m.find()) { out.append(l).append('\n'); continue; }
            String t = m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(4);
            String ev = m.group(2) != null ? "JF_" + m.group(2) : m.group(3) != null ? "JF_ENTER" : "JF_DAMAGED";
            List<String> keys = null;
            for (String c : conditions.getOrDefault(t, List.of())) keys = and(keys, conditionKeys(funcs.get(c)));
            if (keys == null) {
                always++;
                report.append("диспетчер, без ключа: ").append(t).append('\n');
                out.append("call jfRegister(").append(t).append(',').append(ev).append(",JF_ALWAYS,0,0)\n");
            } else if (keys.isEmpty()) {
                never++;
                report.append("диспетчер, не сработает (объектов нет): ").append(t).append('\n');
            } else {
                keyed++;
                for (String k : keys) out.append("call jfRegister(").append(t).append(',').append(ev).append(',').append(k).append(")\n");
            }
        }
        System.out.println("dispatch: " + keyed + " keyed, " + always + " always, " + never + " never");
        return out.substring(0, out.length() - 1);
    }

    static List<String> conditionKeys(List<String> body) {
        if (body == null) return null;
        List<String> st = new ArrayList<>();
        for (String l : body.subList(1, body.size() - 1)) if (!l.trim().isEmpty()) st.add(l.trim());
        if (st.size() != 1 || !st.get(0).startsWith("return")) return null;
        int[] pos = {0};
        String e = st.get(0).substring("return".length()).trim();
        List<String> k = parseOr(e, pos);
        return pos[0] == e.length() ? k : null;
    }

    /** И: хватит ключей одной части — лучше без проверок способностей и с меньшим числом ключей */
    static List<String> and(List<String> a, List<String> b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.isEmpty() || b.isEmpty()) return List.of();
        return score(a) <= score(b) ? a : b;
    }

    static int score(List<String> k) {
        int s = 0;
        for (String x : k) s += x.startsWith("JF_LEVEL") ? 100 : 1;
        return s;
    }

    /** ИЛИ: нужны ключи обеих частей */
    static List<String> or(List<String> a, List<String> b) {
        if (a == null || b == null) return null;
        List<String> r = new ArrayList<>(a);
        for (String x : b) if (!r.contains(x)) r.add(x);
        return r;
    }

    static boolean keyword(String s, int i, String w) {
        return s.startsWith(w, i) && (i + w.length() >= s.length() || !Character.isLetterOrDigit(s.charAt(i + w.length())) && s.charAt(i + w.length()) != '_');
    }

    static List<String> parseOr(String s, int[] p) {
        List<String> k = parseAnd(s, p);
        while (keyword(s, p[0], "or")) { p[0] += 2; k = or(k, parseAnd(s, p)); }
        return k;
    }

    static List<String> parseAnd(String s, int[] p) {
        List<String> k = parseFactor(s, p);
        while (keyword(s, p[0], "and")) { p[0] += 3; k = and(k, parseFactor(s, p)); }
        return k;
    }

    static List<String> parseFactor(String s, int[] p) {
        if (keyword(s, p[0], "not")) { p[0] += 3; parseFactor(s, p); return null; }
        int start = p[0];
        if (start < s.length() && s.charAt(start) == '(') {
            p[0]++;
            List<String> k = parseOr(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ')') {
                p[0]++;
                if (p[0] == s.length() || s.charAt(p[0]) == ')' || keyword(s, p[0], "and") || keyword(s, p[0], "or")) return k;
            }
            p[0] = start; // «(a/b)==c» — скобки внутри сравнения
        }
        // сравнение: до «)», «and», «or» вне скобок
        int depth = 0, i = start;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') { int j = s.indexOf(c, i + 1); i = j < 0 ? s.length() : j; continue; }
            if (c == '(') depth++;
            else if (c == ')') { if (depth == 0) break; depth--; }
            else if (depth == 0 && i > start && !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_'
                && (keyword(s, i, "and") || keyword(s, i, "or"))) break;
        }
        p[0] = i;
        return atom(s.substring(start, i));
    }

    static List<String> atom(String a) {
        Matcher m;
        if ((m = A_TYPE.matcher(a)).matches()) return key("JF_TYPE", m.group(1), m.group(2));
        if ((m = A_SPELL.matcher(a)).matches()) return key("JF_SPELL", null, m.group(1));
        if ((m = A_LEVEL_SWAPPED.matcher(a)).matches())
            return positive(m.group(3), Integer.parseInt(m.group(4))) ? key("JF_LEVEL", m.group(2), m.group(1)) : null;
        if ((m = A_LEVEL.matcher(a)).matches())
            return positive(m.group(3), Integer.parseInt(m.group(4))) ? key("JF_LEVEL", m.group(1), m.group(2)) : null;
        if ((m = A_BUFF.matcher(a)).matches()) return key("JF_LEVEL", m.group(1), m.group(2));
        return null;
    }

    /** Уровень способности больше нуля */
    static boolean positive(String op, int n) {
        return op.equals("==") ? n >= 1 : op.equals(">=") ? n >= 1 : op.equals(">") ? n >= 0 : n == 0;
    }

    static List<String> key(String kind, String role, String id) {
        if (id.equals("0")) return List.of(); // объект Just Fight, которого нет: никогда
        int r = role == null ? 0 : role.equals("GetAttacker") || role.equals("GetEventDamageSource") ? 1 : role.startsWith("GetKillingUnit") ? 2 : 0;
        return new ArrayList<>(List.of(kind + "," + r + "," + id));
    }

    /** Функции, достижимые из f: вызовы и ссылки «function X» */
    static Set<String> closure(String f, Map<String, List<String>> funcs) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(f));
        while (!q.isEmpty()) {
            String g = q.poll();
            if (!seen.add(g)) continue;
            for (String l : funcs.get(g)) {
                Matcher m = REF.matcher(l.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\""));
                while (m.find()) if (funcs.containsKey(m.group(1)) && !seen.contains(m.group(1))) q.add(m.group(1));
            }
        }
        return seen;
    }

    static String closureText(Set<String> closure, Map<String, List<String>> funcs) {
        StringBuilder sb = new StringBuilder();
        for (String g : closure) sb.append(String.join("\n", funcs.get(g))).append('\n');
        return sb.toString();
    }

    /** Глобальная Just Fight в Jurst: хеш-таблицы создаются сразу (в Just Fight — при инициализации карты) */
    static String global(String decl) {
        if (decl.startsWith("hashtable ") && !decl.contains("=")) return decl + "=InitHashtable()";
        return decl;
    }

    /** Локальные, которые только объявляются и обнуляются, — без предупреждений компилятора */
    static List<String> dropUnusedLocals(List<String> body) {
        List<String> res = new ArrayList<>(body);
        // обнуление параметров в конце — тоже
        Matcher pm = Pattern.compile("(?:takes |,)[A-Za-z]+ ([A-Za-z0-9_]+)").matcher(body.get(0).replaceAll(" returns .*", ""));
        while (pm.find()) {
            String v = pm.group(1);
            res.removeIf(x -> x.trim().equals("set " + v + "=null"));
        }
        for (String l : body) {
            Matcher m = Pattern.compile("^local [A-Za-z]+ ([A-Za-z0-9_]+)").matcher(l.trim());
            if (!m.find()) continue;
            String v = m.group(1);
            // обнуление локальной в конце (утечки ссылок в JASS) — Wurst делает это сам
            res.removeIf(x -> x.trim().equals("set " + v + "=null"));
            if (!l.trim().matches("^local [A-Za-z]+ " + v + "$")) continue;
            Pattern use = Pattern.compile("(?<![A-Za-z0-9_])" + v + "(?![A-Za-z0-9_])");
            long uses = body.stream().filter(x -> use.matcher(x).find()).count();
            long nulls = body.stream().filter(x -> x.trim().equals("set " + v + "=null")).count();
            if (uses == 1 + nulls) res.removeIf(x -> x.trim().equals(l.trim()) || x.trim().equals("set " + v + "=null"));
        }
        return res;
    }

    static final Pattern INT_TARGET = Pattern.compile("^(set ([A-Za-z0-9_]+)(?:\\[[^\\]]*\\])?=|local integer [A-Za-z0-9_]+=|return)(.*)$");
    static final Pattern REAL_LITERAL = Pattern.compile("\\d\\.|\\.\\d|I2R|GetUnitState|GetUnitFacing|GetLocation|Get[A-Za-z]*X\\(|Get[A-Za-z]*Y\\(");

    /** В JASS «/» над целыми — целое деление, в Wurst — дробное: в целую переменную (или return integer) без
        дробных значений справа — «div» */
    static String intDivision(String l, Set<String> ints, boolean returnsInt) {
        if (!l.contains("/") || l.contains("\"")) return l;
        Matcher m = INT_TARGET.matcher(l.trim());
        if (!m.find()) return l;
        boolean intTarget = m.group(1).startsWith("local integer") || (m.group(1).equals("return") ? returnsInt : ints.contains(m.group(2)));
        if (!intTarget) return l;
        // R2I(…) — целое; внутри него деление вещественное
        String e = m.group(3);
        char[] outside = e.toCharArray();
        for (int i = e.indexOf("R2I("); i >= 0; i = e.indexOf("R2I(", i + 1)) {
            int depth = 0;
            for (int j = i + 3; j < e.length(); j++) {
                char c = e.charAt(j);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) break;
                if (j > i + 3) outside[j] = ' ';
            }
        }
        if (REAL_LITERAL.matcher(new String(outside)).find()) return l;
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < e.length(); i++) r.append(outside[i] == '/' ? " div " : String.valueOf(e.charAt(i)));
        return m.group(1) + r;
    }

    static String convert(String l, Map<String, String> copies, Set<String> jfObjects, Path models) {
        // строки не трогаем, кроме путей моделей
        StringBuilder sb = new StringBuilder();
        Matcher s = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"").matcher(l);
        int last = 0;
        while (s.find()) {
            sb.append(code(l.substring(last, s.start()), copies, jfObjects));
            String str = s.group();
            Matcher mm = MODEL.matcher(str);
            if (mm.matches() && Files.exists(models.resolve(mm.group(1) + ".mdx"))) str = "\"models\\\\" + mm.group(1) + ".mdx\"";
            sb.append(str);
            last = s.end();
        }
        sb.append(code(l.substring(last), copies, jfObjects));
        return sb.toString();
    }

    /** Имена Just Fight (после обфускации — только из I, l, L, 1) — с маленькой буквы, как принято в Wurst: jf… */
    static final Pattern OBFUSCATED = Pattern.compile("(?<![A-Za-z0-9_'])([IlL][IlL1]*)(?![A-Za-z0-9_])");

    static String code(String c, Map<String, String> copies, Set<String> jfObjects) {
        for (Map.Entry<String, String> g : GLOBAL_REPLACE.entrySet())
            c = c.replaceAll("(?<![A-Za-z0-9_'])" + g.getKey() + "(?![A-Za-z0-9_])", Matcher.quoteReplacement(g.getValue()));
        c = OBFUSCATED.matcher(c).replaceAll("jf$1");
        Matcher r = RAW.matcher(c);
        StringBuffer sb = new StringBuffer();
        while (r.find()) {
            String id = r.group(1);
            String rep = copies.containsKey(id) ? "'" + copies.get(id) + "'" : (jfObjects.contains(id) ? "0" : r.group());
            r.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        r.appendTail(sb);
        c = sb.toString();
        // 0xCFDE6C76 — в JASS отрицательное число: в десятичную со знаком
        Matcher h = HEX.matcher(c);
        sb = new StringBuffer();
        while (h.find()) h.appendReplacement(sb, Integer.toString((int) Long.parseLong(h.group(1), 16)));
        h.appendTail(sb);
        c = sb.toString();
        c = REAL_TRAIL.matcher(c).replaceAll("$1.0");
        c = REAL_LEAD.matcher(c).replaceAll("0.$1");
        return c;
    }
}
