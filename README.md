# Simulasi DRRHA pada Cloud Computing

| No  | Nama                            | NRP        |
| --- | ----------------------          | ---------- |
| 1   | Erlangga Valdhio Putra Sulistio | 5027241030 |
| 2   | Raihan Fahri Ghazali            | 5027241061 |
| 3   | Ahmad Yafi Ar Rizq              | 5027241166 |
| 2   | Aslam Ahmad Usman               | 5027241074 |
| 3   | Az Zahrra Tasya                 | 5027241187 |

## Deskripsi

Proyek ini merupakan simulasi penjadwalan task pada lingkungan cloud computing menggunakan **Dynamic Round-Robin Heuristic Algorithm (DRRHA)**.

Simulasi dibuat menggunakan **CloudSim Plus** dengan beberapa Host dan Virtual Machine (VM) yang memiliki kapasitas berbeda. Task dari dataset GoCJ kemudian dijadwalkan ke VM menggunakan quantum waktu yang dihitung secara dinamis.

## Tujuan Tugas

Proyek ini bertujuan untuk:

- memahami penerapan algoritma penjadwalan pada cloud computing;
- mengimplementasikan DRRHA pada lingkungan simulasi CloudSim Plus;
- menggunakan dynamic time quantum untuk membagi waktu eksekusi task;
- menerapkan preemption pada task yang belum selesai;
- mengamati performa penjadwalan berdasarkan beberapa metrik.

## Gambaran Simulasi

Alur simulasi secara umum adalah:

1. Membuat satu datacenter dengan beberapa Host heterogen.
2. Membuat delapan VM dengan kapasitas rendah, menengah, dan tinggi.
3. Membaca panjang task dari dataset GoCJ.
4. Mengurutkan task berdasarkan sisa panjang eksekusinya.
5. Menghitung quantum secara dinamis berdasarkan rata-rata sisa panjang task.
6. Menjalankan task pada VM dan melakukan preemption jika quantum telah habis.
7. Menghitung hasil simulasi setelah seluruh task selesai.

## Konfigurasi Infrastruktur

Simulasi menggunakan satu datacenter dengan empat Host heterogen. Setiap PE memiliki kapasitas `4000 MIPS` agar dapat menampung VM dengan kapasitas tertinggi.

| Host | Jumlah PE | MIPS per PE | RAM | Bandwidth | Storage |
|---|---:|---:|---:|---:|---:|
| Host 1 | 4 | 4000 | 8192 MB | 8000 Mbps | 500000 MB |
| Host 2 | 8 | 4000 | 16384 MB | 8000 Mbps | 1000000 MB |
| Host 3 | 4 | 4000 | 16384 MB | 8000 Mbps | 1000000 MB |
| Host 4 | 8 | 4000 | 32768 MB | 8000 Mbps | 2000000 MB |

## Konfigurasi Virtual Machine

Terdapat delapan VM yang dibagi menjadi tiga kelompok kapasitas. Semua VM menggunakan scheduler DRRHA dan memiliki bandwidth `1000 Mbps` serta storage `10000 MB`.

| Kelompok VM | Jumlah VM | MIPS | PE | RAM |
|---|---:|---:|---:|---:|
| Kapasitas rendah | 2 | 1000 | 1 | 2048 MB |
| Kapasitas menengah | 4 | 2000 | 2 | 4096 MB |
| Kapasitas tinggi | 2 | 4000 | 4 | 8192 MB |

## Parameter Simulasi

| Parameter | Nilai |
|---|---|
| Dataset default | `GoCJ_Dataset_1000.txt` |
| Dataset alternatif | `GoCJ_Dataset_500.txt` |
| PE setiap Cloudlet | 1 |
| Quantum DRRHA | Rata-rata remaining length task |
| Urutan task | Remaining length terkecil terlebih dahulu |
| Preemption | Aktif saat quantum berakhir |
| Context-switch delay | 0 detik |
| Bobot makespan | 0.4 |
| Bobot energi | 0.3 |
| Bobot utilisasi | 0.3 |
| Power model Host | 150 W idle, 250 W maksimum |

Nilai power model digunakan sebagai asumsi simulasi, bukan pengukuran langsung dari perangkat keras.

## Metrik yang Dihasilkan

Simulasi menghasilkan beberapa metrik utama:

- **Makespan**: waktu yang dibutuhkan sampai seluruh task selesai.
- **Energy Consumption**: perkiraan energi yang digunakan Host selama simulasi.
- **Resource Utilization**: tingkat pemanfaatan CPU Host.
- **Weighted-sum Score**: nilai gabungan dari makespan, energi, dan utilisasi.

Selain itu, informasi setiap Cloudlet juga disimpan, seperti status, VM yang digunakan, waktu mulai, waktu selesai, dan waktu eksekusi.

## Struktur Project

```text
src/main/java/soka/
├── Main.java
├── algorithm/
│   ├── CloudletSchedulerDRRHA.java
│   ├── DatacenterBrokerDRRHA.java
│   └── DRRHAEngine.java
├── config/
│   ├── DatacenterFactory.java
│   └── VmFactory.java
├── dataset/
│   └── GoCJDatasetReader.java
└── metrics/
		├── MultiObjectiveEvaluator.java
		└── ResultReporter.java

src/main/resource/
├── GoCJ_Dataset_500.txt
└── GoCJ_Dataset_1000.txt
```

## Persyaratan

- Java 11 atau versi yang lebih baru
- CloudSim Plus 8.5.4
- Maven, jika ingin menggunakan proses build Maven

## Menjalankan Simulasi

Jalankan perintah berikut dari folder utama project menggunakan PowerShell:

```powershell
$jar = "$env:USERPROFILE\.m2\repository\org\cloudsimplus\cloudsimplus\8.5.4\cloudsimplus-8.5.4.jar"

javac -cp $jar -d target\classes `
	(Get-ChildItem src\main\java -Recurse -Filter *.java |
	Select-Object -ExpandProperty FullName)

$jars = Get-ChildItem "$env:USERPROFILE\.m2\repository" -Recurse -Filter *.jar |
	Where-Object { $_.Name -notmatch 'sources|javadoc' } |
	ForEach-Object { $_.FullName }

java -cp ((@('target\classes', 'src\main\resource') + $jars) -join ';') soka.Main
```

Dataset yang digunakan secara default adalah `GoCJ_Dataset_1000.txt`. Dataset dapat diganti melalui konstanta `DATASET_RESOURCE` pada `Main.java`.

## Hasil Simulasi

Hasil simulasi disimpan di folder berikut:

```text
target/results/drrha-results.csv
target/results/drrha-metrics.csv
```

File `drrha-results.csv` berisi hasil setiap Cloudlet, sedangkan `drrha-metrics.csv` berisi metrik agregat simulasi.

## Referensi

1. Abraham, O. L., Ngadi, M. A. B., Sharif, J. B. M., & Sidik, M. K. M. (2025). *Multi-Objective Optimization Techniques in Cloud Task Scheduling: A Systematic Literature Review*. IEEE Access, 13, 12255–12291.
2. Awad, W. K., Zainol Ariffin, K. A., Ahmad Nazri, M. Z., & Yassen, E. T. (2025). *Resource Allocation Strategies and Task Scheduling Algorithms for Cloud Computing: A Systematic Literature Review*. Journal of Intelligent Systems, 34(1).
3. Houssein, E. H., Gad, A. G., Wazery, Y. M., & Suganthan, P. N. (2021). *Task Scheduling in Cloud Computing based on Meta-heuristics: Review, Taxonomy, Open Challenges, and Future Trends*. Swarm and Evolutionary Computation, 62, 100841.

