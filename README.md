# SOKA A - Kelompok 6: Simulasi Multi-Objective Task Scheduling (DRRHA) di CloudSim Plus

| No  | Nama                            | NRP        |
| --- | ----------------------          | ---------- |
| 1   | Erlangga Valdhio Putra Sulistio | 5027241030 |
| 2   | Raihan Fahri Ghazali            | 5027241061 |
| 3   | Ahmad Yafi Ar Rizq              | 5027241066 |
| 2   | Aslam Ahmad Usman               | 5027241074 |
| 3   | Az Zahrra Tasya Adelia          | 5027241087 |

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
| Host 1 | 4 | 4000 | 8192 MB | 1000 Mbps | 500000 MB |
| Host 2 | 8 | 4000 | 16384 MB | 1000 Mbps | 1000000 MB |
| Host 3 | 4 | 4000 | 16384 MB | 1000 Mbps | 1000000 MB |
| Host 4 | 8 | 4000 | 32768 MB | 1000 Mbps | 2000000 MB |

## Konfigurasi Virtual Machine

Terdapat delapan VM yang dibagi menjadi tiga kelompok kapasitas. Semua VM menggunakan scheduler DRRHA dan memiliki bandwidth `100 Mbps` serta storage `10000 MB`.

| Kelompok VM | Jumlah VM | MIPS | PE | RAM |
|---|---:|---:|---:|---:|
| Kapasitas rendah | 3 | 1000 | 1 | 2048 MB |
| Kapasitas menengah | 3 | 2000 | 2 | 4096 MB |
| Kapasitas tinggi | 2 | 4000 | 4 | 8192 MB |

## Parameter Simulasi

| Parameter | Nilai |
|---|---|
| Dataset yang dijalankan | 100, 200, ..., 1000, 2000, dan 3000 task |
| Pengulangan per dataset | 3 kali; objective tiap pengulangan diverifikasi identik |
| Dataset >1000 | Dibangkitkan deterministik dengan seed 42 |
| Kategori task pendek/panjang | Pendek: panjang <= median dataset; panjang: panjang > median |
| PE setiap Cloudlet | 1 |
| Quantum DRRHA | `(Mean / 2) + ((Mean / 2) / RBT)` |
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
- **Task pendek/panjang**: jumlah serta persentase task dihitung terhadap median panjang task pada dataset yang sama.

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

- Java 17 atau versi yang lebih baru
- CloudSim Plus 8.5.7
- Maven, jika ingin menggunakan proses build Maven

## Menjalankan Simulasi

Jalankan perintah berikut dari folder utama project menggunakan PowerShell:

```powershell
git clone <URL_REPO>
cd soka-drrha-cloudsim
run.bat        (di PowerShell: .\run.bat)
```
Run pertama mengunduh dependency (1-2 menit, tampak diam). Run berikutnya cepat.

## Hasil Simulasi

Hasil simulasi disimpan di folder berikut:

```text
results/drrha-<jumlah-task>-cloudlets.csv
results/drrha-<jumlah-task>-metrics.csv
```

File cloudlets berisi hasil tiap task. File metrics menyimpan objective agregat, median panjang task, serta jumlah dan persentase task pendek/panjang.

Konfigurasi `task.counts` dan `simulation.repeat.count` di `src/main/resources/config.properties` mengatur ukuran dataset dan jumlah pengulangan. Program membandingkan makespan, energi, utilisasi, jumlah task, pelanggaran SLA, dan objective score antarrun; perbedaan akan menghentikan proses dengan pesan error.

Dataset `GoCJ_Dataset_2000.txt` dan `GoCJ_Dataset_3000.txt` bisa dibuat dari root project dengan:

```powershell
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=soka.tools.GenerateGoCJDataset" "-Dexec.args=2000 3000"
```

Generator menggunakan seed tetap per ukuran dataset, sehingga hasil dataset yang sama tetap identik saat dibuat ulang.

### Contoh Output Cloudlet

Berikut adalah 10 baris pertama dari hasil dataset 1000 task:

