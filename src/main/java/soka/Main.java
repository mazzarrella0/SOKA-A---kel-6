package soka;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * STEP 2 (checkpoint): infrastruktur lengkap + 20 cloudlet dummy.
 * Belum ada DRRHA; scheduling masih bawaan broker. Tujuannya hanya
 * memastikan 4 Host dan 8 VM terbentuk dan semua VM berhasil ditempatkan.
 */
public class Main {

    private static final int DUMMY_CLOUDLETS = 20;

    /**
     * Porsi RAM dan BW VM yang dipakai TIAP cloudlet. Kalau 100% (default UtilizationModelFull),
     * cloudlet ke-2 di VM yang sama tidak kebagian RAM/BW dan tidak pernah jalan.
     * Ini realisasi constraint RAM di proposal (sum RAM cloudlet <= RAM VM).
     */
    private static final double RAM_BW_FRACTION = 0.05;

    public static void main(String[] args) throws IOException {
        Config cfg = new Config();
        System.out.println("Java versi: " + System.getProperty("java.version"));

        CloudSimPlus simulation = new CloudSimPlus();
        InfraBuilder infra = new InfraBuilder(cfg);
        infra.buildDatacenter(simulation);

        DatacenterBroker broker = new DatacenterBrokerSimple(simulation);
        List<Vm> vms = infra.buildVms();
        broker.submitVmList(vms);

        Random rnd = new Random(cfg.getLong("random.seed"));
        List<Cloudlet> cloudlets = new ArrayList<>();
        for (int i = 0; i < DUMMY_CLOUDLETS; i++) {
            long lengthMi = 5_000 + rnd.nextInt(45_000);
            Cloudlet c = new CloudletSimple(lengthMi, 1, new UtilizationModelFull());
            c.setUtilizationModelRam(new UtilizationModelDynamic(RAM_BW_FRACTION));
            c.setUtilizationModelBw(new UtilizationModelDynamic(RAM_BW_FRACTION));
            c.setSizes(1024);
            cloudlets.add(c);
        }
        broker.submitCloudletList(cloudlets);

        simulation.start();

        infra.printPlacement(vms);
        System.out.println("\nVM gagal dibuat : " + broker.getVmFailedList().size());
        int done = broker.getCloudletFinishedList().size();
        System.out.println("Cloudlet selesai: " + done + " / " + DUMMY_CLOUDLETS
                + (done == DUMMY_CLOUDLETS ? "  -> OK" : "  -> PERINGATAN: ada cloudlet yang tidak selesai"));

        new CloudletsTableBuilder(broker.getCloudletFinishedList()).build();
    }
}
