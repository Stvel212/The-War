import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Копирует юниты Just Fight (из экспорта grill exportobjects) в Wurst-пакет The War вместе со всем, на что они
 * ссылаются: способностями, баффами, призываемыми юнитами. ID Just Fight пересекаются с ID The War (тот же ID —
 * другой юнит), поэтому всё получает новые ID вида ?J?? (первая буква юнита сохраняется: заглавная — герой).
 * Ссылки на улучшения и требования Just Fight очищаются (их в The War нет).
 *
 *   java CopyJfUnits <каталог экспорта> <список ID юнитов> <выход .wurst> <выход соответствий> <выход путей файлов> <каталог stdlib objediting>
 */
public class CopyJfUnits {
    static final Pattern START = Pattern.compile("^@compiletime function create_(w3[uah])_([A-Za-z0-9]{4})\\(\\)");
    static final Pattern TOKEN = Pattern.compile("(?<![A-Za-z0-9])([A-Za-z0-9]{4})(?![A-Za-z0-9])");
    /** Модель без каталога (импорт Just Fight в корне архива) в строке или списке через запятую */
    static final Pattern ROOT_MODEL = Pattern.compile("([\",])([A-Za-z0-9_]+)\\.(?:mdl|mdx)(?=[\",])", Pattern.CASE_INSENSITIVE);
    static final Pattern ASSET =Pattern.compile("\"([^\"]*\\.(?:mdl|mdx|blp|tga|wav|mp3|flac))\"", Pattern.CASE_INSENSITIVE);
    /** Поля-ссылки на улучшения, требования и постройки Just Fight — обнуляются */
    static final Pattern CLEAR = Pattern.compile("\\.\\.set(UpgradesUsed|Requirements|RequirementsLevels|DependencyEquivalents|ResearchesAvailable|UpgradesTo|UnitsTrained|UnitsSold|ItemsSold|ItemsMade|StructuresBuilt|Revive|RequiredAnimationNames|RequirementsforBuyItem)\\(");
    static final Pattern CLEAR_RAW = Pattern.compile("\\.\\.setString\\(\"(upgr|ureq|urqa|udep|ures|uupt|utra|useu|usei|umki|ubui|areq|arqa)\"");

