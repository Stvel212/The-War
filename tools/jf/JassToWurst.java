import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;

/**
 * Код триггеров Just Fight (строки JASS после PortJfTriggers) -> Wurst: разбор в дерево и перепись по-вурстовски.
 * <ul>
 * <li>триггер (создание, регистрация в диспетчере, условие, действие) — в init, функции названы по событию и ключу
 *     триггера (attackAJ4Q, attackAJ4QCondition…), общие функции и глобальные — по списку names.txt;
 * <li>счётчики YDWE (локальные «переменные триггера» в хеш-таблице по GetTriggeringTrigger) убраны;
 * <li>таймер с данными в хеш-таблице -> doAfter/doPeriodically с замыканием, ForGroup с одной функцией -> for u in g,
 *     цикл bj_forLoopAIndex -> for;
 * <li>location -> vec2, «последний созданный» юнит/эффект/текст -> локальные, BJ и натив -> методы stdlib.
 * </ul>
 *   java JassToWurst <вход> <выход .wurst> <names.txt>
 */
public class JassToWurst {
    // ---------------- дерево ----------------
    interface Expr {}
    record Lit(String s) implements Expr {}
    record Name(String n) implements Expr {}
    record Index(String n, Expr i) implements Expr {}
    record Call(String n, List<Expr> a) implements Expr {}
    record MCall(Expr r, String n, List<Expr> a) implements Expr {}
    record FRef(String n) implements Expr {}
    record Un(String op, Expr e) implements Expr {}
    record Bin(String op, Expr l, Expr r) implements Expr {}
    record Field(Expr r, String n) implements Expr {}

    static abstract class Stmt {}
    static class Local extends Stmt { String type, name; Expr init; Local(String t, String n, Expr i) { type = t; name = n; init = i; } }
    static class Assign extends Stmt { Expr target, value; Assign(Expr t, Expr v) { target = t; value = v; } }
    static class ExprS extends Stmt { Expr e; ExprS(Expr e) { this.e = e; } }
    static class If extends Stmt { List<Expr> conds = new ArrayList<>(); List<List<Stmt>> blocks = new ArrayList<>(); List<Stmt> elseB; }
    static class Loop extends Stmt { List<Stmt> body = new ArrayList<>(); }
    static class Exit extends Stmt { Expr cond; Exit(Expr c) { cond = c; } }
    static class Return extends Stmt { Expr value; Return(Expr v) { value = v; } }
    static class For extends Stmt { String var; Expr from, to; List<Stmt> body; }
    static class ForIn extends Stmt { String type, var; Expr coll; List<Stmt> body; }
    /** doAfter(time) -> / doPeriodically(time) cb -> */
    static class Lambda extends Stmt { String fn; Expr time; String cb; List<Stmt> body; }
    static class Raw extends Stmt { String s; Raw(String s) { this.s = s; } }

    static class Func { String name, ret; List<String[]> params = new ArrayList<>(); List<Stmt> body = new ArrayList<>(); String doc; }
    static class Global { String type, name; boolean array, constant; Expr init; }

    // ---------------- разбор ----------------
    static final Pattern TOKEN = Pattern.compile("\\s*('[^']*'|\"(?:[^\"\\\\]|\\\\.)*\"|\\d+\\.\\d*|\\.\\d+|\\d+|[A-Za-z_][A-Za-z0-9_]*|==|!=|<=|>=|[<>+\\-*/(),\\[\\]])");

    static class P {
        List<String> t = new ArrayList<>();
        int i = 0;
        P(String s) {
            Matcher m = TOKEN.matcher(s);
            int pos = 0;
            while (pos < s.length()) {
                if (s.substring(pos).isBlank()) break;
                if (!m.find(pos) || m.start() != pos) throw new IllegalStateException("лексема: " + s.substring(pos));
                t.add(m.group(1));
                pos = m.end();
            }
        }
        String peek() { return i < t.size() ? t.get(i) : ""; }
        String next() { return t.get(i++); }
        void expect(String s) { if (!next().equals(s)) throw new IllegalStateException("ожидалось " + s + " в " + t); }
        boolean done() { return i >= t.size(); }
    }

    static Expr parse(String s) {
        P p = new P(s);
        Expr e = or(p);
        if (!p.done()) throw new IllegalStateException("лишнее: " + p.t.subList(p.i, p.t.size()) + " в " + s);
        return e;
    }

    static Expr or(P p) { Expr e = and(p); while (p.peek().equals("or")) { p.next(); e = new Bin("or", e, and(p)); } return e; }
    static Expr and(P p) { Expr e = not(p); while (p.peek().equals("and")) { p.next(); e = new Bin("and", e, not(p)); } return e; }
    static Expr not(P p) { if (p.peek().equals("not")) { p.next(); return new Un("not", not(p)); } return cmp(p); }
    static final Set<String> CMP = Set.of("==", "!=", "<", "<=", ">", ">=");
    static Expr cmp(P p) { Expr e = add(p); while (CMP.contains(p.peek())) { String op = p.next(); e = new Bin(op, e, add(p)); } return e; }
    static Expr add(P p) { Expr e = mul(p); while (p.peek().equals("+") || p.peek().equals("-")) { String op = p.next(); e = new Bin(op, e, mul(p)); } return e; }
    static Expr mul(P p) {
        Expr e = unary(p);
        while (p.peek().equals("*") || p.peek().equals("/") || p.peek().equals("div") || p.peek().equals("mod")) { String op = p.next(); e = new Bin(op, e, unary(p)); }
        return e;
    }
    static Expr unary(P p) {
        if (p.peek().equals("-")) { p.next(); return new Un("-", unary(p)); }
        if (p.peek().equals("+")) { p.next(); return unary(p); }
        return primary(p);
    }
    static Expr primary(P p) {
        String t = p.next();
        if (t.equals("(")) { Expr e = or(p); p.expect(")"); return e; }
        if (t.equals("function")) return new FRef(p.next());
        if (Character.isLetter(t.charAt(0)) || t.charAt(0) == '_') {
            if (p.peek().equals("(")) {
                p.next();
                List<Expr> a = new ArrayList<>();
                if (!p.peek().equals(")")) { a.add(or(p)); while (p.peek().equals(",")) { p.next(); a.add(or(p)); } }
                p.expect(")");
                return new Call(t, a);
            }
            if (p.peek().equals("[")) { p.next(); Expr i = or(p); p.expect("]"); return new Index(t, i); }
            return new Name(t);
        }
        return new Lit(t);
    }

    static final Pattern HEADER = Pattern.compile("^function ([A-Za-z0-9_]+) takes (.*) returns ([A-Za-z]+)$");

    static List<String> header = new ArrayList<>();
    static Map<String, Global> globals = new LinkedHashMap<>();
    static Map<String, Func> funcs = new LinkedHashMap<>();
    static List<Stmt> init = new ArrayList<>();