| Cloudlet ID | Status | VM ID | Length (MI) | Finished (MI) | Start (s) | Finish (s) | Execution (s) |
|---:|---|---:|---:|---:|---:|---:|---:|
| 4 | SUCCESS | 4 | 27500 | 27500 | 0.100 | 13.861 | 13.761 |
| 68 | SUCCESS | 4 | 15000 | 15000 | 13.971 | 21.471 | 7.501 |
| 598 | SUCCESS | 6 | 15000 | 15000 | 18.971 | 22.721 | 3.751 |
| 5 | SUCCESS | 5 | 49000 | 49000 | 0.100 | 24.605 | 24.505 |
| 415 | SUCCESS | 7 | 27500 | 27500 | 17.971 | 24.957 | 6.987 |
| 734 | SUCCESS | 6 | 15000 | 15000 | 22.831 | 26.626 | 3.794 |
| 742 | SUCCESS | 6 | 15000 | 15000 | 26.736 | 30.596 | 3.860 |
| 807 | SUCCESS | 7 | 27500 | 27500 | 25.067 | 31.944 | 6.877 |
| 229 | SUCCESS | 5 | 15000 | 15000 | 24.715 | 32.276 | 7.561 |
| 894 | SUCCESS | 6 | 15000 | 15000 | 30.706 | 34.496 | 3.790 |

Baris-baris tersebut ditulis berdasarkan urutan hasil CloudSim, sehingga ID Cloudlet tidak harus berurutan. Status `SUCCESS` menunjukkan task berhasil diselesaikan, sedangkan nilai `Finished (MI)` yang sama dengan `Length (MI)` menunjukkan seluruh instruksi task telah dieksekusi.

### Analisis Hasil

Contoh metrik agregat dari dataset 1000 task:

| Metrik | Nilai |
|---|---:|
| Makespan | 18258.386 detik |
| Energy consumption | 3450.102 Wh |
| Resource utilization | 20.072% |
| Weighted-sum score | 0.939784 |

Makespan menunjukkan waktu simulasi sampai task terakhir selesai. Energy consumption dihitung dari power model Host, sedangkan resource utilization menunjukkan rata-rata pemakaian CPU Host selama simulasi. Nilai utilisasi yang relatif rendah menunjukkan bahwa kapasitas resource yang tersedia lebih besar daripada beban rata-rata workload pada percobaan ini.

Weighted-sum score menggabungkan makespan, energi, dan penalti utilisasi rendah menggunakan bobot `0.4`, `0.3`, dan `0.3`. Nilai ini sebaiknya digunakan untuk membandingkan beberapa algoritma dengan reference normalisasi yang sama; nilainya tidak cukup untuk menyimpulkan kualitas DRRHA jika hanya melihat satu kali percobaan.

---
# Analisis SLA Violation

## 1. Definisi

*SLA violation* adalah kondisi ketika sebuah task selesai **lebih lambat dari batas waktu (deadline) SLA-nya**. Definisi ini mengacu pada constraint ke-4 pada Bagian 5 Draft Design:

```
Completion_Time(task_i) ≤ Deadline_SLA(task_i)
```

Task yang tidak memenuhi syarat tersebut dikategorikan sebagai SLA violation. Draft Design tidak menetapkan nilai deadline secara eksplisit, sehingga cara penentuan deadline pada simulasi ini merupakan **asumsi desain** (parameter eksperimen).

## 2. Perhitungan

Untuk setiap task *i* yang dieksekusi pada VM *j*, deadline ditentukan sebagai kelipatan dari waktu eksekusi ideal task tersebut:

```
waktu_ideal(i) = length_MI(i) / MIPS(VM_j)          (1 PE per cloudlet)
deadline(i)    = SLA_FACTOR × waktu_ideal(i)         (SLA_FACTOR = 3)
violation(i)   = finish_time(i) > deadline(i)
```

Jumlah SLA violation adalah banyaknya task yang memenuhi kondisi `violation(i)`. Seluruh task diasumsikan masuk ke sistem pada t = 0, sehingga `finish_time` dapat dibaca langsung sebagai waktu penyelesaian task sejak masuk.

Nilai `SLA_FACTOR = 3` adalah asumsi: task dianggap wajar apabila selesai dalam waktu paling lama tiga kali waktu eksekusi idealnya. Semakin besar nilainya, semakin longgar deadline.

### Contoh perhitungan (dataset 1.000 task)

| Task | VM | Length (MI) | Waktu ideal (s) | Deadline 3× (s) | Selesai (s) | Hasil |
|---|---|---:|---:|---:|---:|---|
| #3 | VM 3 (2.000 MIPS) | 81.000 | 40,5 | 121,5 | 41,7 | Memenuhi SLA |
| #69 | VM 5 (1.000 MIPS) | 129.000 | 129,0 | 387,0 | 8.514,6 | Melanggar SLA |

