@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java tidak ditemukan. Install JDK 17, lalu buka ulang terminal.
  exit /b 1
)

echo Menjalankan simulasi... (run pertama mengunduh dependency, tunggu 1-2 menit)
if exist mvnw.cmd (
  call mvnw.cmd -q compile exec:java
) else (
  where mvn >nul 2>nul
  if errorlevel 1 (
    echo [ERROR] mvnw.cmd dan mvn tidak ditemukan. Minta admin repo commit Maven Wrapper.
    exit /b 1
  )
  call mvn -q compile exec:java
)
endlocal