    static void read(String text) {
        boolean inGlobals = false;
        Deque<List<Stmt>> stack = new ArrayDeque<>();
        Deque<Stmt> owners = new ArrayDeque<>();
        for (String raw : text.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.equals("endpackage")) continue;
            if (stack.isEmpty() && !inGlobals && (l.startsWith("package ") || l.startsWith("import ") || l.startsWith("//"))) { header.add(l); continue; }
            if (l.equals("globals")) { inGlobals = true; continue; }
            if (l.equals("endglobals")) { inGlobals = false; continue; }
            if (inGlobals) {
                Global g = new Global();
                if (l.startsWith("constant ")) { g.constant = true; l = l.substring(9); }
                String[] w = l.split(" ", 2);
                g.type = w[0];
                String rest = w[1];
                if (rest.startsWith("array ")) { g.array = true; rest = rest.substring(6); }
                int eq = rest.indexOf('=');
                g.name = eq < 0 ? rest : rest.substring(0, eq);
                if (eq >= 0) g.init = parse(rest.substring(eq + 1));
                globals.put(g.name, g);
                continue;
            }
            Matcher h = HEADER.matcher(l);
            if (h.matches()) {
                Func f = new Func();
                f.name = h.group(1);
                f.ret = h.group(3);
                if (!h.group(2).equals("nothing"))
                    for (String p : h.group(2).split(",")) { String[] w = p.trim().split(" "); f.params.add(new String[]{w[0], w[1]}); }
                funcs.put(f.name, f);
                stack.clear(); owners.clear();
                stack.push(f.body);
                continue;
            }
            if (l.equals("init")) { stack.clear(); owners.clear(); stack.push(init); continue; }
            if (l.equals("endfunction") || l.equals("endinit")) { stack.clear(); owners.clear(); continue; }
            if (l.equals("endif") || l.equals("endloop")) { stack.pop(); owners.pop(); continue; }
            if (l.equals("else") || l.matches("^elseif[ (].*")) {
                stack.pop();
                If s = (If) owners.peek();
                if (l.equals("else")) { s.elseB = new ArrayList<>(); stack.push(s.elseB); }
                else { s.conds.add(parse(l.substring(6).replaceFirst("then$", ""))); List<Stmt> b = new ArrayList<>(); s.blocks.add(b); stack.push(b); }
                continue;
            }
            List<Stmt> cur = stack.peek();
            if (l.matches("^if[ (].*")) {
                If s = new If();
                s.conds.add(parse(l.substring(2).replaceFirst("then$", "")));
                List<Stmt> b = new ArrayList<>();
                s.blocks.add(b);
                cur.add(s); owners.push(s); stack.push(b);
            } else if (l.equals("loop")) {
                Loop s = new Loop();
                cur.add(s); owners.push(s); stack.push(s.body);
            } else if (l.matches("^exitwhen[ (].*")) {
                cur.add(new Exit(parse(l.substring(8))));
            } else if (l.startsWith("call ")) {
                cur.add(new ExprS(parse(l.substring(5))));
            } else if (l.startsWith("set ")) {
                String s = l.substring(4);
                int eq = assignEq(s);
                cur.add(new Assign(parse(s.substring(0, eq)), parse(s.substring(eq + 1))));
            } else if (l.startsWith("local ")) {
                String[] w = l.substring(6).split(" ", 2);
                int eq = w[1].indexOf('=');
                cur.add(new Local(w[0], eq < 0 ? w[1] : w[1].substring(0, eq), eq < 0 ? null : parse(w[1].substring(eq + 1))));
            } else if (l.startsWith("return")) {
                String v = l.substring(6).trim();
                cur.add(new Return(v.isEmpty() ? null : parse(v)));
            } else throw new IllegalStateException("не JASS-оператор: " + l);
        }
    }

    static int assignEq(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if (c == ']' || c == ')') depth--;
            else if (c == '=' && depth == 0) return i;
        }
        throw new IllegalStateException("не присваивание: " + s);
    }

    // ---------------- обход ----------------
    /** Снизу вверх: f к каждому подвыражению */
    static Expr map(Expr e, UnaryOperator<Expr> f) {
        if (e == null) return null;
        Expr r = e;
        if (e instanceof Index x) r = new Index(x.n(), map(x.i(), f));
        else if (e instanceof Call c) r = new Call(c.n(), mapAll(c.a(), f));
        else if (e instanceof MCall c) r = new MCall(map(c.r(), f), c.n(), mapAll(c.a(), f));
        else if (e instanceof Un u) r = new Un(u.op(), map(u.e(), f));
        else if (e instanceof Bin b) r = new Bin(b.op(), map(b.l(), f), map(b.r(), f));
        else if (e instanceof Field x) r = new Field(map(x.r(), f), x.n());
        return f.apply(r);
    }

    static List<Expr> mapAll(List<Expr> l, UnaryOperator<Expr> f) {
        List<Expr> r = new ArrayList<>();
        for (Expr e : l) r.add(map(e, f));
        return r;
    }

    /** Все выражения операторов списка (и вложенных блоков; в замыкания — если lambdas) */
    static void mapStmts(List<Stmt> body, UnaryOperator<Expr> f, boolean lambdas) {
        for (Stmt s : body) {
            if (s instanceof Local x) x.init = map(x.init, f);
            else if (s instanceof Assign x) { x.target = map(x.target, f); x.value = map(x.value, f); }
            else if (s instanceof ExprS x) x.e = map(x.e, f);
            else if (s instanceof If x) {
                x.conds.replaceAll(c -> map(c, f));
                for (List<Stmt> b : x.blocks) mapStmts(b, f, lambdas);
                if (x.elseB != null) mapStmts(x.elseB, f, lambdas);
            } else if (s instanceof Loop x) mapStmts(x.body, f, lambdas);
            else if (s instanceof Exit x) x.cond = map(x.cond, f);
            else if (s instanceof Return x) x.value = map(x.value, f);
            else if (s instanceof For x) { x.from = map(x.from, f); x.to = map(x.to, f); mapStmts(x.body, f, lambdas); }
            else if (s instanceof ForIn x) { x.coll = map(x.coll, f); mapStmts(x.body, f, lambdas); }
            else if (s instanceof Lambda x) { x.time = map(x.time, f); if (lambdas) mapStmts(x.body, f, true); }
        }
    }

    static void eachExpr(List<Stmt> body, Consumer<Expr> c) {
        mapStmts(body, e -> { c.accept(e); return e; }, true);
    }

    /** Все списки операторов (сам список и вложенные блоки, включая замыкания) */
    static void eachList(List<Stmt> body, Consumer<List<Stmt>> c) {
        c.accept(body);
        for (Stmt s : new ArrayList<>(body)) {
            if (s instanceof If x) { for (List<Stmt> b : x.blocks) eachList(b, c); if (x.elseB != null) eachList(x.elseB, c); }
            else if (s instanceof Loop x) eachList(x.body, c);
            else if (s instanceof For x) eachList(x.body, c);
            else if (s instanceof ForIn x) eachList(x.body, c);
            else if (s instanceof Lambda x) eachList(x.body, c);
        }
    }

    static boolean mentions(List<Stmt> body, Predicate<Expr> p) {
        boolean[] r = {false};
        eachExpr(body, e -> { if (p.test(e)) r[0] = true; });
        return r[0];
    }

    static boolean mentions(Stmt s, Predicate<Expr> p) { return mentions(List.of(s), p); }

    static boolean isCall(Expr e, String n) { return e instanceof Call c && c.n().equals(n); }
    static boolean isName(Expr e, String n) { return e instanceof Name x && x.n().equals(n); }
    static boolean isCallS(Stmt s, String n) { return s instanceof ExprS x && isCall(x.e, n); }
    static Call call(Stmt s) { return (Call) ((ExprS) s).e; }
    static Call call(String n, Expr... a) { return new Call(n, new ArrayList<>(List.of(a))); }
    static MCall mcall(Expr r, String n, Expr... a) { return new MCall(r, n, new ArrayList<>(List.of(a))); }

    /** Сколько раз функция упомянута во всём коде (вызовы и ссылки) */
    static Map<String, Integer> funcRefs() {
        Map<String, Integer> r = new HashMap<>();
        Consumer<Expr> c = e -> {
            if (e instanceof Call x && funcs.containsKey(x.n())) r.merge(x.n(), 1, Integer::sum);
            if (e instanceof FRef x) r.merge(x.n(), 1, Integer::sum);
        };
        for (Func f : funcs.values()) eachExpr(f.body, c);
        eachExpr(init, c);
        return r;
    }

    // ---------------- проходы ----------------

    /** Счётчик YDWE: local n = LoadInteger(h, id(GetTriggeringTrigger()), k); n = n + 3; SaveInteger(…n); FlushChildHashtable(h, id(…) * n) */
    static void dropYdweCounters() {
        for (Func f : funcs.values()) {
            for (Stmt s : new ArrayList<>(f.body)) {
                if (!(s instanceof Local l) || !isCall(l.init, "LoadInteger") || !triggerKey(((Call) l.init).a().get(1))) continue;
                String v = l.name;
                Predicate<Stmt> junk = x ->
                    x == l
                    || x instanceof Assign a && isName(a.target, v)
                    || isCallS(x, "SaveInteger") && triggerKey(call(x).a().get(1)) && isName(call(x).a().get(3), v)
                    || isCallS(x, "FlushChildHashtable") && call(x).a().get(1) instanceof Bin b && triggerKey(b.l()) && isName(b.r(), v);
                // «локальные» YDWE: Save/Load(h, id(триггера) * n, k) — обычные локальные функции
                Predicate<Expr> localKey = e -> e instanceof Bin b && b.op().equals("*") && triggerKey(b.l()) && isName(b.r(), v);
                Map<String, String> types = new LinkedHashMap<>();
                eachExpr(f.body, e -> {
                    if (e instanceof Call c && c.a().size() >= 3 && localKey.test(c.a().get(1))) {
                        Matcher m = SAVE.matcher(c.n());
                        if (!m.matches()) m = LOAD.matcher(c.n());
                        if (m.matches()) types.putIfAbsent(keyOf(c.a().get(2)), hashType(m.group(1)));
                    }
                });
                Map<String, String> vars = new HashMap<>();
                for (String k : types.keySet()) vars.put(k, "jfYdweVar" + (++lambdaVars));
                List<Stmt> rest = new ArrayList<>(f.body);
                rest.removeIf(junk);
                List<Stmt> probe = new ArrayList<>();
                for (Stmt x : rest) probe.add(x);
                boolean[] other = {false};
                for (List<Stmt> pl : lists(probe)) for (Stmt x : pl) {
                    if (x instanceof ExprS es && es.e instanceof Call c && SAVE.matcher(c.n()).matches() && localKey.test(c.a().get(1))) {
                        for (int ai = 0; ai < c.a().size(); ai++) if (ai != 1 && mentionsYdwe(c.a().get(ai), v, localKey)) other[0] = true;
                        continue;
                    }
                    for (Expr e : ownExprs(x)) if (mentionsYdwe(e, v, localKey)) other[0] = true;
                }
                if (other[0]) continue;
                f.body.removeIf(junk);
                eachList(f.body, bl -> bl.replaceAll(x -> {
                    if (x instanceof ExprS es && es.e instanceof Call c && SAVE.matcher(c.n()).matches() && localKey.test(c.a().get(1)))
                        return new Assign(new Name(vars.get(keyOf(c.a().get(2)))), c.a().get(3));
                    return x;
                }));
                mapStmts(f.body, e -> e instanceof Call c && LOAD.matcher(c.n()).matches() && c.a().size() >= 3 && localKey.test(c.a().get(1))
                    ? new Name(vars.get(keyOf(c.a().get(2)))) : e, true);
                int at = 0;
                for (String k : types.keySet()) f.body.add(at++, new Local(types.get(k), vars.get(k), defaultValue(types.get(k))));
            }
        }
    }

    /** n вне ключа «id(триггера) * n» */
    static boolean mentionsYdwe(Expr e, String v, Predicate<Expr> localKey) {
        if (localKey.test(e)) return false;
        if (isName(e, v)) return true;
        if (e instanceof Call c) { for (Expr x : c.a()) if (mentionsYdwe(x, v, localKey)) return true; return false; }
        if (e instanceof MCall c) { if (mentionsYdwe(c.r(), v, localKey)) return true; for (Expr x : c.a()) if (mentionsYdwe(x, v, localKey)) return true; return false; }
        if (e instanceof Index x) return mentionsYdwe(x.i(), v, localKey);
        if (e instanceof Un u) return mentionsYdwe(u.e(), v, localKey);
        if (e instanceof Bin b) return mentionsYdwe(b.l(), v, localKey) || mentionsYdwe(b.r(), v, localKey);
        if (e instanceof Field f) return mentionsYdwe(f.r(), v, localKey);
        return false;
    }

    static boolean triggerKey(Expr e) { return isCall(e, "GetHandleId") && isCall(((Call) e).a().get(0), "GetTriggeringTrigger"); }
    static boolean timerKey(Expr e, Predicate<Expr> timer) { return isCall(e, "GetHandleId") && timer.test(((Call) e).a().get(0)); }
    static final Predicate<Expr> EXPIRED = e -> isCall(e, "GetExpiredTimer");

    /** Выражения самого оператора (без вложенных блоков) */
    static List<Expr> ownExprs(Stmt s) {
        List<Expr> r = new ArrayList<>();
        if (s instanceof Local x && x.init != null) r.add(x.init);
        else if (s instanceof Assign x) { r.add(x.target); r.add(x.value); }
        else if (s instanceof ExprS x) r.add(x.e);
        else if (s instanceof If x) r.addAll(x.conds);
        else if (s instanceof Exit x) r.add(x.cond);
        else if (s instanceof Return x && x.value != null) r.add(x.value);
        else if (s instanceof For x) { r.add(x.from); r.add(x.to); }
        else if (s instanceof ForIn x) r.add(x.coll);
        else if (s instanceof Lambda x) r.add(x.time);
        return r;
    }

    /** GetExpiredTimer не в ключе Load/Save таймера */
    static boolean expiredOutsideKeys(Expr e) {
        if (EXPIRED.test(e)) return true;
        if (e instanceof Call c) {
            boolean key = (LOAD.matcher(c.n()).matches() || SAVE.matcher(c.n()).matches()) && c.a().size() >= 3 && timerKey(c.a().get(1), EXPIRED);
            for (int i = 0; i < c.a().size(); i++) if (!(key && i == 1) && expiredOutsideKeys(c.a().get(i))) return true;
            return false;
        }
        if (e instanceof Index x) return expiredOutsideKeys(x.i());
        if (e instanceof MCall c) { if (expiredOutsideKeys(c.r())) return true; for (Expr x : c.a()) if (expiredOutsideKeys(x)) return true; return false; }
        if (e instanceof Un u) return expiredOutsideKeys(u.e());
        if (e instanceof Bin b) return expiredOutsideKeys(b.l()) || expiredOutsideKeys(b.r());
        if (e instanceof Field f) return expiredOutsideKeys(f.r());
        return false;
    }

    static final Pattern SAVE = Pattern.compile("^Save(Integer|Real|Boolean|Str|[A-Za-z]+Handle)$");
    static final Pattern LOAD = Pattern.compile("^Load(Integer|Real|Boolean|Str|[A-Za-z]+Handle)$");

    static String hashType(String k) {
        switch (k) {
            case "Integer": return "integer";
            case "Real": return "real";
            case "Boolean": return "boolean";
            case "Str": return "string";
        }
        String t = k.replace("Handle", "");
        return t.equals("TextTag") ? "texttag" : t.toLowerCase();
    }

    static Expr defaultValue(String type) {
        switch (type) {
            case "integer": return new Lit("0");
            case "real": return new Lit("0.0");
            case "boolean": return new Lit("false");
            case "string": return new Lit("\"\"");
        }
        return new Lit("null");
    }

    static String keyOf(Expr k) { return printExpr(k, 0); }

    static int lambdaVars = 0;

    /**
     * t = CreateTimer(); Save*(h, id(t), k, v)…; TimerStart(t, d, periodic, function f) -> doAfter/doPeriodically: данные —
     * переменные замыкания, тело f — внутрь (f только здесь и берёт из хеш-таблицы только своё)
     */
    static boolean timersToClosures() {
        boolean changed = false;
        Map<String, Integer> refs = funcRefs();
        for (Func owner : new ArrayList<>(funcs.values())) {
            if (!funcs.containsKey(owner.name)) continue;
            List<List<Stmt>> lists = new ArrayList<>();
            eachList(owner.body, lists::add);
            for (List<Stmt> list : lists) {
                for (int i = 0; i < list.size(); i++) {
                    Stmt s = list.get(i);
                    if (!(s instanceof Assign a) || !(a.target instanceof Name tn) || !isCall(a.value, "CreateTimer")) continue;
                    String t = tn.n();
                    if (globals.containsKey(t)) continue;
                    int start = -1;
                    for (int j = i + 1; j < list.size(); j++)
                        if (isCallS(list.get(j), "TimerStart") && isName(call(list.get(j)).a().get(0), t)) { start = j; break; }
                    if (start < 0) continue;
                    Call ts = call(list.get(start));
                    if (!(ts.a().get(3) instanceof FRef fr) || refs.getOrDefault(fr.n(), 0) != 1 || !funcs.containsKey(fr.n())) continue;
                    boolean periodic = isName(ts.a().get(2), "true");
                    if (!periodic && !isName(ts.a().get(2), "false")) continue;
                    Func cb = funcs.get(fr.n());
                    if (!cb.params.isEmpty() || cb.name.equals(owner.name)) continue;
                    // сохранения в таймер между созданием и запуском; другое обращение к таймеру — не трогаем
                    Map<String, String> types = new LinkedHashMap<>();
                    boolean ok = true;
                    String hash = null;
                    for (int j = i + 1; j < start && ok; j++) {
                        Stmt x = list.get(j);
                        if (x instanceof ExprS es && es.e instanceof Call c && SAVE.matcher(c.n()).matches() && timerKey(c.a().get(1), e -> isName(e, t))) {
                            if (mentions(List.of(new ExprS(c.a().get(3))), e -> isName(e, t))) ok = false;
                            hash = hash == null ? keyOf(c.a().get(0)) : hash;
                            Matcher m = SAVE.matcher(c.n()); m.matches();
                            types.put(keyOf(c.a().get(2)), hashType(m.group(1)));
                        } else if (mentions(x, e -> isName(e, t))) ok = false;
                    }
                    List<Stmt> after = new ArrayList<>(list.subList(start + 1, list.size()));
                    if (!ok || mentions(after, e -> isName(e, t))) continue;
                    // тело обратного вызова: из таймера только Load/Save своих ключей, Flush и Destroy
                    Set<String> saved = new HashSet<>();
                    boolean[] bad = {false};
                    String[] hashRef = {hash};
                    eachExpr(cb.body, e -> {
                        if (e instanceof Call c && (LOAD.matcher(c.n()).matches() || SAVE.matcher(c.n()).matches()) && c.a().size() >= 3 && timerKey(c.a().get(1), EXPIRED)) {
                            Matcher m = LOAD.matcher(c.n());
                            boolean save = !m.matches();
                            if (save) { m = SAVE.matcher(c.n()); m.matches(); saved.add(keyOf(c.a().get(2))); }
                            types.putIfAbsent(keyOf(c.a().get(2)), hashType(m.group(1)));
                            if (hashRef[0] == null) hashRef[0] = keyOf(c.a().get(0));
                        }
                    });
                    // GetExpiredTimer — только в ключах, Flush и Destroy
                    eachList(cb.body, l -> {
                        for (Stmt x : l) {
                            if (isCallS(x, "FlushChildHashtable") && timerKey(call(x).a().get(1), EXPIRED)) continue;
                            if (isCallS(x, "DestroyTimer") && EXPIRED.test(call(x).a().get(0))) continue;
                            for (Expr e : ownExprs(x)) if (expiredOutsideKeys(e)) bad[0] = true;
                        }
                    });
                    // функции, которые вызывает обратный вызов (фильтры…), тоже не должны читать таймер
                    for (String g : closureOf(cb.name)) if (!g.equals(cb.name) && funcs.containsKey(g) && mentions(funcs.get(g).body, EXPIRED)) bad[0] = true;
                    if (bad[0]) continue;
                    if (!periodic && !saved.isEmpty()) continue;
                    // переменные замыкания
                    Map<String, String> vars = new HashMap<>();
                    for (String k : types.keySet()) vars.put(k, "jfTimerVar" + (++lambdaVars));
                    List<Stmt> repl = new ArrayList<>();
                    Set<String> initialized = new HashSet<>();
                    for (int j = i + 1; j < start; j++) {
                        Stmt x = list.get(j);
                        if (x instanceof ExprS es && es.e instanceof Call c && SAVE.matcher(c.n()).matches() && timerKey(c.a().get(1), e -> isName(e, t))) {
                            String k = keyOf(c.a().get(2));
                            if (initialized.add(k)) repl.add(new Local(types.get(k), vars.get(k), c.a().get(3)));
                            else repl.add(new Assign(new Name(vars.get(k)), c.a().get(3)));
                        } else repl.add(x);
                    }
                    for (String k : types.keySet())
                        if (!initialized.contains(k)) repl.add(new Local(types.get(k), vars.get(k), defaultValue(types.get(k))));
                    Lambda lam = new Lambda();
                    lam.fn = periodic ? "doPeriodically" : "doAfter";
                    lam.cb = periodic ? "cb" : null;
                    lam.time = ts.a().get(1);
                    lam.body = cb.body;
                    // Flush/Destroy
                    eachList(lam.body, l -> {
                        l.removeIf(x -> isCallS(x, "FlushChildHashtable") && timerKey(call(x).a().get(1), EXPIRED));
                        if (periodic) l.replaceAll(x -> isCallS(x, "DestroyTimer") && EXPIRED.test(call(x).a().get(0)) ? new Raw("destroy cb") : x);
                        else l.removeIf(x -> isCallS(x, "DestroyTimer") && EXPIRED.test(call(x).a().get(0)));
                    });
                    // Load -> переменная, Save -> присваивание
                    eachList(lam.body, l -> l.replaceAll(x -> {
                        if (x instanceof ExprS es && es.e instanceof Call c && SAVE.matcher(c.n()).matches() && timerKey(c.a().get(1), EXPIRED))
                            return new Assign(new Name(vars.get(keyOf(c.a().get(2)))), c.a().get(3));
                        return x;
                    }));
                    mapStmts(lam.body, e -> e instanceof Call c && LOAD.matcher(c.n()).matches() && c.a().size() >= 3 && timerKey(c.a().get(1), EXPIRED)
                        ? new Name(vars.get(keyOf(c.a().get(2)))) : e, true);
                    if (lam.body.isEmpty()) lam.body.add(new Raw("skip"));
                    repl.add(lam);
                    List<Stmt> head = new ArrayList<>(list.subList(0, i));
                    List<Stmt> tail = new ArrayList<>(list.subList(start + 1, list.size()));
                    list.clear();
                    list.addAll(head); list.addAll(repl); list.addAll(tail);
                    funcs.remove(cb.name);
                    // локальная таймера больше не нужна
                    eachList(owner.body, l -> l.removeIf(x -> x instanceof Local lo && lo.name.equals(t) && lo.init == null));
                    changed = true;
                    refs = funcRefs();
                    break;
                }
            }
        }
        return changed;
    }

    /** ForGroup(g, function f) -> for u in g: f только здесь, без return, GetEnumUnit — только в самой f */
    static boolean groupsToLoops() {
        boolean changed = false;
        Map<String, Integer> refs = funcRefs();
        Set<String> enumUsers = new HashSet<>();
        for (Func f : funcs.values()) if (mentions(f.body, e -> isCall(e, "GetEnumUnit"))) enumUsers.add(f.name);
        for (Func owner : new ArrayList<>(funcs.values())) {
            if (!funcs.containsKey(owner.name)) continue;
            List<List<Stmt>> lists = new ArrayList<>();
            eachList(owner.body, lists::add);
            for (List<Stmt> list : lists)
                for (int i = 0; i < list.size(); i++) {
                    Stmt s = list.get(i);
                    if (!isCallS(s, "ForGroupBJ") && !isCallS(s, "ForGroup")) continue;
                    Call c = call(s);
                    if (!(c.a().get(1) instanceof FRef fr) || refs.getOrDefault(fr.n(), 0) != 1 || !funcs.containsKey(fr.n())) continue;
                    Func cb = funcs.get(fr.n());
                    if (!cb.params.isEmpty() || cb.name.equals(owner.name)) continue;
                    boolean[] bad = {false};
                    eachList(cb.body, l -> { for (Stmt x : l) if (x instanceof Return) bad[0] = true; });
                    eachExpr(cb.body, e -> { if (e instanceof Call x && enumUsers.contains(x.n()) || e instanceof FRef x2 && enumUsers.contains(x2.n())) bad[0] = true; });
                    if (bad[0]) continue;
                    ForIn f = new ForIn();
                    f.type = "unit";
                    f.var = "jfEnumVar" + (++lambdaVars);
                    f.coll = c.a().get(0);
                    f.body = cb.body;
                    mapStmts(f.body, e -> isCall(e, "GetEnumUnit") ? new Name(f.var) : e, true);
                    if (f.body.isEmpty()) f.body.add(new Raw("skip"));
                    list.set(i, f);
                    funcs.remove(cb.name);
                    refs = funcRefs();
                    changed = true;
                }
        }
        return changed;
    }

    /** bj_forLoopAIndex = a; bj_forLoopAIndexEnd = b; loop { exitwhen bj_forLoopAIndex > bj_forLoopAIndexEnd … +1 } -> for */
    static void bjLoops() {
        for (Func f : funcs.values())
            eachList(f.body, list -> {
                for (int i = 0; i + 2 < list.size(); i++) {
                    if (!(list.get(i) instanceof Assign a1) || !isName(a1.target, "bj_forLoopAIndex")) continue;
                    if (!(list.get(i + 1) instanceof Assign a2) || !isName(a2.target, "bj_forLoopAIndexEnd")) continue;
                    if (!(list.get(i + 2) instanceof Loop lp) || lp.body.size() < 2) continue;
                    if (!(lp.body.get(0) instanceof Exit ex) || !(ex.cond instanceof Bin b) || !b.op().equals(">") || !isName(b.l(), "bj_forLoopAIndex") || !isName(b.r(), "bj_forLoopAIndexEnd")) continue;
                    Stmt last = lp.body.get(lp.body.size() - 1);
                    if (!(last instanceof Assign inc) || !isName(inc.target, "bj_forLoopAIndex")) continue;
                    List<Stmt> body = new ArrayList<>(lp.body.subList(1, lp.body.size() - 1));
                    if (mentions(body, e -> isName(e, "bj_forLoopAIndex") || isName(e, "bj_forLoopAIndexEnd"))) continue;
                    For fo = new For();
                    fo.var = "jfLoopVar" + (++lambdaVars);
                    fo.from = a1.value; fo.to = a2.value; fo.body = body.isEmpty() ? new ArrayList<>(List.of(new Raw("skip"))) : body;
                    list.remove(i + 2); list.remove(i + 1);
                    list.set(i, fo);
                }
            });
    }

    /**
     * «Последний созданный» (GetLastCreatedUnit/эффект/текст) -> локальная: создание в списке операторов, дальше в нём
     * же (не в замыкании) — её значение. Если так не выходит — глобальная пакета (как bj_lastCreated…)
     */
    static final Map<String, String[]> LAST = new LinkedHashMap<>();
    static {
        // вид: [тип, глобальная, чтения…]
        LAST.put("unit", new String[]{"unit", "jfLastUnit", "GetLastCreatedUnit", "bj_lastCreatedUnit"});
        LAST.put("effect", new String[]{"effect", "jfLastEffect", "GetLastCreatedEffectBJ", "bj_lastCreatedEffect"});
        LAST.put("texttag", new String[]{"texttag", "jfLastTextTag", "GetLastCreatedTextTag", "bj_lastCreatedTextTag"});
    }
    static final Set<String> usedLastGlobals = new TreeSet<>();

    static String createdKind(Stmt s) {
        if (!(s instanceof ExprS es) || !(es.e instanceof Call c)) return null;
        switch (c.n()) {
            case "CreateNUnitsAtLoc": return "unit";
            case "AddSpecialEffectLocBJ": case "AddSpecialEffectTargetUnitBJ": return "effect";
            case "CreateTextTagUnitBJ": case "CreateTextTagLocBJ": return "texttag";
        }
        return null;
    }

    static boolean readsLast(Expr e, String kind) {
        String[] k = LAST.get(kind);
        for (int i = 2; i < k.length; i++) if (isCall(e, k[i]) || isName(e, k[i])) return true;
        return false;
    }

    /** Создание -> значение: юнит — createUnit, эффект — addEffect, текст — jfTextTag (PveJfSupport) */
    static Expr creation(Call c) {
        List<Expr> a = c.a();
        switch (c.n()) {
            case "CreateNUnitsAtLoc":
                // копия чужого юнита (тип одного, хозяин другого) — через jfCopyType: юниты некроманта The War не копируются
                if (isCall(a.get(1), "GetUnitTypeId") && isCall(a.get(2), "GetOwningPlayer")
                    && !printExpr(((Call) a.get(1)).a().get(0), 0).equals(printExpr(((Call) a.get(2)).a().get(0), 0))) {
                    a = new ArrayList<>(a);
                    a.set(1, new Call("jfCopyType", List.of(((Call) a.get(1)).a().get(0))));
                }
                if (!(a.get(0) instanceof Lit l) || !l.s().equals("1")) return new Call("jfCreateUnits", a); // несколько — последний
                return call("createUnit", a.get(2), a.get(1), a.get(3), mcall(a.get(4), "fromDeg"));
            case "AddSpecialEffectLocBJ": return call("addEffect", a.get(1), a.get(0));
            case "AddSpecialEffectTargetUnitBJ": return call("addEffect", a.get(2), a.get(1), a.get(0));
            case "CreateTextTagUnitBJ": case "CreateTextTagLocBJ": return new Call("jfTextTag", a);
        }
        throw new IllegalStateException(c.n());
    }

    static void lastCreated() {
        for (Func f : funcs.values()) for (String kind : LAST.keySet()) {
            boolean any = false;
            for (List<Stmt> l : lists(f.body)) for (Stmt s : l) if (kind.equals(createdKind(s))) any = true;
            boolean reads = mentions(f.body, e -> readsLast(e, kind));
            if (!any && !reads) continue;
            if (localizeLast(f.body, kind, true)) {
                localizeLast(f.body, kind, false);
                continue;
            }
            // глобальная
            String g = LAST.get(kind)[1];
            usedLastGlobals.add(kind);
            eachList(f.body, l -> l.replaceAll(s -> kind.equals(createdKind(s)) ? new Assign(new Name(g), creation((Call) ((ExprS) s).e)) : s));
            mapStmts(f.body, e -> readsLast(e, kind) ? new Name(g) : e, true);
        }
    }

    static List<List<Stmt>> lists(List<Stmt> body) { List<List<Stmt>> r = new ArrayList<>(); eachList(body, r::add); return r; }

    /** dry: только проверить, что каждое чтение — после создания в том же списке (или вложенном блоке, не замыкании) */
    static boolean localizeLast(List<Stmt> body, String kind, boolean dry) {
        return localizeIn(body, kind, null, dry);
    }

    static boolean localizeIn(List<Stmt> list, String kind, String current, boolean dry) {
        for (int i = 0; i < list.size(); i++) {
            Stmt s = list.get(i);
            if (kind.equals(createdKind(s))) {
                Call c = (Call) ((ExprS) s).e;
                // аргументы создания могут читать прежнее значение
                if (mentions(new ExprS(new Call("x", c.a())), e -> readsLast(e, kind)) && current == null) return false;
                String v = "jfCreatedVar" + (++lambdaVars);
                if (!dry) {
                    String cur = current;
                    Expr value = creation(c);
                    if (cur != null) value = map(value, e -> readsLast(e, kind) ? new Name(cur) : e);
                    list.set(i, new Local(LAST.get(kind)[0], v, value));
                }
                current = v;
                continue;
            }
            // вложенные блоки: чтения в них — текущей; создания в них — свои; замыкания — нельзя
            if (s instanceof Lambda lam) {
                if (mentions(lam.body, e -> readsLast(e, kind)) || hasCreation(lam.body, kind)) {
                    if (!localizeIn(lam.body, kind, null, dry)) return false;
                }
                continue;
            }
            if (s instanceof If || s instanceof Loop || s instanceof For || s instanceof ForIn) {
                boolean creates = hasCreationDirect(s, kind);
                boolean loop = !(s instanceof If);
                for (List<Stmt> b : blocks(s)) if (!localizeIn(b, kind, loop && creates ? null : current, dry)) return false;
                // заголовки (условия) — текущей
                if (!replaceHead(s, kind, current, dry)) return false;
                if (creates && readsAfter(list, i + 1, kind)) return false;
                continue;
            }
            if (mentions(s, e -> readsLast(e, kind))) {
                if (current == null) return false;
                if (!dry) { String cur = current; mapStmts(List.of(s), e -> readsLast(e, kind) ? new Name(cur) : e, false); }
            }
        }
        return true;
    }

    /** Дальше в списке есть чтение, перед которым нет своего создания */
    static boolean readsAfter(List<Stmt> list, int from, String kind) {
        return !localizeIn(new ArrayList<>(list.subList(from, list.size())), kind, null, true);
    }

    static boolean hasCreation(List<Stmt> body, String kind) {
        for (List<Stmt> l : lists(body)) for (Stmt s : l) if (kind.equals(createdKind(s))) return true;
        return false;
    }

    static boolean hasCreationDirect(Stmt s, String kind) {
        for (List<Stmt> b : blocks(s)) if (hasCreation(b, kind)) return true;
        return false;
    }

    static List<List<Stmt>> blocks(Stmt s) {
        List<List<Stmt>> r = new ArrayList<>();
        if (s instanceof If x) { r.addAll(x.blocks); if (x.elseB != null) r.add(x.elseB); }
        else if (s instanceof Loop x) r.add(x.body);
        else if (s instanceof For x) r.add(x.body);
        else if (s instanceof ForIn x) r.add(x.body);
        return r;
    }

    static boolean replaceHead(Stmt s, String kind, String current, boolean dry) {
        List<Expr> heads = new ArrayList<>();
        if (s instanceof If x) heads.addAll(x.conds);
        if (s instanceof For x) { heads.add(x.from); heads.add(x.to); }
        if (s instanceof ForIn x) heads.add(x.coll);
        boolean reads = false;
        for (Expr h : heads) if (mentions(List.of(new ExprS(h)), e -> readsLast(e, kind))) reads = true;
        if (!reads) return true;
        if (current == null) return false;
        if (!dry) {
            UnaryOperator<Expr> f = e -> readsLast(e, kind) ? new Name(current) : e;
            if (s instanceof If x) x.conds.replaceAll(c -> map(c, f));
            if (s instanceof For x) { x.from = map(x.from, f); x.to = map(x.to, f); }
            if (s instanceof ForIn x) x.coll = map(x.coll, f);
        }
        return true;
    }

    // ---------------- выражения: BJ и натив -> stdlib ----------------

    static final Map<String, String> UNIT_STATE = Map.of("UNIT_STATE_LIFE", "getHP", "UNIT_STATE_MANA", "getMana",
        "UNIT_STATE_MAX_LIFE", "getMaxHP", "UNIT_STATE_MAX_MANA", "getMaxMana");

    static Expr rewrite(Expr e) {
        if (!(e instanceof Call c)) {
            return e;
        }
        List<Expr> a = c.a();
        switch (c.n()) {
            case "GetKillingUnitBJ": return call("GetKillingUnit");
            case "GetAttackedUnitBJ": return call("GetTriggerUnit");
            case "GetUnitTypeId": return mcall(a.get(0), "getTypeId");
            case "GetOwningPlayer": return mcall(a.get(0), "getOwner");
            case "GetUnitAbilityLevelSwapped": return mcall(a.get(1), "getAbilityLevel", a.get(0));
            case "GetUnitAbilityLevel": return mcall(a.get(0), "getAbilityLevel", a.get(1));
            case "SetUnitAbilityLevelSwapped": return mcall(a.get(1), "setAbilityLevel", a.get(0), a.get(2));
            case "SetUnitAbilityLevel": return mcall(a.get(0), "setAbilityLevel", a.get(1), a.get(2));
            case "IncUnitAbilityLevelSwapped": return mcall(a.get(1), "incAbilityLevel", a.get(0));
            case "DecUnitAbilityLevelSwapped": return mcall(a.get(1), "decAbilityLevel", a.get(0));
            case "UnitAddAbilityBJ": return mcall(a.get(1), "addAbility", a.get(0));
            case "UnitAddAbility": return mcall(a.get(0), "addAbility", a.get(1));
            case "UnitRemoveAbilityBJ": return mcall(a.get(1), "removeAbility", a.get(0));
            case "UnitRemoveAbility": return mcall(a.get(0), "removeAbility", a.get(1));
            case "UnitHasBuffBJ": return mcall(a.get(0), "hasAbility", a.get(1));
            case "GetUnitStateSwap": return unitState(a.get(1), a.get(0));
            case "GetUnitState": return unitState(a.get(0), a.get(1));
            case "SetUnitLifeBJ": return mcall(a.get(0), "setHP", a.get(1));
            case "SetUnitManaBJ": return mcall(a.get(0), "setMana", a.get(1));
            case "SetUnitManaPercentBJ": return mcall(a.get(0), "setMana", new Bin("*", new Bin("*", mcall(a.get(0), "getMaxMana"), a.get(1)), new Lit("0.01")));
            case "IsUnitType": return mcall(a.get(0), "isType", a.get(1));
            case "IsUnitDeadBJ": return new Un("not", mcall(a.get(0), "isAlive"));
            case "IsUnitAliveBJ": return mcall(a.get(0), "isAlive");
            case "IsUnitIllusionBJ": return mcall(a.get(0), "isIllusion");
            case "IsUnitEnemy": return mcall(a.get(0), "isEnemyOf", a.get(1));
            case "IsUnitAlly": return mcall(a.get(0), "isAllyOf", a.get(1));
            case "IsUnitOwnedByPlayer": return new Bin("==", mcall(a.get(0), "getOwner"), a.get(1));
            case "IssueImmediateOrder": return mcall(a.get(0), "issueImmediateOrder", a.get(1));
            case "IssueTargetOrder": return mcall(a.get(0), "issueTargetOrder", a.get(1), a.get(2));
            case "IssuePointOrderLoc": return mcall(a.get(0), "issuePointOrder", a.get(1), a.get(2));
            case "UnitDamageTargetBJ": return mcall(a.get(0), "damageTarget", a.get(1), a.get(2), new Lit("true"), new Lit("false"), a.get(3), a.get(4), new Name("WEAPON_TYPE_WHOKNOWS"));
            case "UnitApplyTimedLifeBJ": return mcall(a.get(2), "setTimedLife", a.get(0), a.get(1));
            case "UnitApplyTimedLife": return mcall(a.get(0), "setTimedLife", a.get(2), a.get(1));
            case "RemoveUnit": return mcall(a.get(0), "remove");
            case "SetUnitAnimation": return mcall(a.get(0), "setAnimation", a.get(1));
            case "SetUnitVertexColor": return mcall(a.get(0), "setVertexColor", a.get(1), a.get(2), a.get(3), a.get(4));
            case "SetUnitVertexColorBJ": return mcall(a.get(0), "setVertexColor", pct255(a.get(1)), pct255(a.get(2)), pct255(a.get(3)), new Bin("-", new Lit("255"), pct255(a.get(4))));
            case "SetUnitPathing": return mcall(a.get(0), "setPathing", a.get(1));
            case "SetUnitOwner": return mcall(a.get(0), "setOwner", a.get(1), a.get(2));
            case "ShowUnitHide": return mcall(a.get(0), "hide");
            case "UnitAddItemByIdSwapped": return mcall(a.get(1), "addItemById", a.get(0));
            case "SetUnitPositionLoc": return mcall(a.get(0), "setPos", a.get(1));
            case "GetHandleId": return mcall(a.get(0), "getHandleId");
            case "DestroyEffect": return isCall(a.get(0), "addEffect") ? new Call("flashEffect", ((Call) a.get(0)).a()) : mcall(a.get(0), "destr");
            case "DestroyTimer": case "DestroyGroup": case "DestroyForce": case "DestroyBoolExpr": return mcall(a.get(0), "destr");
            case "CountUnitsInGroup": return mcall(a.get(0), "size");
            case "GroupAddUnit": return mcall(a.get(0), "add", a.get(1));
            case "GroupPickRandomUnit": return mcall(a.get(0), "getRandom");
            case "GroupEnumUnitsInRangeOfLoc": return mcall(a.get(0), "enumUnitsInRange", a.get(1), a.get(2), a.get(3));
            case "ForGroupBJ": case "ForGroup": return mcall(a.get(0), "forGroup", a.get(1));
            case "I2S": return mcall(a.get(0), "toString");
            case "R2I": return mcall(a.get(0), "toInt");
            case "I2R": return mcall(a.get(0), "toReal");
            case "GetConvertedPlayerId": return new Bin("+", mcall(a.get(0), "getId"), new Lit("1"));
            case "AdjustPlayerStateBJ":
                if (isName(a.get(2), "PLAYER_STATE_RESOURCE_GOLD")) return mcall(a.get(1), "addGold", a.get(0));
                if (isName(a.get(2), "PLAYER_STATE_RESOURCE_LUMBER")) return mcall(a.get(1), "addLumber", a.get(0));
                return e;
            case "TriggerExecute": return mcall(a.get(0), "execute");
            case "EnableTrigger": return mcall(a.get(0), "enable");
            case "DisableTrigger": return mcall(a.get(0), "disable");
            // location -> vec2
            case "GetUnitLoc": return mcall(a.get(0), "getPos");
            case "PolarProjectionBJ": return mcall(a.get(0), "polarOffset", mcall(a.get(2), "fromDeg"), a.get(1));
            case "GetSpellTargetLoc": return mcall(new Name("EventData"), "getSpellTargetPos");
            case "GetRectCenter": return mcall(a.get(0), "getCenter");
            case "GetLocationX": return new Field(a.get(0), "x");
            case "GetLocationY": return new Field(a.get(0), "y");
            case "AddSpecialEffectLoc": return call("addEffect", a.get(0), a.get(1));
            case "AddSpecialEffectTarget": return call("addEffect", a.get(0), a.get(1), a.get(2));
            case "CreateUnitAtLoc": return call("createUnit", a.get(0), a.get(1), a.get(2), mcall(a.get(3), "fromDeg"));
            // текст
            case "SetTextTagPermanent": return mcall(a.get(0), "setPermanent", a.get(1));
            case "SetTextTagLifespan": return mcall(a.get(0), "setLifespan", a.get(1));
            case "SetTextTagFadepoint": return mcall(a.get(0), "setFadepoint", a.get(1));
            case "SetTextTagVisibility": return mcall(a.get(0), "setVisibility", a.get(1));
            case "SetTextTagVelocityBJ": return mcall(a.get(0), "jfSetVelocity", a.get(1), a.get(2));
            case "ShowTextTagForceBJ": return mcall(a.get(1), "jfShowForce", a.get(0), a.get(2));
        }
        return e;
    }

    static Expr pct255(Expr p) { return mcall(new Bin("*", p, new Lit("2.55")), "toInt"); }

    static Expr unitState(Expr u, Expr s) {
        if (s instanceof Name n && UNIT_STATE.containsKey(n.n())) return mcall(u, UNIT_STATE.get(n.n()));
        return call("GetUnitState", u, s);
    }

    /** x == true -> x, x != true / x == false -> not x, "a" + n.toString() -> "a" + n, not (a == b) -> a != b */
    static Expr simplify(Expr e) {
        if (e instanceof Bin b && (b.op().equals("==") || b.op().equals("!="))) {
            boolean neg = b.op().equals("!=");
            if (isName(b.r(), "true")) return neg ? negate(b.l()) : b.l();
            if (isName(b.r(), "false")) return neg ? b.l() : negate(b.l());
        }
        if (e instanceof Bin b && b.op().equals("+")) {
            if (b.l() instanceof Lit l && l.s().startsWith("\"") && b.r() instanceof MCall m && m.n().equals("toString")) return new Bin("+", b.l(), m.r());
            if (b.r() instanceof Lit l && l.s().startsWith("\"") && b.l() instanceof MCall m && m.n().equals("toString")) return new Bin("+", m.r(), b.r());
        }
        if (e instanceof Un u && u.op().equals("not")) return negate(u.e());
        // константы: области Just Fight (их нет) — false, 0 == 0…
        if (e instanceof Bin b && b.op().equals("==") && b.l() instanceof Lit l && b.r() instanceof Lit r && l.s().equals(r.s())) return new Name("true");
        if (e instanceof Bin b && (b.op().equals("or") || b.op().equals("and"))) {
            boolean or = b.op().equals("or");
            String unit = or ? "false" : "true", zero = or ? "true" : "false";
            if (isName(b.l(), unit)) return b.r();
            if (isName(b.r(), unit)) return b.l();
            if (isName(b.l(), zero) || isName(b.r(), zero) && !hasCall(b.l())) return new Name(zero);
        }
        return e;
    }

    /** Области Just Fight (глобальные rect, которые не задаются) — в The War их нет: юнит в них не бывает */
    static void missingRects() {
        Set<String> assigned = new HashSet<>();
        for (Func f : funcs.values()) assigned.addAll(assignedIn(f.body));
        Set<String> rects = new HashSet<>();
        for (Global g : globals.values())
            if (g.type.equals("rect") && !g.array && !assigned.contains(g.name) && (g.init == null || isName(g.init, "null"))) rects.add(g.name);
        for (Func f : funcs.values())
            mapStmts(f.body, e -> isCall(e, "RectContainsUnit") && ((Call) e).a().get(0) instanceof Name n && rects.contains(n.n()) ? new Name("false") : e, true);
    }

    static final Map<String, String> NEG = Map.of("==", "!=", "!=", "==", "<", ">=", ">=", "<", ">", "<=", "<=", ">");

    static Expr negate(Expr e) {
        if (e instanceof Un u && u.op().equals("not")) return u.e();
        if (isName(e, "true")) return new Name("false");
        if (isName(e, "false")) return new Name("true");
        // де Морган: условие в начале обработчика — «если не (a и b) — выход» -> «если не a или не b — выход»
        if (e instanceof Bin b && b.op().equals("and")) return new Bin("or", negate(b.l()), negate(b.r()));
        if (e instanceof Bin b && b.op().equals("or")) return new Bin("and", negate(b.l()), negate(b.r()));
        if (e instanceof Bin b && NEG.containsKey(b.op()) && !(b.l() instanceof Lit l0 && l0.s().contains("."))) {
            // вещественные сравнения отрицаем как есть (NaN нет, но так понятнее)
            return new Bin(NEG.get(b.op()), b.l(), b.r());
        }
        return new Un("not", e);
    }

    /** Блоки: пустые else убрать, else { if … } -> else if, if без тела -> if not */
    static void tidy(List<Stmt> list) {
        for (int i = 0; i < list.size(); i++) {
            Stmt s = list.get(i);
            for (List<Stmt> b : blocks(s)) tidy(b);
            if (s instanceof Lambda l) tidy(l.body);
            if (!(s instanceof If x)) continue;
            // if false / if true
            for (int c = 0; c < x.conds.size(); c++) {
                if (isName(x.conds.get(c), "false")) { x.conds.remove(c); x.blocks.remove(c); c--; }
                else if (isName(x.conds.get(c), "true")) {
                    x.elseB = x.blocks.get(c);
                    while (x.conds.size() > c) { x.conds.remove(c); x.blocks.remove(c); }
                    break;
                }
            }
            if (x.conds.isEmpty()) {
                list.remove(i);
                if (x.elseB != null) list.addAll(i, x.elseB);
                i--;
                continue;
            }
            if (x.elseB != null && (x.elseB.isEmpty() || x.elseB.size() == 1 && x.elseB.get(0) instanceof Raw r && r.s.equals("skip"))) x.elseB = null;
            if (x.elseB != null && x.elseB.size() == 1 && x.elseB.get(0) instanceof If inner) {
                x.conds.addAll(inner.conds);
                x.blocks.addAll(inner.blocks);
                x.elseB = inner.elseB;
            }
            if (x.conds.size() == 1 && x.blocks.get(0).isEmpty() && x.elseB != null) {
                x.conds.set(0, negate(x.conds.get(0)));
                x.blocks.set(0, x.elseB);
                x.elseB = null;
            }
            if (x.conds.size() == 1 && x.blocks.get(0).isEmpty() && x.elseB == null && !hasCall(x.conds.get(0))) { list.remove(i--); }
        }
    }

    /** Локальная, которую не читают: только её значение (вызов) */
    static void dropUnread(List<Stmt> body) {
        // let a = b (обе локальные и не меняются) — дальше просто b
        Set<String> assigned = assignedIn(body);
        Set<String> locals = new HashSet<>();
        eachList(body, list -> { for (Stmt s : list) if (s instanceof Local lo) locals.add(lo.name); });
        eachList(body, list -> {
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof Local lo) || !(lo.init instanceof Name src) || !locals.contains(src.n())) continue;
                if (assigned.contains(lo.name) || assigned.contains(src.n())) continue;
                list.remove(i--);
                mapStmts(body, e -> isName(e, lo.name) ? src : e, true);
            }
        });
        eachList(body, list -> list.replaceAll(s -> {
            if (s instanceof Local lo && lo.init == null && !mentions(body, e -> isName(e, lo.name))) return new Raw("skip");
            if (!(s instanceof Local lo) || lo.init == null) return s;
            boolean[] read = {false};
            eachExpr(body, e -> { if (isName(e, lo.name) || e instanceof Index x && x.n().equals(lo.name)) read[0] = true; });
            if (read[0]) return s;
            return hasCall(lo.init) ? new ExprS(lo.init) : new Raw("skip");
        }));
        eachList(body, list -> { if (list.size() > 1) list.removeIf(s -> s instanceof Raw r && r.s.equals("skip")); });
    }

    /** let fx = addEffect(…); fx.destr() -> flashEffect(…) */
    static void flashEffects(List<Stmt> body) {
        eachList(body, list -> {
            for (int i = 0; i + 1 < list.size(); i++) {
                if (!(list.get(i) instanceof Local lo) || !isCall(lo.init, "addEffect")) continue;
                if (!(list.get(i + 1) instanceof ExprS es) || !(es.e instanceof MCall m) || !m.n().equals("destr") || !isName(m.r(), lo.name)) continue;
                List<Stmt> rest = new ArrayList<>(list.subList(i + 2, list.size()));
                if (mentions(rest, e -> isName(e, lo.name))) continue;
                list.set(i, new ExprS(new Call("flashEffect", ((Call) lo.init).a())));
                list.remove(i + 1);
            }
        });
    }

    static boolean hasCall(Expr e) {
        boolean[] r = {false};
        map(e, x -> { if (x instanceof Call || x instanceof MCall) r[0] = true; return x; });
        return r[0];
    }

    // ---------------- имена ----------------

    static Map<String, String> rename = new HashMap<>();

    /** Объекты копий Just Fight (UnitsPveJf): имя юнита, юнит со способностью — для имён и описаний обработчиков */
    static final Map<String, String> unitNames = new HashMap<>();
    static final Map<String, String> abilityOwner = new HashMap<>();

    public static void readObjects(Path unitsPveJf) throws Exception {
        unitNames.clear(); abilityOwner.clear();
        if (!Files.exists(unitsPveJf)) return;
        String unit = null;
        Pattern def = Pattern.compile("new (?:Unit|Hero|Building)Definition\\('([A-Za-z0-9]{4})'");
        Pattern name = Pattern.compile("\\.\\.setName\\(\"(.*)\"\\)");
        Pattern abil = Pattern.compile("\\.\\.set(?:Normal|Hero)Abilities\\(\"([^\"]*)\"\\)");
        for (String l : Files.readAllLines(unitsPveJf, StandardCharsets.UTF_8)) {
            if (l.contains("new ") && l.contains("Definition(")) {
                Matcher d = def.matcher(l);
                unit = d.find() ? d.group(1) : null;
                continue;
            }
            if (unit == null) continue;
            Matcher n = name.matcher(l);
            if (n.find()) unitNames.putIfAbsent(unit, n.group(1));
            Matcher a = abil.matcher(l);
            if (a.find()) for (String id : a.group(1).split(",")) if (!id.isBlank()) abilityOwner.putIfAbsent(id.trim(), unit);
        }
    }

    /** «Demon Hunter |cffff0000(Xixi)|r» -> «Demon Hunter» */
    static String plainName(String id) {
        String n = unitNames.get(id);
        if (n == null && abilityOwner.containsKey(id)) n = unitNames.get(abilityOwner.get(id));
        if (n == null) return null;
        n = n.replaceAll("\\|c[0-9A-Fa-f]{8}", "").replaceAll("\\|[rn]", "").replaceAll("\\([^)]*\\)", "").replaceAll("[^A-Za-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
        return n.isEmpty() || !Character.isLetter(n.charAt(0)) ? null : n;
    }

    static String camel(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            sb.append(sb.length() == 0 ? w.substring(0, 1).toLowerCase() : w.substring(0, 1).toUpperCase()).append(w.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    static String idOf(Expr e) { return e instanceof Lit l && l.s().startsWith("'") ? l.s().substring(1, 5) : null; }

    /** Регистрация обработчика в init: заголовок с «->» и вызов (или одна строка) */
    static class Reg extends Stmt { String head, call; List<String> more = new ArrayList<>(); Reg(String h, String c) { head = h; call = c; } }


    /** Юниты события -> локальные обработчика */
    static Map<String, String[]> eventUnits(String ev) {
        Map<String, String[]> m = new LinkedHashMap<>();
        switch (ev) {
            case "JF_ATTACKED":
                m.put("GetAttacker", new String[]{"a", "GetAttacker"});
                m.put("GetTriggerUnit", new String[]{"target", "GetTriggerUnit"});
                m.put("GetAttackedUnitBJ", new String[]{"target", "GetTriggerUnit"});
                break;
            case "JF_DEATH":
                m.put("GetTriggerUnit", new String[]{"dying", "GetDyingUnit"});
                m.put("GetDyingUnit", new String[]{"dying", "GetDyingUnit"});
                m.put("GetKillingUnit", new String[]{"killer", "GetKillingUnit"});
                m.put("GetKillingUnitBJ", new String[]{"killer", "GetKillingUnit"});
                break;
            case "JF_DAMAGED":
                m.put("GetEventDamageSource", new String[]{"source", "GetEventDamageSource"});
                m.put("GetTriggerUnit", new String[]{"target", "GetTriggerUnit"});
                break;
            case "JF_ENTER":
                m.put("GetTriggerUnit", new String[]{"entering", "GetTriggerUnit"});
                m.put("GetEnteringUnit", new String[]{"entering", "GetTriggerUnit"});
                break;
            default:
                m.put("GetTriggerUnit", new String[]{"caster", "GetTriggerUnit"});
                m.put("GetSpellAbilityUnit", new String[]{"caster", "GetTriggerUnit"});
        }
        return m;
    }

    static final Set<String> FIXED = Set.of("a", "source", "target", "dying", "killer", "entering", "caster");

    /** Атом условия: «тип юнита роли == ID» -> "T:роль:ID", «ID заклинания == ID» -> "S:ID" */
    static String atomKey(Expr e) {
        if (!(e instanceof Bin b) || !b.op().equals("==") || idOf(b.r()) == null) return null;
        if (isCall(b.l(), "GetSpellAbilityId")) return "S:" + idOf(b.r());
        if (isCall(b.l(), "GetUnitTypeId") && ((Call) b.l()).a().get(0) instanceof Call r && r.a().isEmpty()) {
            String n = r.n();
            int role = n.equals("GetAttacker") || n.equals("GetEventDamageSource") ? 1 : n.startsWith("GetKillingUnit") ? 2 : 0;
            return "T:" + role + ":" + idOf(b.r());
        }
        return null;
    }

    /** Листья цепочки «or» — атомы с ключами; иначе null */
    static Set<String> orKeys(Expr e) {
        if (e instanceof Bin b && b.op().equals("or")) {
            Set<String> l = orKeys(b.l()), r = orKeys(b.r());
            if (l == null || r == null) return null;
            l.addAll(r);
            return l;
        }
        String k = atomKey(e);
        return k == null ? null : new HashSet<>(Set.of(k));
    }

    /** Условие при том, что обработчик вызван только для ключей registered: «или» по ним — истина */
    static Expr assume(Expr e, Set<String> registered) {
        if (registered.isEmpty()) return e;
        Set<String> k = orKeys(e);
        if (k != null && k.containsAll(registered)) return new Name("true");
        if (e instanceof Bin b && b.op().equals("and")) {
            Expr l = assume(b.l(), registered), r = assume(b.r(), registered);
            if (isName(l, "true")) return r;
            if (isName(r, "true")) return l;
            return new Bin("and", l, r);
        }
        return e;
    }

    static List<String> chunks(List<String> ids, String fn) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 4) r.add(fn + "(" + String.join(", ", ids.subList(i, Math.min(ids.size(), i + 4))) + ")");
        return r;
    }

    /**
     * Триггеры Just Fight -> обработчики, как остальные триггеры карты (HeroSpells, autocast): функция с условием в
     * начале (return), юниты события — локальные (a, target, dying, killer, caster, entering); в init — подписка
     * UnitEvents (по типу юнита: onAttackBy/onAttacked/onDeathOf/onKillBy; по способности/баффу: onAnyAttack/onAnyDeath),
     * EventListener.onCast (по заклинанию), onEnter (появление)
     */
    static void triggers(Map<String, String> names) {
        rename.putAll(names);
        Set<String> taken = new HashSet<>(names.values());
        taken.addAll(funcs.keySet());
        Map<String, String> owner = new HashMap<>(); // функция -> обработчик (или "" — у нескольких)
        Map<String, String> actionOf = new HashMap<>(); // триггер -> его функция (TriggerExecute)
        Set<String> conditionFuncs = new HashSet<>();
        List<Stmt> newInit = new ArrayList<>();
        for (Stmt s : init) {
            String fn = ((Call) ((ExprS) s).e).n();
            Func f = funcs.get(fn);
            String trig = null, cond = null, act = null;
            List<Expr[]> regs = new ArrayList<>();
            boolean simple = f != null;
            if (f != null) for (Stmt x : f.body) {
                if (x instanceof Assign a && a.target instanceof Name n && isCall(a.value, "CreateTrigger")) trig = n.n();
                else if (isCallS(x, "jfRegister")) regs.add(call(x).a().toArray(new Expr[0]));
                else if (isCallS(x, "TriggerAddCondition") && call(x).a().get(1) instanceof Call cc && cc.a().get(0) instanceof FRef r) cond = r.n();
                else if (isCallS(x, "TriggerAddAction") && call(x).a().get(1) instanceof FRef r) act = r.n();
                else if (isCallS(x, "jfLllLlI")) { } // появление в области Just Fight — таких областей в The War нет
                else simple = false;
            }
            if (!simple || trig == null || act == null || !funcs.containsKey(act)) throw new IllegalStateException("триггер не по образцу: " + fn);
            funcs.remove(fn);
            globals.remove(trig);
            actionOf.put(trig, act);
            Func h = funcs.get(act);
            String ev = regs.isEmpty() ? null : ((Name) regs.get(0)[1]).n();
            // подписка
            List<String> heads = new ArrayList<>();
            String suffix = "", what = "";
            boolean generic = false, castParam = false;
            Set<String> registered = new HashSet<>();
            if (ev != null) {
                Map<String, List<String>> byKind = new LinkedHashMap<>();
                for (Expr[] r : regs) {
                    if (!((Name) r[1]).n().equals(ev)) throw new IllegalStateException("разные события: " + fn);
                    byKind.computeIfAbsent(((Name) r[2]).n() + ":" + ((Lit) r[3]).s(), k -> new ArrayList<>()).add(printExpr(r[4], 0));
                }
                String only = byKind.size() == 1 ? byKind.keySet().iterator().next() : "";
                List<String> ids = byKind.size() == 1 ? byKind.values().iterator().next() : List.of();
                switch (ev) {
                    case "JF_ATTACKED":
                        if (only.equals("JF_TYPE:1")) { heads.addAll(chunks(ids, "onAttackBy")); suffix = "Attack"; what = "атакует"; }
                        else if (only.equals("JF_TYPE:0")) { heads.addAll(chunks(ids, "onAttacked")); suffix = "Attacked"; what = "его атакуют"; }
                        else { heads.add("onAnyAttack()"); generic = true; suffix = "Attack"; what = "атака"; }
                        break;
                    case "JF_DEATH":
                        if (only.equals("JF_TYPE:0")) { heads.addAll(chunks(ids, "onDeathOf")); suffix = "Death"; what = "смерть"; }
                        else if (only.equals("JF_TYPE:2")) { heads.addAll(chunks(ids, "onKillBy")); suffix = "Kill"; what = "убивает"; }
                        else { heads.add("onAnyDeath()"); generic = true; suffix = "Death"; what = "смерть"; }
                        break;
                    case "JF_DAMAGED":
                        if (only.equals("JF_TYPE:1")) { heads.addAll(chunks(ids, "onDamageBy")); suffix = "Damage"; what = "наносит урон"; }
                        else if (only.equals("JF_TYPE:0")) { heads.addAll(chunks(ids, "onDamaged")); suffix = "Damaged"; what = "получает урон"; }
                        else { heads.add("onAnyDamage()"); generic = true; suffix = "Damage"; what = "урон"; }
                        break;
                    case "JF_SPELL_EFFECT":
                        if (only.startsWith("JF_SPELL:")) { for (String id : ids) heads.add("EventListener.onCast(" + id + ") (caster)"); castParam = true; }
                        else { heads.add("EventListener.add(EVENT_PLAYER_UNIT_SPELL_EFFECT)"); generic = true; }
                        suffix = "Cast"; what = "применяет способность";
                        break;
                    case "JF_SPELL_FINISH": heads.add("EventListener.add(EVENT_PLAYER_UNIT_SPELL_FINISH)"); generic = true; suffix = "CastEnd"; what = "конец применения"; break;
                    case "JF_SPELL_CAST": heads.add("EventListener.add(EVENT_PLAYER_UNIT_SPELL_CAST)"); generic = true; suffix = "CastStart"; what = "начало применения"; break;
                    case "JF_ENTER": heads.add("onEnter"); suffix = "Enter"; what = "появляется"; break;
                }
                for (Expr[] r : regs) {
                    String kind = ((Name) r[2]).n();
                    if (generic && !ev.equals("JF_ENTER")) continue;
                    if (kind.equals("JF_TYPE")) registered.add("T:" + ((Lit) r[3]).s() + ":" + idOf(r[4]));
                    if (kind.equals("JF_SPELL")) registered.add("S:" + idOf(r[4]));
                }
                // onCast: по каждому ID отдельно — условие «или» по ним всем всё равно истинно
                if (ev.equals("JF_ENTER")) registered.clear();
            }
            // имя и описание: по юниту (или юниту со способностью) первого ключа
            List<String> objs = new ArrayList<>();
            for (Expr[] r : regs) { String id = idOf(r[4]); if (id != null && !objs.contains(id)) objs.add(id); }
            String base = names.containsKey(trig) ? names.get(trig).replaceFirst("Trigger$", "") : null;
            if (base == null) {
                String pn = objs.isEmpty() ? null : plainName(objs.get(0));
                base = pn != null ? camel(pn) + suffix : "jf" + suffix + (objs.isEmpty() ? "" : objs.get(0));
            }
            String b = base;
            for (int k = 2; taken.contains(b); k++) b = base + k;
            base = b;
            taken.add(base);
            rename.put(act, base);
            StringBuilder doc = new StringBuilder();
            for (String id : objs) {
                String pn = plainName(id);
                boolean ability = !unitNames.containsKey(id);
                doc.append(doc.length() > 0 ? ", " : "").append(pn != null ? pn + (ability ? " (способность " : " (") + "'" + id + "')" : "'" + id + "'");
            }
            h.doc = ev == null ? null : "Триггер Just Fight: " + (doc.length() > 0 ? doc : "любой юнит") + (what.isEmpty() ? "" : " — " + what);
            // условие — в начало (return), то, что уже дала подписка, — нет
            Expr guard = null;
            if (cond != null) {
                Func c = funcs.get(cond);
                List<Stmt> st = c.body;
                if (st.size() == 1 && st.get(0) instanceof Return ret && ret.value != null) {
                    guard = assume(ret.value, registered);
                    funcs.remove(cond);
                } else {
                    guard = call(cond);
                    conditionFuncs.add(cond);
                }
                if (isName(guard, "true")) guard = null;
            }
            List<Stmt> head = new ArrayList<>();
            // повторный вход: Just Fight выключает свой триггер на время действия
            boolean selfOff = false;
            for (List<Stmt> l : lists(h.body)) for (Stmt x : l)
                if (isCallS(x, "DisableTrigger") && isCall(call(x).a().get(0), "GetTriggeringTrigger")) selfOff = true;
            if (selfOff) {
                String flag = base + "Running";
                Global g = new Global(); g.type = "boolean"; g.name = flag; g.init = new Name("false");
                globals.put(flag, g);
                head.add(ifReturn(new Name(flag)));
                eachList(h.body, l -> l.replaceAll(x -> {
                    if (isCallS(x, "DisableTrigger") && isCall(call(x).a().get(0), "GetTriggeringTrigger")) return new Assign(new Name(flag), new Name("true"));
                    if (isCallS(x, "EnableTrigger") && isCall(call(x).a().get(0), "GetTriggeringTrigger")) return new Assign(new Name(flag), new Name("false"));
                    return x;
                }));
            }
            if (mentions(h.body, e -> isCall(e, "GetTriggeringTrigger"))) throw new IllegalStateException("GetTriggeringTrigger в " + act);
            boolean grouped = heads.size() == 1 && heads.get(0).startsWith("onAny");
            if (guard != null) head.add(ifReturn(negate(guard)));
            h.body.addAll(0, head);
            for (String g : closureOf(act)) owner.merge(g, base, (x, y) -> x.equals(y) ? x : "");
            Pending p = new Pending();
            p.h = h; p.ev = ev; p.castParam = castParam; p.base = base;
            for (String hd : heads) {
                if (hd.equals("onEnter")) { newInit.add(new Raw("onUnitEnter(function " + base + ")")); continue; }
                if (grouped) { anyGroups.computeIfAbsent(hd, x -> new ArrayList<>()).add(base + "()"); continue; }
                Reg r = new Reg(hd + " ->", base + (castParam ? "(caster)" : "()"));
                p.regs.add(r);
                newInit.add(r);
            }
            pending.add(p);
            handlers.add(act);
        }
        // выключение чужого триггера (Just Fight: на время своего урона) — флаг того обработчика, как у своего
        for (Func f : new ArrayList<>(funcs.values())) eachList(f.body, l -> l.replaceAll(x -> {
            boolean off = isCallS(x, "DisableTrigger"), on = isCallS(x, "EnableTrigger");
            if (!off && !on || !(call(x).a().get(0) instanceof Name t) || !actionOf.containsKey(t.n())) return x;
            String act2 = actionOf.get(t.n());
            String flag = rename.get(act2) + "Running";
            if (!globals.containsKey(flag)) {
                Global g = new Global(); g.type = "boolean"; g.name = flag; g.init = new Name("false");
                globals.put(flag, g);
                funcs.get(act2).body.add(0, ifReturn(new Name(flag)));
            }
            return new Assign(new Name(flag), new Name(off ? "true" : "false"));
        }));
        // без привязки к типу юнита (по способности/баффу) — одной подпиской на все
        for (Map.Entry<String, List<String>> g : anyGroups.entrySet()) {
            List<String> calls = g.getValue();
            Reg r = new Reg(g.getKey() + " ->", calls.get(0));
            r.more.addAll(calls.subList(1, calls.size()));
            newInit.add(r);
        }
        init.clear();
        init.addAll(newInit);
        // запуск триггера (TriggerExecute) — вызов его функции
        for (Func f : funcs.values())
            mapStmts(f.body, e -> (isCall(e, "TriggerExecute") || isCall(e, "ConditionalTriggerExecute")) && ((Call) e).a().get(0) instanceof Name n && actionOf.containsKey(n.n())
                ? call(actionOf.get(n.n())) : e, true);
        // функции только одного обработчика: его имя + роль; в файле — сразу после него
        Map<String, Func> handlerByBase = new HashMap<>();
        for (Pending p : pending) handlerByBase.put(p.base, p.h);
        ownedBy.clear();
        for (Func f : funcs.values()) {
            String base = owner.get(f.name);
            if (base != null && !base.isEmpty() && handlerByBase.containsKey(base) && handlerByBase.get(base) != f) ownedBy.put(f, handlerByBase.get(base));
        }
        Map<String, Integer> counters = new HashMap<>();
        for (Func f : funcs.values()) {
            String base = owner.get(f.name);
            if (base == null || base.isEmpty() || rename.containsKey(f.name)) continue;
            String role = f.ret.equals("boolean") ? "Filter" : "Helper";
            int k = counters.merge(base + role, 1, Integer::sum);
            rename.put(f.name, base + role + (k > 1 ? k : ""));
        }
    }

    static final List<String> handlers = new ArrayList<>();
    static final Map<String, List<String>> anyGroups = new LinkedHashMap<>();
    /** Функция -> обработчик, которому одному она нужна (фильтры, помощники) */
    static final Map<Func, Func> ownedBy = new HashMap<>();

    /** Порядок в файле: общие функции, затем обработчики (каждый — со своими функциями) в порядке подписки */
    static List<Func> printOrder() {
        List<Func> r = new ArrayList<>();
        Set<Func> handlerSet = new HashSet<>();
        for (Pending p : pending) handlerSet.add(p.h);
        for (Func f : funcs.values()) if (!handlerSet.contains(f) && !ownedBy.containsKey(f)) r.add(f);
        for (Pending p : pending) {
            if (!funcs.containsValue(p.h)) continue;
            r.add(p.h);
            for (Func f : funcs.values()) if (ownedBy.get(f) == p.h) r.add(f);
        }
        return r;
    }

    static class Pending { Func h; String ev, base; boolean castParam; List<Reg> regs = new ArrayList<>(); }
    static final List<Pending> pending = new ArrayList<>();

    /** Юниты события в обработчиках — локальные (a, target, dying, killer, entering) или параметр caster (onCast) */
    static void eventLocals() {
        for (Pending p : pending) {
            if (p.ev == null) continue;
            Func h = p.h;
            Map<String, String[]> units = eventUnits(p.ev);
            Set<String> used = new LinkedHashSet<>();
            mapStmts(h.body, e -> {
                if (e instanceof Call c && c.a().isEmpty() && units.containsKey(c.n())) { used.add(units.get(c.n())[0]); return new Name(units.get(c.n())[0]); }
                return e;
            }, true);
            Map<String, String> inits = new LinkedHashMap<>();
            for (String[] u : units.values()) inits.putIfAbsent(u[0], u[1]);
            int at = 0;
            for (Map.Entry<String, String> u : inits.entrySet()) {
                if (!used.contains(u.getKey()) || p.castParam && u.getKey().equals("caster")) continue;
                h.body.add(at++, new Local("unit", u.getKey(), call(u.getValue())));
            }
            if (p.castParam && used.contains("caster")) h.params.add(new String[]{"unit", "caster"});
            else if (p.castParam) for (Reg r : p.regs) { r.head = r.head.replace(" (caster)", " (_caster)"); r.call = p.base + "()"; }
        }
    }

    static If ifReturn(Expr cond) {
        If x = new If();
        x.conds.add(cond);
        x.blocks.add(new ArrayList<>(List.of(new Return(null))));
        return x;
    }


    static Set<String> closureOf(String... roots) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>();
        for (String r : roots) if (r != null) q.add(r);
        while (!q.isEmpty()) {
            String g = q.poll();
            if (!seen.add(g) || !funcs.containsKey(g)) continue;
            eachExpr(funcs.get(g).body, e -> {
                if (e instanceof Call c && funcs.containsKey(c.n())) q.add(c.n());
                if (e instanceof FRef r) q.add(r.n());
            });
        }
        return seen;
    }

    static final Map<String, String> TYPE_BASE = Map.ofEntries(Map.entry("unit", "u"), Map.entry("integer", "n"),
        Map.entry("real", "value"), Map.entry("location", "pos"), Map.entry("group", "g"), Map.entry("effect", "fx"),
        Map.entry("timer", "t"), Map.entry("player", "p"), Map.entry("force", "players"), Map.entry("boolean", "flag"),
        Map.entry("string", "text"), Map.entry("texttag", "tag"), Map.entry("boolexpr", "filter"), Map.entry("region", "reg"),
        Map.entry("rect", "area"), Map.entry("trigger", "trig"), Map.entry("item", "it"));

    /** Локальные и параметры: по типу (u, u2, pos…), переменные циклов — i, j… */
    static void nameLocals(Func f) {
        Map<String, String> m = new HashMap<>();
        Map<String, Integer> used = new HashMap<>();
        Function<String, String> fresh = base -> {
            int k = used.merge(base, 1, Integer::sum);
            return k == 1 ? base : base + k;
        };
        for (String[] p : f.params) { if (FIXED.contains(p[1])) continue; String n = fresh.apply(TYPE_BASE.getOrDefault(p[0], "arg")); m.put(p[1], n); p[1] = n; }
        String[] loopNames = {"i", "j", "k"};
        int[] loops = {0};
        eachList(f.body, l -> {
            for (Stmt s : l) {
                if (s instanceof Local x) { if (FIXED.contains(x.name)) continue; String n = fresh.apply(TYPE_BASE.getOrDefault(x.type, "v")); m.put(x.name, n); x.name = n; }
                else if (s instanceof For x) { String n = loops[0] < 3 ? loopNames[loops[0]++] : fresh.apply("i"); m.put(x.var, n); x.var = n; }
                else if (s instanceof ForIn x) { String n = fresh.apply("u"); m.put(x.var, n); x.var = n; }
            }
        });
        UnaryOperator<Expr> r = e -> e instanceof Name n && m.containsKey(n.n()) ? new Name(m.get(n.n())) : e instanceof Index x && m.containsKey(x.n()) ? new Index(m.get(x.n()), x.i()) : e;
        mapStmts(f.body, r, true);
        eachList(f.body, l -> { for (Stmt s : l) if (s instanceof Assign a) a.target = map(a.target, r); });
    }

    /** Глобальные и функции: по rename */
    static void applyRename() {
        UnaryOperator<Expr> r = e -> {
            if (e instanceof Name n && rename.containsKey(n.n())) return new Name(rename.get(n.n()));
            if (e instanceof Index x && rename.containsKey(x.n())) return new Index(rename.get(x.n()), x.i());
            if (e instanceof Call c && rename.containsKey(c.n())) return new Call(rename.get(c.n()), c.a());
            if (e instanceof FRef x && rename.containsKey(x.n())) return new FRef(rename.get(x.n()));
            return e;
        };
        Map<String, Func> nf = new LinkedHashMap<>();
        for (Func f : funcs.values()) { mapStmts(f.body, r, true); f.name = rename.getOrDefault(f.name, f.name); nf.put(f.name, f); }
        funcs.clear(); funcs.putAll(nf);
        mapStmts(init, r, true);
        Map<String, Global> ng = new LinkedHashMap<>();
        for (Global g : globals.values()) { g.init = map(g.init, r); g.name = rename.getOrDefault(g.name, g.name); ng.put(g.name, g); }
        globals.clear(); globals.putAll(ng);
    }

    /** Локальная без значения, первое обращение — присваивание в том же списке: объявление там */
    static void declareAtAssignment(List<Stmt> list) {
        for (int i = 0; i < list.size(); i++) {
            Stmt s = list.get(i);
            for (List<Stmt> b : blocks(s)) declareAtAssignment(b);
            if (s instanceof Lambda l) declareAtAssignment(l.body);
            if (!(s instanceof Local lo) || lo.init != null && !(lo.init instanceof Lit) && !isName(lo.init, "null") && !isName(lo.init, "false") && !isName(lo.init, "true")) continue;
            // вся работа с ней — в одной ветке if (или в замыкании) дальше: объявление туда
            if (lo.init == null || isName(lo.init, "null") || lo.init instanceof Lit nl && nl.s().equals("null")) {
                List<Integer> users = new ArrayList<>();
                for (int j = i + 1; j < list.size(); j++) if (mentions(list.get(j), e -> isName(e, lo.name))) users.add(j);
                if (users.size() == 1 && (list.get(users.get(0)) instanceof If || list.get(users.get(0)) instanceof Lambda)) {
                    Stmt u = list.get(users.get(0));
                    List<List<Stmt>> bs = u instanceof Lambda lam ? List.of(lam.body) : blocks(u);
                    List<List<Stmt>> with = new ArrayList<>();
                    for (List<Stmt> b : bs) if (mentions(b, e -> isName(e, lo.name))) with.add(b);
                    boolean inHead = false;
                    for (Expr e0 : ownExprs(u)) if (mentions(List.of(new ExprS(e0)), e -> isName(e, lo.name))) inHead = true;
                    if (with.size() == 1 && !inHead) {
                        list.remove(i--);
                        with.get(0).add(0, lo);
                        continue;
                    }
                }
            }
            for (int j = i + 1; j < list.size(); j++) {
                Stmt x = list.get(j);
                boolean uses = mentions(x, e -> isName(e, lo.name));
                if (x instanceof Assign a && isName(a.target, lo.name) && !mentions(List.of(new ExprS(a.value)), e -> isName(e, lo.name))) {
                    lo.init = a.value;
                    list.remove(j);
                    list.remove(i);
                    list.add(j - 1, lo);
                    i--;
                    break;
                }
                if (uses) break;
            }
        }
    }

    /** Локальные, которые больше не меняются — let */
    static Set<String> assignedIn(List<Stmt> body) {
        Set<String> r = new HashSet<>();
        eachList(body, l -> { for (Stmt s : l) if (s instanceof Assign a && a.target instanceof Name n) r.add(n.n()); });
        return r;
    }

    // ---------------- печать ----------------
    static int prec(Expr e) {
        if (e instanceof Bin b) {
            switch (b.op()) {
                case "or": return 1;
                case "and": return 2;
                case "+": case "-": return 4;
                case "*": case "/": case "div": case "mod": return 5;
                default: return 3;
            }
        }
        if (e instanceof Un u) return u.op().equals("not") ? 2 : 6;
        return 9;
    }

    static String printExpr(Expr e, int ctx) {
        if (e instanceof Lit l) return l.s();
        if (e instanceof Name n) return n.n();
        if (e instanceof Index x) return x.n() + "[" + printExpr(x.i(), 0) + "]";
        if (e instanceof FRef r) return "function " + r.n();
        if (e instanceof Call c) return c.n() + "(" + args(c.a()) + ")";
        if (e instanceof Field f) return recv(f.r()) + "." + f.n();
        if (e instanceof MCall c) {
            if (c.n().startsWith("..")) return printExpr(c.r(), 9) + c.n() + "(" + args(c.a()) + ")";
            return recv(c.r()) + "." + c.n() + "(" + args(c.a()) + ")";
        }
        if (e instanceof Un u) {
            String s = u.op().equals("not") ? "not " + printExpr(u.e(), 3) : "-" + printExpr(u.e(), 6);
            return prec(e) < ctx ? "(" + s + ")" : s;
        }
        Bin b = (Bin) e;
        int p = prec(b);
        String s = printExpr(b.l(), p) + " " + b.op() + " " + printExpr(b.r(), p == 1 || p == 2 ? p : p + 1);
        return p < ctx ? "(" + s + ")" : s;
    }

    /** Каскад из нескольких «..» — по строке на звено */
    static String cascade(Expr e, int depth) {
        List<MCall> links = new ArrayList<>();
        Expr head = e;
        while (head instanceof MCall m && m.n().startsWith("..")) { links.add(0, m); head = m.r(); }
        if (links.size() < 2) return printExpr(e, 0);
        StringBuilder sb = new StringBuilder(printExpr(head, 9));
        for (MCall m : links) sb.append('\n').append("    ".repeat(depth + 1)).append(m.n()).append('(').append(args(m.a())).append(')');
        return sb.toString();
    }

    static String recv(Expr r) {
        String s = printExpr(r, 9);
        return r instanceof Lit l && l.s().matches("-?\\d.*") ? "(" + s + ")" : s;
    }

    static String args(List<Expr> a) {
        StringBuilder sb = new StringBuilder();
        for (Expr x : a) sb.append(sb.length() > 0 ? ", " : "").append(printExpr(x, 0));
        return sb.toString();
    }

    static String type(String t) {
        switch (t) {
            case "integer": return "int";
            case "boolean": return "bool";
            case "location": return "vec2";
        }
        return t;
    }

    static void printBody(StringBuilder out, List<Stmt> body, int depth, Set<String> assigned) {
        String pre = "    ".repeat(depth);
        if (body.isEmpty()) { out.append(pre).append("skip\n"); return; }
        for (Stmt s : body) {
            if (s instanceof Local x) {
                if (x.init == null) out.append(pre).append(type(x.type)).append(' ').append(x.name).append('\n');
                else {
                    boolean mutable = assigned.contains(x.name);
                    boolean isNull = x.init instanceof Lit l && l.s().equals("null") || isName(x.init, "null");
                    if (isNull && x.type.equals("location")) { out.append(pre).append("vec2 ").append(x.name).append('\n'); continue; }
                    boolean explicit = isNull || x.type.equals("real") && x.init instanceof Lit l2 && !l2.s().contains(".");
                    String kw = explicit ? type(x.type) : mutable ? "var" : "let";
                    out.append(pre).append(kw).append(' ').append(x.name).append(" = ").append(printExpr(x.init, 0)).append('\n');
                }
            } else if (s instanceof Assign x) {
                out.append(pre).append(printExpr(x.target, 0)).append(" = ").append(cascade(x.value, depth)).append('\n');
            } else if (s instanceof ExprS x) {
                out.append(pre).append(cascade(x.e, depth)).append('\n');
            } else if (s instanceof If x) {
                for (int i = 0; i < x.conds.size(); i++) {
                    out.append(pre).append(i == 0 ? "if " : "else if ").append(printExpr(x.conds.get(i), 0)).append('\n');
                    printBody(out, x.blocks.get(i), depth + 1, assigned);
                }
                if (x.elseB != null) { out.append(pre).append("else\n"); printBody(out, x.elseB, depth + 1, assigned); }
            } else if (s instanceof Loop x) {
                out.append(pre).append("while true\n");
                printBody(out, x.body, depth + 1, assigned);
            } else if (s instanceof Exit x) {
                out.append(pre).append("if ").append(printExpr(x.cond, 0)).append('\n').append(pre).append("    break\n");
            } else if (s instanceof Return x) {
                out.append(pre).append(x.value == null ? "return" : "return " + printExpr(x.value, 0)).append('\n');
            } else if (s instanceof For x) {
                out.append(pre).append("for ").append(x.var).append(" = ").append(printExpr(x.from, 0)).append(" to ").append(printExpr(x.to, 0)).append('\n');
                printBody(out, x.body, depth + 1, assigned);
            } else if (s instanceof ForIn x) {
                out.append(pre).append("for ").append(mentions(x.body, e -> isName(e, x.var)) ? x.var : "_" + x.var).append(" in ").append(printExpr(x.coll, 0)).append('\n');
                printBody(out, x.body, depth + 1, assigned);
            } else if (s instanceof Lambda x) {
                out.append(pre).append(x.fn).append('(').append(printExpr(x.time, 0)).append(')').append(x.cb != null ? " " + x.cb : "").append(" ->\n");
                printBody(out, x.body, depth + 1, assigned);
            } else if (s instanceof Reg x) {
                out.append(pre).append(x.head).append('\n').append(pre).append("    ").append(x.call).append('\n');
                for (String m : x.more) out.append(pre).append("    ").append(m).append('\n');
            } else if (s instanceof Raw x) {
                out.append(pre).append(x.s).append('\n');
            }
        }
    }

    // ---------------- всё ----------------
    public static String convert(String text, Map<String, String> names) {
        header.clear(); globals.clear(); funcs.clear(); init.clear(); rename.clear(); usedLastGlobals.clear(); handlers.clear(); pending.clear(); anyGroups.clear(); lambdaVars = 0;
        read(text);
        applySubstitutes();
        dropYdweCounters();
        triggers(names);
        dropMissingTriggers();
        for (boolean ch = true; ch; ) ch = timersToClosures() | groupsToLoops();
        bjLoops();
        eventLocals();
        lastCreated();
        missingRects();
        for (Func f : funcs.values()) { mapStmts(f.body, JassToWurst::rewrite, true); mapStmts(f.body, JassToWurst::simplify, true); }
        mapStmts(init, JassToWurst::rewrite, true);
        // location -> vec2: RemoveLocation не нужен
        for (Func f : funcs.values()) eachList(f.body, l -> l.removeIf(s -> isCallS(s, "RemoveLocation")));
        localizeTemps();
        for (Func f : funcs.values()) { tidy(f.body); declareAtAssignment(f.body); flashEffects(f.body); dropUnread(f.body); declareAtAssignment(f.body); }
        dropUnused();
        applyRename();
        for (Func f : funcs.values()) nameLocals(f);

        StringBuilder out = new StringBuilder();
        for (String h : header) out.append(h).append('\n');
        out.append('\n');
        for (String kind : usedLastGlobals) {
            String[] k = LAST.get(kind);
            out.append(k[0]).append(' ').append(k[1]).append(" = null\n");
        }
        for (Global g : globals.values()) {
            out.append(g.constant ? "constant " : "").append(type(g.type)).append(g.array ? " array " : " ").append(g.name);
            if (g.init != null && !(g.type.equals("location") && isName(g.init, "null"))) out.append(" = ").append(printExpr(map(g.init, JassToWurst::rewrite), 0));
            out.append('\n');
        }
        for (Func f : printOrder()) {
            if (f.doc != null) {
                String bar = "// ============================================================";
                out.append('\n').append(bar).append("\n// ").append(f.doc).append('\n').append(bar).append("\n\n@noinline");
            }
            out.append("\nfunction ").append(f.name).append('(');
            for (int i = 0; i < f.params.size(); i++) out.append(i > 0 ? ", " : "").append(type(f.params.get(i)[0])).append(' ').append(f.params.get(i)[1]);
            out.append(')');
            if (!f.ret.equals("nothing")) out.append(" returns ").append(type(f.ret));
            out.append('\n');
            printBody(out, f.body, 1, assignedIn(f.body));
        }
        out.append("\n/** Подписка триггеров Just Fight — при старте режима с копиями (PvE, PvEvP): в PvP их нет вовсе */\n");
        out.append("public function jfRegisterTriggers()\n");
        printBody(out, init, 1, Set.of());
        // юниты и способности, которые создают/выдают обработчики (даммики Звездопада и т. п.), — загрузить при старте
        // карты: иначе первое создание в бою подтормаживает (Preloader работает только в init)
        Set<String> units = new TreeSet<>(), abils = new TreeSet<>();
        for (Func f : funcs.values()) eachExpr(f.body, e -> {
            if (e instanceof Call c && (c.n().equals("createUnit") || c.n().equals("jfCreateUnits")) && c.a().size() > 1 && idOf(c.a().get(1)) != null)
                units.add(printExpr(c.a().get(1), 0));
            if (e instanceof MCall m && m.n().equals("addAbility") && m.a().size() == 1 && idOf(m.a().get(0)) != null)
                abils.add(printExpr(m.a().get(0), 0));
        });
        out.append("\ninit\n");
        // vararg в Jass — не больше 31 параметра: по 20
        for (String[] kind : new String[][]{{"preloadUnit"}, {"preloadAbility"}}) {
            List<String> ids = new ArrayList<>(kind[0].equals("preloadUnit") ? units : abils);
            for (int i = 0; i < ids.size(); i += 20)
                out.append("    ").append(kind[0]).append('(').append(String.join(", ", ids.subList(i, Math.min(ids.size(), i + 20)))).append(")\n");
        }
        return out.toString().replace("\nnull\n", "\n");
    }

    /** Функции Just Fight, вместо которых — функции The War (names.txt: «старое -> новое»): вызовы заменяются, сама — нет */
    static final Map<String, String> substitutes = new LinkedHashMap<>();

    /** names.txt: «jfИмя новоеИмя» — переименовать, «jfИмя -> функция» — вызывать функцию The War вместо */
    public static Map<String, String> readNames(Path p) throws Exception {
        Map<String, String> r = new LinkedHashMap<>();
        substitutes.clear();
        for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            l = l.replaceAll("#.*", "").trim();
            if (l.isEmpty()) continue;
            String[] w = l.split("\\s+");
            if (w.length == 3 && w[1].equals("->")) substitutes.put(w[0], w[2]);
            else r.put(w[0], w[1]);
        }
        return r;
    }

    static void applySubstitutes() {
        UnaryOperator<Expr> f = e -> e instanceof Call c && substitutes.containsKey(c.n()) ? new Call(substitutes.get(c.n()), c.a()) : e;
        for (Func fn : funcs.values()) mapStmts(fn.body, f, true);
        mapStmts(init, f, true);
        for (String s : substitutes.keySet()) funcs.remove(s);
    }

    /** Триггеры Just Fight, которые не перенесены (глобальная не создаётся): их включение/выключение/запуск — ничего */
    static void dropMissingTriggers() {
        Set<String> assigned = new HashSet<>();
        for (Func f : funcs.values()) assigned.addAll(assignedIn(f.body));
        assigned.addAll(assignedIn(init));
        for (Global g : globals.values()) {
            if (!g.type.equals("trigger") || g.array || assigned.contains(g.name) || g.init != null && !isName(g.init, "null")) continue;
            Predicate<Stmt> dead = s -> (isCallS(s, "DisableTrigger") || isCallS(s, "EnableTrigger") || isCallS(s, "TriggerExecute") || isCallS(s, "ConditionalTriggerExecute"))
                && isName(call(s).a().get(0), g.name);
            for (Func f : funcs.values()) eachList(f.body, l -> l.removeIf(dead));
        }
    }

    // ---------------- временные глобальные -> локальные ----------------

    /** Ключ обращения к глобальной: «g» или «g[0]» (индекс — число); null — не глобальная или индекс не число */
    static String tempKey(Expr e) {
        if (e instanceof Name n && globals.containsKey(n.n()) && !globals.get(n.n()).array) return n.n();
        if (e instanceof Index x && globals.containsKey(x.n()) && x.i() instanceof Lit l && l.s().matches("\\d+")) return x.n() + "[" + l.s() + "]";
        return null;
    }

    static String tempGlobal(Expr e) {
        if (e instanceof Name n && globals.containsKey(n.n())) return n.n();
        if (e instanceof Index x && globals.containsKey(x.n())) return x.n();
        return null;
    }

    /**
     * Глобальная Just Fight как временная (tempPos[0] = …; потом чтение): если в каждой функции она сначала
     * присваивается и только потом читается (и никто не читает чужое значение), то это локальные функций.
     * Поток: последовательность операторов; после if — присвоенное во всех ветках; цикл и замыкание — без последствий.
     */
    static void localizeTemps() {
        Set<String> bad = new HashSet<>();
        Map<String, Set<String>> users = new HashMap<>(); // глобальная -> функции
        for (Func f : funcs.values()) {
            eachExpr(f.body, e -> {
                String g = tempGlobal(e);
                if (g == null) return;
                users.computeIfAbsent(g, k -> new HashSet<>()).add(f.name);
                if (e instanceof Index x && tempKey(x) == null) bad.add(g);
            });
        }
        eachExpr(init, e -> { String g = tempGlobal(e); if (g != null) bad.add(g); });
        for (Global g : globals.values()) if (g.init != null && !isName(g.init, "null") && !(g.init instanceof Lit)) bad.add(g.name);
        // в каждой функции чтение — после своего присваивания
        for (Func f : funcs.values()) {
            Set<String> readFirst = new HashSet<>();
            flow(f.body, new HashSet<>(), readFirst, false);
            for (String k : readFirst) bad.add(k.replaceFirst("\\[.*", ""));
        }
        // функция, которая вызывает другую с этой же глобальной (та её меняет или читает), — значение общее
        for (Map.Entry<String, Set<String>> u : users.entrySet())
            for (String fn : u.getValue()) {
                Set<String> callees = closureOf(fn);
                callees.remove(fn);
                callees.retainAll(u.getValue());
                if (!callees.isEmpty()) bad.add(u.getKey());
            }
        for (Map.Entry<String, Set<String>> u : users.entrySet()) {
            String g = u.getKey();
            if (bad.contains(g) || globals.get(g).type.equals("trigger") || globals.get(g).constant) continue;
            for (String fn : u.getValue()) {
                Func f = funcs.get(fn);
                Map<String, String> locals = new LinkedHashMap<>();
                UnaryOperator<Expr> r = e -> {
                    String k = tempKey(e);
                    if (k == null || !k.replaceFirst("\\[.*", "").equals(g)) return e;
                    return new Name(locals.computeIfAbsent(k, x -> "jfTempVar" + (++lambdaVars)));
                };
                mapStmts(f.body, r, true);
                int at = 0;
                for (String v : locals.values()) declareInScope(f.body, new Local(globals.get(g).type, v, null));
            }
            globals.remove(g);
        }
    }

    /** Объявление — в начале самого внутреннего замыкания, где все обращения (или в начале функции) */
    static void declareInScope(List<Stmt> body, Local lo) {
        List<Stmt> scope = body;
        while (true) {
            List<Stmt> inner = null;
            int usesOutside = 0;
            List<Lambda> lams = new ArrayList<>();
            collectLambdas(scope, lams);
            for (Lambda l : lams) if (mentions(l.body, e -> isName(e, lo.name))) { if (inner == null) inner = l.body; else { inner = null; usesOutside = 1; break; } }
            if (inner == null || usesOutside > 0) break;
            // вне этого замыкания не упоминается?
            List<Stmt> outside = withoutLambdaBody(scope, inner);
            if (mentions(outside, e -> isName(e, lo.name))) break;
            scope = inner;
        }
        scope.add(0, lo);
    }

    /** Замыкания верхнего уровня списка (не вложенные в другие замыкания) */
    static void collectLambdas(List<Stmt> list, List<Lambda> out) {
        for (Stmt s : list) {
            if (s instanceof Lambda l) out.add(l);
            else for (List<Stmt> b : blocks(s)) collectLambdas(b, out);
        }
    }

    /** Список без тела данного замыкания (для проверки обращений снаружи) */
    static List<Stmt> withoutLambdaBody(List<Stmt> list, List<Stmt> body) {
        List<Stmt> r = new ArrayList<>();
        for (Stmt s : list) {
            if (s instanceof Lambda l && l.body == body) { r.add(new ExprS(l.time)); continue; }
            if (s instanceof If x) {
                If c = new If();
                c.conds.addAll(x.conds);
                for (List<Stmt> b : x.blocks) c.blocks.add(withoutLambdaBody(b, body));
                if (x.elseB != null) c.elseB = withoutLambdaBody(x.elseB, body);
                r.add(c);
            } else if (s instanceof Loop x) { Loop c = new Loop(); c.body = withoutLambdaBody(x.body, body); r.add(c); }
            else if (s instanceof For x) { For c = new For(); c.var = x.var; c.from = x.from; c.to = x.to; c.body = withoutLambdaBody(x.body, body); r.add(c); }
            else if (s instanceof ForIn x) { ForIn c = new ForIn(); c.var = x.var; c.coll = x.coll; c.body = withoutLambdaBody(x.body, body); r.add(c); }
            else r.add(s);
        }
        return r;
    }

    /** assigned — присвоенные к этому месту; в readFirst — прочитанные до присваивания */
    static void flow(List<Stmt> list, Set<String> assigned, Set<String> readFirst, boolean inLambda) {
        for (Stmt s : list) {
            if (s instanceof Assign a) {
                reads(a.value, assigned, readFirst);
                if (a.target instanceof Index x) reads(x.i(), assigned, readFirst);
                String k = tempKey(a.target);
                if (k != null) {
                    assigned.add(k);
                }
                continue;
            }
            for (Expr e : ownExprs(s)) reads(e, assigned, readFirst);
            if (s instanceof If x) {
                Set<String> common = null;
                List<List<Stmt>> branches = new ArrayList<>(x.blocks);
                if (x.elseB != null) branches.add(x.elseB);
                for (List<Stmt> b : branches) {
                    Set<String> in = new HashSet<>(assigned);
                    flow(b, in, readFirst, inLambda);
                    if (common == null) common = in; else common.retainAll(in);
                }
                if (x.elseB != null && common != null) assigned.addAll(common);
            } else if (s instanceof Loop || s instanceof For || s instanceof ForIn) {
                for (List<Stmt> b : blocks(s)) flow(b, new HashSet<>(assigned), readFirst, inLambda);
            } else if (s instanceof Lambda l) {
                flow(l.body, new HashSet<>(assigned), readFirst, true);
            }
        }
    }

    static void reads(Expr e, Set<String> assigned, Set<String> readFirst) {
        map(e, x -> {
            String k = tempKey(x);
            if (k != null && !assigned.contains(k)) readFirst.add(k);
            String g = tempGlobal(x);
            if (g != null && k == null) readFirst.add(g); // индекс не число
            // вызов функции, которая читает глобальную, — тоже чтение; это ловит проверка «сначала присваивание» в ней
            return x;
        });
    }

    /** Только то, что достижимо из init */
    static void dropUnused() {
        Set<String> live = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        Consumer<Expr> refs = e -> {
            if (e instanceof Call c && funcs.containsKey(c.n())) q.add(c.n());
            if (e instanceof FRef r && funcs.containsKey(r.n())) q.add(r.n());
        };
        eachExpr(init, refs);
        q.addAll(handlers);
        while (!q.isEmpty()) {
            String g = q.poll();
            if (live.add(g)) eachExpr(funcs.get(g).body, refs);
        }
        funcs.keySet().retainAll(live);
        Set<String> used = new HashSet<>();
        Consumer<Expr> names = e -> {
            if (e instanceof Name n) used.add(n.n());
            if (e instanceof Index x) used.add(x.n());
        };
        for (Func fn : funcs.values()) eachExpr(fn.body, names);
        eachExpr(init, names);
        for (Global g : globals.values()) if (g.init != null) map(g.init, e -> { names.accept(e); return e; });
        globals.keySet().retainAll(used);
    }

    public static void main(String[] a) throws Exception {
        String text = Files.readString(Paths.get(a[0]), StandardCharsets.UTF_8);
        Files.writeString(Paths.get(a[1]), convert(text, readNames(Paths.get(a[2]))), StandardCharsets.UTF_8);
    }
}
