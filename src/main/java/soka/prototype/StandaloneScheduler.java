package soka.prototype;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
