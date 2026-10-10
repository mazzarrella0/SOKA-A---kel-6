package soka;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;
import soka.algorithm.DatacenterBrokerDRRHA;
import soka.dataset.GoCJDatasetReader;
import soka.metrics.MultiObjectiveEvaluator;
import soka.metrics.MultiObjectiveEvaluator.Evaluation;
import soka.metrics.ResultReporter;
import soka.metrics.TaskLengthDistribution;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orkestrasi simulasi. Untuk tiap jumlah task (task.counts), jalankan semua kombinasi
 *   kebijakan broker (broker.policies)  x  algoritma per-VM (compare.algorithms),
 * masing-masing simulation.repeat.count kali (objective harus identik).
 * Setelah semua kombinasi pada satu dataset selesai, makespan dan energi dinormalisasi
 * dengan referensi BERSAMA (nilai terburuk antar kombinasi) lalu skor F dihitung ulang.
 */
public class Main {

    private record ComboResult(String policy, String algorithm, List<Cloudlet> finished, Evaluation raw) {}

    private record SummaryRow(int numJobs, String policy, String algorithm,
                              Evaluation evaluation, TaskLengthDistribution distribution) {}

    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        System.out.println("Java versi: " + System.getProperty("java.version"));

        double wMakespan = cfg.getDouble("weight.makespan");
        double wEnergy = cfg.getDouble("weight.energy");
        double wUtilization = cfg.getDouble("weight.utilization");
        int repeatCount = cfg.getInt("simulation.repeat.count");
        if (repeatCount < 1) {
            throw new IllegalStateException("simulation.repeat.count harus minimal 1");
        }

        int[] scenarios = parseScenarios(cfg.getString("task.counts"));
        List<String> policies = parseList(cfg.getString("broker.policies"));
        List<String> algorithms = parseList(cfg.getString("compare.algorithms"));
        System.out.println("Skenario     : " + cfg.getString("task.counts"));
        System.out.println("Broker       : " + policies);
        System.out.println("Algoritma    : " + algorithms);
        System.out.println("Pengulangan  : " + repeatCount);

