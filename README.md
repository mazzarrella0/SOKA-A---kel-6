# SOKA - DRRHA Cloud Scheduling

Proyek ini mengimplementasikan Dynamic Round Robin Heuristic Algorithm (DRRHA) pada lingkungan CloudSim Plus dengan infrastruktur cloud multi-data-center dan dataset GoCJ. Fokus pengembangan adalah menjaga konsistensi infrastruktur, konfigurasi VM, analisis dataset, serta evaluasi multi-objective yang valid.

## 1. Arsitektur akhir

- 2 data center aktif
- 4 host heterogen total
- 8 VM heterogen total
- Penempatan VM deterministik dan konsisten per skenario

### Data center

- Datacenter 1: Host 1 dan Host 2
- Datacenter 2: Host 3 dan Host 4

### Host

| Host | PE | MIPS/PE | RAM | Storage | BW |
|---|---:|---:|---:|---:|---:|
| Host 1 | 4 | 4000 | 8192 MB | 500000 MB | 1000 Mbps |
| Host 2 | 8 | 4000 | 16384 MB | 1000000 MB | 1000 Mbps |
| Host 3 | 4 | 4000 | 16384 MB | 1000000 MB | 1000 Mbps |
| Host 4 | 8 | 4000 | 32768 MB | 2000000 MB | 1000 Mbps |

### VM

| Kategori | Jumlah | PE/VM | MIPS/PE | RAM |
|---|---:|---:|---:|---:|
| LOW | 2 | 1 | 1000 | 2048 MB |
| MEDIUM | 4 | 2 | 2000 | 4096 MB |
| HIGH | 2 | 4 | 4000 | 8192 MB |

## 2. Penempatan VM

Penempatan VM dihitung secara deterministik dengan first-fit kapasitas-aware atas host terurut, memeriksa agregat PE, RAM, bandwidth, dan storage. Broker memetakan setiap VM ke data center yang ditentukan; allocation policy normal membuat VM pada host target. Snapshot aktual diambil pada event VM-created sebelum broker melepas VM ketika shutdown. Urutan cloudlet tidak memengaruhi mapping.

Mapping aktual terverifikasi (host ID CloudSim lokal per DC; hasil berasal dari [infrastructure CSV](results/drrha-100-infrastructure.csv)):

| DC / Host | VM aktual | Total VM resource | Host capacity |
|---|---|---|---|
| DC1 / H0 | VM0 LOW, VM1 LOW, VM2 MEDIUM | 4 PE, 8192 MB RAM, 300 Mbps, 30000 MB storage | 4 PE, 8192 MB, 1000 Mbps, 500000 MB |
| DC1 / H1 | VM3 MEDIUM, VM4 MEDIUM, VM5 MEDIUM | 6 PE, 12288 MB RAM, 300 Mbps, 30000 MB storage | 8 PE, 16384 MB, 1000 Mbps, 1000000 MB |
| DC2 / H0 | VM6 HIGH | 4 PE, 8192 MB RAM, 100 Mbps, 10000 MB storage | 4 PE, 16384 MB, 1000 Mbps, 1000000 MB |
| DC2 / H1 | VM7 HIGH | 4 PE, 8192 MB RAM, 100 Mbps, 10000 MB storage | 8 PE, 32768 MB, 1000 Mbps, 2000000 MB |

## 3. DRRHA dan formula quantum

Formula yang dipertahankan sesuai desain proyek adalah:

QT_ij = (M / 2) + (M / 2) / BT_ij

dengan:

- M = rata-rata burst time task di ready queue
- BT_ij = remaining burst time task yang sedang diproses
- quantum minimum = 1.0

Penanganan nol dan negatif dijaga untuk mencegah stuck dan negative remaining time. Scheduler menempatkan task kembali ke antrean jika remaining burst > 0 setelah slice quantum selesai.

## 4. Dataset GoCJ dan statistik

Dataset dibaca dari folder resource dan dihitung statistik berikut:

- task count
- min, max, mean, median, std dev
- short task count, long task count
- short task percentage, long task percentage
- kuartil pertama dan ketiga (interpolasi linear posisi $p(n-1)$)
- histogram 10 bin equal-width

Sumber input ialah `src/main/resources/dataset/GoCJ_Dataset_<N>.txt`, satu panjang task MI positif per baris. Reader mengabaikan baris kosong dan nilai nonpositif, lalu membuat satu Cloudlet 1-PE per panjang valid. Dataset dibaca ulang untuk setiap run; SHA-256 barisan panjang task ditulis agar identitas input bisa diperiksa. Klasifikasi operasional berdasarkan median dataset: pendek `<= median`, panjang `> median`; kategori saling lepas dan lengkap. Bin histogram terakhir mencakup nilai maksimum.

