@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java tidak ditemukan. Install JDK 17, lalu buka ulang terminal.
  exit /b 1
)
java src\main\java\soka\prototype\StandaloneScheduler.java %*
endlocal
