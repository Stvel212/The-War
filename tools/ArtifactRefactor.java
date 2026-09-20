import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Refactor wurst/map/Artifacts.wurst around a class Artifact:
 *  - the 53-branch ArtefactUp chain -> data table (new Artifact(...)) + Artifact.upgrade()
 *  - spell-effect triggers (condition == ability id) -> Artifact.onCast(abil) closures
 *  - "find item charges in inventory" loops -> Artifact.charges()
 *  - dummy caster boilerplate -> Artifact.dummy()
 *   args: <Artifacts.wurst>
 */
public class ArtifactRefactor {
    static class Fn { String name, header; List<String> lines = new ArrayList<>(); }
    static class Block { String title; List<Fn> fns = new ArrayList<>(); List<String> pre = new ArrayList<>(); }

    public static void main(String[] a) throws Exception {
        Path p = Paths.get(a[0]);
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        // header
        int i = 0; List<String> header = new ArrayList<>();
        while (i < lines.size() && (lines.get(i).startsWith("package ") || lines.get(i).startsWith("import ") || lines.get(i).isBlank())) { if (!lines.get(i).isBlank()) header.add(lines.get(i)); i++; }
        // blocks
        List<Block> blocks = new ArrayList<>(); Block cur = null; Fn fn = null; List<String> initLines = new ArrayList<>(); boolean inInit = false;
        for (; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("// Триггер: ")) { cur = new Block(); cur.title = l.substring(12).strip(); blocks.add(cur); fn = null; continue; }
            if (l.startsWith("// ====")) continue;
            if (l.equals("init")) { inInit = true; fn = null; continue; }
            if (inInit) { if (!l.isBlank()) initLines.add(l.strip()); continue; }
            if (l.startsWith("public function ") || l.startsWith("function ")) { fn = new Fn(); fn.header = l; fn.name = l.replaceFirst("^(public )?function (\\w+).*$", "$2"); cur.fns.add(fn); continue; }
            if (fn != null) { if (!l.isBlank() || !fn.lines.isEmpty()) fn.lines.add(l); }
            else if (cur != null && !l.isBlank()) cur.pre.add(l);
        }
        for (Block b : blocks) for (Fn f : b.fns) while (!f.lines.isEmpty() && f.lines.get(f.lines.size() - 1).isBlank()) f.lines.remove(f.lines.size() - 1);

        StringBuilder out = new StringBuilder();
        for (String h : header) out.append(h).append("\n");
        if (header.stream().noneMatch(x -> x.equals("import ClosureEvents"))) out.append("import ClosureEvents\n");
        if (header.stream().noneMatch(x -> x.equals("import HashMap"))) out.append("import HashMap\n");
        if (header.stream().noneMatch(x -> x.equals("import RegisterEvents"))) out.append("import RegisterEvents\n");
        out.append("\n").append(CLASS.replace("\t", "    "));

