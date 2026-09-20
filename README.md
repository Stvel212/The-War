# The War Reborn — Wurst-проект

Проект устроен как SurvivelChaosReborn: карта-архив с одним ландшафтом, всё остальное — в проекте. Сборка через WurstScript.

## Структура

```
TheWar.w3x             карта-исходник (MPQ, git LFS, ~0.5 MB) — ТОЛЬКО ландшафт: рельеф/тени/проходимость/миникарта
                       (w3e, shd, wpm, mmp), расставленные декорации и юниты (doo, Units.doo), регионы, камеры,
                       инфо и строки (w3i, wts), типы декораций (w3b, w3d), превью, WE-скелет war3map.j и пустые
                       wtg/wct (без них World Editor карту не открывает)
                       (юниты на карте, регионы, config). Триггеров и объектов юнитов/способностей в ней нет.
wurst/
  TheWar.wurst         точка входа (импортирует MapStart)
  map/                 вся логика карты на Wurst (см. ниже)
  objects/             данные объектов в виде Wurst-кода, по категориям:
    units/             Heroes, HeroUnits ("[Герой] - Тралл" и т.п.), Buildings<Раса>, Units<Раса>, UnitsOriginal
    abilities/         Abilities<Раса> (по носителю), AbilitiesHeroes/HeroUnits, AbilitiesItems,
                       AbilitiesShared (несколько рас), AbilitiesScript (даются из скрипта), AbilitiesOther
    items/             Items, ItemsOriginal
    misc/              Buffs, Upgrades, Destructables, Doodads (+ *Original)
imports/               все ассеты карты: models/ textures/ icons/ sounds/ ui/ ReplaceableTextures/ units/,
                       war3mapSkin.txt, war3mapMisc.txt, dummy.mdx — Wurst кладёт их в карту при сборке
locales/               переводы: enUS.txt (русский текст → английский); подхватываются сборкой, см. «Локализация»
tools/                 утилиты, которыми делался перенос (см. tools/README.md)
backup/                оригинальный архив, исходные триггеры, распакованная папка карты (в git не попадает)
_build/                результат сборки (в git не попадает)
```

Пути к ассетам внутри объектов/скрипта/w3i/.mdx были переписаны под эту раскладку (models\…, icons\… и т.д.).

## Сборка

```
build.cmd
```

`build.cmd` вызывает компилятор Wurst напрямую (как `grill build`, но с оптимизациями
`-inline -opt -localOptimizations`, которые grill не включает: скрипт 783 KB → 539 KB) и перед этим:
- чистит `_build/cache` — иначе Wurst кладёт в кэш уже инжектированные объекты и на каждой следующей
  сборке добавляет модификации поверх (файлы `war3map.w3u/w3b/w3h` растут);
- удаляет `wurst/war3map.j` — это копия скрипта карты, которую делает компилятор; если файл уже есть,
  компилятор берёт его и не обновляет.
- после компилятора запускает `tools/Localize.java` — локализация (см. ниже) — и `tools/W3iModes.java`: переписывает
  `war3map.w3i` в формат Reforged (31) с «поддерживаемые режимы графики = только SD», чтобы карта шла только в
  классической графике (в самом `TheWar.w3x` w3i остаётся формата 25, Wurst пишет его как есть). Обёртки VS Code
  («Wurst: Build map») этих шагов не делают: карта из них — только русская и без ограничения графики.

Готовая карта: `_build/TheWarReborn.w3x`. `run.cmd` = build.cmd + копирование в `Documents\Warcraft III\Maps\Test`
+ запуск Reforged с этой картой (то же, что «Wurst: Run map»). В VS Code: `Ctrl+Shift+B` — сборка, `Terminal → Run Task → Wurst: Run map`.

В VS Code также работают «Wurst: Build map» / «Wurst: Run map» — выбирать `TheWar.w3x`. Не выбирай
`_build/TheWarReborn.w3x` — это уже собранная карта, компилятор откажется («already was compiled with wurst»).

## Логика карты (wurst/map)

Весь скрипт карты (241 триггер, ~12k строк) переведён с JASS на Wurst:

