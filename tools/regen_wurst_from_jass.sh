#!/bin/bash
# regenerate wurst/map from the organised JASS + apply manual post-edits, then typecheck
S="$(cd "$(dirname "$0")" && pwd)"
R="$S/.."
set -e
cd "$S" && "/c/Program Files/Java/jdk-20/bin/javac" -encoding UTF-8 JassToWurst.java Wurstify.java Closurify.java
rm -rf "$S/wout" && mkdir "$S/wout"
~/.wurst/wurst-runtime/bin/java.exe -Dfile.encoding=UTF-8 -cp "$S" JassToWurst "$R/backup/triggers/war3map.organized.j" "$R/_build/common.j" "$R/_build/blizzard.j" "$R/wurst/objects" "$S/wout" "$S/stdnames.txt" | tail -1
rm -rf "$R/wurst/map" && mkdir -p "$R/wurst/map" && cp -r "$S/wout/"*.wurst "$S/wout/lib" "$S/wout/autocast" "$R/wurst/map/"
cp "$S/wout/war3map.skeleton.j" "$R/backup/triggers/war3map.skeleton.j"  # WE-скелет; в архиве TheWar.w3x он уже такой
rm -f "$R/wurst/war3map.j"
# ---- post edits ----
cd "$R"
f=wurst/map/DamageSystem.wurst
sed -i 's/^import MapGlobals$/import MapGlobals\nimport DamageEvent/; s/^public function trig_DamageOne_Actions()$/public function trig_DamageOne_Actions()\n    real amount = DamageEvent.getAmount()/; s/    unit u = DamageEventSource/    unit u = DamageEvent.getSource()/; s/    unit c = DamageEventTarget/    unit c = DamageEvent.getTarget()/; s/\bDamageEventAmount\b/amount/g; s/if IsDamageSpell$/if DamageEvent.getType() == DamageType.SPELL/; s/^\(\s*\)UnitDamageTarget(c, u, amount,/\1DamageEvent.setNextDamageFromCode()\n\1UnitDamageTarget(c, u, amount,/' $f
perl -0pi -e 's/    s = null\n    u = null\n    c = null\n/    DamageEvent.setAmount(amount)\n    s = null\n    u = null\n    c = null\n/' $f
perl -0pi -e 's/public function initTrig_DamageOne\(\)\n.*?\n\ninit\n    initTrig_DamageOne\(\)\n/\/\/ Damage Engine (Bribe) заменён на stdlib DamageEvent: слушатель срабатывает на каждый урон после брони,\n\/\/ как и бывшее событие DamageModifierEvent = 1.\ninit\n    DamageEvent.addListener() ->\n        trig_DamageOne_Actions()\n/s' $f
sed -i "s|        SetUnitUserData(u, 5)|        SaveBoolean(Hash, GetHandleId(u), KEY_MULTICAST, true) // было: custom value = 5 (конфликтует с UnitIndexer из stdlib)|; s|    return GetSpellAbilityId() == 'A03O' and GetUnitUserData(GetTriggerUnit()) == 5|    return GetSpellAbilityId() == 'A03O' and LoadBoolean(Hash, GetHandleId(GetTriggerUnit()), KEY_MULTICAST)|" wurst/map/autocast/AutocastOrc.wurst
sed -i "s|    int dat = GetUnitUserData(GetTriggerUnit())|    int dat = LoadInteger(Hash, GetHandleId(GetTriggerUnit()), KEY_KINGHP) // было: custom value (конфликтует с UnitIndexer из stdlib)|; s|        SetUnitUserData(GetTriggerUnit(), hp)|        SaveInteger(Hash, GetHandleId(GetTriggerUnit()), KEY_KINGHP, hp)|" wurst/map/King.wurst
perl -0pi -e 's|(\n// регионы из редактора)|\n// ключи в Hash для данных, которые раньше хранились в custom value юнита (custom value занят UnitIndexer из stdlib)\npublic constant KEY_MULTICAST = \x27MCST\x27\npublic constant KEY_KINGHP = \x27KHP_\x27\n$1|' wurst/map/MapGlobals.wurst
~/.wurst/wurst-runtime/bin/java.exe -Dfile.encoding=UTF-8 -cp "$S" Wurstify "$R/wurst/map"
~/.wurst/wurst-runtime/bin/java.exe -Dfile.encoding=UTF-8 -cp "$S" Closurify "$R/wurst/map"
# ---- typecheck ----
G="$HOME/.wurst/wurst-runtime/bin/java.exe -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar C:/Users/lyapa/.wurst/grill-cli/grill.jar"
$G typecheck > "$S/tc.txt" 2>&1 || true
echo "errors: $(grep -c '^Error in' "$S/tc.txt")"
grep -A1 "^Error in" "$S/tc.txt" | grep -v "^--\|^Error in" | sed -E "s/[A-Za-z_0-9]+ (is|are|to|of|in|as|but)/@ \1/g" | sort | uniq -c | sort -rn | head -${1:-25}
perl "$S/fix_zapretwater.pl" "$R/wurst/map/Main.wurst"
perl "$S/fix_no_tournament.pl" "$R/wurst/map/Initialization.wurst"
~/.wurst/wurst-runtime/bin/java.exe -Dfile.encoding=UTF-8 -cp "$S" ArtifactRefactor "$R/wurst/map/Artifacts.wurst"