Statistik semua dataset aktual dapat dilihat di `results/drrha-<N>-dataset-statistics.csv`; count histogram ada di `results/drrha-<N>-task-distribution.csv`. Persentase short dan long selalu berasal dari input yang sama dan berjumlah 100%.

## 5. Metode evaluasi dan objective score

Objective score mengikuti formula:

F = w1 * (makespan / reference_makespan) + w2 * (energy / reference_energy) + w3 * (1 - utilization)

dengan bobot:

- w1 = 0.4 makespan
- w2 = 0.3 energy
- w3 = 0.3 utilization

Semua bobot dijumlahkan menjadi 1.0. Untuk setiap dataset dan konfigurasi infrastruktur, reference_makespan adalah total MI dibagi kapasitas MIPS agregat host (detik); reference_energy adalah total daya puncak host dikalikan reference_makespan dan dibagi 3600 (Wh). Nilai referensi yang sama dipakai pada seluruh pengulangan skenario. Ini baseline analitis/lower bound, bukan hasil run, dan rasio tidak dipotong pada 1 sehingga score boleh lebih dari 1. File metrik mencantumkan kedua rasio dan bobot untuk menghitung ulang score.

## 6. Metrik yang dihitung

- Makespan: selisih antara waktu start pertama dan finish terakhir
- Energy: integrasi power model host sepanjang rentang waktu simulasi, satuan Wh
- Utilization: rasio penggunaan sumber daya selama simulasi
- Waiting time: rata-rata `finish_time - arrival_time - total_CPU_execution_time`; model eksperimen menganggap semua task tersedia pada $t=0$
- Response time: waktu dari arrival $t=0$ sampai start pertama (tersedia per task pada cloudlet CSV)
- Turnaround time: `finish_time - arrival_time`
- Load balancing degree: coefficient of variation beban per VM
- SLA violation: paling banyak satu per task, untuk task gagal, canceled, belum selesai, atau melampaui deadline
- Objective score: gabungan output normalisasi dan bobot

Definisi SLA tetap untuk seluruh ukuran workload: arrival $=0$, ideal execution time $=L_{MI}/MIPS_{VM}$ untuk Cloudlet 1 PE, deadline $=3 \times ideal$, completion time adalah CloudSim finish time. `finish > deadline` melanggar; task failed/canceled/unfinished/unassigned juga melanggar sekali. Cloudlet CSV menyimpan ideal time, deadline, completion, lateness dan reason. Deadline sengaja tidak dinaikkan untuk mengurangi count. Pada dataset besar, broker membagi cloudlet ke hanya 8 VM sehingga antrean membuat hampir semua deadline tiga-kali-ideal terlampaui; angka tinggi bukan berarti count melebihi workload atau task dievaluasi ganda.

Run dengan task yang belum selesai berstatus `PARTIAL`; status cloudlet diekspor dan setiap task gagal/tak selesai dihitung sekali sebagai pelanggaran SLA. Run summary mencatat submitted, finished, failed, unfinished, error reason, hash input, hash mapping dan hash konfigurasi. Ringkasan skenario menggunakan mean dan sample standard deviation dari tiga run; jumlah violation antar-run tidak ditafsirkan sebagai task unik.

## 7. Eksperimen dan pengulangan

Konfigurasi default di [src/main/resources/config.properties](src/main/resources/config.properties) memakai:

- task.counts = 100,200,300,400,500,600,700,800,900,1000,2000,3000
- simulation.repeat.count = 3

Setiap skenario dijalankan 3 kali dengan dataset dan VM mapping yang sama. Untuk smoke test kecil, properti JVM dapat membatasi skenario tanpa mengubah konfigurasi permanen, misalnya `-Dtask.counts=100 -Dsimulation.repeat.count=3`.

### Hasil eksperimen aktual

Tabel berikut disalin dari CSV agregat setelah semua skenario dijalankan ulang: makespan detik, energy Wh, utilization rasio dan weighted score. Tiga status run pada setiap skenario `SUCCESS`, task selesai seluruhnya, dan ketiga pasangan run mempunyai input/mapping/task outcome/metrics identik (`drrha-<N>-reproducibility.csv`).

