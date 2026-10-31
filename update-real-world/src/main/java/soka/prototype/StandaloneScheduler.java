package soka.prototype;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * REVISI 7 - Prototipe scheduler yang berjalan DI LUAR CloudSim.
 *
 * Java murni (hanya JDK, tanpa Maven/CloudSim). Memodelkan hal yang sama dengan
 * simulasi CloudSim: 8 VM (2x4000, 3x2000, 3x1000 MIPS), semua task masuk di t=0,
 * tiap VM menjalankan SATU task per waktu (1 PE per task), pembagian task ke VM
 * ROUND_ROBIN atau MIPS_AWARE, lalu algoritma per-VM: FCFS, SJF, atau DRRHA.
 *
 * Tujuan: membuktikan logika scheduler (terutama DRRHA) benar tanpa bergantung pada
 * engine CloudSim, dan sebagai pembanding silang hasil CloudSim.
 *
 * Cara jalan (dari root project, tanpa Maven):
 *   javac -d target/prototype src/main/java/soka/prototype/StandaloneScheduler.java
 *   java -cp target/prototype soka.prototype.StandaloneScheduler 100 200 300
 * Tanpa argumen: 100..1000 (interval 100), 2000, 3000.
 *
 * Mode laporan perbandingan (dipakai di akhir Job Kubernetes):
 *   java src/main/java/soka/prototype/StandaloneScheduler.java --compare results/summary.csv
 * Membaca hasil CloudSim dari summary.csv, menghitung ulang dengan prototipe, lalu mencetak
 * laporan teks (infrastruktur, tabel perbandingan, grafik batang ASCII, kesimpulan).
 *
 * Perbedaan yang disengaja terhadap CloudSim: waktu kontinu murni, tidak ada
 * scheduling interval / pembulatan event, tidak ada model energi. Jadi angka makespan
 * boleh beda tipis (selisih kecil) dari CloudSim; urutan perbandingan antar algoritma
 * harus sama.
 */
public final class StandaloneScheduler {

    // Urutan VM sama dengan InfraBuilder.buildVms(): TINGGI x2, MENENGAH x3, RENDAH x3.
    private static final double[] VM_MIPS = {4000, 4000, 2000, 2000, 2000, 1000, 1000, 1000};
    private static final double SLA_FACTOR = 3.0;
    private static final double MIN_QUANTUM = 1.0;

    /** Hasil satu task setelah dijadwalkan. */
    private static final class Task {
        final int id;
        final long length;
        int vm;
        double remaining;
        double finish = -1;
        double executed; // total waktu CPU nyata
        Task(int id, long length) {
            this.id = id;
            this.length = length;
            this.remaining = length;
        }
    }

    private record Result(String policy, String algorithm, double makespan, double avgWait,
                          long slaViolations, double lbd, long contextSwitches, int total) {}

    public static void main(String[] args) throws IOException {
        if (args.length >= 1 && args[0].equals("--compare")) {
            compare(Paths.get(args.length > 1 ? args[1] : "results/summary.csv"));
            return;
        }
        int[] scenarios = args.length == 0
                ? new int[]{100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 2000, 3000}
                : java.util.Arrays.stream(args).mapToInt(Integer::parseInt).toArray();
        Path datasetDir = Paths.get("src", "main", "resources", "dataset");

        for (int n : scenarios) {
            long[] lengths = readDataset(datasetDir.resolve("GoCJ_Dataset_" + n + ".txt"));
            printDistribution(n, lengths);
            System.out.printf(Locale.US, "%-12s %-6s %12s %11s %9s %7s %8s%n",
                    "Broker", "Algo", "Makespan(s)", "AvgWait(s)", "SLA-viol", "LBD", "CtxSw");
            for (String policy : new String[]{"ROUND_ROBIN", "MIPS_AWARE"}) {
                for (String algo : new String[]{"DRRHA", "FCFS", "SJF"}) {
                    Result r = run(lengths, policy, algo);
                    // Verifikasi determinisme: jalankan ulang, hasil harus IDENTIK.
                    for (int rep = 2; rep <= 3; rep++) {
                        Result again = run(lengths, policy, algo);
                        if (Double.compare(r.makespan, again.makespan) != 0
                                || Double.compare(r.avgWait, again.avgWait) != 0
                                || r.slaViolations != again.slaViolations) {
                            throw new IllegalStateException("Hasil tidak konsisten: " + n + " " + policy + " " + algo);
                        }
                    }
                    System.out.printf(Locale.US, "%-12s %-6s %12.3f %11.3f %5d/%-4d %7.4f %8d%n",
                            r.policy, r.algorithm, r.makespan, r.avgWait,
                            r.slaViolations, r.total, r.lbd, r.contextSwitches);
                }
            }
            System.out.println("(3 run per kombinasi: identik)\n");
        }
    }

