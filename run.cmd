@echo off
rem Сборка (build.cmd: компилятор + локализация + w3i) -> копия в Documents\Warcraft III\Maps\Test -> запуск Reforged.
rem Команда расширения VS Code «Wurst: Run map» собирает карту сама, минуя build.cmd: карта из неё без локализации.
rem Используй задачу «Wurst: Run map (TheWar.w3x)» (Terminal -> Run Task) или этот файл.
setlocal
set ROOT=%~dp0
set ROOT=%ROOT:~0,-1%
call "%ROOT%\build.cmd" %*
if errorlevel 1 exit /b 1
set TEST=%USERPROFILE%\Documents\Warcraft III\Maps\Test
if not exist "%TEST%" mkdir "%TEST%"
copy /y "%ROOT%\_build\TheWarReborn.w3x" "%TEST%\TheWarReborn.w3x" >nul || exit /b 1
set WC3=D:\Warcraft III\_retail_\x86_64\Warcraft III.exe
if not exist "%WC3%" set WC3=%ProgramFiles(x86)%\Warcraft III\_retail_\x86_64\Warcraft III.exe
start "" "%WC3%" -launch -loadfile "Maps\Test\TheWarReborn.w3x"
endlocal
