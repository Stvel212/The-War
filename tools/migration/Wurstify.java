import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Second pass over generated Wurst: native calls -> stdlib extension methods, Player(i) -> players[i],
 * GetLocalPlayer() -> localPlayer, trigger setup -> cascade (..) syntax.
 *   args: <dir with .wurst files>
 */
public class Wurstify {
    // native name -> template; $1..$n = rewritten arguments. Only applied when the argument count matches.
    static final Map<String, String> MAP = new LinkedHashMap<>();
    static {
        String[][] m = {
            // unit
            {"GetUnitX", "$1.getX()"}, {"GetUnitY", "$1.getY()"}, {"GetOwningPlayer", "$1.getOwner()"},
            {"UnitAddAbility", "$1.addAbility($2)"}, {"UnitRemoveAbility", "$1.removeAbility($2)"},
            {"GetUnitAbilityLevel", "$1.getAbilityLevel($2)"}, {"SetUnitAbilityLevel", "$1.setAbilityLevel($2, $3)"}, {"IncUnitAbilityLevel", "$1.incAbilityLevel($2)"},
            {"GetUnitTypeId", "$1.getTypeId()"}, {"IsUnitType", "$1.isType($2)"}, {"KillUnit", "$1.kill()"}, {"UnitAlive", "$1.isAlive()"},
            {"IsUnitEnemy", "$1.isEnemyOf($2)"}, {"IsUnitAlly", "$1.isAllyOf($2)"},
            {"IssueTargetOrder", "$1.issueTargetOrder($2, $3)"}, {"IssueImmediateOrder", "$1.issueImmediateOrder($2)"}, {"IssuePointOrder", "$1.issuePointOrder($2, vec2($3, $4))"},
            {"SetUnitOwner", "$1.setOwner($2, $3)"}, {"GetUnitPointValue", "$1.getPointValue()"}, {"SetUnitPathing", "$1.setPathing($2)"},
            {"GetUnitName", "$1.getName()"}, {"SetUnitVertexColor", "$1.setVertexColor($2, $3, $4, $5)"},
            {"UnitDamageTarget", "$1.damageTarget($2, $3, $4, $5, $6, $7, $8)"}, {"UnitApplyTimedLife", "$1.setTimedLife($3, $2)"},
            {"IsUnitIllusion", "$1.isIllusion()"}, {"RemoveUnit", "$1.remove()"}, {"SetUnitAnimation", "$1.setAnimation($2)"}, /* GetUnitLevel not mapped: stdlib getLevel() returns hero level for heroes */
            {"UnitAddItemById", "$1.addItemById($2)"}, {"SetUnitUseFood", "$1.setUseFood($2)"}, {"GetUnitFoodUsed", "$1.getFoodUsed()"}, {"SetUnitInvulnerable", "$1.setInvulnerable($2)"},
            /* SetUnitFacing not mapped: stdlib setFacingEx() is the instant Blz variant */ {"UnitItemInSlot", "$1.itemInSlot($2)"}, {"GetWidgetLife", "$1.getLife()"}, {"SetWidgetLife", "$1.setLife($2)"},
            {"GetHeroStr", "$1.getStr($2)"}, {"GetHeroInt", "$1.getInt($2)"}, {"GetHeroAgi", "$1.getAgi($2)"}, {"SetUnitMoveSpeed", "$1.setMoveSpeed($2)"}, {"GetUnitMoveSpeed", "$1.getMoveSpeed()"}, {"GetUnitDefaultMoveSpeed", "$1.getDefaultMovespeed()"},
            {"UnitInventorySize", "$1.inventorySize()"}, {"UnitRemoveItem", "$1.removeItem($2)"}, {"UnitAddItem", "$1.addItemHandle($2)"}, {"GetItemUserData", "$1.getUserData()"}, {"SetItemUserData", "$1.setUserData($2)"},
            {"SubString", "$1.substring($2, $3)"}, {"GetUnitCurrentOrder", "$1.getCurrentOrder()"}, {"SetPlayerAlliance", "$1.setPlayerAlliance($2, $3, $4)"}, {"GetPlayerAlliance", "$1.getPlayerAlliance($2, $3)"},
            // player
            {"GetPlayerId", "$1.getId()"}, {"GetPlayerName", "$1.getName()"}, {"GetPlayerSlotState", "$1.getSlotState()"}, {"GetPlayerController", "$1.getController()"},
            {"IsPlayerAlly", "$1.isAllyOf($2)"}, {"IsPlayerEnemy", "$1.isEnemyOf($2)"}, {"Player", "players[$1]"}, {"GetLocalPlayer", "localPlayer"},
            {"ForceAddPlayer", "$1.addPlayer($2)"}, {"ForceRemovePlayer", "$1.removePlayer($2)"}, {"IsPlayerInForce", "$2.containsPlayer($1)"},
            // timer / handle
            {"DestroyTimer", "$1.destr()"}, {"PauseTimer", "$1.pause()"}, {"ResumeTimer", "$1.resume()"}, {"TimerGetRemaining", "$1.getRemaining()"}, {"TimerGetElapsed", "$1.getElapsed()"}, {"GetHandleId", "$1.getHandleId()"},
            // hashtable
            {"SaveInteger", "$1.saveInt($2, $3, $4)"}, {"LoadInteger", "$1.loadInt($2, $3)"}, {"SaveReal", "$1.saveReal($2, $3, $4)"}, {"LoadReal", "$1.loadReal($2, $3)"},
            {"SaveBoolean", "$1.saveBoolean($2, $3, $4)"}, {"LoadBoolean", "$1.loadBoolean($2, $3)"}, {"SaveStr", "$1.saveString($2, $3, $4)"}, {"LoadStr", "$1.loadString($2, $3)"},
            {"SaveUnitHandle", "$1.saveUnitHandle($2, $3, $4)"}, {"LoadUnitHandle", "$1.loadUnitHandle($2, $3)"}, {"SaveTimerHandle", "$1.saveTimerHandle($2, $3, $4)"}, {"LoadTimerHandle", "$1.loadTimerHandle($2, $3)"},
            {"SaveTriggerHandle", "$1.saveTriggerHandle($2, $3, $4)"}, {"LoadTriggerHandle", "$1.loadTriggerHandle($2, $3)"}, {"SaveEffectHandle", "$1.saveEffectHandle($2, $3, $4)"}, {"LoadEffectHandle", "$1.loadEffectHandle($2, $3)"},
            {"SaveGroupHandle", "$1.saveGroupHandle($2, $3, $4)"}, {"LoadGroupHandle", "$1.loadGroupHandle($2, $3)"}, {"SavePlayerHandle", "$1.savePlayerHandle($2, $3, $4)"}, {"LoadPlayerHandle", "$1.loadPlayerHandle($2, $3)"},
            {"SaveItemHandle", "$1.saveItemHandle($2, $3, $4)"}, {"LoadItemHandle", "$1.loadItemHandle($2, $3)"}, {"SaveLightningHandle", "$1.saveLightningHandle($2, $3, $4)"}, {"LoadLightningHandle", "$1.loadLightningHandle($2, $3)"},
            {"SaveTextTagHandle", "$1.saveTextTagHandle($2, $3, $4)"}, {"LoadTextTagHandle", "$1.loadTextTagHandle($2, $3)"},
            {"FlushChildHashtable", "$1.flushChild($2)"}, {"FlushParentHashtable", "$1.flush()"},
            {"HaveSavedInteger", "$1.hasInt($2, $3)"}, {"HaveSavedHandle", "$1.hasHandle($2, $3)"}, {"HaveSavedReal", "$1.hasReal($2, $3)"}, {"HaveSavedBoolean", "$1.hasBoolean($2, $3)"}, {"HaveSavedString", "$1.hasString($2, $3)"},
            // group
            {"GroupEnumUnitsInRange", "$1.enumUnitsInRange(vec2($2, $3), $4, $5)"}, {"GroupEnumUnitsInRect", "$1.enumUnitsInRect($2, $3)"}, {"GroupEnumUnitsOfPlayer", "$1.enumUnitsOfPlayer($2, $3)"},
            {"GroupClear", "$1.clear()"}, {"DestroyGroup", "$1.destr()"}, {"GroupAddUnit", "$1.add($2)"}, {"GroupRemoveUnit", "$1.remove($2)"}, {"IsUnitInGroup", "$2.has($1)"},
            {"CountUnitsInGroup", "$1.size()"}, {"ForGroup", "$1.forGroup($2)"}, {"GroupPickRandomUnit", "$1.getRandom()"},
            // trigger
            {"TriggerRegisterAnyUnitEventBJ", "$1.registerAnyUnitEvent($2)"}, {"TriggerAddCondition", "$1.addCondition($2)"}, {"TriggerAddAction", "$1.addAction($2)"},
            {"EnableTrigger", "$1.enable()"}, {"DisableTrigger", "$1.disable()"}, {"TriggerExecute", "$1.execute()"}, {"TriggerEvaluate", "$1.evaluate()"}, {"DestroyTrigger", "$1.destr()"},
            {"TriggerRegisterTimerEvent", "$1.registerTimerEvent($2, $3)"}, {"TriggerRegisterPlayerChatEvent", "$1.registerPlayerChatEvent($2, $3, $4)"}, {"TriggerRegisterPlayerEvent", "$1.registerPlayerEvent($2, $3)"},
            {"TriggerRegisterPlayerUnitEvent", "$1.registerPlayerUnitEvent($2, $3, $4)"}, {"TriggerRegisterUnitEvent", "$1.registerUnitEvent($2, $3)"}, {"TriggerRegisterDialogButtonEvent", "$1.registerDialogButtonEvent($2)"},
            {"TriggerRegisterDialogEvent", "$1.registerDialogEvent($2)"}, {"TriggerRegisterEnterRegion", "$1.registerEnterRegion($2, $3)"}, {"TriggerRegisterLeaveRegion", "$1.registerLeaveRegion($2, $3)"},
            {"TriggerRegisterUnitInRange", "$1.registerUnitInRange($2, $3, $4)"}, {"TriggerRegisterDeathEvent", "$1.registerDeathEvent($2)"}, {"TriggerRegisterGameEvent", "$1.registerGameEvent($2)"}, {"TriggerRegisterTimerExpireEvent", "$1.registerTimerExpireEvent($2)"},
            // texttag
            // SetTextTagText stays native: stdlib texttag.setText() multiplies the size by 0.0023 itself
            {"SetTextTagColor", "$1.setColor($2, $3, $4, $5)"}, {"SetTextTagVelocity", "$1.setVelocity($2, $3)"}, {"SetTextTagPermanent", "$1.setPermanent($2)"},
            {"SetTextTagLifespan", "$1.setLifespan($2)"}, {"SetTextTagFadepoint", "$1.setFadepoint($2)"}, {"SetTextTagPos", "$1.setPos($2, $3, $4)"},
            // multiboard
            {"MultiboardGetItem", "$1.getItem($2, $3)"}, {"MultiboardSetItemValue", "$1.setValue($2)"}, {"MultiboardSetItemStyle", "$1.setStyle($2, $3)"}, {"MultiboardSetItemValueColor", "$1.setValueColor($2, $3, $4, $5)"},
            {"MultiboardSetItemWidth", "$1.setWidth($2)"}, {"MultiboardSetRowCount", "$1.setRowCount($2)"}, {"MultiboardSetColumnCount", "$1.setColumnCount($2)"}, {"MultiboardSetTitleText", "$1.setTitle($2)"},
            {"MultiboardDisplay", "$1.display($2)"}, {"DestroyMultiboard", "$1.destr()"}, {"MultiboardReleaseItem", "$1.release()"},
            // item / effect / rect / dialog / region / lightning / misc
            {"GetItemTypeId", "$1.getTypeId()"}, {"RemoveItem", "$1.remove()"}, {"GetItemCharges", "$1.getCharges()"}, {"SetItemCharges", "$1.setCharges($2)"}, {"GetItemName", "$1.getName()"},
            {"DestroyEffect", "$1.destr()"}, {"GetRectCenterX", "$1.getCenterX()"}, {"GetRectCenterY", "$1.getCenterY()"}, {"GetRectMinX", "$1.getMinX()"}, {"GetRectMaxX", "$1.getMaxX()"}, {"GetRectMinY", "$1.getMinY()"}, {"GetRectMaxY", "$1.getMaxY()"}, {"RemoveRect", "$1.remove()"},
            {"DialogAddButton", "$1.addButton($2, $3)"}, {"DialogSetMessage", "$1.setMessage($2)"}, {"DialogDisplay", "$1.display($2, $3)"}, {"DialogClear", "$1.clear()"}, {"RegionAddRect", "$1.addRect($2)"}, {"DestroyLightning", "$1.destr()"},
            {"I2S", "$1.toString()"}, {"R2I", "$1.toInt()"}, {"I2R", "$1.toReal()"}, {"R2S", "$1.toString()"}, {"S2I", "$1.toInt()"}, {"S2R", "$1.toReal()"}, {"StringLength", "$1.length()"},
        };
        for (String[] e : m) MAP.put(e[0], e[1]);
    }
    static int rewritten = 0;

