# tools/

Утилиты сборки и данных карты. Java — JDK 17+, запуск JRE из `~/.wurst/wurst-runtime`
(classpath `wurstscript.jar` нужен тем, кто читает/пишет MPQ).

- `Extract.java` — распаковать `.w3x` (MPQ) в папку через jmpq из `wurstscript.jar`:
  `java -cp "tools;%USERPROFILE%\.wurst\wurst-compiler\wurstscript.jar" Extract map.w3x outdir`
- `Pack.java` — упаковать папку-карту в `.w3x` (MPQ) через jmpq из `wurstscript.jar`, заголовок HM3W берётся
  из карты-шаблона: `java -cp "tools;%USERPROFILE%\.wurst\wurst-compiler\wurstscript.jar" Pack template.w3x folder out.w3x`.
  Так из `backup/TheWar.w3x-folder` был собран `TheWar.w3x`.
  Текущий `TheWar.w3x` (только ландшафт) собран так: из `backup/TheWar.w3x-folder` взяты 15 файлов
  (w3e, shd, wpm, mmp, doo, Units.doo, w3r, w3c, w3i, wts, w3b, w3d, war3map.j, war3mapMap.blp, war3mapPreview.tga),
  остальное ушло в `imports/`.
- `Localize.java` — не одноразовый, вызывается из `build.cmd` после компилятора: локализация объектов (юниты, предметы,
  способности, улучшения, баффы; скрипт — нет, см. README) через
  `TRIGSTR_N` + `war3map.wts` + `_Locales\<locale>.w3mod\war3map.wts` из `locales/<locale>.txt` (см. README.md,
  «Локализация»). `--missing` печатает шаблон непереведённых строк.
- `Blp.java` — PNG ↔ BLP1 (JPEG-сжатие, каналы B,G,R,A, как у текстур редактора): `java -cp tools Blp encode in.png out.blp [w h] [quality]`,
  `decode in.blp out.png`. Так сделан лоадскрин `imports/ui/LoadingScreen.blp` (2048×1024 из 1920×1200-арта, quality 0.9);
  `LoadingScreen.mdx` — квадрат на весь экран с этой текстурой (путь в TEXS-чанке).
- `W3iModes.java` — не одноразовый, вызывается из `build.cmd` после `Localize`: переписывает `war3map.w3i` из формата 25 (TFT)
  в 31 (Reforged) и ставит «поддерживаемые режимы графики» = только SD (классика) — в HD/Reforged-графике карта не запускается
  (`W3iModes карта.w3x sd|hd|both`; `--dump файл.w3i` печатает поля).
- `jf/gen_pve_waves.sh` — таблицы волн PvE/PvEvP `wurst/map/modes/PveWaveTables.wurst` из карты Just Fight 5.6.2:
  `bash tools/jf/gen_pve_waves.sh ".../3082_Just_Fight!_[5.6.2_A1].w3x"`. Читает защищённый скрипт карты по имени
  (`ReadMpq.java`, jmpq из wurstscript.jar), объекты — `grill exportobjects`, разбирает функции-таблицы волн и
  сопоставляет юниты Just Fight юнитам The War (`ext.awk`, `match2.awk`: тот же базовый тип и ближайшее здоровье
  среди юнитов бараков, казарма -> её юнит), характеристики юнитов Just Fight (`stats.awk`: профили stat*),
  пишет пакет (`gen.awk`). Юниты без двойника в The War (другая модель или другие способности — `abcls.awk`,
  `unitabil.awk`) копирует целиком `CopyJfUnits.java` в `wurst/objects/units/UnitsPveJf.wurst` (новые ID ?J??,
  ссылки на улучшения Just Fight очищаются), импорт моделей из корня архива Just Fight — в imports/models (пути текстур —
  `PatchMdxTextures.java`, текстуры в imports/textures). Перезапускать после изменения юнитов бараков.

## migration/

Одноразовые утилиты, которыми карта переводилась из JASS в Wurst-проект. Перевод закончен, скрипт
карты с тех пор правится руками — эти утилиты больше не запускать (`regen_wurst_from_jass.sh` стирает `wurst/map`).

- `Reorg.java` — разложить импорты папочной карты по `models/ textures/ icons/ sounds/ ui/` и переписать
  все ссылки (объекты, скрипт, w3i, текстуры в .mdx), перегенерировать `war3map.imp`. `--apply` применяет.
- `SplitObjects.java` — разбить экспорт `grill exportobjects` на файлы по категориям (раса/герои/предметы…).
- `WtgCats.java` — вытащить из `war3map.wtg` папки триггеров и описания (без TriggerData.txt).
- `JassOrganize.java` — привести `war3map.j` в порядок: разделы по папкам триггеров, заголовки,
  индентация, `0x…`→`'xxxx'`, точечные фиксы утечек.
- `JassToWurst.java` — транслятор отсортированного `war3map.j` в пакеты Wurst (вывод типов для `div`,
  переименование `udg_`, разбиение по папкам триггеров и расам, скелет `war3map.j` для карты).
- `Wurstify.java` — второй проход: нативы → extension-методы stdlib (`u.addAbility()`, `Hash.loadInt()`…),
  `Player(i)`→`players[i]`, `GetLocalPlayer()`→`localPlayer`, `vec2(x.getX(), x.getY())`→`x.getPos()`,
  каскады `..` при создании триггеров.
- `Closurify.java` — одноразовые таймеры с хэш-таблицей → `doAfter(d) -> …` с захватом значений.
- `regen_wurst_from_jass.sh` — полный цикл: транслятор → `wurst/map/` → ручные правки (DamageEvent,
  custom value → Hash) → Wurstify → Closurify → typecheck. Нужен `backup/triggers/war3map.organized.j`
  и `stdnames.txt` (публичные имена stdlib; grep по `_build/dependencies`).
- `ArtifactRefactor.java` — переписывает `wurst/map/Artifacts.wurst` вокруг класса `Artifact`: цепочка
  `ArtefactUp` → таблица `new Artifact(item, abil, maxCharges, gold[, lifeMult, udStep])`, триггеры-касты →
  `Artifact.onCast(abil)`, циклы «заряды предмета в инвентаре» → `Artifact.charges()`, даммик-касты → `Artifact.dummy()`.
  Входит в `regen_wurst_from_jass.sh`.

