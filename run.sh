#!/usr/bin/env bash
# Menjalankan simulasi di Linux / macOS / WSL. Windows: pakai run.bat
set -e
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "[ERROR] Java tidak ditemukan. Install JDK 17 (Ubuntu/Debian: sudo apt install openjdk-17-jdk)."
  exit 1
fi

echo "Menjalankan simulasi... (run pertama mengunduh dependency, tunggu 1-2 menit)"
if [ -f ./mvnw ]; then
  # dipanggil lewat 'sh' supaya tidak bergantung pada permission executable
  sh ./mvnw -q compile exec:java
elif command -v mvn >/dev/null 2>&1; then
  mvn -q compile exec:java
else
  echo "[ERROR] mvnw dan mvn tidak ditemukan. Minta admin repo commit Maven Wrapper."
  exit 1
fi
