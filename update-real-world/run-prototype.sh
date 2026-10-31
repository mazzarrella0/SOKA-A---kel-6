#!/usr/bin/env bash
# Prototipe scheduler di luar CloudSim (Java murni, tanpa Maven). Contoh: ./run-prototype.sh 100 1000
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then echo "[ERROR] Java tidak ditemukan (butuh JDK 17+)."; exit 1; fi
java src/main/java/soka/prototype/StandaloneScheduler.java "$@"