| Tasks | Mean MI | Short / long (%) | Makespan (s) | Energy (Wh) | Utilization | SLA mean / rate | Objective F |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 135095.00 | 55.00 / 45.00 | 1580.774 | 208.508 | 0.34360 | 78 / 0.78000 | 6.29039 |
| 200 | 136097.50 | 50.50 / 49.50 | 4449.518 | 523.313 | 0.30647 | 186 / 0.93000 | 8.47855 |
| 300 | 138420.00 | 50.33 / 49.67 | 6660.010 | 818.849 | 0.27512 | 287 / 0.95667 | 8.42058 |
| 400 | 129673.75 | 51.25 / 48.75 | 6600.254 | 836.817 | 0.34435 | 390 / 0.97500 | 6.75567 |
| 500 | 130003.00 | 50.80 / 49.20 | 7598.370 | 1008.246 | 0.35789 | 491 / 0.98200 | 6.28960 |
| 600 | 137235.00 | 51.67 / 48.33 | 10059.186 | 1334.860 | 0.34103 | 592 / 0.98667 | 6.56962 |
| 700 | 124990.71 | 51.86 / 48.14 | 9699.489 | 1327.782 | 0.37794 | 694 / 0.99143 | 6.01705 |
| 800 | 123697.50 | 50.50 / 49.50 | 14119.590 | 1781.232 | 0.30077 | 795 / 0.99375 | 7.55501 |
| 900 | 133385.56 | 50.11 / 49.89 | 15929.331 | 2046.443 | 0.32015 | 893 / 0.99222 | 7.06678 |
| 1000 | 129662.00 | 52.10 / 47.90 | 15897.483 | 2057.270 | 0.35595 | 990 / 0.99000 | 6.54636 |
| 2000 | 130367.75 | 50.25 / 49.75 | 34718.341 | 4425.639 | 0.32579 | 1997 / 0.99850 | 7.07526 |
| 3000 | 127974.67 | 52.00 / 48.00 | 44911.727 | 6005.122 | 0.34928 | 2996 / 0.99867 | 6.30898 |

Macro-average tanpa bobot lintas 12 ukuran dataset (bukan score sebuah run gabungan): makespan 14352.006 s, energy 1864.507 Wh, utilization 0.33320, waiting 2306.372 s, SLA rate 0.96458, score macro 6.94782. Dataset berbeda mempunyai normalisasi dan jumlah task berbeda; gunakan hasil per-dataset untuk analisis utama.

Persentase short task teramati 50.11–55.00%, sedangkan SLA rate 0.780–0.99867. Ini hanya deskripsi hasil: jumlah task, total kerja, dan antrean berubah serentak, sehingga eksperimen ini tidak membuktikan hubungan kausal antara komposisi kelas dan SLA/makespan.

## 8. Struktur proyek

- [src/main/java/soka/Main.java](src/main/java/soka/Main.java)
- [src/main/java/soka/InfraBuilder.java](src/main/java/soka/InfraBuilder.java)
- [src/main/java/soka/algorithm/DRRHAEngine.java](src/main/java/soka/algorithm/DRRHAEngine.java)
- [src/main/java/soka/algorithm/CloudletSchedulerDRRHA.java](src/main/java/soka/algorithm/CloudletSchedulerDRRHA.java)
- [src/main/java/soka/algorithm/DatacenterBrokerDRRHA.java](src/main/java/soka/algorithm/DatacenterBrokerDRRHA.java)
- [src/main/java/soka/metrics/MultiObjectiveEvaluator.java](src/main/java/soka/metrics/MultiObjectiveEvaluator.java)
- [src/main/java/soka/metrics/TaskLengthDistribution.java](src/main/java/soka/metrics/TaskLengthDistribution.java)
- [src/main/java/soka/metrics/ResultReporter.java](src/main/java/soka/metrics/ResultReporter.java)
- [src/main/java/soka/dataset/GoCJDatasetReader.java](src/main/java/soka/dataset/GoCJDatasetReader.java)
- [src/main/java/soka/runtime/RealDrrhaScheduler.java](src/main/java/soka/runtime/RealDrrhaScheduler.java)
- [src/main/java/soka/runtime/RuntimeMain.java](src/main/java/soka/runtime/RuntimeMain.java)
- [src/test/java/soka/DRRHARegressionTest.java](src/test/java/soka/DRRHARegressionTest.java)

## 9. Menjalankan proyek

### Build + test

```powershell
.\mvnw.cmd test -q
```

### Jalankan simulasi

```powershell
.\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java '-Dexec.mainClass=soka.Main'
```

### Jalankan Java runtime prototype tanpa CloudSim

```powershell
.\mvnw.cmd -o -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java '-Dexec.mainClass=soka.runtime.RuntimeMain' '-Dexec.args=src/main/resources/dataset/GoCJ_Dataset_100.txt results/runtime-prototype-100.csv'
```

