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

        int[] scenarios = parseScenarios(cfg.getString("task.counts"));
        System.out.println("Skenario yang akan dijalankan: " + cfg.getString("task.counts"));

        for (int numJobs : scenarios) {
            runScenario(cfg, numJobs, wMakespan, wEnergy, wUtilization);
        }
    }

    private static void runScenario(Config cfg, int numJobs,
                                     double wMakespan, double wEnergy, double wUtilization) throws Exception {
        System.out.println("\n================================================");
        System.out.println(" SKENARIO: " + numJobs + " task (DRRHA)");
        System.out.println("================================================");

        CloudSimPlus simulation = new CloudSimPlus();
        InfraBuilder infra = new InfraBuilder(cfg);
        Datacenter datacenter = infra.buildDatacenter(simulation);

        DatacenterBrokerDRRHA broker = new DatacenterBrokerDRRHA(simulation);
        List<Vm> vms = infra.buildVms();
        broker.submitVmList(vms);

        List<Cloudlet> cloudlets = new GoCJDatasetReader().readScenario(numJobs);
        broker.submitCloudletList(cloudlets);

        simulation.start();

        infra.printPlacement(vms);

        List<Cloudlet> finished = broker.getCloudletFinishedList();
        new CloudletsTableBuilder(finished).build();

        int failed = cloudlets.size() - finished.size();
        System.out.printf("%nCloudlet selesai: %d / %d%s%n", finished.size(), cloudlets.size(),
                failed == 0 ? "  -> OK" : "  -> PERINGATAN: " + failed + " cloudlet tidak selesai");

        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        MultiObjectiveEvaluator.Evaluation evaluation =
                evaluator.evaluate(finished, datacenter.getHostList(), wMakespan, wEnergy, wUtilization);

        System.out.printf(Locale.US,
                "Makespan=%.3f s | Energy=%.3f Wh | Utilization=%.2f%% | AvgWaitingTime=%.3f s | "
                        + "LoadBalancingDegree=%.4f | SLA violation=%d/%d | Score(F)=%.4f%n",
                evaluation.getMakespan(), evaluation.getEnergyWh(), evaluation.getUtilization() * 100,
                evaluation.getAvgWaitingTime(), evaluation.getLoadBalancingDegree(),
                evaluation.getSlaViolations(), evaluation.getTotalCloudlets(), evaluation.getWeightedScore());

        ResultReporter reporter = new ResultReporter();
        Path cloudletsCsv = Paths.get("results", "drrha-" + numJobs + "-cloudlets.csv");
        Path metricsCsv = Paths.get("results", "drrha-" + numJobs + "-metrics.csv");
        reporter.writeCloudletsCsv(finished, cloudletsCsv);
        reporter.writeEvaluationCsv(evaluation, metricsCsv);
        System.out.println("CSV detail cloudlet : " + cloudletsCsv);
        System.out.println("CSV metrik          : " + metricsCsv);
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
