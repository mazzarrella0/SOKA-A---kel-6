# SOKA A - Kelompok 6: Simulasi Multi-Objective Task Scheduling (DRRHA) di CloudSim Plus

## Prasyarat (Windows)
1. **JDK 17** (Temurin). Install: `winget install EclipseAdoptium.Temurin.17.JDK`, lalu **tutup & buka ulang terminal**.
2. **Git**.
3. Tidak perlu install Maven kalau file `mvnw.cmd` ada di repo (Maven Wrapper).

Cek: `java -version` harus menampilkan versi 17 atau lebih baru.

## Cara menjalankan
```
git clone <URL_REPO>
cd soka-drrha-cloudsim
run.bat
```
Run pertama mengunduh dependency (1-2 menit, tampak diam). Run berikutnya cepat.

## Hasil yang benar (smoke test)
Muncul tabel 4 cloudlet berstatus SUCCESS dan tulisan `>>> ENVIRONMENT OK <<<`.

## Kalau error
Kirim ke admin: (1) output `java -version`, (2) **seluruh** teks terminal (copy-paste, bukan screenshot sebagian).

Masalah umum:
- `'java' is not recognized` -> JDK belum terinstall atau terminal belum dibuka ulang.
- `release version 17 not supported` -> JDK yang terpakai lebih lama dari 17. Cek `java -version`.
- Gagal download dependency -> cek koneksi internet / VPN kampus.

## Struktur
- `src/main/java/soka/` kode simulasi
- `src/main/resources/config.properties` parameter eksperimen (skenario, bobot, seed, dll)
- `results/` output CSV per run
- `run.bat` satu perintah untuk menjalankan

## Aturan kerja
- `main` hanya berisi kode yang sudah dijalankan admin dan operator.
- Fitur baru di branch terpisah (`feature/...`), merge setelah diverifikasi.
- Versi untuk demo diberi tag `demo-1`.
