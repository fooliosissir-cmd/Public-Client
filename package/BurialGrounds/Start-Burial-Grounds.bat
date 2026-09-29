@echo off
cd /d "%~dp0"
if exist "runtime\bin\javaw.exe" (
  start "" "runtime\bin\javaw.exe" -Xms512m -Xmx2048m -jar "BurialGrounds-Client.jar"
  exit /b 0
)
java -jar "BurialGrounds-Client.jar"
pause
