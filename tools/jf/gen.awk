# Вход: jf_prof.tsv (характеристики юнитов Just Fight, stats.awk), matches.tsv (JF id -> War id), затем t1..t4.parsed.
# Выход: Wurst-пакет PveWaveTables.
BEGIN { FS = "\t" }
FILENAME == "jf_prof.tsv" {
    prof[$1] = $0
    next
}
FILENAME == "matches.tsv" {
    war[$1] = $7
    # профиль характеристик юнита Just Fight (столбец 2 — юнит, которого выпускает казарма из таблицы)
    if (!($1 in stat)) stat[$1] = ($6 != "copy" && $2 in prof) ? profileOf($2) : 0
    next
}
{
    split($0, f, " ")
    table = FILENAME
    sub(/\.parsed$/, "", table)
    t = substr(table, 2) + 0
}
function profileOf(jf,   p) {
    if (jf in pidx) return pidx[jf]
    pidx[jf] = ++np
    split(prof[jf], p, "\t")
    for (k = 2; k <= 12; k++) pv[np, k] = p[k]
    pname[np] = jf
    return np
}
function add(t, w, id, count, perTick) {
    if (!(id in war)) { print "UNMAPPED " id > "/dev/stderr"; return }
    n[t]++
    ty[t, n[t]] = war[id]; ct[t, n[t]] = count; st[t, n[t]] = stat[id]; tick[t, n[t]] = perTick
    if (!((t, w) in first)) first[t, w] = n[t]
    last[t] = w
}
# t1/t2: «волна тип AABB» (AA за раз, BB раз); строки 201-213 / 301-313 в t2 — элитные добавки
(t == 1 || t == 2) {
    w = f[1]; id = f[2]
    if (w >= 200) { elite[w] = war[id]; elitest[w] = stat[id]; next }
    aa = substr(f[3], 1, 2) + 0; bb = substr(f[3], 3, 2) + 0
    add(t, w, id, aa * bb, aa)
    next
}
# t3: «волна тип1 тип2 AABB;CCDD»
t == 3 {
    w = f[1]
    aa = substr(f[4], 1, 2) + 0; bb = substr(f[4], 3, 2) + 0
    cc = substr(f[4], 6, 2) + 0; dd = substr(f[4], 8, 2) + 0
    add(3, w, f[2], aa * bb, aa)
    add(3, w, f[3], cc * dd, cc)
    next
}
# t4: «волна T1-NN;T2-NN;…;ценность»
t == 4 {
    w = f[1]
    m = split(f[2], parts, ";")
    for (i = 1; i < m; i++) {
        split(parts[i], p, "-")
        if (p[1] != "XXXX" && p[2] + 0 > 0)
            add(4, w, p[1], p[2] + 0, 1)
    }
    next
}
function arr(name, t, field,   i, s) {
    s = ""
    for (i = 1; i <= n[t]; i++) {
        if (field == "ty") v = "'" ty[t, i] "'"
        else if (field == "ct") v = ct[t, i]
        else if (field == "st") v = st[t, i]
        else v = tick[t, i]
        s = s (i > 1 ? ", " : "") v
    }
    return "public constant " name " = [" s "]"
}
# столбец профиля k (2 база, 3 здоровье … 12 тип брони); real — с точкой
function parr(name, k, real,   i, s, x) {
    s = real ? "-1." : (k == 2 ? "0" : "-1")
    for (i = 1; i <= np; i++) {
        x = pv[i, k]
        if (k == 2) x = "'" x "'"
        else if (real && x !~ /\./) x = x "."
        s = s ", " x
    }
    return "public constant " name " = [" s "]"
}
END {
    print "package PveWaveTables"
    print ""
    print "// Таблицы волн PvE Just Fight 5.6.2 (ILL1III / ILL1I1I / ILL1LII / ILL1L1I), переведённые на юниты The War:"
    print "// юнит Just Fight -> юнит The War с тем же базовым типом и ближайшим здоровьем (казармы -> их юнит) — его вид"
    print "// и способности; характеристики — профиль юнита Just Fight (stat*, CreepWaves.applyJfStats). Сгенерировано"
    print "// tools/jf/gen_pve_waves.sh из карты Just Fight; руками не править."
    print "// Волна w таблицы T: записи waveFirstT[w] .. waveFirstT[w + 1] - 1 (тип, всего юнитов на игрока, юнитов за раз,"
    print "// профиль; 0 — без профиля)."
    print ""
    for (t = 1; t <= 4; t++) {
        print "public constant WAVES_" t " = " last[t]
        print arr("waveType" t, t, "ty")
        print arr("waveCount" t, t, "ct")
        print arr("waveTick" t, t, "tk")
        print arr("waveStat" t, t, "st")
        s = ""
        for (w = 1; w <= last[t] + 1; w++) {
            # пустая волна (в таблице 3 нет 69-й) — без записей
            if ((t, w) in first) v = first[t, w] - 1
            else v = (w <= last[t] ? -1 : n[t])
            fw[w] = v
        }
        # -1 → следующий первый индекс (пустой диапазон)
        for (w = last[t]; w >= 1; w--) if (fw[w] < 0) fw[w] = fw[w + 1]
        s = "0"
        for (w = 1; w <= last[t] + 1; w++) s = s ", " fw[w]
        print "public constant waveFirst" t " = [" s "]"
        print ""
    }
    s1 = ""; s2 = ""; s3 = ""; s4 = ""
    for (i = 201; i <= 213; i++) { s1 = s1 (i > 201 ? ", " : "") "'" elite[i] "'"; s3 = s3 (i > 201 ? ", " : "") elitest[i] + 0 }
    for (i = 301; i <= 313; i++) { s2 = s2 (i > 301 ? ", " : "") "'" elite[i] "'"; s4 = s4 (i > 301 ? ", " : "") elitest[i] + 0 }
    print "// Уровень 2, волны 31-60: элитные добавки (lILlI[201..213] до 49-й, [301..313] с 50-й)"
    print "public constant eliteEarly = [" s1 "]"
    print "public constant eliteEarlyStat = [" s3 "]"
    print "public constant eliteLate = [" s2 "]"
    print "public constant eliteLateStat = [" s4 "]"
    print ""
    print "// Профили характеристик юнитов Just Fight (индекс 1..; 0 — пусто): базовый тип, здоровье, урон, кубики, грани,"
    print "// броня, перезарядка атаки, дальность, скорость, тип атаки (ConvertAttackType), тип брони (ConvertDefenseType);"
    print "// -1 — не изменено в Just Fight, т. е. как у базового типа"
    print "public constant STAT_PROFILES = " np
    print parr("statBase", 2, 0)
    print parr("statHp", 3, 0)
    print parr("statDamage", 4, 0)
    print parr("statDice", 5, 0)
    print parr("statSides", 6, 0)
    print parr("statArmor", 7, 1)
    print parr("statCooldown", 8, 1)
    print parr("statRange", 9, 0)
    print parr("statSpeed", 10, 0)
    print parr("statAttackType", 11, 0)
    print parr("statDefenseType", 12, 0)
}