    // ---------------------------------------------------------------- laporan perbandingan

    /** Cetak laporan teks: hasil CloudSim (summary.csv) vs prototipe di luar CloudSim. */
    private static void compare(Path summaryCsv) throws IOException {
        List<String> lines = Files.readAllLines(summaryCsv);
        String[] header = lines.get(0).trim().split(",");
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.length; i++) col.put(header[i], i);

        Map<Integer, long[]> datasets = new HashMap<>();
        Path datasetDir = Paths.get("src", "main", "resources", "dataset");

        String sep = "=".repeat(100);
        System.out.println();
        System.out.println(sep);
        System.out.println("LAPORAN: SIMULASI CLOUDSIM vs PROTOTIPE DI LUAR CLOUDSIM");
        System.out.println(sep);
        System.out.println("Infrastruktur : 2 datacenter | 8 Host | " + VM_MIPS.length
                + " VM (4 VM per datacenter, 1 VM per Host)");
        System.out.println("Kelompok VM   : 2 tinggi (4000 MIPS) | 3 menengah (2000 MIPS) | 3 rendah (1000 MIPS)");
        System.out.println("Catatan       : prototipe = model Java murni (waktu kontinu, tanpa energi/utilisasi);");
        System.out.println("                bukan pengukuran di cloud sungguhan.");
        System.out.println();
        System.out.printf(Locale.US, "%-6s %-12s %-6s | %10s %10s %7s | %9s %9s | %9s%n",
                "Task", "Broker", "Algo", "MS-CloudSim", "MS-Proto", "Beda%", "Tunggu-CS", "Tunggu-PR", "SLA CS/PR");
        System.out.println("-".repeat(100));

        // data untuk ringkasan
        Map<String, double[]> diffRange = new HashMap<>(); // algo -> {min, max}
        List<String> chartLabels = new ArrayList<>();
        List<Double> chartDrrha = new ArrayList<>();
        List<Double> chartFcfs = new ArrayList<>();
        Map<String, Double> csWait = new LinkedHashMap<>();
        Map<String, Double> prWait = new LinkedHashMap<>();
        int maxSlaGap = 0;
        int rowCount = 0;

        for (int r = 1; r < lines.size(); r++) {
            if (lines.get(r).isBlank()) continue;
            String[] f = lines.get(r).trim().split(",");
            int n = Integer.parseInt(f[col.get("tasks")]);
            String broker = f[col.get("broker")];
            String algo = f[col.get("algorithm")];
            double csMk = Double.parseDouble(f[col.get("makespan_s")]);
            double csW = Double.parseDouble(f[col.get("avg_waiting_s")]);
            int csSla = Integer.parseInt(f[col.get("sla_violations")]);

            long[] lengths = datasets.computeIfAbsent(n, k -> {
                try { return readDataset(datasetDir.resolve("GoCJ_Dataset_" + k + ".txt")); }
                catch (IOException e) { throw new IllegalStateException(e); }
            });
            Result pr = run(lengths, broker, algo);
            double diff = (csMk - pr.makespan) / pr.makespan * 100.0;
            maxSlaGap = Math.max(maxSlaGap, (int) Math.abs(csSla - pr.slaViolations));
            rowCount++;

            System.out.printf(Locale.US, "%-6d %-12s %-6s | %10.1f %10.1f %6.2f%% | %9.1f %9.1f | %4d/%-4d%n",
                    n, broker, algo, csMk, pr.makespan, diff, csW, pr.avgWait, csSla, (int) pr.slaViolations);

            double[] rg = diffRange.computeIfAbsent(algo, k -> new double[]{Double.MAX_VALUE, -Double.MAX_VALUE});
            rg[0] = Math.min(rg[0], diff);
            rg[1] = Math.max(rg[1], diff);
            csWait.put(n + "|" + broker + "|" + algo, csW);
            prWait.put(n + "|" + broker + "|" + algo, pr.avgWait);

            if (broker.equals("ROUND_ROBIN") && (algo.equals("DRRHA") || algo.equals("FCFS"))) {
                String label = String.valueOf(n);
                if (algo.equals("DRRHA")) { chartLabels.add(label); chartDrrha.add(diff); }
                else chartFcfs.add(diff);
            }
        }

