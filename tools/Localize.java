import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.lang.reflect.*;

/**
 * Локализация после сборки (build.cmd вызывает после компилятора). Из готовой карты берёт файлы объектов (OBJ_FILES) и
 * war3map.wts, заменяет видимые в игре тексты (имена, подсказки) на ссылки TRIGSTR_N,
 * дописывает их в war3map.wts (нейтральная копия = русский, запасной вариант) и кладёт в MPQ ещё по копии wts
 * на каждый код языка (0x409 enUS и т.д.) с переводом из locales/<locale>.txt (формат описан в шапке locales/enUS.txt).
 * Локали, у которых нет своего файла, получают копию enUS. Из старого wts выбрасываются строки, на которые
 * никто в карте не ссылается (в оригинале 4282 строки, используется ~70). Скрипт (сообщения, мультиборд) не трогает:
 * TRIGSTR в JASS резолвится только для литерала целиком, склейка "TRIGSTR_N" + I2S(x) даёт пустую строку — поэтому
 * тексты скрипта в Wurst-коде просто английские.
 *
 *   java -cp "tools;%USERPROFILE%\.wurst\wurst-compiler\wurstscript.jar" Localize _build\TheWarReborn.w3x locales
 *   java -cp ... Localize --missing _build\TheWarReborn.w3x locales enUS   -- шаблон недостающих строк на stdout
 */
public class Localize {
    /** Файлы объектов: имя, есть ли у модификаций уровень+dataPointer (w3a/w3q/w3d), какие строковые поля показываются в игре. */
    static final String[][] OBJ_FILES = {
        {"war3map.w3u", "0", "unam utip utub upro"},                     // юниты: имя, подсказки, имена героев
        {"war3map.w3t", "0", "unam utip utub ides"},                     // предметы: имя, подсказки, описание
        {"war3map.w3a", "1", "anam atp1 aub1 aut1 auu1 aret arut"},      // способности: имя, подсказки, откл., изучение
        {"war3map.w3q", "1", "gnam gtp1 gub1"},                          // улучшения
        {"war3map.w3h", "0", "fnam ftip fube"},                          // баффы
    };

    static final class ObjFile { String name; boolean levels; Set<String> fields; byte[] data; List<Slot> slots; }
    /** Коды языков MPQ (Windows LCID), под которыми лежат копии war3map.wts. */
    static final LinkedHashMap<String, Short> LOCALE_ID = new LinkedHashMap<>();
    static {
        String[][] l = {{"enUS", "409"}, {"deDE", "407"}, {"frFR", "40C"}, {"esES", "40A"}, {"esMX", "80A"}, {"itIT", "410"},
            {"koKR", "412"}, {"plPL", "415"}, {"ptBR", "416"}, {"zhCN", "804"}, {"zhTW", "404"}, {"jaJP", "411"}};
        for (String[] e : l) LOCALE_ID.put(e[0], (short) Integer.parseInt(e[1], 16));
    }
    static final String FALLBACK = "enUS";
    static final int FIRST_ID = 100;
    static final Pattern REF = Pattern.compile("TRIGSTR_0*(\\d+)");
    static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");

    /** Одна строковая модификация в w3u: где лежит значение и что там. */
    static final class Slot { int start, end, level; String unit, field, value; }

