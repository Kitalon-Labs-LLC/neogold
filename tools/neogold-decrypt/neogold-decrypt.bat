@echo off
setlocal
set DIR=%~dp0
call "%DIR%..\..\android\gradlew.bat" -p "%DIR%..\..\android" :neogold-decrypt:run --args="%*"