```
map/MapGlobals.wurst      глобальные переменные (бывшие udg_*, без префикса), регионы, Hash; бывший InitGlobals
map/lib/SendResult, CjLib   библиотеки оригинала (статистика для ботов и т.п.), переведены как есть; MMD — wurst/MMD.wurst (готовая Wurst-версия)
map/Initialization        Zadania, Int, Int2, Wave, Win
map/DamageSystem          модификаторы урона (бывший DamageOne) — слушатель stdlib DamageEvent
map/Scoreboard            мультиборды, LeaveGame
map/Main                  постройка, бараки, запреты, камни, книга
map/Commands, Synergy, King, Builder, HeroSpells   по папкам триггеров редактора
map/Artifacts             класс Artifact: таблица улучшений предметов (53 записи вместо 680 строк if/else),
                          Artifact.onCast(abil) для эффектов, charges()/dummy() вместо повторяющихся циклов
map/autocast/Autocast<Раса>   способности юнитов, разбиты по расе юнитов/способностей, которые они используют
map/UnitSearch            поиск юнитов в радиусе поверх ClosureForGroups: firstUnitInRange / randomUnitInRange /
                          countUnitsInRange / unitsInRange / firstUnitsInRange (условие — замыкание)
map/Dummies               auraDummy() — даммик-носитель пассивки (аура, Phoenix Fire) из DummyRecycler; unit.isDummy()
map/UnitEvents            диспетчер событий по типу юнита: onAttackBy / onAttacked / onDeathOf / onKillBy (+ onAnyAttack/onAnyDeath)
map/MapStart              триггеры «Map initialization» (Zadania, Int) — стартуют после инициализации всех пакетов
```

Внутри пакета структура повторяет триггеры: `// Триггер: X — что делает`, одна функция на триггер, а `init` пакета
подписывает её на событие **по типу юнита**: `onAttackBy('h00P') -> setka()` (атакует юнит этого типа),
`onAttacked('n02B')`, `onDeathOf('u009')`, `onKillBy('h00Q')` (map/UnitEvents: один слушатель на событие и HashMap
«тип → обработчики», O(1) вместо ~90 проверок `GetAttacker().getTypeId() == …` на каждую атаку); триггеры по способности —
`EventListener.onCast('A01S') (caster) -> …` (ClosureEvents, тоже HashMap); обработчики по баффам/уровням, не по типу —
`onAnyAttack()`/`onAnyDeath()` (их ~15). Периодика — `doPeriodically`, задержки — `doAfter`; свои `trigger` остались
только там, где stdlib не даёт замыкания: регионы (`registerEnterRegion`), чат (`registerPlayerChatEvent`) и таймер раунда.
Бывшая цепочка `AttackCast` (35 веток `if id == …`) разложена на `onAttackBy(...) -> selfOrder/targetOrder(...)`.
Порядок обработчиков одного типа — исходный порядок триггеров. Функции-триггеры помечены `@noinline`:
без этого инлайнер склеивает все вызовы одного слушателя в одну огромную функцию, и оптимизатор компилятора
(`LocalMerger.analyzeLiveness`) на ней зависает на десятки минут — сборка «висит» на «extracting script».

Что заменено на stdlib / изменено по сравнению с оригиналом:
- **Damage Engine (Bribe) → `DamageEvent`** из stdlib. Единственный потребитель был `DamageOne` (модификаторы урона),
  он стал `DamageEvent.addListener()`; «spell damage» = `DamageEvent.getType() == SPELL`.
- **Unit Indexer удалён** — его использовал только Damage Engine. Но stdlib-индексер (тянется за DamageEvent)
  занимает custom value юнитов, поэтому `KingMesage` (HP короля в custom value) переведён на `Hash` с ключом `KEY_KINGHP`.
- **Даммики → `DummyCaster` / `InstantDummyCaster`** из stdlib вместо юнита 'e00W' с таймером жизни ('e00W', 'e00X', 'e01O'
  из объектов убраны). Мгновенные способности (innerfire, frostarmor, bloodlust, purge, cripple, curse, roar, stomp,
  thunderclap, rejuvination, illusion) — `InstantDummyCaster.castX(owner, abil, lvl, OrderIds.x, target, pos)`;
  снаряды, каналы и отложенный урон (thunderbolt, chainlightning, shockwave, impale, magicleash, healingwave,
  fingerofdeath, drain, starfall, tranquility, monsoon, tornado, inferno) — `new DummyCaster()..owner()..origin()..delay()..castX()`
  с тем же временем жизни, что было у 'e00W'. Даммики-носители аур/Phoenix Fire — `auraDummy()` (map/Dummies).
  Два триггера различали даммиков по самому юниту (`Multikast` — флаг мультикаста огра, `Sycyb2` — тип даммика 'e01O');
  теперь даммик общий, а эффект каста срабатывает синхронно внутри приказа, поэтому они читают глобалы
  `ogreMulticast` / `sycybCaster`, выставленные на время каста.
