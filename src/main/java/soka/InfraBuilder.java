package soka;

import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Membangun infrastruktur sesuai Draft Design (Bagian 2):
 * 1 Datacenter, 4 Host heterogen, 8 VM (rendah / menengah / tinggi).
 */
public final class InfraBuilder {

    /** Metadata desain (Draft 2.1). Dicatat untuk laporan/demo. */
    public static final String VMM = "Xen";
    public static final String OS = "Linux";

    private record HostSpec(int pes, long ramMb, long storageMb, long bwMbps) {}

    private record VmSpec(String name, double mipsPerPe, int pes, long ramMb) {}

    // Draft 2.2 (RAM/storage dalam MB, BW dalam Mbps; 1 Gbps = 1000 Mbps)
    private static final List<HostSpec> HOSTS = List.of(
            new HostSpec(4, 8 * 1024, 500_000, 1000),     // Host 1
            new HostSpec(8, 16 * 1024, 1_000_000, 1000),  // Host 2
            new HostSpec(4, 16 * 1024, 1_000_000, 1000),  // Host 3
            new HostSpec(8, 32 * 1024, 2_000_000, 1000)); // Host 4

    // Draft 2.3. ASUMSI: MIPS dihitung PER PE (semantik CloudSim Plus).
    private static final VmSpec LOW = new VmSpec("RENDAH", 1000, 1, 2 * 1024);
    private static final VmSpec MEDIUM = new VmSpec("MENENGAH", 2000, 2, 4 * 1024);
    private static final VmSpec HIGH = new VmSpec("TINGGI", 4000, 4, 8 * 1024);

    private static final long VM_BW_MBPS = 100;
    private static final long VM_STORAGE_MB = 10_000;

    private final double hostPeMips;
    private final int lowCount;
    private final int mediumCount;
    private final int highCount;
    private final Map<Vm, String> categoryOf = new IdentityHashMap<>();
    /** Diisi oleh listener saat VM dialokasikan ke Host (VM sudah dihancurkan saat simulasi selesai). */
    private final Map<Vm, String> placementOf = new IdentityHashMap<>();

    public InfraBuilder(Config cfg) {
        this.hostPeMips = cfg.getDouble("host.pe.mips");
        this.lowCount = cfg.getInt("vm.count.low");
        this.mediumCount = cfg.getInt("vm.count.medium");
        this.highCount = cfg.getInt("vm.count.high");

        if (hostPeMips < HIGH.mipsPerPe()) {
            throw new IllegalStateException("host.pe.mips (" + hostPeMips
                    + ") lebih kecil dari MIPS VM tertinggi (" + HIGH.mipsPerPe() + ")");
        }
    }

    public Datacenter buildDatacenter(CloudSimPlus simulation) {
        List<Host> hosts = new ArrayList<>();
        for (HostSpec s : HOSTS) {
            List<Pe> pes = new ArrayList<>();
            for (int i = 0; i < s.pes(); i++) {
                pes.add(new PeSimple(hostPeMips));
            }
            hosts.add(new HostSimple(s.ramMb(), s.bwMbps(), s.storageMb(), pes));
        }
        return new DatacenterSimple(simulation, hosts);
    }

    /** VM dibuat urut TINGGI -> MENENGAH -> RENDAH supaya penempatan tidak terfragmentasi. */
    public List<Vm> buildVms() {
        List<Vm> vms = new ArrayList<>();
        addVms(vms, HIGH, highCount);
        addVms(vms, MEDIUM, mediumCount);
        addVms(vms, LOW, lowCount);
        return vms;
    }

    private void addVms(List<Vm> vms, VmSpec spec, int count) {
        for (int i = 0; i < count; i++) {
            Vm vm = new VmSimple(spec.mipsPerPe(), spec.pes());
            vm.setRam(spec.ramMb()).setBw(VM_BW_MBPS).setSize(VM_STORAGE_MB);
            categoryOf.put(vm, spec.name());
            vm.addOnHostAllocationListener(info -> {
                long id = info.getHost().getId();
                // id CloudSim mulai dari 0; penomoran Draft Design mulai dari 1
                placementOf.put(vm, "Host " + (id + 1) + " [CloudSim id " + id + "]");
            });
            vms.add(vm);
        }
    }

    /** Boleh dipanggil setelah simulation.start(): tampilkan VM ditempatkan di Host mana. */
    public void printPlacement(List<Vm> vms) {
        System.out.println("\nPENEMPATAN VM -> HOST  (Xen / Linux)");
        for (Vm vm : vms) {
            String where = placementOf.getOrDefault(vm, "GAGAL DITEMPATKAN");
            System.out.printf("VM %-2d %-9s %d PE x %.0f MIPS, RAM %5d MB -> %s%n",
                    vm.getId(), categoryOf.get(vm), vm.getPesNumber(), vm.getMips(),
                    vm.getRam().getCapacity(), where);
        }
    }
}
