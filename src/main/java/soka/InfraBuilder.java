package soka;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.power.models.PowerModelHostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Infrastruktur (revisi dosen):
 *  - 2 Datacenter IDENTIK, masing-masing 4 Host heterogen (Draft 2.2).
 *  - 8 VM (2 tinggi, 3 menengah, 3 rendah) dengan penempatan TETAP ke
 *    (Datacenter, Host) lewat tabel PLACEMENT di bawah -- tidak ada
 *    keputusan acak/otomatis dari allocation policy.
 *
 * Tabel PLACEMENT (urutan = urutan VM dibuat: tinggi, menengah, rendah):
 *   VM0 TINGGI   -> DC1 Host1 (4PE/8GB, pas)
 *   VM1 TINGGI   -> DC2 Host1
 *   VM2 MENENGAH -> DC1 Host2
 *   VM3 MENENGAH -> DC1 Host3
 *   VM4 MENENGAH -> DC2 Host2
 *   VM5 RENDAH   -> DC1 Host4
 *   VM6 RENDAH   -> DC2 Host3
 *   VM7 RENDAH   -> DC2 Host4
 * Hasilnya 1 VM per Host, 4 VM per Datacenter.
 */
public final class InfraBuilder {

    public static final String VMM = "Xen";
    public static final String OS = "Linux";

    public static final int DATACENTER_COUNT = 2;

    private static final double HOST_MAX_POWER_WATT = 250.0;
    private static final double HOST_STATIC_POWER_WATT = 150.0;

    private record HostSpec(int pes, long ramMb, long storageMb, long bwMbps) {}

    private record VmSpec(String name, double mipsPerPe, int pes, long ramMb) {}

    /** Posisi tetap sebuah VM: indeks datacenter (0-based) dan indeks host di datacenter itu (0-based). */
    private record Slot(int dc, int host) {}

    private static final List<HostSpec> HOSTS = List.of(
            new HostSpec(4, 8 * 1024, 500_000, 1000),     // Host 1
            new HostSpec(8, 16 * 1024, 1_000_000, 1000),  // Host 2
            new HostSpec(4, 16 * 1024, 1_000_000, 1000),  // Host 3
            new HostSpec(8, 32 * 1024, 2_000_000, 1000)); // Host 4

    private static final VmSpec LOW = new VmSpec("RENDAH", 1000, 1, 2 * 1024);
    private static final VmSpec MEDIUM = new VmSpec("MENENGAH", 2000, 2, 4 * 1024);
    private static final VmSpec HIGH = new VmSpec("TINGGI", 4000, 4, 8 * 1024);

    private static final List<Slot> PLACEMENT = List.of(
            new Slot(0, 0), new Slot(1, 0),                   // TINGGI x2
            new Slot(0, 1), new Slot(0, 2), new Slot(1, 1),   // MENENGAH x3
            new Slot(0, 3), new Slot(1, 2), new Slot(1, 3));  // RENDAH x3

    private static final long VM_BW_MBPS = 100;
    private static final long VM_STORAGE_MB = 10_000;

    private final double hostPeMips;
    private final int lowCount;
    private final int mediumCount;
    private final int highCount;

    private final List<Datacenter> datacenters = new ArrayList<>();
    private final Map<Vm, String> categoryOf = new IdentityHashMap<>();
    private final Map<Vm, Slot> slotOf = new IdentityHashMap<>();
    private final Map<Vm, Host> placedOn = new IdentityHashMap<>();
    private final Map<Host, String> hostLabel = new IdentityHashMap<>();

    private final String algorithm;

    public InfraBuilder(Config cfg) {
        this(cfg, "DRRHA");
    }

    public InfraBuilder(Config cfg, String algorithm) {
        this.algorithm = algorithm;
        this.hostPeMips = cfg.getDouble("host.pe.mips");
        this.lowCount = cfg.getInt("vm.count.low");
        this.mediumCount = cfg.getInt("vm.count.medium");
        this.highCount = cfg.getInt("vm.count.high");

        if (hostPeMips < HIGH.mipsPerPe()) {
            throw new IllegalStateException("host.pe.mips (" + hostPeMips
                    + ") lebih kecil dari MIPS VM tertinggi (" + HIGH.mipsPerPe() + ")");
        }
        if (highCount + mediumCount + lowCount != PLACEMENT.size()) {
            throw new IllegalStateException("Tabel PLACEMENT tetap hanya berlaku untuk "
                    + PLACEMENT.size() + " VM (2 tinggi, 3 menengah, 3 rendah). "
                    + "Kalau komposisi diubah, ubah juga tabel PLACEMENT.");
        }
    }