    public static void main(String[] a) throws Exception {
        boolean missing = a[0].equals("--missing");
        int i = missing ? 1 : 0;
        Path map = Paths.get(a[i]), dir = Paths.get(a[i + 1]);
        Class<?> A = Class.forName("org.inwc3.jmpq.MpqArchive");
        Object ar = A.getMethod("open", Path.class).invoke(null, map);
        Method read = A.getMethod("read", String.class);
        @SuppressWarnings("unchecked") List<String> names = (List<String>) A.getMethod("names").invoke(ar);
        List<ObjFile> objFiles = new ArrayList<>();
        for (String[] f : OBJ_FILES) {
            if (!names.contains(f[0])) continue;
            ObjFile o = new ObjFile(); o.name = f[0]; o.levels = f[1].equals("1");
            o.fields = new HashSet<>(Arrays.asList(f[2].split(" ")));
            o.data = (byte[]) read.invoke(ar, f[0]);
            objFiles.add(o);
        }

        // старые строки wts: оставляем только те, на которые ссылается хоть один файл карты (w3i, скрипт, war3mapSkin.txt…)
        Set<Integer> referenced = new HashSet<>();
        for (String n : names) {
            if (n.equals("war3map.wts") || n.startsWith("_Locales")) continue;
            Matcher m = REF.matcher(new String((byte[]) read.invoke(ar, n), StandardCharsets.ISO_8859_1));
            while (m.find()) referenced.add(Integer.parseInt(m.group(1)));
        }
        LinkedHashMap<Integer, String[]> base = keepReferenced(new String((byte[]) read.invoke(ar, "war3map.wts"), StandardCharsets.UTF_8), referenced);

        // номера строк: с FIRST_ID, пропуская занятые старыми (в т.ч. прошлым прогоном по этой же карте); держим их
        // четырёхзначными — со «TRIGSTR_2xxxx» скрипт получал пустые строки (таймер «Раунд», сообщения)
        Iterator<Integer> freeIds = new Iterator<Integer>() {
            int next = FIRST_ID;
            public boolean hasNext() { return true; }
            public Integer next() { while (base.containsKey(next)) next++; return next++; }
        };

        // ru-текст -> id строки; порядок = порядок в файлах объектов
        LinkedHashMap<String, Integer> ids = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> usage = new LinkedHashMap<>();
        Map<String, String> ruText = new HashMap<>();   // ключ -> исходный текст (с пробелами по краям строк)
        int objFields = 0;
        for (ObjFile o : objFiles) {
            o.slots = scan(o.data, o.levels, o.fields);
            objFields += o.slots.size();
            for (Slot s : o.slots) {
                String key = norm(s.value);
                ruText.putIfAbsent(key, raw(s.value));
                ids.computeIfAbsent(key, k -> freeIds.next());
                String use = s.unit + " " + s.field + (s.level > 0 ? "/" + s.level : "");
                List<String> u = usage.computeIfAbsent(key, k -> new ArrayList<>());
                if (!u.contains(use)) u.add(use);
            }
        }

        if (missing) {
            Map<String, String> tr = loadLocale(dir.resolve(a[i + 2] + ".txt"));
            int n = 0; Set<String> oldSeen = new HashSet<>();
            PrintStream out = new PrintStream(System.out, true, "UTF-8");
            for (String[] s : base.values()) if (!tr.containsKey(s[1]) && !s[1].isBlank() && oldSeen.add(s[1])) { n++; out.print("=== (старая строка)\n" + s[2] + "\n---\n\n\n"); }
            for (String ru : ids.keySet()) if (!tr.containsKey(ru)) { n++; out.print("=== " + used(usage.get(ru)) + "\n" + ruText.get(ru) + "\n---\n\n\n"); }
            System.err.println(n + " of " + (ids.size() + base.size()) + " strings untranslated");
            ((AutoCloseable) ar).close();
            return;
        }

        // патчим файлы объектов: значение -> TRIGSTR_N
        Map<String, byte[]> patchedObj = new LinkedHashMap<>();
        for (ObjFile o : objFiles) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(o.data.length);
            int pos = 0;
            for (Slot s : o.slots) {
                bo.write(o.data, pos, s.start - pos);
                bo.write(("TRIGSTR_" + ids.get(norm(s.value))).getBytes(StandardCharsets.UTF_8));
                pos = s.end;
            }
            bo.write(o.data, pos, o.data.length - pos);
            patchedObj.put(o.name, bo.toByteArray());
        }


        // war3map.wts по локалям: один и тот же файл лежит в MPQ несколько раз с разным кодом языка в хеш-таблице
        // (0 = нейтральный/русский, 0x409 = enUS…); игра берёт запись своей локали, иначе нейтральную
        Map<Short, byte[]> wtsByLocale = new LinkedHashMap<>();
        wtsByLocale.put((short) 0, buildWts(base, ids, ruText, usage, null));
        Map<String, byte[]> perLocale = new HashMap<>();
        int untranslated = 0;
        for (String loc : LOCALE_ID.keySet()) {
            Path f = dir.resolve(loc + ".txt");
            if (!Files.exists(f)) continue;
            Map<String, String> tr = loadLocale(f);
            perLocale.put(loc, buildWts(base, ids, ruText, usage, tr));
            int miss = 0, old = 0;
            for (String ru : ids.keySet()) if (!tr.containsKey(ru)) miss++;
            for (String[] s : base.values()) if (!tr.containsKey(s[1]) && !s[1].isBlank()) old++;
            System.out.println(loc + ": " + (ids.size() - miss) + "/" + ids.size() + " unit strings, " + (base.size() - old) + "/" + base.size() + " old strings translated");
            if (loc.equals(FALLBACK)) untranslated = miss;
        }
        for (Map.Entry<String, Short> e : LOCALE_ID.entrySet()) {
            byte[] data = perLocale.containsKey(e.getKey()) ? perLocale.get(e.getKey()) : perLocale.get(FALLBACK);
            if (data != null) wtsByLocale.put(e.getValue(), data);
        }

