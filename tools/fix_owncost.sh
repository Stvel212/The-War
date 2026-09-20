#!/bin/bash
# script must keep using a unit type's OWN cost (see wurst/map/UpgradeCosts.wurst)
D="$1"
for f in $(grep -rl "GetUnitGoldCost(\|GetUnitWoodCost(" "$D" --include=*.wurst | grep -v UpgradeCosts.wurst); do
  sed -i 's/\bGetUnitGoldCost(/unitOwnGoldCost(/g; s/\bGetUnitWoodCost(/unitOwnWoodCost(/g' "$f"
  grep -q "^import UpgradeCosts$" "$f" || sed -i '0,/^import /s//import UpgradeCosts\nimport /' "$f"
done