- **`Group.enumUnitsInRange(...) + forGroup/FirstOfGroup/getRandom` → `ClosureForGroups`**: `forUnitsInRange(pos, r) u -> …`
  (условие — прямо в замыкании) и обёртки из map/UnitSearch, где нужен первый/случайный/подсчёт/группа.
  Глобальные группы `Group`/`Group2` и счётчик `udg_CountUnit` не нужны и убраны. Там, где колбэк удаляет или
  переносит юнитов, сначала собирается группа (`unitsInRange`), а потом `forEachFrom` — не во время перебора.
- Код в стиле Wurst: extension-методы stdlib вместо нативов (`u.addAbility('A000')`, `p.getGold()`), `players[i]`,
  `localPlayer`, `u.getPos()`, `let`/`var`, `for i = 1 to N`, каскады `..`, тернарники. Повторяющийся код вынесен в
  помощники: `unit.attackLane()` (приказ в атаку по своей линии, ~30 копий), `goldText`, `unitGold`, `player.isLeftTeam()`.
- Все таймеры — замыкания: одноразовые «CreateTimer + Hash.save… + колбэк с Hash.load…» и `TriggerSleepAction`
  → `doAfter(d) -> …`, периодические с Hash → `doPeriodically` / `doPeriodicallyCounted` (счётчик и объекты живут в
  замыкании, `Hash` не нужен). Триггеры-глобалы `trg_*` убраны.
- `wDammiHP/MP/Att` (бонусы HP/маны/урона через способности-бонусы `A1ZV`/`A1ZT` и предметы `I1ZA..I1ZD`, по 3 хендла
  на каждые 16 единиц) → нативы Reforged `setMaxHP` / `setMaxMana` / `setBaseDamage`.
- Что убрано из «горячего» пути: `Tree` раз в 3 с раздавал всем юнитам метку `A0BO` и всем помеченным — новый attack-move
  (каждый боец на марше перестраивал путь каждые ~6 с) и чинил жизнь всем деревьям у баз (тысячи `DestructableRestoreLife`)
  — теперь приказ получают только стоящие без приказа (`getCurrentOrder() == 0`), а деревья отрастают по событию смерти.
  Мультиборд/текст ресурсов переписываются только при изменении значения (в JASS строки не освобождаются — иначе
  таблица строк росла всю игру), слоты и юниты-маркеры силы пересчитываются только при изменении `Force`.
  Группы для `unitsInRange` берутся из пула stdlib (`getGroup`/`release`), а не создаются на каждый каст.
- Найденные при переписывании ошибки переноса исправлены: `DamageSystem` считал модификаторы, но не вызывал
  `DamageEvent.setAmount` и читал источник/цель из пустых глобалов (модификаторы урона не работали вообще);
  `KingMesage` читал «прошлое HP» из custom value, хотя писал в `Hash`; `Synergy` при продаже здания собой (`A006`)
  брал игрока у пустой цели; `KickAfk` сообщал ник игрока 0 вместо афк-игрока; `DarkDragon` (взрыв никого не задевал),
  `DeathSpider` (второй паук всегда жил 2 с), `Proxod` (юниты короля 8 сравнивались с игроком 10).
- 90 неиспользуемых глобалов (остатки движков и WE) и `set x=null` в конце функций убраны (Wurst обнуляет сам).
- `int/int` переведено как `div` (в JASS это целочисленное деление; в Wurst `/` даёт real).
- Сборка с `-inline -opt -localOptimizations` (build.cmd).

Исходники JASS на всякий случай в `backup/triggers/` (`war3map.orig.j` — как было, `war3map.organized.j` — отсортированный,
из него генерировался Wurst; `tools/JassToWurst.java` + `tools/regen_wurst_from_jass.sh` — сам транслятор).

Ландшафт можно править в World Editor (открыть `TheWar.w3x`, сохранить): триггеров в карте нет, а свой `war3map.j`
редактор перегенерирует сам, компилятор извлекает его при каждой сборке. Нюанс: расставленные на карте юниты кастомных
типов WE покажет как неизвестные (их типы живут в `wurst/objects`), при сохранении в WE они могут потеряться —
после правки ландшафта проверь `war3mapUnits.doo` (или расставляй такие юниты кодом). Модели из `imports/` WE тоже
не видит (в редакторе вместо кастомных декораций будут заглушки, в игре всё на месте).

## Как работают объекты

`wurst/objects/**` — единственный источник юнитов, способностей, предметов, баффов и апгрейдов
(в карте их нет, Wurst генерирует `war3map.w3u/w3a/w3t/w3h/w3q` при сборке). Правишь юнита в `units/UnitsOrc.wurst` →
он меняется в собранной карте. Типы декораций/разрушаемых (w3b/w3d) остались в карте — они часть ландшафта.
Раса у юнитов без явного `setRace` выведена по связям (здание → кого тренирует; иначе по базовому
объекту — у этой карты все такие базы человеческие). Категория способности — по тому, кто её носит.

