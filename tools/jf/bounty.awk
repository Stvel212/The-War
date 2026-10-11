# Золото за убийство монстров волн — скопированных юнитов Just Fight (I1IlILI): 2 × еда барака, номер которого —
# point value юнита (l11L[pv]); 'n047' — 1, 'n02E', 'n02I' и point value 309 / 171 — 2.
#   awk -f bounty.awk jf.j WurstExportedObjects_w3u.wurst copy_map.tsv
# Печатает строки "новый ID<TAB>золото" (только ненулевые).
FILENAME == ARGV[1] {
    # таблица заполняется блоками: set l1LL=N, потом set l11L[l1LL]='…' и set l1LL=(l1LL+1)
    if ($0 ~ /^set l1LL=[0-9]+$/) { split($0, a, "="); idx = a[2] + 0 }
    else if ($0 ~ /^set l1LL=\(l1LL\+1\)$/) idx++
    else if ($0 ~ /^set l11L\[l1LL\]=/) { match($0, /'[A-Za-z0-9]{4}'/); bar[idx] = substr($0, RSTART + 1, 4) }
    next
}
FILENAME == ARGV[2] {
    if ($0 ~ /^@compiletime function create_w3u_/) { id = substr($0, length("@compiletime function create_w3u_") + 1, 4); next }
    if ($0 ~ /\.\.setPointValue\(/) { v = $0; gsub(/[^0-9]/, "", v); pv[id] = v + 0 }
    if ($0 ~ /\.\.setFoodCost\(/) { v = $0; gsub(/[^0-9]/, "", v); food[id] = v + 0 }
    next
}
$3 == "w3u" {
    j = $1
    if (j == "n047") g = 1
    else if (j == "n02E" || j == "n02I" || pv[j] == 309 || pv[j] == 171) g = 2
    else if ((j in pv) && pv[j] != 100 && (pv[j] in bar) && food[j] == 0) g = 2 * food[bar[pv[j]]]
    else g = 0
    if (g > 0) print $2 "\t" g
}