        List<SummaryRow> summary = new ArrayList<>();
        for (int numJobs : scenarios) {
            runScenario(cfg, numJobs, policies, algorithms, repeatCount,
                    wMakespan, wEnergy, wUtilization, summary);
        }
        Path summaryCsv = Paths.get("results", "summary.csv");
        writeSummaryCsv(summary, summaryCsv);
        System.out.println("\nRingkasan semua skenario: " + summaryCsv);
    }

    private static void runScenario(Config cfg, int numJobs, List<String> policies, List<String> algorithms,
                                    int repeatCount, double wMakespan, double wEnergy, double wUtilization,
                                    List<SummaryRow> summary) throws Exception {
        System.out.println("\n================================================");
        System.out.println(" SKENARIO: " + numJobs + " task");
        System.out.println("================================================");

        TaskLengthDistribution dist = TaskLengthDistribution.from(new GoCJDatasetReader().readScenario(numJobs));
        System.out.printf(Locale.US,
                "Rata-rata panjang task=%.1f MI | Pendek=%d (%.2f%%) | Panjang=%d (%.2f%%)%n",
                dist.getMeanLength(), dist.getShortTaskCount(), dist.getShortTaskPercent(),
                dist.getLongTaskCount(), dist.getLongTaskPercent());

        List<ComboResult> results = new ArrayList<>();
        boolean first = true;
        for (String policy : policies) {
            for (String algorithm : algorithms) {
                results.add(runCombo(cfg, numJobs, policy, algorithm, repeatCount,
                        wMakespan, wEnergy, wUtilization, first));
                first = false;
            }
        }

        // Referensi normalisasi bersama: nilai terburuk antar semua kombinasi pada dataset ini.
        double refMakespan = 1.0;
        double refEnergy = 1.0;
        for (ComboResult r : results) {
            refMakespan = Math.max(refMakespan, r.raw().getMakespan());
            refEnergy = Math.max(refEnergy, r.raw().getEnergyWh());
        }

        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        ResultReporter reporter = new ResultReporter();
        System.out.printf(Locale.US, "%nHASIL %d task (referensi normalisasi: makespan=%.1f s, energi=%.1f Wh)%n",
                numJobs, refMakespan, refEnergy);
        System.out.printf("%-12s %-8s %12s %10s %8s %10s %7s %12s %10s%n",
                "Broker", "Algo", "Makespan(s)", "Energi(kWh)", "Util(%)", "AvgWait(s)", "LBD", "SLA-viol", "Score(F)");

        for (ComboResult r : results) {
            Evaluation scored = evaluator.rescore(r.raw(), refMakespan, refEnergy,
                    wMakespan, wEnergy, wUtilization);
            String tag = r.algorithm().toLowerCase(Locale.ROOT) + "-"
                    + r.policy().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + numJobs;
            reporter.writeCloudletsCsv(r.finished(), Paths.get("results", tag + "-cloudlets.csv"));
            reporter.writeEvaluationCsv(scored, dist, Paths.get("results", tag + "-metrics.csv"));

            System.out.printf(Locale.US, "%-12s %-8s %12.3f %10.4f %8.2f %10.3f %7.4f %7d/%-4d %10.6f%n",
                    r.policy(), r.algorithm(), scored.getMakespan(), scored.getEnergyWh() / 1000.0,
                    scored.getUtilization() * 100, scored.getAvgWaitingTime(),
                    scored.getLoadBalancingDegree(), scored.getSlaViolations(),
                    scored.getTotalCloudlets(), scored.getWeightedScore());
            summary.add(new SummaryRow(numJobs, r.policy(), r.algorithm(), scored, dist));
        }
    }

    private static ComboResult runCombo(Config cfg, int numJobs, String policy, String algorithm,
                                        int repeatCount, double wMakespan, double wEnergy,
                                        double wUtilization, boolean printPlacement) throws Exception {
        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        Evaluation firstEvaluation = null;
        Evaluation evaluation = null;
        List<Cloudlet> finished = List.of();

        for (int run = 1; run <= repeatCount; run++) {
            CloudSimPlus simulation = new CloudSimPlus();
            InfraBuilder infra = new InfraBuilder(cfg, algorithm);
            infra.buildDatacenters(simulation);

            DatacenterBrokerDRRHA broker = new DatacenterBrokerDRRHA(simulation);
            broker.setDatacenterMapper(infra::mapVmToDatacenter);
            List<Vm> vms = infra.buildVms();
            switch (policy) {
                case "ROUND_ROBIN" -> { /* default broker */ }
                case "MIPS_AWARE" -> broker.useMipsAwareMapping(vms);
                default -> throw new IllegalArgumentException("broker.policies tidak dikenal: " + policy
                        + " (pilihan: ROUND_ROBIN, MIPS_AWARE)");
            }
            broker.submitVmList(vms);
            List<Cloudlet> cloudlets = new GoCJDatasetReader().readScenario(numJobs);
            broker.submitCloudletList(cloudlets);

            simulation.start();
            finished = broker.getCloudletFinishedList();
            evaluation = evaluator.evaluate(finished, infra.allHosts(), wMakespan, wEnergy, wUtilization);

            int failed = cloudlets.size() - finished.size();
            System.out.printf(Locale.US, "[%d task | %s | %s] run %d/%d - selesai %d/%d%s%n",
                    numJobs, policy, algorithm, run, repeatCount, finished.size(), cloudlets.size(),
                    failed == 0 ? "" : "  -> PERINGATAN: " + failed + " cloudlet tidak selesai");

            if (firstEvaluation == null) {
                firstEvaluation = evaluation;
            } else {
                verifySameObjective(numJobs, policy, algorithm, firstEvaluation, evaluation, run);
            }
            if (printPlacement && run == repeatCount) {
                infra.printPlacement(vms);
            }
        }
        if (repeatCount > 1) {
            System.out.printf(Locale.US, "[%d task | %s | %s] konsistensi: %d run identik%n",
                    numJobs, policy, algorithm, repeatCount);
        }
        return new ComboResult(policy, algorithm, finished, evaluation);
    }

    private static void verifySameObjective(int numJobs, String policy, String algorithm,
                                            Evaluation expected, Evaluation actual, int run) {
        boolean identical = Double.compare(expected.getMakespan(), actual.getMakespan()) == 0
                && Double.compare(expected.getEnergyWh(), actual.getEnergyWh()) == 0
                && Double.compare(expected.getUtilization(), actual.getUtilization()) == 0
                && Double.compare(expected.getWeightedScore(), actual.getWeightedScore()) == 0
                && expected.getSlaViolations() == actual.getSlaViolations()
                && expected.getTotalCloudlets() == actual.getTotalCloudlets();
        if (!identical) {
            throw new IllegalStateException(String.format(Locale.US,
                    "Objective tidak konsisten (%d task, %s, %s): run 1 F=%.8f, run %d F=%.8f",
                    numJobs, policy, algorithm, expected.getWeightedScore(), run, actual.getWeightedScore()));
        }
    }

    private static void writeSummaryCsv(List<SummaryRow> rows, Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("tasks,broker,algorithm,makespan_s,energy_kwh,utilization_pct,avg_waiting_s,"
                    + "load_balancing_degree,sla_violations,total_tasks,norm_makespan,norm_energy,"
                    + "score_f,mean_task_mi,short_pct,long_pct");
            w.newLine();
            for (SummaryRow r : rows) {
                Evaluation e = r.evaluation();
                TaskLengthDistribution d = r.distribution();
                w.write(String.format(Locale.US,
                        "%d,%s,%s,%.6f,%.6f,%.4f,%.6f,%.6f,%d,%d,%.6f,%.6f,%.8f,%.1f,%.4f,%.4f",
                        r.numJobs(), r.policy(), r.algorithm(), e.getMakespan(), e.getEnergyWh() / 1000.0,
                        e.getUtilization() * 100, e.getAvgWaitingTime(), e.getLoadBalancingDegree(),
                        e.getSlaViolations(), e.getTotalCloudlets(), e.getNormalizedMakespan(),
                        e.getNormalizedEnergy(), e.getWeightedScore(), d.getMeanLength(),
                        d.getShortTaskPercent(), d.getLongTaskPercent()));
                w.newLine();
            }
        }
    }

    private static int[] parseScenarios(String csv) {
        String[] parts = csv.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private static List<String> parseList(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Daftar kosong di config.properties: " + csv);
        }
        return out;
    }
}