        Class<?> W = Class.forName("org.inwc3.jmpq.MpqArchiveWriter"), O = Class.forName("org.inwc3.jmpq.MpqWriteOptions");
        Object opts = O.getMethod("recompressed").invoke(null);
        opts = O.getMethod("withPrefix", boolean.class).invoke(opts, true);
        opts = O.getMethod("withListfile", boolean.class).invoke(opts, true);
        Object w = W.getMethod("from", A, O).invoke(null, ar, opts);
        Method remove = W.getMethod("remove", String.class, short.class), put = W.getMethod("put", String.class, short.class, byte[].class);
        for (Map.Entry<String, byte[]> e : patchedObj.entrySet()) { remove.invoke(w, e.getKey(), (short) 0); put.invoke(w, e.getKey(), (short) 0, e.getValue()); }
        for (Map.Entry<Short, byte[]> e : wtsByLocale.entrySet()) { remove.invoke(w, "war3map.wts", e.getKey()); put.invoke(w, "war3map.wts", e.getKey(), e.getValue()); }
        // и второй способ, Reforged: _Locales\<locale>.w3mod\war3map.wts (Hive: "How can I make my map multi-language")
        for (Map.Entry<String, Short> e : LOCALE_ID.entrySet()) {
            String n = "_Locales\\" + e.getKey() + ".w3mod\\war3map.wts";
            remove.invoke(w, n, (short) 0); put.invoke(w, n, (short) 0, wtsByLocale.get(e.getValue()));
        }
        Path tmp = map.resolveSibling(map.getFileName() + ".loc.tmp");
        W.getMethod("save", Path.class).invoke(w, tmp);
        ((AutoCloseable) ar).close();
        Files.move(tmp, map, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("localized " + objFields + " object fields (" + ids.size() + " strings), kept " + referenced.size()
            + " old strings, locales " + perLocale.keySet() + " -> " + map
            + (untranslated > 0 ? "; " + untranslated + " untranslated in " + FALLBACK + " (see --missing)" : ""));
    }

    /** Проходит файл объектов (версия 1-2; в w3a/w3q/w3d у модификации ещё уровень и dataPointer) и запоминает
        строковые модификации нужных полей. */
    static List<Slot> scan(byte[] d, boolean levels, Set<String> fields) {
        List<Slot> res = new ArrayList<>();
        int p = 4; // version
        int version = le(d, 0);
        if (version != 1 && version != 2) throw new RuntimeException("unsupported object file version " + version);
        for (int table = 0; table < 2; table++) {
            int count = le(d, p); p += 4;
            for (int i = 0; i < count; i++) {
                String oldId = id(d, p), newId = id(d, p + 4); p += 8;
                String unit = newId.equals("\0\0\0\0") ? oldId : newId;
                int mods = le(d, p); p += 4;
                for (int m = 0; m < mods; m++) {
                    String field = id(d, p); int type = le(d, p + 4); p += 8;
                    int level = 0;
                    if (levels) { level = le(d, p); p += 8; }   // уровень + dataPointer
                    if (type == 3) {
                        int start = p; while (d[p] != 0) p++;
                        if (fields.contains(field)) {
                            Slot s = new Slot(); s.start = start; s.end = p; s.unit = unit; s.field = field; s.level = level;
                            s.value = new String(d, start, p - start, StandardCharsets.UTF_8);
                            if (!s.value.isEmpty() && !s.value.startsWith("TRIGSTR_")) res.add(s);
                        }
                        p++;
                    } else p += 4;
                    p += 4; // end id
                }
            }
        }
        return res;
    }

    static final Pattern ENTRY = Pattern.compile("STRING (\\d+)\\r?\\n((?://[^\\r\\n]*\\r?\\n)*)\\{\\r?\\n(.*?)\\r?\\n?\\}\\r?\\n?", Pattern.DOTALL);

