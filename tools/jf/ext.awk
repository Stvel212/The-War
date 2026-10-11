/new (Unit|Hero|Building)Definition\('/ { if (id != "") print id "\t" kind "\t" base "\t" hp "\t" model "\t" name; match($0, /'([A-Za-z0-9]{4})', '([A-Za-z0-9]{4})'/); s = substr($0, RSTART, RLENGTH); id = substr(s, 2, 4); base = substr(s, 10, 4); kind = ($0 ~ /HeroDefinition/) ? "H" : (($0 ~ /BuildingDefinition/) ? "B" : "U"); hp = "-"; model = "-"; name = "-" }
/setHitPointsMaximumBase\(/ { match($0, /\(([0-9]+)/); hp = substr($0, RSTART + 1, RLENGTH - 1) }
/setModelFile\(/ { match($0, /"[^"]*"/); model = substr($0, RSTART + 1, RLENGTH - 2) }
/\.\.setName\(/ { if (name == "-") { match($0, /"[^"]*"/); name = substr($0, RSTART + 1, RLENGTH - 2) } }
END { if (id != "") print id "\t" kind "\t" base "\t" hp "\t" model "\t" name }