        // grafik batang ASCII: selisih makespan CloudSim terhadap prototipe
        System.out.println();
        System.out.println("GRAFIK: selisih makespan CloudSim terhadap prototipe (ROUND_ROBIN), tiap '#' = 0,02%");
        for (int i = 0; i < chartLabels.size(); i++) {
            double d = chartDrrha.get(i);
            double fc = i < chartFcfs.size() ? chartFcfs.get(i) : Double.NaN;
            System.out.printf(Locale.US, "%6s DRRHA %5.2f%% %s%n", chartLabels.get(i), d, "#".repeat((int) Math.round(Math.abs(d) / 0.02)));
            if (!Double.isNaN(fc)) {
                System.out.printf(Locale.US, "%6s FCFS  %5.2f%% %s%n", "", fc, "#".repeat((int) Math.round(Math.abs(fc) / 0.02)));
            }
        }

        // pengurangan waktu tunggu DRRHA vs FCFS
        System.out.println();
        System.out.println("GRAFIK: penurunan waktu tunggu DRRHA dibanding FCFS (ROUND_ROBIN), tiap '#' = 2%");
        double minRed = Double.MAX_VALUE, maxRed = -Double.MAX_VALUE;
        for (String key : csWait.keySet()) {
            if (!key.endsWith("|ROUND_ROBIN|DRRHA")) continue;
            String n = key.substring(0, key.indexOf('|'));
            Double fcs = csWait.get(n + "|ROUND_ROBIN|FCFS");
            Double fpr = prWait.get(n + "|ROUND_ROBIN|FCFS");
            if (fcs == null) continue;
            double redCs = (1 - csWait.get(key) / fcs) * 100;
            double redPr = (1 - prWait.get(key) / fpr) * 100;
            minRed = Math.min(minRed, redCs);
            maxRed = Math.max(maxRed, redCs);
            System.out.printf(Locale.US, "%6s CloudSim %5.1f%% %s%n", n, redCs, "#".repeat((int) Math.round(redCs / 2)));
            System.out.printf(Locale.US, "%6s Prototipe %4.1f%% %s%n", "", redPr, "#".repeat((int) Math.round(redPr / 2)));
        }

        // kesimpulan terhitung dari data (bukan teks tetap)
        System.out.println();
        System.out.println("KESIMPULAN (dihitung dari data di atas, " + rowCount + " baris):");
        for (Map.Entry<String, double[]> e : diffRange.entrySet()) {
            System.out.printf(Locale.US, "- Makespan %-5s: CloudSim berbeda %.2f%% sampai %.2f%% dari prototipe.%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        System.out.println("- Selisih SLA violation CloudSim vs prototipe: paling banyak " + maxSlaGap + " task.");
        if (minRed <= maxRed) {
            System.out.printf(Locale.US, "- Waktu tunggu DRRHA lebih rendah %.0f%% sampai %.0f%% dari FCFS (CloudSim).%n", minRed, maxRed);
        }
        System.out.println("- Selisih kecil kemungkinan karena CloudSim memakai event diskret (dugaan, belum diverifikasi).");
        System.out.println(sep);
    }

    // ---------------------------------------------------------------- dataset

    private static long[] readDataset(Path file) throws IOException {
        List<Long> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (!line.isEmpty() && Long.parseLong(line) > 0) {
                out.add(Long.parseLong(line));
            }
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    private static void printDistribution(int n, long[] lengths) {
        double mean = 0;
        for (long l : lengths) mean += l;
        mean /= lengths.length;
        int shortCount = 0;
        for (long l : lengths) if (l <= mean) shortCount++;
        int longCount = lengths.length - shortCount;
        System.out.printf(Locale.US,
                "=== %d task ===%nRata-rata panjang task=%.1f MI | Pendek=%d (%.2f%%) | Panjang=%d (%.2f%%)%n",
                n, mean, shortCount, shortCount * 100.0 / lengths.length,
                longCount, longCount * 100.0 / lengths.length);
    }

    // ---------------------------------------------------------------- simulasi

    private static Result run(long[] lengths, String policy, String algo) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < lengths.length; i++) tasks.add(new Task(i, lengths[i]));

        // 1) Pembagian task -> VM
        List<List<Task>> perVm = new ArrayList<>();
        for (int v = 0; v < VM_MIPS.length; v++) perVm.add(new ArrayList<>());
        double[] busyUntil = new double[VM_MIPS.length];
        for (Task t : tasks) {
            int chosen;
            if (policy.equals("ROUND_ROBIN")) {
                chosen = t.id % VM_MIPS.length;
            } else { // MIPS_AWARE: Earliest Finish Time, seri -> VM urutan pertama
                chosen = 0;
                double best = Double.MAX_VALUE;
                for (int v = 0; v < VM_MIPS.length; v++) {
                    double f = busyUntil[v] + t.length / VM_MIPS[v];
                    if (f < best) { best = f; chosen = v; }
                }
                busyUntil[chosen] = best;
            }
            t.vm = chosen;
            perVm.get(chosen).add(t);
        }

        // 2) Jalankan algoritma di tiap VM (antrean tiap VM independen)
        long ctx = 0;
        for (int v = 0; v < VM_MIPS.length; v++) {
            switch (algo) {
                case "FCFS" -> runNonPreemptive(perVm.get(v), VM_MIPS[v], false);
                case "SJF" -> runNonPreemptive(perVm.get(v), VM_MIPS[v], true);
                case "DRRHA" -> ctx += runDrrha(perVm.get(v), VM_MIPS[v]);
                default -> throw new IllegalArgumentException(algo);
            }
        }

        // 3) Metrik
        double makespan = 0, wait = 0;
        long sla = 0;
        double[] work = new double[VM_MIPS.length];
        for (Task t : tasks) {
            makespan = Math.max(makespan, t.finish);
            wait += Math.max(0, t.finish - t.executed); // tiba di t=0 -> turnaround = finish
            if (t.finish > SLA_FACTOR * t.length / VM_MIPS[t.vm]) sla++;
            work[t.vm] += t.length;
        }
        double mean = 0;
        for (double w : work) mean += w;
        mean /= work.length;
        double var = 0;
        for (double w : work) var += (w - mean) * (w - mean);
        double lbd = Math.sqrt(var / work.length) / mean;
        return new Result(policy, algo, makespan, wait / tasks.size(), sla, lbd, ctx, tasks.size());
    }