Task #3 berada di awal antrean VM-nya sehingga selesai hampir bersamaan dengan waktu ideal. Task #69 harus menunggu sekitar 8.385 detik, jauh melebihi batas toleransi sekitar 258 detik (2 × waktu ideal).

## 3. Penyebab

SLA violation yang tinggi pada simulasi ini disebabkan oleh tiga faktor yang bekerja bersamaan:

1. **Seluruh task masuk sekaligus pada t = 0.** Tidak ada jeda kedatangan, sehingga semua task langsung bersaing pada antrean yang sama.
2. **Setiap VM menjalankan satu task pada satu waktu.** Task pada urutan ke-*k* harus menunggu seluruh task di depannya selesai, sehingga waktu penyelesaiannya jauh melampaui waktu eksekusinya sendiri.
3. **Deadline sebanding dengan panjang task itu sendiri.** Sebuah task hanya aman jika waktu tunggunya tidak lebih dari 2 × waktu ideal-nya (karena waktu tunggu + waktu eksekusi harus ≤ 3 × waktu ideal).

Akibatnya, hanya task yang berada di urutan paling awal pada tiap VM yang memenuhi SLA. Pada dataset 1.000 task hanya 10 task yang memenuhi SLA (990 melanggar). Median waktu selesai task yang melanggar adalah 2.127 detik, sedangkan median deadline-nya hanya 150 detik.

## 4. Pengaruh jumlah task dan algoritma

**Pengaruh jumlah task.** Panjang antrean per VM tumbuh sebanding dengan jumlah task (sekitar 12 task per VM pada 100 task, 125 pada 1.000 task, dan 375 pada 3.000 task). Persentase task yang melanggar ikut meningkat:

| Jumlah task | SLA violation |
|---:|---|
| 100 | 72–78 dari 100 |
| 1.000 | 990 dari 1.000 |
| 3.000 | 2.996 dari 3.000 (99,9%) |

**Pengaruh algoritma.** FCFS, SJF, dan DRRHA memproses total beban kerja yang sama pada VM yang sama. Perbedaan urutan eksekusi hanya mengubah *task mana* yang melanggar, bukan *berapa banyak* yang melanggar. Pada dataset 100 task, jumlah violation FCFS 72, SJF 78, dan DRRHA 78 dari 100.

## 5. Kesimpulan

- Tingginya SLA violation berasal dari **pola beban (seluruh task masuk bersamaan) dan definisi deadline**, bukan semata-mata dari kekurangan algoritma scheduling yang diuji.
- `SLA_FACTOR = 3` merupakan asumsi desain. Perubahan nilai ini akan mengubah jumlah violation, sehingga nilainya perlu dicantumkan secara eksplisit sebagai parameter eksperimen.
- Pada skenario saat ini, metrik SLA kurang mampu membedakan kualitas antar algoritma. Perbandingan algoritma sebaiknya lebih bertumpu pada makespan, energi, utilisasi, dan waktu tunggu.
---

## Screenshot

<img width="1499" height="595" alt="Screenshot 2026-10-02 103311" src="https://github.com/user-attachments/assets/248ccaaa-ed3f-4bb9-bd38-22400d98b278" />

<img width="1337" height="158" alt="Screenshot 2026-10-02 103600" src="https://github.com/user-attachments/assets/ca427810-59a3-4929-8acb-82dfe6a5440c" />


## Referensi

1. Abraham, O. L., Ngadi, M. A. B., Sharif, J. B. M., & Sidik, M. K. M. (2025). *Multi-Objective Optimization Techniques in Cloud Task Scheduling: A Systematic Literature Review*. IEEE Access, 13, 12255–12291.
2. Awad, W. K., Zainol Ariffin, K. A., Ahmad Nazri, M. Z., & Yassen, E. T. (2025). *Resource Allocation Strategies and Task Scheduling Algorithms for Cloud Computing: A Systematic Literature Review*. Journal of Intelligent Systems, 34(1).
3. Houssein, E. H., Gad, A. G., Wazery, Y. M., & Suganthan, P. N. (2021). *Task Scheduling in Cloud Computing based on Meta-heuristics: Review, Taxonomy, Open Challenges, and Future Trends*. Swarm and Evolutionary Computation, 62, 100841.