Perintah ini membaca satu MI length per baris, menjalankan kerja hash CPU pada cooperative slices, menulis task metrics dan sidecar `*-summary.csv`. Prototipe memiliki satu worker thread dan tidak dapat mem-preempt thread lewat OS. MI hanya dipetakan ke jumlah batch kerja, bukan instruksi CPU nyata.

Jika wrapper gagal mengambil Maven, cek distribusi lokal di `%USERPROFILE%\.m2\wrapper\dists` dan jalankan `bin\mvn.cmd -o test -q` dari distribusi yang sudah tersedia.

### Output CSV

Semua output eksperimen disimpan di folder [results](results):

- [results/drrha-100-dataset-statistics.csv](results/drrha-100-dataset-statistics.csv)
- [results/drrha-100-infrastructure.csv](results/drrha-100-infrastructure.csv)
- [results/drrha-100-metrics.csv](results/drrha-100-metrics.csv)
- [results/drrha-100-cloudlets.csv](results/drrha-100-cloudlets.csv)
- [results/drrha-100-run-summary.csv](results/drrha-100-run-summary.csv)
- [results/drrha-100-reproducibility.csv](results/drrha-100-reproducibility.csv)
- [results/drrha-experiment-summary.csv](results/drrha-experiment-summary.csv)

## 10. Validasi yang sudah dilakukan

Status audit terbaru (10 Oktober 2026): `mvnw.cmd -o test -q` berhasil dengan exit 0. Entry point `soka.Main` menjalankan 12 dataset × 3 run (36 simulasi), semua 36 berstatus `SUCCESS`, seluruh 31,500 cloudlet selesai (10,500 per putaran), dan 8/8 VM dibuat pada dua DC setiap run. Ada 36/36 pasangan run reproducible; hash input dan mapping identik untuk ukuran workload yang sama. CSV task, metrics, infra, statistik, histogram, tiga file per-run, ringkasan dan reproduksibilitas diregenerasi dari source final. RuntimeMain juga berhasil pada file GoCJ 100 dengan 100 task selesai.

### Selisih proposal dan implementasi

| Aspek | Draft awal yang tercantum pada permintaan | Implementasi final yang diuji |
|---|---|---|
| Datacenter | 1 DC, 4 host heterogen | 2 DC, 2 host per DC; dipilih sesuai revisi dosen minimum 2 DC |
| Host | 4 heterogen | 4 heterogen, total resource tetap sesuai README |
| VM | rancangan sebelumnya berbeda | 8 VM: 2 LOW + 4 MEDIUM + 2 HIGH (spesifikasi final lampiran) |
| Placement | berpotensi berubah/implicit | first-fit deterministik, target DC/host eksplisit dan hash aktual |
| Workloads | 100–1000, tiap 100 | 100–1000, tiap 100, ditambah 2000 dan 3000 |
| Runtime | CloudSim | CloudSim dan Java cooperative prototype dipisah |

Draft dan transkrip dosen asli tidak ditemukan sebagai file terpisah di workspace; tabel proposal merujuk pada isi yang diberikan pada permintaan audit. Perubahan 1 ke 2 DC tidak mengubah total empat host. VM final 2/4/2 mengikuti spesifikasi final lampiran.

## 11. Keterbatasan

- Prototipe di [src/main/java/soka/runtime/RealDrrhaScheduler.java](src/main/java/soka/runtime/RealDrrhaScheduler.java) melakukan cooperative time slicing dan benar-benar menjalankan batch hash CPU yang durasinya diukur dengan `System.nanoTime()`. Satu batch per 1000 MI hanyalah skala kerja prototype; MI dataset adalah estimasi CloudSim dan tidak dapat diperlakukan sebagai instruksi atau waktu CPU nyata. Ini bukan OS-level CPU preemption.
- CloudSim DRRHA mengurutkan waiting queue berdasarkan remaining length (SJF-like order), memakai quantum formula di atas dan mem-preempt cloudlet pada quantum expiry. Ini tidak dengan sendirinya membuktikan mengurangi convoy effect atau mengungguli SJF/FCFS/RR. Benchmark pembanding yang adil belum diimplementasikan/dijalankan, sehingga klaim keunggulan relatif belum dibuat.
- CloudSim tetap merupakan simulator dan bukan lingkungan cloud fisik nyata.
- Hasil eksperimen dapat dijadikan bahan analisis, tetapi pengujian lebih lanjut diperlukan untuk perbandingan algoritma lain seperti SJF atau Round Robin.

## 12. Referensi

- CloudSim Plus 8.5.7
- GoCJ Dataset
- SOKA DRRHA task scheduling project