    static final class Block { String kind, id, text; }

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a[0]);
        loadStdlib(Paths.get(a[5]));
        Map<String, Block> blocks = new LinkedHashMap<>();
        for (String kind : new String[]{"w3u", "w3a", "w3h"}) {
            List<String> lines = Files.readAllLines(dir.resolve("WurstExportedObjects_" + kind + ".wurst"), StandardCharsets.UTF_8);
            Block cur = null; StringBuilder sb = null;
            for (String line : lines) {
                Matcher m = START.matcher(line);
                if (m.find()) {
                    if (cur != null) { cur.text = sb.toString(); blocks.put(cur.id, cur); }
                    cur = new Block(); cur.kind = m.group(1); cur.id = m.group(2); sb = new StringBuilder();
                }
                if (cur != null) sb.append(line).append('\n');
            }
            if (cur != null) { cur.text = sb.toString(); blocks.put(cur.id, cur); }
        }
        // замыкание по ссылкам
        Deque<String> queue = new ArrayDeque<>();
        for (String s : Files.readAllLines(Paths.get(a[1]))) if (!s.isBlank()) queue.add(s.trim());
        Set<String> take = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            Block b = blocks.get(id);
            // изменённый стандартный объект ('hmpr' на основе 'hmpr') — не копия: на него ссылаются как на основу
            if (b == null || isStandard(b) || !take.add(id)) continue;
            for (String line : b.text.split("\n")) {
                if (CLEAR.matcher(line).find() || CLEAR_RAW.matcher(line).find() || START.matcher(line).find()) continue;
                Matcher t = TOKEN.matcher(line);
                while (t.find()) if (blocks.containsKey(t.group(1)) && !take.contains(t.group(1))) queue.add(t.group(1));
            }
        }
        // новые ID
        Map<String, String> ren = new LinkedHashMap<>();
        int nu = 0, na = 0, nh = 0;
        for (String id : take) {
            Block b = blocks.get(id);
            switch (b.kind) {
                case "w3u": ren.put(id, id.charAt(0) + "J" + code(nu++)); break;
                case "w3a": ren.put(id, "AJ" + code(na++)); break;
                default: ren.put(id, "BJ" + code(nh++));
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("package UnitsPveJf\n");
        boolean raw = take.stream().anyMatch(i -> blocks.get(i).text.contains("createObjectDefinition"));
        out.append(raw ? "import ObjEditingNatives\n" : "").append("import AbilityObjEditing\nimport BuffObjEditing\n\n");
        out.append("// Юниты волн PvE Just Fight 5.6.2, которых нет в The War, со способностями и баффами — копия объектов Just Fight\n");
        out.append("// под новыми ID (?J??). Сгенерировано tools/jf/gen_pve_waves.sh (CopyJfUnits.java); руками не править.\n\n");
        Set<String> assets = new TreeSet<>();
        for (String id : take) {
            Matcher dm = DEF.matcher(blocks.get(id).text);
            String cls = dm.find() ? dm.group(1) : "";
            StringBuilder sb = new StringBuilder();
            for (String line : blocks.get(id).text.split("\n")) {
                if (line.startsWith("//")) continue;
                if (CLEAR.matcher(line).find()) line = line.replaceAll("\\(\"[^\"]*\"\\)\\s*$", "(\"\")").replaceAll("\\((\\d+), \"[^\"]*\"\\)\\s*$", "($1, \"\")");
                else if (CLEAR_RAW.matcher(line).find()) line = line.replaceAll(", \"[^\"]*\"\\)\\s*$", ", \"\")");
                Matcher t = TOKEN.matcher(line);
                StringBuffer r = new StringBuffer();
                while (t.find()) t.appendReplacement(r, Matcher.quoteReplacement(ren.getOrDefault(t.group(1), t.group(1))));
                t.appendTail(r);
                line = r.toString();
                // stdlib The War: где параметр — целое, а в экспорте «7.0» — целое; иконка баффа — по уровню
                line = fixIntArgs(line, cls);
                if (blocks.get(id).kind.equals("w3h")) line = line.replaceAll("\\.\\.setIconNormal\\(\"", "..setIconNormal(1, \"");
                // пути файлов — как в Just Fight (до переноса), по ним скрипт достаёт импорт
                Matcher as = ASSET.matcher(line);
                while (as.find()) assets.add(as.group(1).replace("\\\\", "\\"));
                // импортированные модели Just Fight лежат в корне архива — в The War они в imports/models
                line = ROOT_MODEL.matcher(line).replaceAll(mm -> Matcher.quoteReplacement(mm.group(1) + "models\\\\" + mm.group(2) + ".mdx"));
                sb.append(line).append('\n');
            }
            out.append(sb.toString().replaceAll("\n{3,}", "\n\n"));
        }
        Files.writeString(Paths.get(a[2]), out.toString(), StandardCharsets.UTF_8);
        StringBuilder map = new StringBuilder();
        for (Map.Entry<String, String> e : ren.entrySet()) map.append(e.getKey()).append('\t').append(e.getValue()).append('\t').append(blocks.get(e.getKey()).kind).append('\n');
        Files.writeString(Paths.get(a[3]), map.toString(), StandardCharsets.UTF_8);
        Files.writeString(Paths.get(a[4]), String.join("\n", assets) + "\n", StandardCharsets.UTF_8);
        System.out.println("copied " + take.size() + " objects (units " + nu + ", abilities " + na + ", buffs " + nh + "), " + assets.size() + " asset paths");
    }

    static final Pattern DEF = Pattern.compile("new ([A-Za-z0-9]*Definition[A-Za-z0-9]*)\\(");
    static final Pattern CALL = Pattern.compile("\\.\\.(set[A-Za-z0-9]+)\\(((?:-?\\d+, )?)(-?\\d+)\\.0+\\)");
    static final Pattern CLASS = Pattern.compile("^public class ([A-Za-z0-9]+)(?: extends ([A-Za-z0-9]+))?");
    static final Pattern FUNC = Pattern.compile("^\\s+function (set[A-Za-z0-9]+)\\(([^)]*)\\)");
    /** stdlib: класс -> предок; «класс.метод/число параметров» -> тип последнего параметра */
    static final Map<String, String> parent = new HashMap<>();
    static final Map<String, String> lastType = new HashMap<>();

    /** Сигнатуры методов редактирования объектов из stdlib (каталог objediting) */
    static void loadStdlib(Path dir) throws Exception {
        for (String f : new String[]{"AbilityObjEditing.wurst", "UnitObjEditing.wurst", "BuffObjEditing.wurst"}) {
            String cls = "";
            for (String line : Files.readAllLines(dir.resolve(f), StandardCharsets.UTF_8)) {
                Matcher c = CLASS.matcher(line);
                if (c.find()) { cls = c.group(1); if (c.group(2) != null) parent.put(cls, c.group(2)); continue; }
                Matcher m = FUNC.matcher(line);
                if (m.find() && !cls.isEmpty()) {
                    String[] ps = m.group(2).split(",");
                    String last = ps[ps.length - 1].trim();
                    lastType.putIfAbsent(cls + "." + m.group(1) + "/" + ps.length, last.substring(0, last.indexOf(' ')));
                }
            }
        }
    }

    /** «..setX(1, 7.0)» -> «..setX(1, 7)», если в stdlib у метода класса (или предка) последний параметр int */
    static String fixIntArgs(String line, String cls) {
        Matcher m = CALL.matcher(line);
        if (!m.find()) return line;
        int params = m.group(2).isEmpty() ? 1 : 2;
        for (String c = cls; c != null; c = parent.get(c)) {
            String t = lastType.get(c + "." + m.group(1) + "/" + params);
            if (t != null) return t.equals("int") ? line.substring(0, m.start()) + ".." + m.group(1) + "(" + m.group(2) + m.group(3) + ")" + line.substring(m.end()) : line;
        }
        return line;
    }

    static final Pattern SAME_BASE = Pattern.compile("Definition\\('([A-Za-z0-9]{4})', '\\1'\\)");

    /** Стандартный объект игры, изменённый в Just Fight (основа — он сам): остаётся под своим ID */
    static boolean isStandard(Block b) {
        return SAME_BASE.matcher(b.text).find();
    }

    static String code(int n) {
        String d = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        return "" + d.charAt(n / 36) + d.charAt(n % 36);
    }
}
