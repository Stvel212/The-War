# Характеристики юнитов из экспорта объектов (grill exportobjects) -> TSV:
#   id base hp damage dice sides armor cooldown range speed attackType defenseType
# -1 — поле не изменено (берётся у базового типа). Типы атаки/брони — числа ConvertAttackType / ConvertDefenseType.
BEGIN {
    OFS = "\t"
    atk["Spells"] = 0; atk["Normal"] = 1; atk["Pierce"] = 2; atk["Siege"] = 3; atk["Magic"] = 4; atk["Chaos"] = 5; atk["Hero"] = 6
    def["Small"] = 0; def["Medium"] = 1; def["Large"] = 2; def["Fortified"] = 3; def["Normal"] = 4; def["Hero"] = 5; def["Divine"] = 6; def["Unarmored"] = 7
    split("HitPointsMaximumBase Attack1DamageBase Attack1DamageNumberofDice Attack1DamageSidesperDie DefenseBase Attack1CooldownTime Attack1Range SpeedBase Attack1AttackType ArmorType", F, " ")
    split("uhpm ua1b ua1d ua1s udef ua1c ua1r umvs ua1t udty", R, " ")
    for (k = 1; k <= 10; k++) raw[R[k]] = F[k]
}
function flush(   k, s, x) {
    if (id == "") return
    s = id OFS base
    for (k = 1; k <= 10; k++) {
        x = (F[k] in v) ? v[F[k]] : "-1"
        sub(/^AttackType\./, "", x); if (x in atk && F[k] == "Attack1AttackType") x = atk[x]
        sub(/^ArmorType\./, "", x); if (x in def && F[k] == "ArmorType") x = def[x]
        s = s OFS x
    }
    print s
    delete v; id = ""
}
/(Unit|Building|Hero)Definition\('/ {
    flush()
    match($0, /'[A-Za-z0-9]{4}', *'[A-Za-z0-9]{4}'/); s = substr($0, RSTART, RLENGTH); gsub(/[' ]/, "", s); split(s, a, ",")
    id = a[1]; base = a[2]; next
}
/createObjectDefinition\("w3u", '/ {
    flush()
    match($0, /'[A-Za-z0-9]{4}', *'[A-Za-z0-9]{4}'/); s = substr($0, RSTART, RLENGTH); gsub(/[' ]/, "", s); split(s, a, ",")
    id = a[1]; base = a[2]; next
}
/^@compiletime|^function/ { flush(); next }
# сырые поля (createObjectDefinition): ..setInt("uhpm", 780)
id != "" && match($0, /\.\.set(Int|Real|Unreal|String)\("(uhpm|ua1b|ua1d|ua1s|udef|ua1c|ua1r|umvs|ua1t|udty)"/) {
    code = substr($0, RSTART, RLENGTH); sub(/.*\("/, "", code); sub(/"$/, "", code)
    val = $0; sub(/^[^,]*, */, "", val); sub(/\)[ \t\r]*$/, "", val); gsub(/"/, "", val)
    if (code == "ua1t") val = (val == "normal" ? "Normal" : (val == "pierce" ? "Pierce" : (val == "siege" ? "Siege" : (val == "magic" ? "Magic" : (val == "chaos" ? "Chaos" : (val == "hero" ? "Hero" : "Spells"))))))
    if (code == "udty") val = (val == "small" ? "Small" : (val == "medium" ? "Medium" : (val == "large" ? "Large" : (val == "fort" ? "Fortified" : (val == "hero" ? "Hero" : (val == "divine" ? "Divine" : (val == "none" ? "Unarmored" : "Normal")))))))
    v[raw[code]] = val
    next
}
id != "" && match($0, /\.\.set(HitPointsMaximumBase|Attack1DamageBase|Attack1DamageNumberofDice|Attack1DamageSidesperDie|DefenseBase|Attack1CooldownTime|Attack1Range|SpeedBase|Attack1AttackType|ArmorType)\(/) {
    line = $0; sub(/^[^.]*\.\.set/, "", line); f = line; sub(/\(.*/, "", f)
    val = line; sub(/^[^(]*\(/, "", val); sub(/\)[ \t\r]*$/, "", val); v[f] = val
}
END { flush() }
