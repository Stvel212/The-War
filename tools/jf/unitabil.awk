# Вход: TSV «способность -> класс AbilityDefinition» (abcls.awk), затем объекты юнитов (Wurst).
# Выход: «юнит <TAB> отсортированные классы обычных способностей» — набор способностей, сравнимый между картами.
BEGIN { FS = "\t" }
FILENAME == ARGV[1] { cls[$1] = $2; next }
function flush(   n, a, i, j, t, s) {
    if (id == "") return
    n = split(abil, a, ",")
    for (i = 1; i <= n; i++) a[i] = (a[i] in cls) ? cls[a[i]] : a[i]
    for (i = 2; i <= n; i++) for (j = i; j > 1 && a[j - 1] > a[j]; j--) { t = a[j]; a[j] = a[j - 1]; a[j - 1] = t }
    s = ""; for (i = 1; i <= n; i++) s = s (i > 1 ? "," : "") a[i]
    print id "\t" s
    id = ""; abil = ""
}
/(Unit|Building|Hero)Definition\(\x27|createObjectDefinition\("w3u", \x27/ { flush(); match($0, /\x27[A-Za-z0-9]{4}\x27/); id = substr($0, RSTART + 1, 4); next }
/^@compiletime|^function/ { flush(); next }
id != "" && /\.\.setNormalAbilities\("|\.\.setString\("uabi"/ { s = $0; sub(/^[^"]*"(uabi", ")?"?/, "", s); sub(/".*/, "", s); abil = s }
END { flush() }
