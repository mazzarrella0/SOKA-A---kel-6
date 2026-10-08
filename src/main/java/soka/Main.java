package soka;

import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.vms.Vm;
import soka.algorithm.DatacenterBrokerDRRHA;
import soka.dataset.GoCJDatasetReader;
import soka.metrics.MultiObjectiveEvaluator;
import soka.metrics.ResultReporter;
import soka.metrics.TaskLengthDistribution;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Orkestrasi simulasi DRRHA sesuai Draft Design: untuk tiap skenario di
 * config.properties (task.counts), bangun infrastruktur baru (Bagian 2),
 * muat dataset GoCJ (Bagian 1.2), jalankan DRRHA (per-VM scheduler), lalu
 * hitung & simpan kelima metrik (Bagian 4) + skor multi-objective (Bagian 3).
 */
public class Main {

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
        System.out.println("Skenario yang akan dijalankan: " + cfg.getString("task.counts"));
        System.out.println("Jumlah pengulangan per dataset: " + repeatCount);

        for (int numJobs : scenarios) {
            runScenario(cfg, numJobs, repeatCount, wMakespan, wEnergy, wUtilization);
        }
    }

    private static void runScenario(Config cfg, int numJobs, int repeatCount,
                                     double wMakespan, double wEnergy, double wUtilization) throws Exception {
        System.out.println("\n================================================");
        System.out.println(" SKENARIO: " + numJobs + " task (DRRHA)");
        System.out.println("================================================");

        List<Cloudlet> cloudlets = new GoCJDatasetReader().readScenario(numJobs);
        TaskLengthDistribution taskDistribution = TaskLengthDistribution.from(cloudlets);
        System.out.printf(Locale.US,
                "Median panjang task=%.1f MI | Pendek=%d (%.2f%%) | Panjang=%d (%.2f%%)%n",
                taskDistribution.getMedianLength(), taskDistribution.getShortTaskCount(),
                taskDistribution.getShortTaskPercent(), taskDistribution.getLongTaskCount(),
                taskDistribution.getLongTaskPercent());

        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        MultiObjectiveEvaluator.Evaluation firstEvaluation = null;
        MultiObjectiveEvaluator.Evaluation evaluation = null;
        List<Cloudlet> finished = List.of();

        for (int run = 1; run <= repeatCount; run++) {
            CloudSimPlus simulation = new CloudSimPlus();
            InfraBuilder infra = new InfraBuilder(cfg);
            Datacenter datacenter = infra.buildDatacenter(simulation);

            DatacenterBrokerDRRHA broker = new DatacenterBrokerDRRHA(simulation);
            List<Vm> vms = infra.buildVms();
            broker.submitVmList(vms);
            broker.submitCloudletList(new GoCJDatasetReader().readScenario(numJobs));

            simulation.start();
            finished = broker.getCloudletFinishedList();
            evaluation = evaluator.evaluate(finished, datacenter.getHostList(),
                    wMakespan, wEnergy, wUtilization);

            int failed = cloudlets.size() - finished.size();
            System.out.printf("%nRun %d/%d - Cloudlet selesai: %d / %d%s%n",
                    run, repeatCount, finished.size(), cloudlets.size(),
                    failed == 0 ? "  -> OK" : "  -> PERINGATAN: " + failed + " cloudlet tidak selesai");

            if (firstEvaluation == null) {
                firstEvaluation = evaluation;
            } else {
                verifySameObjective(numJobs, firstEvaluation, evaluation, run);
            }

            if (run == repeatCount) {
                infra.printPlacement(vms);
                new CloudletsTableBuilder(finished).build();
            }
        }

        if (repeatCount > 1) {
            System.out.printf(Locale.US, "Konsistensi objective: %d run identik; Score(F)=%.8f%n",
                    repeatCount, evaluation.getWeightedScore());
        }

        System.out.printf(Locale.US,
                "Makespan=%.3f s | Energy=%.3f Wh | Utilization=%.2f%% | AvgWaitingTime=%.3f s | "
                        + "LoadBalancingDegree=%.4f | SLA violation=%d/%d | Score(F)=%.8f%n",
                evaluation.getMakespan(), evaluation.getEnergyWh(), evaluation.getUtilization() * 100,
                evaluation.getAvgWaitingTime(), evaluation.getLoadBalancingDegree(),
                evaluation.getSlaViolations(), evaluation.getTotalCloudlets(), evaluation.getWeightedScore());

        ResultReporter reporter = new ResultReporter();
        Path cloudletsCsv = Paths.get("results", "drrha-" + numJobs + "-cloudlets.csv");
        Path metricsCsv = Paths.get("results", "drrha-" + numJobs + "-metrics.csv");
        reporter.writeCloudletsCsv(finished, cloudletsCsv);
        reporter.writeEvaluationCsv(evaluation, taskDistribution, metricsCsv);
        System.out.println("CSV detail cloudlet : " + cloudletsCsv);
        System.out.println("CSV metrik          : " + metricsCsv);
    }

    private static void verifySameObjective(int numJobs,
                                            MultiObjectiveEvaluator.Evaluation expected,
                                            MultiObjectiveEvaluator.Evaluation actual,
                                            int run) {
        boolean identical = Double.compare(expected.getMakespan(), actual.getMakespan()) == 0
                && Double.compare(expected.getEnergyWh(), actual.getEnergyWh()) == 0
                && Double.compare(expected.getUtilization(), actual.getUtilization()) == 0
                && Double.compare(expected.getWeightedScore(), actual.getWeightedScore()) == 0
                && expected.getSlaViolations() == actual.getSlaViolations()
                && expected.getTotalCloudlets() == actual.getTotalCloudlets();
        if (!identical) {
            throw new IllegalStateException(String.format(Locale.US,
                    "Objective tidak konsisten untuk dataset %d task: run pertama F=%.8f, run %d F=%.8f",
                    numJobs, expected.getWeightedScore(), run, actual.getWeightedScore()));
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
}
