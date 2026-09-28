package soka;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * SMOKE TEST: hanya memastikan environment (JDK, Maven, CloudSim Plus,
 * config.properties) sudah benar di laptop ini. Nanti diganti dengan
 * orkestrasi simulasi DRRHA yang sebenarnya.
 */
public class Main {

    public static void main(String[] args) throws IOException {
        System.out.println("Java versi: " + System.getProperty("java.version"));

        Properties config = loadConfig();
        System.out.println("Config terbaca. task.counts = " + config.getProperty("task.counts"));

        CloudSimPlus simulation = new CloudSimPlus();

        // 1 Host: 8 PE x 1000 MIPS
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            peList.add(new PeSimple(1000));
        }
        Host host = new HostSimple(8192, 10_000, 1_000_000, peList);
        new DatacenterSimple(simulation, List.of(host));

        DatacenterBroker broker = new DatacenterBrokerSimple(simulation);

        // 2 VM: 4 PE x 1000 MIPS
        List<Vm> vms = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Vm vm = new VmSimple(1000, 4);
            vm.setRam(512).setBw(1000).setSize(10_000);
            vms.add(vm);
        }

        // 4 Cloudlet: 10.000 MI, 2 PE
        List<Cloudlet> cloudlets = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Cloudlet c = new CloudletSimple(10_000, 2, new UtilizationModelFull());
            c.setSizes(1024);
            cloudlets.add(c);
        }

        broker.submitVmList(vms);
        broker.submitCloudletList(cloudlets);

        simulation.start();

        List<Cloudlet> finished = broker.getCloudletFinishedList();
        new CloudletsTableBuilder(finished).build();

        long ok = finished.stream().filter(Cloudlet::isFinished).count();
        System.out.printf("%nSMOKE TEST: %d/%d cloudlet selesai%n", ok, cloudlets.size());
        System.out.println(ok == cloudlets.size()
                ? ">>> ENVIRONMENT OK <<<"
                : ">>> ADA MASALAH, kirim log lengkap ke admin <<<");
    }

    private static Properties loadConfig() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Main.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                throw new IOException("config.properties tidak ditemukan di classpath");
            }
            p.load(in);
        }
        return p;
    }
}