    /** Bangun 2 datacenter identik. Harus dipanggil SEBELUM buildVms(). */
    public List<Datacenter> buildDatacenters(CloudSimPlus simulation) {
        datacenters.clear();
        for (int d = 0; d < DATACENTER_COUNT; d++) {
            final int dcIndex = d;
            List<Host> hosts = new ArrayList<>();
            for (int h = 0; h < HOSTS.size(); h++) {
                HostSpec s = HOSTS.get(h);
                List<Pe> pes = new ArrayList<>();
                for (int i = 0; i < s.pes(); i++) {
                    pes.add(new PeSimple(hostPeMips));
                }
                Host host = new HostSimple(s.ramMb(), s.bwMbps(), s.storageMb(), pes);
                host.setVmScheduler(new VmSchedulerTimeShared());
                host.setPowerModel(new PowerModelHostSimple(HOST_MAX_POWER_WATT, HOST_STATIC_POWER_WATT));
                host.setStateHistoryEnabled(true);
                hostLabel.put(host, "DC" + (d + 1) + " Host " + (h + 1));
                hosts.add(host);
            }

            // Penempatan tetap: host dipilih dari tabel PLACEMENT, bukan oleh policy.
            VmAllocationPolicySimple policy = new VmAllocationPolicySimple();
            policy.setFindHostForVmFunction((p, vm) -> {
                Slot slot = slotOf.get(vm);
                if (slot == null || slot.dc() != dcIndex) {
                    return Optional.empty();
                }
                return Optional.of(hosts.get(slot.host()));
            });

            Datacenter dc = new DatacenterSimple(simulation, hosts, policy);
            dc.setSchedulingInterval(1.0);
            datacenters.add(dc);
        }
        return datacenters;
    }

    /** Urutan VM tetap: TINGGI -> MENENGAH -> RENDAH (cocok dengan tabel PLACEMENT). */
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
            vm.setCloudletScheduler(soka.algorithm.SchedulerFactory.create(algorithm));
            categoryOf.put(vm, spec.name());
            slotOf.put(vm, PLACEMENT.get(vms.size()));
            vm.addOnHostAllocationListener(info -> placedOn.put(vm, info.getHost()));
            vms.add(vm);
        }
    }

    /** Dipakai broker.setDatacenterMapper(...): VM selalu dikirim ke datacenter sesuai tabel. */
    public Datacenter mapVmToDatacenter(Datacenter lastDatacenter, Vm vm) {
        return datacenters.get(slotOf.get(vm).dc());
    }

    /** Semua Host dari semua datacenter (untuk hitung energi & utilisasi). */
    public List<Host> allHosts() {
        List<Host> all = new ArrayList<>();
        for (Datacenter dc : datacenters) {
            all.addAll(dc.getHostList());
        }
        return all;
    }

    /** Panggil setelah simulation.start(): cetak penempatan dan pastikan sama dengan rencana. */
    public void printPlacement(List<Vm> vms) {
        System.out.println("\nPENEMPATAN VM -> DATACENTER/HOST  (Xen / Linux)");
        for (Vm vm : vms) {
            Host actual = placedOn.get(vm);
            Slot slot = slotOf.get(vm);
            Host planned = datacenters.get(slot.dc()).getHostList().get(slot.host());
            String where = actual == null ? "GAGAL DITEMPATKAN" : hostLabel.get(actual);
            String check = actual == planned ? "sesuai rencana" : "MENYIMPANG DARI RENCANA";
            System.out.printf("VM %-2d %-9s %d PE x %.0f MIPS, RAM %5d MB -> %s  (%s)%n",
                    vm.getId(), categoryOf.get(vm), vm.getPesNumber(), vm.getMips(),
                    vm.getRam().getCapacity(), where, check);
            if (actual != planned) {
                throw new IllegalStateException("Penempatan VM " + vm.getId() + " tidak sesuai tabel PLACEMENT");
            }
        }
    }
}
