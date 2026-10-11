# Таблица автокаста монстров (CreepCast) для способностей, скопированных из Just Fight (UnitsPveJf.wurst):
#   awk -f casts.awk orders.tsv UnitsPveJf.wurst >> PveWaveTables.wurst
# orders.tsv: класс AbilityDefinition… <TAB> приказ <TAB> цель (0 без цели, 1 враг, 2 точка врага, 3 союзник)
FILENAME == ARGV[1] {
    if ($0 ~ /^#/ || NF < 3) next
    split($0, f, "\t"); order[f[1]] = f[2]; target[f[1]] = f[3]
    next
}
/new AbilityDefinition[A-Za-z0-9]*\('/ {
    match($0, /AbilityDefinition[A-Za-z0-9]*/); cls = substr($0, RSTART + 17, RLENGTH - 17)
    match($0, /'[A-Za-z0-9]{4}'/); id = substr($0, RSTART, 6)
    if (cls in order) { n++; ids = ids (n > 1 ? ", " : "") id; ords = ords (n > 1 ? ", " : "") "\"" order[cls] "\""; tgs = tgs (n > 1 ? ", " : "") target[cls] }
}
END {
    print ""
    print "// Автокаст монстров (CreepCast): активные способности скопированных юнитов Just Fight — приказ и цель"
    print "// (0 без цели, 1 враг, 2 точка врага, 3 раненый союзник). По tools/jf/orders.tsv"
    print "public constant JF_CASTS = " n
    print "public constant jfCastAbil = [" ids "]"
    print "public constant jfCastOrder = [" ords "]"
    print "public constant jfCastTarget = [" tgs "]"
}
