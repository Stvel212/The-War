#!/usr/bin/env bash
# Таблицы волн PvE/PvEvP из карты Just Fight 5.6.2 -> wurst/map/modes/PveWaveTables.wurst.
#   bash tools/jf/gen_pve_waves.sh "путь/к/3082_Just_Fight!_[5.6.2_A1].w3x"
# Шаги: скрипт карты (scripts\war3map.j, его нет в listfile) читается по имени (ReadMpq), строки CR -> LF;
# объекты — grill exportobjects; из функций-таблиц (ILL1III, ILL1I1I, ILL1LII, ILL1L1I) берутся волны;
# казарма Just Fight -> юнит, которого она обучает; все юниты волн копируются из Just Fight целиком (CopyJfUnits ->
# UnitsPveJf), их триггеры — PveJfTriggers (PortJfTriggers). Сопоставление с юнитами The War (match2.awk) осталось только
# для записей без объекта в экспорте (N01C).
set -e
MAP="$1"
[ -f "$MAP" ] || { echo "usage: $0 just_fight.w3x"; exit 1; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JF="$ROOT/tools/jf"
W="$HOME/.wurst"
TMP="$(mktemp -d)"
# для Java под Windows — путь в виде C:/...
WTMP="$(cygpath -m "$TMP" 2>/dev/null || echo "$TMP")"
WW="$(cygpath -m "$W" 2>/dev/null || echo "$W")"
trap 'rm -rf "$TMP"' EXIT
cp "$MAP" "$TMP/jf.w3x"

# скрипт
javac --release 17 -d "$WTMP/cls" "$JF/ReadMpq.java"
"$W/wurst-runtime/bin/java" -cp "$WTMP/cls;$WW/wurst-compiler/wurstscript.jar" ReadMpq "$WTMP/jf.w3x" "$WTMP" 'scripts/war3map.j' > /dev/null
tr '\r' '\n' < "$TMP/scripts_war3map.j" > "$TMP/jf.j"

# объекты (grill пишет в ../objectEditingOutput от проекта)
mkdir -p "$TMP/proj/wurst"
cp "$ROOT/wurst.build" "$TMP/proj/"
(cd "$TMP/proj" && "$W/grill.cmd" exportobjects ../jf.w3x > /dev/null)
awk -f "$JF/ext.awk" "$TMP/objectEditingOutput/WurstExportedObjects_w3u.wurst" > "$TMP/jf_units.tsv"
awk '/new BuildingDefinition\(/{match($0,/\x27[A-Za-z0-9]{4}\x27/); id=substr($0,RSTART+1,4)} /setUnitsTrained\("/{match($0,/"[^",]*/); t=substr($0,RSTART+1,RLENGTH-1); if(id!="" && t!="") print id "\t" t}' \
    "$TMP/objectEditingOutput/WurstExportedObjects_w3u.wurst" > "$TMP/jf_trains.tsv"
# без UnitsPveJf (сами копии Just Fight, их выпускает этот скрипт)
ls "$ROOT"/wurst/objects/units/*.wurst | grep -v UnitsPveJf | xargs cat | awk -f "$JF/ext.awk" > "$TMP/war_units.tsv"

# функции-таблицы
table() { # $1 — имя функции, $2 — файл
    local s e
    s=$(grep -n "^function $1 " "$TMP/jf.j" | cut -d: -f1)
    e=$(awk -v s="$s" 'NR > s && /^endfunction/ { print NR; exit }' "$TMP/jf.j")
    sed -n "${s},${e}p" "$TMP/jf.j" > "$2"
}
table ILL1III "$TMP/t1.txt"
table ILL1I1I "$TMP/t2.txt"
table ILL1LII "$TMP/t3.txt"
table ILL1L1I "$TMP/t4.txt"
cd "$TMP"
for t in t1 t2; do
    awk -F"[][]" '/^set lILlI\[[0-9]+\]=/{split($0,a,"=");gsub(/\x27/,"",a[2]);ty[$2]=a[2]} /^set LILLI\[[0-9]+\]=/{split($0,a,"=");gsub(/"/,"",a[2]);ct[$2]=a[2]} END{for(i=1;i<=313;i++) if(i in ty) print i, ty[i], (i in ct?ct[i]:"-")}' $t.txt > $t.parsed
done
awk '/^set lILlI\[Load/{split($0,a,"=");t1=a[2]} /^set LLlLI\[Load/{split($0,a,"=");t2=a[2]} /^set LILLI\[Load/{split($0,a,"=");n++;gsub(/["\x27]/,"",t1);gsub(/["\x27]/,"",t2);gsub(/"/,"",a[2]);print n, t1, t2, a[2]}' t3.txt > t3.parsed
awk -F'"' '/^set LlILI\[/{n++; print n, $2}' t4.txt > t4.parsed

# сопоставление юнитов
{ awk '{print $2}' t1.parsed t2.parsed; awk '{print $2; print $3}' t3.parsed
  awk '{n=split($2,a,";"); for(i=1;i<n;i++){split(a[i],b,"-"); if(b[1]!="XXXX") print b[1]}}' t4.parsed; } | grep -v H0BT | sort -u > jf_ids.txt
# кандидаты: юниты бараков The War (fillTiers и все их улучшения)
cd "$ROOT"
info() { local f blk; f=$(grep -ln "BuildingDefinition('$1'" wurst/objects/units/*.wurst | head -1); [ -z "$f" ] && return 0
    blk=$(awk -v id="'$1'" 'index($0,"Definition(" id){f=1;next} f&&/^@compiletime/{exit} f' "$f")
    echo "$1 $(echo "$blk" | grep -o 'custom_[A-Za-z0-9]*' | sed 's/custom_//' | head -1) $(echo "$blk" | grep -o 'setUpgradesTo("[^"]*")' | sed 's/setUpgradesTo("//;s/")//' | tr ',' ' ')"; }
queue=$(for t in 1 2 3 4 5 6 7 10; do grep -o "tier$t\[[0-9]*\] = '[^']*'" wurst/map/core/Initialization.wurst | sed "s/.*= '//;s/'//"; done)
: > "$TMP/war_cand.txt"; seen=" "
while [ -n "$queue" ]; do
    next=""
    for b in $queue; do
        case "$seen" in *" $b "*) continue ;; esac
        seen="$seen$b "
        read -r id unit ups <<< "$(info "$b")"
        [ -n "$unit" ] && echo "$unit" >> "$TMP/war_cand.txt"
        next="$next $ups"
    done
    queue="$next"
done
cd "$TMP"
sort -u war_cand.txt -o war_cand.txt
awk -F'\t' '$2=="U"{print $1}' war_units.tsv > war_all.txt
awk -f "$JF/match2.awk" jf_units.tsv jf_trains.tsv war_units.tsv war_cand.txt war_all.txt jf_ids.txt > matches.tsv
awk -F'\t' '$6 == "none" { print "НЕ СОПОСТАВЛЕН: " $1 > "/dev/stderr" }' matches.tsv

# все юниты волн Just Fight копируются целиком (способности, баффы, призываемые) в UnitsPveJf.wurst под новыми ID, их
# триггеры (способности, которые в Just Fight делает скрипт карты) — в PveJfTriggers.wurst (PortJfTriggers). Объекты,
# нужные только триггерам (даммики, призываемые триггером), копируются следующим проходом, пока список не перестанет расти
awk -F'\t' '!seen[$2]++ { print $2 }' matches.tsv > copy_ids.txt
javac --release 17 -encoding UTF-8 -d "$WTMP/cls" "$JF/CopyJfUnits.java" "$JF/PatchMdxTextures.java" "$JF/PortJfTriggers.java" "$JF/JassToWurst.java"
M="$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")"
copy_and_port() {
    "$W/wurst-runtime/bin/java" -cp "$WTMP/cls" CopyJfUnits "$WTMP/objectEditingOutput" "$WTMP/copy_ids.txt" \
        "$M/wurst/objects/units/UnitsPveJf.wurst" "$WTMP/copy_map.tsv" "$WTMP/assets.txt" \
        "$M/_build/dependencies/wurstStdlib2/wurst/objediting" "$WTMP/script_ids.txt" > /dev/null
    "$W/wurst-runtime/bin/java" -cp "$WTMP/cls" PortJfTriggers "$WTMP/jf.j" "$WTMP/copy_map.tsv" "$WTMP/objectEditingOutput" \
        "$M/imports/models" "$M/wurst/map/modes/PveJfTriggers.wurst" "$(cygpath -m "$JF" 2>/dev/null || echo "$JF")/trigger_exclude.txt" \
        "$WTMP/script_ids_next.txt" 2> port_report.txt
}
: > script_ids.txt
for pass in 1 2 3 4 5; do
    copy_and_port
    cat script_ids.txt script_ids_next.txt | sort -u > script_ids_all.txt
    cmp -s script_ids_all.txt <(sort -u script_ids.txt) && break
    mv script_ids_all.txt script_ids.txt
done
cat port_report.txt >&2
# казарма из таблицы -> её юнит (столбец 2) скопирован: запись ведёт на копию
awk -F'\t' -v OFS='\t' 'FILENAME == ARGV[1] { if ($3 == "w3u") nid[$1] = $2; next } ($2 in nid) { $7 = nid[$2]; $6 = "copy" } { print }' \
    copy_map.tsv matches.tsv > matches2.tsv && mv matches2.tsv matches.tsv
# импортированные модели Just Fight (в корне архива; у объектов и в триггерах): в imports/models, их текстуры — в
# imports/textures
for m in $({ tr ',' '\n' < assets.txt; grep -o '"[A-Za-z0-9_]*\.md[lxLX]"' "$ROOT/wurst/map/modes/PveJfTriggers.wurst" | tr -d '"'; } \
        | grep -i -E '^[A-Za-z0-9_]+\.md[lx]$' | sed -E 's/\.[A-Za-z]+$//' | sort -u); do
    [ -f "$ROOT/imports/models/$m.mdx" ] && continue
    "$W/wurst-runtime/bin/java" -cp "$WTMP/cls;$WW/wurst-compiler/wurstscript.jar" ReadMpq "$WTMP/jf.w3x" "$WTMP" "$m.mdx" > /dev/null || continue
    [ -f "$TMP/$m.mdx" ] || continue
    "$W/wurst-runtime/bin/java" -cp "$WTMP/cls" PatchMdxTextures "$WTMP/$m.mdx" "$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")/imports/models/$m.mdx" 'textures\'
    for t in $(grep -a -o -E 'textures.[A-Za-z0-9_]+\.blp' "$ROOT/imports/models/$m.mdx" | sed 's/^textures.//' | sort -u); do
        [ -f "$ROOT/imports/textures/$t" ] && continue
        "$W/wurst-runtime/bin/java" -cp "$WTMP/cls;$WW/wurst-compiler/wurstscript.jar" ReadMpq "$WTMP/jf.w3x" "$WTMP" "$t" > /dev/null
        [ -f "$TMP/$t" ] && cp "$TMP/$t" "$ROOT/imports/textures/$t"
    done
done
# триггеры — ещё раз: пути к только что импортированным моделям
copy_and_port

awk -f "$JF/gen.awk" matches.tsv t1.parsed t2.parsed t3.parsed t4.parsed > "$ROOT/wurst/map/modes/PveWaveTables.wurst"
# золото за убийство скопированных юнитов (у юнитов The War — по их бараку, Initialization.unitGold)
awk -f "$JF/bounty.awk" "$TMP/jf.j" "$TMP/objectEditingOutput/WurstExportedObjects_w3u.wurst" copy_map.tsv > bounty.tsv
# в подсказках — то же золото («Reward: +N gold» у Just Fight местами расходится с формулой)
awk -f "$JF/bounty_tips.awk" bounty.tsv "$ROOT/wurst/objects/units/UnitsPveJf.wurst" > UnitsPveJf.new \
    && mv UnitsPveJf.new "$ROOT/wurst/objects/units/UnitsPveJf.wurst"
awk -F'\t' '{ t = t (NR > 1 ? ", " : "") "\x27" $1 "\x27"; g = g (NR > 1 ? ", " : "") $2 }
    END { print ""; print "// Золото за убийство скопированных юнитов Just Fight (I1IlILI: 2 × еда барака с номером = point value юнита)"
          print "public constant JF_BOUNTIES = " NR; print "public constant jfBountyType = [" t "]"; print "public constant jfBounty = [" g "]" }' \
    bounty.tsv >> "$ROOT/wurst/map/modes/PveWaveTables.wurst"
echo "wurst/map/modes/PveWaveTables.wurst: $(awk -F'\t' '{print $6}' matches.tsv | sort | uniq -c | tr '\n' ' ')"
