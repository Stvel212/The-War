# Подсказки скопированных юнитов Just Fight: «Reward: +N gold» — то золото, что юнит действительно даёт
# (bounty.tsv из bounty.awk; кого там нет — 0).
#   awk -f bounty_tips.awk bounty.tsv UnitsPveJf.wurst > UnitsPveJf.new
FILENAME == ARGV[1] { g[$1] = $2; next }
/new UnitDefinition\('|createObjectDefinition\("w3u", '/ { match($0, /'[A-Za-z0-9]{4}'/); id = substr($0, RSTART + 1, 4) }
/new [A-Za-z]*Definition\(|createObjectDefinition\("w3[^u]"/ && !/UnitDefinition|"w3u"/ { id = "" }
/Reward: \+[0-9]+ gold/ && id != "" { gsub(/Reward: \+[0-9]+ gold/, "Reward: +" ((id in g) ? g[id] : 0) " gold") }
{ print }