        List<String> table = new ArrayList<>(), casts = new ArrayList<>(), plainInits = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        int converted = 0;
        for (Block b : blocks) {
            String name = b.title.replace(' ', '_');
            Fn init = b.fns.stream().filter(f -> f.name.equals("initTrig_" + name)).findFirst().orElse(null);
            if (b.title.equals("ArtefactUp")) { table.addAll(parseTable(b.fns.stream().filter(f -> f.name.equals("trig_ArtefactUp_Actions")).findFirst().get())); continue; }
            String spellId = null, extra = null, condName = null, actName = null;
            if (init != null) {
                String it = String.join("\n", init.lines);
                Matcher mc = Pattern.compile("addCondition\\(Condition\\(function (\\w+)\\)\\)").matcher(it); if (mc.find()) condName = mc.group(1);
                Matcher ma = Pattern.compile("addAction\\(function (\\w+)\\)").matcher(it); if (ma.find()) actName = ma.group(1);
                if (it.contains("registerAnyUnitEvent(EVENT_PLAYER_UNIT_SPELL_EFFECT)") && condName != null && actName != null && init.lines.size() == 4) {
                    final String cn = condName; Fn cond = b.fns.stream().filter(f -> f.name.equals(cn)).findFirst().orElse(null);
                    if (cond != null && cond.lines.size() == 1) {
                        Matcher m = Pattern.compile("^\\s*return GetSpellAbilityId\\(\\) == '(\\w{4})'(?: and (.*))?$").matcher(cond.lines.get(0));
                        if (m.find()) { spellId = m.group(1); extra = m.group(2); }
                    }
                }
            }
            // other unit events: "art<Name>()" handler + Artifact.onEvent / onAction (own trigger when the action waits)
            String eventId = null; boolean condInline = false; String condExpr = null;
            if (spellId == null && init != null && init.lines.size() >= 3 && actName != null) {
                Matcher me = Pattern.compile("registerAnyUnitEvent\\((EVENT_PLAYER_UNIT_\\w+)\\)").matcher(String.join("\n", init.lines));
                if (me.find() && init.lines.size() == (condName == null ? 3 : 4)) {
                    eventId = me.group(1);
                    if (condName != null) { final String cn2 = condName; Fn cond = b.fns.stream().filter(f -> f.name.equals(cn2)).findFirst().orElse(null);
                        if (cond != null && cond.lines.size() == 1 && cond.lines.get(0).strip().startsWith("return ")) { condInline = true; condExpr = cond.lines.get(0).strip().substring(7); } }
                }
            }
            body.append("\n// ============================================================\n// Артефакт: ").append(b.title).append(spellId != null ? " — способность '" + spellId + "'" : eventId != null ? " — " + eventId : "").append("\n// ============================================================\n");
            for (String pre : b.pre) body.append(pre).append("\n");
            for (Fn f : b.fns) {
                if (eventId != null && (f.name.equals("initTrig_" + name) || (condInline && f.name.equals(condName)))) continue;
                if (eventId != null && f.name.equals(actName)) {
                    String fname = "art" + name; boolean waits = f.lines.stream().anyMatch(l -> l.contains("TriggerSleepAction("));
                    body.append("\nfunction ").append(fname).append("()\n");
                    if (condName != null) body.append("    if not ").append(condInline ? "(" + condExpr + ")" : condName + "()").append("\n        return\n");
                    for (String l : f.lines) body.append(l).append("\n");
                    plainInits.add("    Artifact." + (waits ? "onAction" : "onEvent") + "(" + eventId + ", function " + fname + ")" + (waits ? " // в действии есть TriggerSleepAction — свой триггер" : ""));
                    converted++;
                    continue;
                }
                if (spellId != null && (f.name.equals(condName) || f.name.equals("initTrig_" + name))) continue;
                if (spellId != null && f.name.equals(actName)) {
                    String fname = "art" + name;
                    body.append("\nfunction ").append(fname).append("(unit caster)\n");
                    if (extra != null) body.append("    if not (").append(extra.replace("GetTriggerUnit()", "caster").replace("GetSpellAbilityUnit()", "caster")).append(")\n        return\n");
                    for (String l : f.lines) body.append(l.replace("GetTriggerUnit()", "caster").replace("GetSpellAbilityUnit()", "caster")).append("\n");
                    casts.add("    Artifact.onCast('" + spellId + "') (caster) ->\n        " + fname + "(caster)");
                    converted++;
                    continue;
                }
                body.append("\n").append(f.header).append("\n"); for (String l : f.lines) body.append(l).append("\n");
            }
            if (spellId == null && eventId == null && init != null) plainInits.add("    initTrig_" + name + "()");
        }
        out.append(body);
        out.append("\n// ============================================================\n// Регистрация\n// ============================================================\n\ninit\n");
        out.append("    // таблица улучшений артефактов (бывший ArtefactUp: 53 одинаковых ветки)\n");
        for (String t : table) out.append("    ").append(t).append("\n");
        out.append("    Artifact.onCast('A007') (caster) ->\n        Artifact.upgradeItem(caster, GetSpellTargetItem())\n");
        out.append("    // способности артефактов\n");
        for (String c : casts) out.append(c).append("\n");
        out.append("    // триггеры на другие события\n");
        for (String c : plainInits) out.append(c).append("\n");
        String text = out.toString();
        text = chargesLoops(text);
        text = dummyCasts(text);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        System.out.println("artifacts: spell handlers " + converted + ", table rows " + table.size() + ", charges loops " + chargesCount + ", dummies " + dummyCount);
    }

    static final String CLASS =
        "/*\n\tАртефакт — предмет героя. Улучшается способностью «Улучшить артефакт» ('A007'): каждое улучшение даёт\n" +
        "\tпредмету заряд и поднимает уровень его пассивной способности (бывший 680-строчный ArtefactUp).\n" +
        "\tЭффекты самих артефактов регистрируются через Artifact.onCast(abilId). */\n" +
        "public class Artifact\n" +
        "\tstatic constant byItem = new HashMap<int, Artifact>()\n" +
        "\tint itemId\n" +
        "\tint abilId      // пассивка, уровень которой растёт с каждым улучшением (0 — нет)\n" +
        "\tint maxCharges\n" +
        "\tint gold        // сколько золота нужно для улучшения\n" +
        "\tint lifeMult    // 0 — списывается gold; иначе списывается (жизнь предмета * lifeMult), а userData предмета += udStep\n" +
        "\tint udStep\n\n" +
        "\tconstruct(int itemId, int abilId, int maxCharges, int gold)\n" +
        "\t\tthis.itemId = itemId\n\t\tthis.abilId = abilId\n\t\tthis.maxCharges = maxCharges\n\t\tthis.gold = gold\n\t\tbyItem.put(itemId, this)\n\n" +
        "\tconstruct(int itemId, int abilId, int maxCharges, int gold, int lifeMult, int udStep)\n" +
        "\t\tthis.itemId = itemId\n\t\tthis.abilId = abilId\n\t\tthis.maxCharges = maxCharges\n\t\tthis.gold = gold\n\t\tthis.lifeMult = lifeMult\n\t\tthis.udStep = udStep\n\t\tbyItem.put(itemId, this)\n\n" +
        "\t/** Улучшение предмета i, лежащего у героя u */\n" +
        "\tfunction upgrade(unit u, item i)\n" +
        "\t\tlet p = u.getOwner()\n" +
        "\t\tif i.getCharges() >= maxCharges\n\t\t\tDisplayTextToPlayer(p, 0.0, 0.0, \"Максимально улучшено\")\n\t\t\treturn\n" +
        "\t\tif p.getGold() < gold\n\t\t\tDisplayTextToPlayer(p, 0.0, 0.0, \"Не хватает золота\")\n\t\t\treturn\n" +
        "\t\tvar paid = gold\n" +
        "\t\tvar value = i.getLife().toInt()\n" +
        "\t\tif lifeMult > 0\n" +
        "\t\t\tlet same = u.itemInSlot(slotOf(u, itemId))\n" +
        "\t\t\tsame.setUserData(same.getUserData() + udStep)\n" +
        "\t\t\tArtG = (i.getLife() * lifeMult).toInt()\n" +
        "\t\t\tpaid = ArtG\n\t\t\tvalue = ArtG\n" +
        "\t\tp.setGold(p.getGold() - paid)\n" +
        "\t\ti.setCharges(i.getCharges() + 1)\n" +
        "\t\tif abilId != 0\n\t\t\tu.incAbilityLevel(abilId)\n" +
        "\t\taddEffect(\"Abilities\\\\Spells\\\\Items\\\\AIsm\\\\AIsmTarget.mdl\", u.getPos()).destr()\n" +
        "\t\tForceItem[p.getId()] = ForceItem[p.getId()] + value\n\n" +
        "\t/** Улучшение по предмету: если предмет — артефакт из таблицы */\n" +
        "\tstatic function upgradeItem(unit u, item i)\n" +
        "\t\tlet art = byItem.get(i.getTypeId())\n\t\tif art != null\n\t\t\tart.upgrade(u, i)\n\n" +
        "\t/** Эффект артефакта: срабатывает, когда любой юнит применяет способность abilId */\n" +
        "\tstatic function onCast(int abilId, OnCastListener listener)\n" +
        "\t\tEventListener.onCast(abilId, listener)\n\n" +
        "\t/** Реакция на событие любого юнита (общий триггер stdlib; внутри нельзя ждать) */\n" +
        "\tstatic function onEvent(playerunitevent e, code handler)\n" +
        "\t\tregisterPlayerUnitEvent(e, handler)\n\n" +
        "\t/** То же, но своим триггером и как действие — можно использовать TriggerSleepAction */\n" +
        "\tstatic function onAction(playerunitevent e, code action)\n" +
        "\t\tCreateTrigger()\n\t\t\t..registerAnyUnitEvent(e)\n\t\t\t..addAction(action)\n\n" +
        "\t/** Заряды предмета данного типа в инвентаре (0, если предмета нет; при дублях — последний слот) */\n" +
        "\tstatic function charges(unit u, int itemId) returns int\n" +
        "\t\tvar n = 0\n\t\tfor slot = 0 to 5\n\t\t\tlet itm = u.itemInSlot(slot)\n\t\t\tif itm != null and itm.getTypeId() == itemId\n\t\t\t\tn = itm.getCharges()\n\t\treturn n\n\n" +
        "\t/** Первый слот с предметом данного типа (7, если нет — как в оригинале) */\n" +
        "\tstatic function slotOf(unit u, int itemId) returns int\n" +
        "\t\tvar slot = 0\n\t\twhile not (u.itemInSlot(slot).getTypeId() == itemId or slot == 7)\n\t\t\tslot++\n\t\treturn slot\n\n" +
        "\t/** Даммик-кастер ('e00W' с таймером жизни 'BHwe') со способностью abilId уровня level (0 — не менять) */\n" +
        "\tstatic function dummy(player owner, vec2 pos, int abilId, int level, real life) returns unit\n" +
        "\t\tlet u = CreateUnit(owner, 'e00W', pos.x, pos.y, 0.0)\n" +
        "\t\tu.setTimedLife(life, 'BHwe')\n\t\tu.addAbility(abilId)\n\t\tif level > 0\n\t\t\tu.setAbilityLevel(abilId, level)\n\t\treturn u\n";

    // ---- ArtefactUp table ----
    static List<String> parseTable(Fn f) {
        List<String> rows = new ArrayList<>(); String item = null, max = null, cost = null, mult = null, ud = null, abil = null; boolean open = false;
        List<String> ls = new ArrayList<>(f.lines); ls.add("    if GetSpellAbilityId() == 'A007' and GetSpellTargetItem().getTypeId() == 'ZZZZ'"); // sentinel
        for (String l : ls) {
            Matcher m = Pattern.compile("^    (?:else )?if GetSpellAbilityId\\(\\) == 'A007' and GetSpellTargetItem\\(\\)\\.getTypeId\\(\\) == '(\\w{4})'$").matcher(l);
            if (m.find()) {
                if (open) rows.add(mult == null ? String.format("new Artifact('%s', %s, %s, %s)", item, abil == null ? "0" : "'" + abil + "'", max, cost) : String.format("new Artifact('%s', %s, %s, %s, %s, %s)", item, abil == null ? "0" : "'" + abil + "'", max, cost, mult, ud));
                item = m.group(1); max = cost = mult = ud = abil = null; open = true; continue;
            }
            String s = l.strip(); Matcher x;
            if ((x = Pattern.compile("^if i\\.getCharges\\(\\) < (\\d+)$").matcher(s)).find()) max = x.group(1);
            else if ((x = Pattern.compile("^if u\\.getOwner\\(\\)\\.getGold\\(\\) >= (\\d+)$").matcher(s)).find()) cost = x.group(1);
            else if ((x = Pattern.compile("^ArtG = \\(i\\.getLife\\(\\) \\* (\\d+)\\)\\.toInt\\(\\)$").matcher(s)).find()) mult = x.group(1);
            else if ((x = Pattern.compile("getUserData\\(\\) \\+ (\\d+)\\)$").matcher(s)).find()) ud = x.group(1);
            else if ((x = Pattern.compile("^u\\.incAbilityLevel\\('(\\w{4})'\\)$").matcher(s)).find()) { if (abil != null) throw new RuntimeException("two abilities for " + item); abil = x.group(1); }
        }
        return rows;
    }

    // ---- charges loops ----
    static int chargesCount = 0;
    static String chargesLoops(String text) {
        Pattern p = Pattern.compile("(?m)^([ \\t]+)int (\\w+) = 0\\r?\\n\\1int (\\w+) = 0\\r?\\n((?:\\1(?:unit|int|real|item|timer|group) \\w+(?: = [^\\n]*)?\\r?\\n)*)\\1while not \\((\\w+) == 6\\)\\r?\\n\\1    if ([^\\n]+?)\\.itemInSlot\\(\\5\\)\\.getTypeId\\(\\) == '(\\w{4})'\\r?\\n\\1        (\\w+) = \\6\\.itemInSlot\\(\\5\\)\\.getCharges\\(\\)\\r?\\n\\1    \\5 = \\5 \\+ 1\\r?\\n");
        Matcher m = p.matcher(text); StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String idx = m.group(5), v = m.group(8), extra = m.group(4);
            if (!(idx.equals(m.group(2)) || idx.equals(m.group(3))) || !(v.equals(m.group(2)) || v.equals(m.group(3))) || idx.equals(v)) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
            String rest = text.substring(m.end()); int fnEnd = rest.indexOf("\nfunction "); int fnEnd2 = rest.indexOf("\npublic function "); if (fnEnd < 0 || (fnEnd2 >= 0 && fnEnd2 < fnEnd)) fnEnd = fnEnd2; if (fnEnd < 0) fnEnd = rest.length();
            boolean reassigned = Pattern.compile("(?m)^\\s*" + v + " = ").matcher(rest.substring(0, fnEnd)).find();
            m.appendReplacement(sb, Matcher.quoteReplacement(extra + m.group(1) + (reassigned ? "var " : "let ") + v + " = Artifact.charges(" + m.group(6) + ", '" + m.group(7) + "')\n"));
            chargesCount++;
        }
        m.appendTail(sb); return sb.toString();
    }

    // ---- dummy casters ----
    static int dummyCount = 0;
    static String dummyCasts(String text) {
        String[] ls = text.split("\n", -1); List<String> out = new ArrayList<>();
        Pattern cu = Pattern.compile("^(\\s*)(let |var |)(\\w+) = CreateUnit\\((.*), 'e00W', (.*), (0\\.0|270\\.0)\\)$");
        for (int i = 0; i < ls.length; i++) {
            Matcher m = cu.matcher(ls[i]);
            if (m.find()) {
                String ind = m.group(1), decl = m.group(2), v = m.group(3); List<String> owner = splitArgs(m.group(4)); List<String> xy = splitArgs(m.group(5)); String facing = m.group(6);
                if (owner.size() == 1 && xy.size() == 2) {
                    // consecutive statements on the dummy: setup (timed life / ability / level) is folded, orders are kept
                    String life = null, abil = null, level = "0"; int j = i + 1; List<String> keep = new ArrayList<>();
                    while (j < ls.length) {
                        String s = ls[j].strip(); Matcher t, ab, lv;
                        if ((t = Pattern.compile("^" + v + "\\.setTimedLife\\((.+), 'BHwe'\\)$").matcher(s)).find() && life == null) life = t.group(1);
                        else if ((ab = Pattern.compile("^" + v + "\\.addAbility\\('(\\w{4})'\\)$").matcher(s)).find() && abil == null) abil = ab.group(1);
                        else if (abil != null && (lv = Pattern.compile("^" + v + "\\.setAbilityLevel\\('" + abil + "', (.+)\\)$").matcher(s)).find()) level = lv.group(1);
                        else if (s.startsWith(v + ".issue")) keep.add(ls[j]);
                        else break;
                        j++;
                    }
                    if (life != null && abil != null) {
                        String pos = "vec2(" + xy.get(0).strip() + ", " + xy.get(1).strip() + ")";
                        out.add(ind + decl + v + " = Artifact.dummy(" + owner.get(0).strip() + ", " + pos + ", '" + abil + "', " + level + ", " + life + ")"); // facing of the invisible dummy caster is irrelevant
                        out.addAll(keep);
                        i = j - 1; dummyCount++; continue;
                    }
                }
            }
            out.add(ls[i]);
        }
        return String.join("\n", out).replaceAll("vec2\\(([A-Za-z_][\\w.]*(?:\\(\\))?)\\.getX\\(\\), \\1\\.getY\\(\\)\\)", "$1.getPos()");
    }
    static List<String> splitArgs(String s) { List<String> r = new ArrayList<>(); int d = 0, st = 0; for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == '"') { i++; while (s.charAt(i) != '"') { if (s.charAt(i) == '\\') i++; i++; } continue; } if (c == '\'') { i = s.indexOf('\'', i + 1); continue; } if (c == '(' || c == '[') d++; else if (c == ')' || c == ']') d--; else if (c == ',' && d == 0) { r.add(s.substring(st, i)); st = i + 1; } } r.add(s.substring(st)); return r; }
}