    /** FCFS / SJF non-preemptive. Task pertama yang tiba langsung jalan (sama dengan CloudSim). */
    private static void runNonPreemptive(List<Task> queue, double mips, boolean sjf) {
        if (queue.isEmpty()) return;
        List<Task> order = new ArrayList<>(queue);
        if (sjf && order.size() > 1) {
            List<Task> rest = new ArrayList<>(order.subList(1, order.size()));
            rest.sort((a, b) -> Long.compare(a.length, b.length)); // stabil
            order = new ArrayList<>();
            order.add(queue.get(0));
            order.addAll(rest);
        }
        double clock = 0;
        for (Task t : order) {
            double d = t.length / mips;
            clock += d;
            t.executed = d;
            t.finish = clock;
            t.remaining = 0;
        }
    }

    /**
     * DRRHA per VM:
     *  - ready queue diurutkan sisa burst terkecil (SJF); task pertama yang tiba langsung jalan,
     *  - quantum QT = (M/2) + (M/2)/BT (MI), M = rata-rata sisa burst seluruh task di queue
     *    (termasuk yang sedang jalan), BT = sisa burst task yang sedang jalan,
     *  - kalau sisa <= QT: selesai; kalau tidak: preempt, kembali ke queue, queue diurutkan ulang.
     * @return jumlah context switch (preemption)
     */
    private static long runDrrha(List<Task> queue, double mips) {
        if (queue.isEmpty()) return 0;
        List<Task> waiting = new ArrayList<>(queue.subList(1, queue.size()));
        Task running = queue.get(0);
        double clock = 0;
        long switches = 0;

        while (running != null) {
            waiting.sort((a, b) -> Double.compare(a.remaining, b.remaining)); // stabil
            double sum = running.remaining;
            int count = 1;
            for (Task w : waiting) { sum += w.remaining; count++; }
            double m = sum / count;
            double qt = Math.max(MIN_QUANTUM, m / 2.0 + (m / 2.0) / running.remaining);

            if (running.remaining <= qt) {
                double d = running.remaining / mips;
                clock += d;
                running.executed += d;
                running.remaining = 0;
                running.finish = clock;
                running = waiting.isEmpty() ? null : waiting.remove(0);
            } else {
                double d = qt / mips;
                clock += d;
                running.executed += d;
                running.remaining -= qt;
                switches++;
                waiting.add(running);
                waiting.sort((a, b) -> Double.compare(a.remaining, b.remaining));
                running = waiting.remove(0);
            }
        }
        return switches;
    }
}
