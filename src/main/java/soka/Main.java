package soka;

import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;
import soka.algorithm.DatacenterBrokerDRRHA;
import soka.config.DatacenterFactory;
import soka.config.VmFactory;
import soka.dataset.GoCJDatasetReader;
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
        new DatacenterFactory().create(simulation);

        List<Vm> vms = new VmFactory().createAll();
        DatacenterBrokerDRRHA broker = new DatacenterBrokerDRRHA(simulation);
        broker.submitVmList(vms);
        broker.submitCloudletList(readWorkload());

        simulation.start();
        List<Cloudlet> finishedCloudlets = broker.getCloudletFinishedList();
        new CloudletsTableBuilder(finishedCloudlets).build();
        new ResultReporter().writeCloudletsCsv(
            finishedCloudlets,
            Paths.get("target", "results", "drrha-results.csv"));
        System.out.println("CSV result: target/results/drrha-results.csv");
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