    public static void main(String[] a) throws Exception {
        try (Stream<Path> s = Files.walk(Paths.get(a[0]))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".wurst")).collect(Collectors.toList())) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                List<String> out = new ArrayList<>();
                for (String l : lines) out.add(rewriteLine(l));
                out = cascades(out);
                for (int k = 0; k < out.size(); k++) out.set(k, out.get(k).replaceAll("vec2\\(([A-Za-z_][\\w.]*(?:\\(\\))?)\\.getX\\(\\), \\1\\.getY\\(\\)\\)", "$1.getPos()"));
                Files.write(p, out, StandardCharsets.UTF_8);
            }
        }
        System.out.println("calls rewritten: " + rewritten);
    }

    static String rewriteLine(String l) {
        String t = l.stripLeading();
        if (t.startsWith("//") || t.startsWith("import ") || t.startsWith("package ")) return l;
        String indent = l.substring(0, l.length() - t.length());
        return indent + rewrite(t);
    }

    /** rewrite calls in an expression/statement text, innermost first */
    static String rewrite(String s) {
        StringBuilder o = new StringBuilder(); int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') { int j = i + 1; while (j < n && s.charAt(j) != '"') { if (s.charAt(j) == '\\') j++; j++; } o.append(s, i, Math.min(n, j + 1)); i = j + 1; continue; }
            if (c == '\'') { int j = s.indexOf('\'', i + 1); o.append(s, i, j + 1); i = j + 1; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') { o.append(s.substring(i)); break; }
            if (Character.isLetter(c) || c == '_') {
                int j = i; while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                String id = s.substring(i, j);
                boolean isFuncRef = o.toString().endsWith("function ");
                if (j < n && s.charAt(j) == '(' && !isFuncRef && (i == 0 || s.charAt(i - 1) != '.')) {
                    int close = matchParen(s, j); String inner = s.substring(j + 1, close);
                    List<String> args = splitArgs(inner).stream().map(x -> rewrite(x.strip())).collect(Collectors.toList());
                    o.append(apply(id, args)); i = close + 1; continue;
                }
                o.append(id); i = j; continue;
            }
            o.append(c); i++;
        }
        return o.toString();
    }
    static int matchParen(String s, int open) { int d = 0; for (int i = open; i < s.length(); i++) { char c = s.charAt(i); if (c == '"') { i++; while (s.charAt(i) != '"') { if (s.charAt(i) == '\\') i++; i++; } continue; } if (c == '\'') { i = s.indexOf('\'', i + 1); continue; } if (c == '(') d++; else if (c == ')') { d--; if (d == 0) return i; } } throw new RuntimeException("unbalanced: " + s); }
    static List<String> splitArgs(String s) { List<String> r = new ArrayList<>(); if (s.isBlank()) return r; int d = 0, st = 0; for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == '"') { i++; while (s.charAt(i) != '"') { if (s.charAt(i) == '\\') i++; i++; } continue; } if (c == '\'') { i = s.indexOf('\'', i + 1); continue; } if (c == '(' || c == '[') d++; else if (c == ')' || c == ']') d--; else if (c == ',' && d == 0) { r.add(s.substring(st, i)); st = i + 1; } } r.add(s.substring(st)); return r; }

    /** receiver of a method call: identifier / call / index chains stay as is, anything with top-level operators gets parentheses */
    static String recv(String a) {
        if (a.isEmpty() || !(Character.isLetter(a.charAt(0)) || a.charAt(0) == '_')) return "(" + a + ")";
        int d = 0;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (c == '"') { i++; while (i < a.length() && a.charAt(i) != '"') { if (a.charAt(i) == '\\') i++; i++; } continue; }
            if (c == '\'') { i = a.indexOf('\'', i + 1); continue; }
            if (c == '(' || c == '[') d++; else if (c == ')' || c == ']') d--;
            else if (d == 0 && !(Character.isLetterOrDigit(c) || c == '_' || c == '.')) return "(" + a + ")";
        }
        return a;
    }

    static String apply(String id, List<String> args) {
        String call = id + "(" + String.join(", ", args) + ")";
        int n = args.size();
        // special cases first
        if (id.equals("GetUnitState") && n == 2) { rewritten++; switch (args.get(1)) { case "UNIT_STATE_LIFE": return recv(args.get(0)) + ".getHP()"; case "UNIT_STATE_MAX_LIFE": return recv(args.get(0)) + ".getMaxHP()"; case "UNIT_STATE_MANA": return recv(args.get(0)) + ".getMana()"; case "UNIT_STATE_MAX_MANA": return recv(args.get(0)) + ".getMaxMana()"; default: return recv(args.get(0)) + ".getState(" + args.get(1) + ")"; } }
        if (id.equals("SetUnitState") && n == 3) { rewritten++; switch (args.get(1)) { case "UNIT_STATE_LIFE": return recv(args.get(0)) + ".setHP(" + args.get(2) + ")"; case "UNIT_STATE_MANA": return recv(args.get(0)) + ".setMana(" + args.get(2) + ")"; default: return recv(args.get(0)) + ".setState(" + args.get(1) + ", " + args.get(2) + ")"; } }
        if (id.equals("GetPlayerState") && n == 2) { rewritten++; switch (args.get(1)) { case "PLAYER_STATE_RESOURCE_GOLD": return recv(args.get(0)) + ".getGold()"; case "PLAYER_STATE_RESOURCE_LUMBER": return recv(args.get(0)) + ".getLumber()"; default: return recv(args.get(0)) + ".getState(" + args.get(1) + ")"; } }
        if (id.equals("SetPlayerState") && n == 3) { rewritten++; switch (args.get(1)) { case "PLAYER_STATE_RESOURCE_GOLD": return recv(args.get(0)) + ".setGold(" + args.get(2) + ")"; case "PLAYER_STATE_RESOURCE_LUMBER": return recv(args.get(0)) + ".setLumber(" + args.get(2) + ")"; default: return recv(args.get(0)) + ".setState(" + args.get(1) + ", " + args.get(2) + ")"; } }
        if (id.equals("DisplayTimedTextToPlayer") && n == 5 && args.get(1).matches("0(\\.0?)?") && args.get(2).matches("0(\\.0?)?")) { rewritten++; return recv(args.get(0)) + ".print(" + args.get(4) + ", " + args.get(3) + ")"; }
        if (id.equals("PauseUnit") && n == 2 && (args.get(1).equals("true") || args.get(1).equals("false"))) { rewritten++; return recv(args.get(0)) + (args.get(1).equals("true") ? ".pause()" : ".unpause()"); }
        if (id.equals("ShowUnit") && n == 2 && (args.get(1).equals("true") || args.get(1).equals("false"))) { rewritten++; return recv(args.get(0)) + (args.get(1).equals("true") ? ".show()" : ".hide()"); }
        if (id.equals("SetUnitScale") && n == 4 && args.get(1).equals(args.get(2)) && args.get(1).equals(args.get(3))) { rewritten++; return recv(args.get(0)) + ".setScale(" + args.get(1) + ")"; }
        if (id.equals("TimerStart") && n == 4 && args.get(2).equals("false")) { rewritten++; return recv(args.get(0)) + ".start(" + args.get(1) + ", " + args.get(3) + ")"; }
        if (id.equals("TimerStart") && n == 4 && args.get(2).equals("true")) { rewritten++; return recv(args.get(0)) + ".startPeriodic(" + args.get(1) + ", " + args.get(3) + ")"; }
        if (id.equals("DestroyEffect") && n == 1) {
            Matcher m = Pattern.compile("^AddSpecialEffect\\((.*)\\)$").matcher(args.get(0));
            if (m.find()) { List<String> a = splitArgs(m.group(1)); if (a.size() == 3) { rewritten++; return "addEffect(" + a.get(0).strip() + ", vec2(" + a.get(1).strip() + ", " + a.get(2).strip() + ")).destr()"; } } // not flashEffect(path, vec2): it adds a random yaw
            Matcher m2 = Pattern.compile("^AddSpecialEffectTarget\\((.*)\\)$").matcher(args.get(0));
            if (m2.find()) { List<String> a = splitArgs(m2.group(1)); if (a.size() == 3) { rewritten++; return "flashEffect(" + a.get(0).strip() + ", " + a.get(1).strip() + ", " + a.get(2).strip() + ")"; } }
        }
        if (id.equals("AddSpecialEffect") && n == 3) { rewritten++; return "addEffect(" + args.get(0) + ", vec2(" + args.get(1) + ", " + args.get(2) + "))"; }
        if (id.equals("AddSpecialEffectTarget") && n == 3) { rewritten++; return "addEffect(" + args.get(0) + ", " + args.get(1) + ", " + args.get(2) + ")"; }
        if ((id.equals("I2S") || id.equals("R2I") || id.equals("I2R") || id.equals("R2S")) && n == 1 && (args.get(0).matches("-?[0-9.]+") || args.get(0).startsWith("GetRandomInt("))) return call; // literal / int-typed argument
        String tpl = MAP.get(id);
        if (tpl == null) return call;
        int need = 0; Matcher pm = Pattern.compile("\\$(\\d)").matcher(tpl); while (pm.find()) need = Math.max(need, Integer.parseInt(pm.group(1)));
        if (need != n) return call;
        String r = tpl;
        for (int k = n; k >= 1; k--) { String a = args.get(k - 1); boolean receiver = r.startsWith("$" + k + "."); r = r.replace("$" + k, receiver ? recv(a) : a); }
        rewritten++;
        return r;
    }

    /** "x = CreateTrigger()" followed by "x.foo(...)" lines (same indent) -> cascade */
    static List<String> cascades(List<String> in) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            String l = in.get(i);
            Matcher m = Pattern.compile("^(\\s*)(?:let |var )?([A-Za-z_]\\w*) = (Create(?:Trigger|Timer|Group|Multiboard|Dialog|Force)\\(\\)|CreateTextTag\\(\\))$").matcher(l);
            if (m.find()) {
                String ind = m.group(1), var = m.group(2); int j = i + 1; List<String> calls = new ArrayList<>();
                while (j < in.size() && in.get(j).matches("^" + Pattern.quote(ind) + var + "\\.[a-zA-Z]\\w*\\(.*\\)$")) { calls.add(in.get(j).substring(ind.length() + var.length())); j++; }
                if (calls.size() >= 2) { out.add(l); for (String c : calls) out.add(ind + "    .." + c.substring(1)); i = j - 1; continue; }
            }
            out.add(l);
        }
        return out;
    }
}