    static LinkedHashMap<Integer, String[]> keepReferenced(String wts, Set<Integer> referenced) {
        LinkedHashMap<Integer, String[]> res = new LinkedHashMap<>();
        Matcher m = ENTRY.matcher(wts.startsWith("﻿") ? wts.substring(1) : wts);
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            if (referenced.contains(id)) res.put(id, new String[] {m.group(2).replace("\r\n", "\n"), norm(m.group(3)), raw(m.group(3))});
        }
        return res;
    }

    static byte[] buildWts(LinkedHashMap<Integer, String[]> base, Map<String, Integer> ids, Map<String, String> ruText, Map<String, List<String>> usage, Map<String, String> tr) {
        // строго по возрастанию номера, как пишет редактор: скрипт ищет TRIGSTR в таблице двоичным поиском,
        // в неотсортированной не находит и подставляет пустую строку
        TreeMap<Integer, String[]> all = new TreeMap<>();
        for (Map.Entry<Integer, String[]> e : base.entrySet())
            all.put(e.getKey(), new String[] {e.getValue()[0], translate(tr, e.getValue()[1], e.getValue()[2])});
        for (Map.Entry<String, Integer> e : ids.entrySet())
            all.put(e.getValue(), new String[] {"// " + used(usage.get(e.getKey())) + "\n", translate(tr, e.getKey(), ruText.get(e.getKey()))});
        StringBuilder sb = new StringBuilder("﻿");
        for (Map.Entry<Integer, String[]> e : all.entrySet()) entry(sb, e.getKey(), e.getValue()[0], e.getValue()[1]);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Перевод по ключу; пробелы по краям (склейка «Раунд » + номер) берутся из русского, если в переводе их нет. */
    static String translate(Map<String, String> tr, String key, String ru) {
        if (tr == null || !tr.containsKey(key)) return ru;
        String en = tr.get(key);
        int lead = 0; while (lead < ru.length() && ru.charAt(lead) == ' ') lead++;
        int trail = 0; while (trail < ru.length() - lead && ru.charAt(ru.length() - 1 - trail) == ' ') trail++;
        if (lead > 0 && !en.startsWith(" ")) en = ru.substring(0, lead) + en;
        if (trail > 0 && !en.endsWith(" ")) en = en + ru.substring(ru.length() - trail);
        return en;
    }

    static void entry(StringBuilder sb, int id, String comment, String text) {
        sb.append("STRING ").append(id).append("\r\n").append(comment.replace("\n", "\r\n"))
          .append("{\r\n").append(text.replace("\n", "\r\n")).append("\r\n}\r\n\r\n");
    }

    static String used(List<String> u) {
        return u.size() <= 12 ? String.join(", ", u) : String.join(", ", u.subList(0, 12)) + " … (+" + (u.size() - 12) + ")";
    }

    /** locales/<loc>.txt: блоки "=== комментарий" / русский текст / "---" / перевод. */
    static Map<String, String> loadLocale(Path f) throws IOException {
        Map<String, String> res = new HashMap<>();
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        lines.add("===");
        List<String> ru = null, tr = null, cur = null;
        for (String line : lines) {
            if (line.startsWith("# ")) continue;   // комментарий (заголовки разделов) — иначе прилипает к предыдущему переводу
            if (line.startsWith("===")) {
                if (ru != null && tr != null && !String.join("", tr).isBlank()) res.put(norm(join(ru)), join(tr));
                ru = new ArrayList<>(); tr = null; cur = ru;
            } else if (line.equals("---") && tr == null) { tr = new ArrayList<>(); cur = tr; }
            else if (cur != null) cur.add(line);
        }
        return res;
    }

    static String join(List<String> ls) {
        int end = ls.size(); while (end > 0 && ls.get(end - 1).isEmpty()) end--;
        return String.join("\n", ls.subList(0, end));
    }

    static String raw(String s) { return s.replace("\r\n", "\n").replaceAll("\n+$", ""); }

    /** Ключ для поиска перевода: как raw, но без пробелов в конце строк (редакторы и Write их срезают). */
    static String norm(String s) { return raw(s).replaceAll("[ \t]+(?=\n|$)", ""); }

    static int le(byte[] d, int p) { return (d[p] & 255) | (d[p + 1] & 255) << 8 | (d[p + 2] & 255) << 16 | (d[p + 3] & 255) << 24; }
    static String id(byte[] d, int p) { return new String(d, p, 4, StandardCharsets.ISO_8859_1); }
}