Полная подсказка юнита/барака («Характеристики: Награда, Атакующие, Атака…») не пишется текстом, а собирается из
цифр (`objects/UnitStats.wurst`): в начале функции `let stats = new Stats()..bounty(2)..ranged(400)..attack(18, 24, 1.5,
AttackType.Pierce)..defense(520, 0, ArmorType.Medium)..skill("текст способности")..skill("(Требуется 60 маны)…")`,
у объекта — `..setStats(stats)`. Меняешь цифру — меняется подсказка; текст способности — по строке на `skill(...)`.
Пока это только текст: на поля объекта (урон, здоровье) `Stats` не влияет, и у бараков свой `Stats` (цифры юнита,
которого они тренируют, продублированы). 597 подсказок переведены на `Stats` скриптом; 77 остались текстом
(`setTooltipExtended`) — это нестандартные (герои, светлячки, камень).

## Локализация

Reforged (1.32+) умеет держать в карте строки для каждого языка клиента: игра берёт `_Locales\<locale>.w3mod\war3map.wts`
(enUS, deDE, ruRU, zhCN…) вместо корневого `war3map.wts`, если такая папка есть в архиве. Работает это только через
таблицу строк (ссылки `TRIGSTR_N` в объектах), поэтому Wurst-код объектов остаётся русским, а перевод делает
`tools/Localize.java` после сборки:

1. в готовой `_build/TheWarReborn.w3x` заменяет в файлах объектов тексты, которые видны в игре, на `TRIGSTR_N`
   (одинаковые тексты — одна строка): юниты `w3u` (`unam/utip/utub/upro`), предметы `w3t` (`unam/utip/utub/ides`),
   способности `w3a` (`anam/atp1/aub1/aut1/auu1/aret/arut`, по уровням), улучшения `w3q` (`gnam/gtp1/gub1`),
   баффы `w3h` (`fnam/ftip/fube`); суффиксы редактора (`unsf/ansf/fnsf`) не трогает — их в игре нет;
2. пишет `war3map.wts` в MPQ несколько раз с кодом языка в хеш-таблице (0 — русский, запасной вариант; 0x409 enUS —
   английский из `locales/enUS.txt`; остальные локали — копия английского) и дублирует это как
   `_Locales\<locale>.w3mod\war3map.wts` (второй механизм Reforged). Из старого wts остаются только ~70 строк, на
   которые ссылаются w3i/`war3mapSkin.txt`/задания, остальные 4200 были мусором.

`locales/enUS.txt` — блоки `=== где используется` / русский текст / `---` / перевод (формат в шапке файла). Ключ —
русский текст ровно как в объекте, поэтому после правки подсказки в Wurst старый перевод перестаёт подходить —
`Localize` сообщит «N untranslated», а шаблон недостающих блоков даёт
`java -cp "_build\tools;%USERPROFILE%\.wurst\wurst-compiler\wurstscript.jar" Localize --missing _build\TheWarReborn.w3x locales enUS`
(дописать в enUS.txt, заполнить перевод). Другой язык — `locales/<locale>.txt` с тем же форматом, ничего больше
настраивать не нужно. Проверить английский можно, переключив язык клиента в Battle.net.

Шаблон, который делает `Localize --missing`, включает всё: объекты (помечены id и полем, у способностей ещё уровень:
`A02S aub1/3`). Не локализуются только декорации/разрушаемые (w3b/w3d — они в `TheWar.w3x`) и текстуры с надписями.

**Скрипт (сообщения, мультиборд, подписи магазинов) не локализуется — тексты в Wurst-коде просто английские.**
Пробовали через `TRIGSTR_N` в `war3map.j`: одиночный литерал игра резолвит (мультиборд, `print("…")`), а склейка
`"TRIGSTR_N" + n.toString()` даёт пустую строку (таймер «Раунд», текстаги) — видимо, подстановка идёт по готовой строке
в нативе, а не по литералу при загрузке. Поэтому в скрипте кириллицу не писать.

Десинхрона это не даёт (тот же механизм, что у GUI-триггеров: текст подставляется каждому клиенту локально и идёт только
в вывод), но правило: локализованная строка (литерал с кириллицей, имя/подсказка юнита) — только для показа. В логике
сравнивать id и числа, не текст (`GetUnitName(u) == "…"`, `StringHash` от такого текста — разные значения у клиентов с
разным языком).

## Известное

- В карте изначально была битая ссылка на `war3mapImported\Christmas Tree.mdl` (файла нет) — оставлена как была.
