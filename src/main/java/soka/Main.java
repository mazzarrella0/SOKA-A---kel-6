package soka;

import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;
import soka.algorithm.DatacenterBrokerDRRHA;
import soka.config.DatacenterFactory;
import soka.config.VmFactory;
import soka.dataset.GoCJDatasetReader;
import soka.metrics.MultiObjectiveEvaluator;
import soka.metrics.ResultReporter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;

public class Main {
    private static final String DATASET_RESOURCE = "/GoCJ_Dataset_1000.txt";
    private static final String FALLBACK_WORKLOAD =
            "2000 15000 4000 30000 8000 50000 1000 20000 6000 40000";

    public static void main(String[] args) throws Exception {
        CloudSimPlus simulation = new CloudSimPlus();
        Datacenter datacenter = new DatacenterFactory().create(simulation);

        List<Vm> vms = new VmFactory().createAll();
        DatacenterBrokerDRRHA broker = new DatacenterBrokerDRRHA(simulation);
        broker.submitVmList(vms);
        broker.submitCloudletList(readWorkload());

        simulation.start();
        List<Cloudlet> finishedCloudlets = broker.getCloudletFinishedList();
        new CloudletsTableBuilder(finishedCloudlets).build();
        List<Host> hosts = datacenter.getHostList();
        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        MultiObjectiveEvaluator.Evaluation evaluation = evaluator.evaluate(
            finishedCloudlets, hosts, 0.4, 0.3, 0.3);

        ResultReporter reporter = new ResultReporter();
        reporter.writeCloudletsCsv(finishedCloudlets,
            Paths.get("target", "results", "drrha-results.csv"));
        reporter.writeEvaluationCsv(evaluation,
            Paths.get("target", "results", "drrha-metrics.csv"));
        System.out.printf(java.util.Locale.US,
            "Metrics: makespan=%.3f s, energy=%.3f Wh, utilization=%.3f, score=%.3f%n",
            evaluation.getMakespan(), evaluation.getEnergyWh(),
            evaluation.getUtilization(), evaluation.getWeightedScore());
        System.out.println("CSV result: target/results/drrha-results.csv");
        System.out.println("CSV metrics: target/results/drrha-metrics.csv");
    }

    private static List<Cloudlet> readWorkload() throws Exception {
        InputStream stream = Main.class.getResourceAsStream(DATASET_RESOURCE);
        if (stream == null) {
            return readFallbackWorkload();
        }

        List<Cloudlet> cloudlets = new GoCJDatasetReader().read(
                new InputStreamReader(stream, StandardCharsets.UTF_8), 1000);
        return cloudlets.isEmpty() ? readFallbackWorkload() : cloudlets;
    }

    private static List<Cloudlet> readFallbackWorkload() throws Exception {
        return new GoCJDatasetReader().read(
                new InputStreamReader(
                        new ByteArrayInputStream(FALLBACK_WORKLOAD.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8),
                10);
    }
}
