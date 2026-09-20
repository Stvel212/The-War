@echo off
rem Релизная сборка карты: компилятор Wurst напрямую, с оптимизациями (-inline -opt -localOptimizations),
rem которые grill build не включает. Кэш _build\cache чистим: иначе Wurst кладёт в него уже
rem инжектированные объекты и на следующей сборке добавляет модификации поверх (файл растёт).
setlocal
set ROOT=%~dp0
set ROOT=%ROOT:~0,-1%
set WURST=%USERPROFILE%\.wurst
if exist "%ROOT%\_build\cache" rmdir /s /q "%ROOT%\_build\cache"
rem wurst\war3map.j - копия скрипта карты, которую делает компилятор; если файл уже есть, компилятор берёт его и не обновляет
if exist "%ROOT%\wurst\war3map.j" del /q "%ROOT%\wurst\war3map.j"
if not exist "%ROOT%\_build\dependencies\wurstStdlib2" call "%WURST%\grill.cmd" install
"%WURST%\wurst-runtime\bin\java.exe" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Xmx4G -jar "%WURST%\wurst-compiler\wurstscript.jar" ^
  -out "%ROOT%\_build\grill\output.j" "%ROOT%\_build\common.j" "%ROOT%\_build\blizzard.j" "%ROOT%\wurst" ^
  -lib "%ROOT%\_build\dependencies\wurstStdlib2" -lib "%ROOT%\_build\dependencies\wurst-table-layout" ^
  -build -workspaceroot "%ROOT%" -inputmap "%ROOT%\TheWar.w3x" ^
  -runcompiletimefunctions -injectobjects -stacktraces -inline -opt -localOptimizations %*
if errorlevel 1 exit /b 1
rem Локализация: имена/подсказки юнитов -> TRIGSTR + war3map.wts (ru) + _Locales\<locale>.w3mod\war3map.wts из locales\<locale>.txt
javac -encoding UTF-8 -d "%ROOT%\_build\tools" "%ROOT%\tools\Localize.java" || exit /b 1
"%WURST%\wurst-runtime\bin\java.exe" -Dfile.encoding=UTF-8 -cp "%ROOT%\_build\tools;%WURST%\wurst-compiler\wurstscript.jar" Localize "%ROOT%\_build\TheWarReborn.w3x" "%ROOT%\locales" || exit /b 1
rem Только классическая графика: w3i -> формат Reforged (31) с «поддерживаемые режимы = SD»
javac -encoding UTF-8 -d "%ROOT%\_build\tools" "%ROOT%\tools\W3iModes.java" || exit /b 1
"%WURST%\wurst-runtime\bin\java.exe" -cp "%ROOT%\_build\tools;%WURST%\wurst-compiler\wurstscript.jar" W3iModes "%ROOT%\_build\TheWarReborn.w3x" sd || exit /b 1
endlocal
