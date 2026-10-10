package soka;

import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.power.models.PowerModelHostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import soka.algorithm.DatacenterBrokerDRRHA;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Infrastruktur simulasi dengan dua data center dan empat host heterogen.
 * Penempatan VM dibuat deterministik dan divalidasi terhadap kapasitas host yang sesungguhnya.
 */
public final class InfraBuilder {
    public static final String VMM = "Xen";
    public static final String OS = "Linux";

    private static final double HOST_MAX_POWER_WATT = 250.0;
    private static final double HOST_STATIC_POWER_WATT = 150.0;

    private record HostSpec(int pes, long ramMb, long storageMb, long bwMbps) {}
    private record VmSpec(String name, double mipsPerPe, int pes, long ramMb) {}
    public record VmPlacement(int vmOrder, String category, int datacenterId, int hostLocalIndex) {}
    public record VmAllocationSnapshot(long datacenterId, long hostId, long hostPes, double hostMips,
                                       long hostRamMb, long hostBwMbps, long hostStorageMb,
                                       long vmId, String vmCategory, long vmPes, double vmMips,
                                       long vmRamMb, long vmBwMbps, long vmStorageMb) {}

    private static final List<HostSpec> HOSTS = List.of(
            new HostSpec(4, 8 * 1024, 500_000, 1000),
            new HostSpec(8, 16 * 1024, 1_000_000, 1000),
            new HostSpec(4, 16 * 1024, 1_000_000, 1000),
            new HostSpec(8, 32 * 1024, 2_000_000, 1000));

    private static final VmSpec LOW = new VmSpec("LOW", 1000, 1, 2 * 1024);
    private static final VmSpec MEDIUM = new VmSpec("MEDIUM", 2000, 2, 4 * 1024);
    private static final VmSpec HIGH = new VmSpec("HIGH", 4000, 4, 8 * 1024);

    private static final long VM_BW_MBPS = 100;
    private static final long VM_STORAGE_MB = 10_000;

    private final double hostPeMips;
    private final int lowCount;
    private final int mediumCount;
    private final int highCount;
    private final Map<Vm, String> categoryOf = new IdentityHashMap<>();
    private final Map<Vm, String> placementOf = new IdentityHashMap<>();
    private final Map<Integer, VmPlacement> placementByVmOrder = new HashMap<>();

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

    public List<Datacenter> buildDatacenters(CloudSimPlus simulation) {
        List<Datacenter> datacenters = new ArrayList<>();
        datacenters.add(buildDatacenter(simulation, 1, HOSTS.subList(0, 2)));
        datacenters.add(buildDatacenter(simulation, 2, HOSTS.subList(2, 4)));
        return datacenters;
    }

    private Datacenter buildDatacenter(CloudSimPlus simulation, int datacenterId, List<HostSpec> specs) {
        List<Host> hosts = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            HostSpec spec = specs.get(i);
            List<Pe> pes = new ArrayList<>();
            for (int j = 0; j < spec.pes(); j++) {
                pes.add(new PeSimple(hostPeMips));
            }
            Host host = new HostSimple(spec.ramMb(), spec.bwMbps(), spec.storageMb(), pes);
            host.setVmScheduler(new VmSchedulerTimeShared());
            host.setPowerModel(new PowerModelHostSimple(HOST_MAX_POWER_WATT, HOST_STATIC_POWER_WATT));
            host.setStateHistoryEnabled(true);
            hosts.add(host);
        }
        Datacenter datacenter = new DatacenterSimple(simulation, hosts).setSchedulingInterval(1.0);
        return datacenter;
    }

    public List<Vm> buildVms() {
        List<Vm> vms = new ArrayList<>();
        addVms(vms, LOW, lowCount);
        addVms(vms, MEDIUM, mediumCount);
        addVms(vms, HIGH, highCount);
        return vms;
    }

    private void addVms(List<Vm> vms, VmSpec spec, int count) {
        for (int i = 0; i < count; i++) {
            Vm vm = new VmSimple(spec.mipsPerPe(), spec.pes());
            vm.setRam(spec.ramMb()).setBw(VM_BW_MBPS).setSize(VM_STORAGE_MB);
            vm.setCloudletScheduler(new soka.algorithm.CloudletSchedulerDRRHA());
            categoryOf.put(vm, spec.name());
            vms.add(vm);
        }
    }

    public List<VmPlacement> deterministicPlacement() {
        List<VmPlacement> placements = new ArrayList<>();
        int order = 0;

        Map<Integer, Integer> pePerHost = new HashMap<>();
        Map<Integer, Long> ramPerHost = new HashMap<>();
        Map<Integer, Long> bwPerHost = new HashMap<>();
        Map<Integer, Long> storagePerHost = new HashMap<>();
        List<int[]> orderedHosts = List.of(
                new int[] {1, 0},
                new int[] {1, 1},
                new int[] {2, 0},
                new int[] {2, 1}
        );

        List<VmSpec> categories = List.of(LOW, MEDIUM, HIGH);
        List<Integer> counts = List.of(lowCount, mediumCount, highCount);

        for (int categoryIndex = 0; categoryIndex < categories.size(); categoryIndex++) {
            VmSpec spec = categories.get(categoryIndex);
            int count = counts.get(categoryIndex);

            for (int i = 0; i < count; i++) {
                int selectedHostKey = -1;
                int[] selectedSlot = null;

                for (int[] slot : orderedHosts) {
                    int datacenterId = slot[0];
                    int hostLocalIndex = slot[1];
                    int hostKey = (datacenterId - 1) * 2 + hostLocalIndex;
                    HostSpec hostSpec = HOSTS.get(hostKey);
                    int peUsed = pePerHost.getOrDefault(hostKey, 0);
                    long ramUsed = ramPerHost.getOrDefault(hostKey, 0L);
                        long bwUsed = bwPerHost.getOrDefault(hostKey, 0L);
                        long storageUsed = storagePerHost.getOrDefault(hostKey, 0L);

                        if (peUsed + spec.pes() <= hostSpec.pes() && ramUsed + spec.ramMb() <= hostSpec.ramMb()
                            && bwUsed + VM_BW_MBPS <= hostSpec.bwMbps()
                            && storageUsed + VM_STORAGE_MB <= hostSpec.storageMb()) {
                        selectedHostKey = hostKey;
                        selectedSlot = slot;
                        break;
                    }
                }

                if (selectedHostKey < 0 || selectedSlot == null) {
                    throw new IllegalStateException("Tidak ada host yang cukup untuk menampung VM " + spec.name() + " ke-" + (i + 1));
                }

                int datacenterId = selectedSlot[0];
                int hostLocalIndex = selectedSlot[1];
                pePerHost.merge(selectedHostKey, spec.pes(), Integer::sum);
                ramPerHost.merge(selectedHostKey, spec.ramMb(), Long::sum);
                bwPerHost.merge(selectedHostKey, VM_BW_MBPS, Long::sum);
                storagePerHost.merge(selectedHostKey, VM_STORAGE_MB, Long::sum);
                placements.add(new VmPlacement(order++, spec.name(), datacenterId, hostLocalIndex));
            }
        }

        placementByVmOrder.clear();
        for (VmPlacement placement : placements) {
            placementByVmOrder.put(placement.vmOrder(), placement);
        }

        validatePlannedPlacement(placements);
        return placements;
    }

    public void configureDeterministicPlacement(List<Vm> vms, List<Datacenter> datacenters,
                                                DatacenterBrokerDRRHA broker) {
        List<VmPlacement> placements = deterministicPlacement();
        if (vms.size() != placements.size()) {
            throw new IllegalStateException("Jumlah VM tidak sesuai dengan pemetaan deterministik: " + vms.size() + " vs " + placements.size());
        }

        Map<Vm, Datacenter> datacenterByVm = new IdentityHashMap<>();
        Map<Vm, Host> hostByVm = new IdentityHashMap<>();
        for (int i = 0; i < vms.size(); i++) {
            Vm vm = vms.get(i);
            VmPlacement placement = placements.get(i);
            Datacenter datacenter = datacenters.get(placement.datacenterId() - 1);
            Host host = datacenter.getHostList().get(placement.hostLocalIndex());
            validateHostCapacity(host, vm);
            datacenterByVm.put(vm, datacenter);
            hostByVm.put(vm, host);
            placementOf.put(vm, "DC" + placement.datacenterId() + "/Host " + host.getId());
        }

        for (Datacenter datacenter : datacenters) {
            ((DatacenterSimple) datacenter).setVmAllocationPolicy(new VmAllocationPolicySimple((policy, vm) -> {
                Host target = hostByVm.get(vm);
                return target != null && target.getDatacenter() == datacenter
                        ? Optional.of(target) : Optional.empty();
            }));
        }
        broker.setDatacenterMapper((defaultDatacenter, vm) -> datacenterByVm.getOrDefault(vm, defaultDatacenter));
    }

    public void validateAggregateHostCapacity(List<Datacenter> datacenters, List<Vm> vms) {
        validateAggregateHostCapacity(datacenters, vms, false);
    }

    public void validateAggregateHostCapacity(List<Datacenter> datacenters, List<Vm> vms, boolean requireCreated) {
        Map<Host, Long> peDemandByHost = new IdentityHashMap<>();
        Map<Host, Long> ramDemandByHost = new IdentityHashMap<>();
        Map<Host, Long> bwDemandByHost = new IdentityHashMap<>();
        Map<Host, Long> storageDemandByHost = new IdentityHashMap<>();

        for (Vm vm : vms) {
            Host host = vm.getHost();
            if (host == null || (requireCreated && (!vm.isCreated() || !host.getVmList().contains(vm)))) {
                throw new IllegalStateException("VM " + vm.getId() + " belum memiliki host yang valid");
            }
            peDemandByHost.merge(host, Math.round(vm.getMips() * vm.getPesNumber()), Long::sum);
            ramDemandByHost.merge(host, vm.getRam().getCapacity(), Long::sum);
            bwDemandByHost.merge(host, vm.getBw().getCapacity(), Long::sum);
            storageDemandByHost.merge(host, ((VmSimple) vm).getStorage().getCapacity(), Long::sum);
        }

        for (Datacenter datacenter : datacenters) {
            for (Host host : datacenter.getHostList()) {
                long peDemand = peDemandByHost.getOrDefault(host, 0L);
                long ramDemand = ramDemandByHost.getOrDefault(host, 0L);
                long bwDemand = bwDemandByHost.getOrDefault(host, 0L);
                long storageDemand = storageDemandByHost.getOrDefault(host, 0L);

                if (peDemand > Math.round(host.getTotalMipsCapacity())) {
                    throw new IllegalStateException("Host " + host.getId() + " melebihi kapasitas PE: "
                            + peDemand + " > " + Math.round(host.getTotalMipsCapacity()));
                }
                if (ramDemand > host.getRam().getCapacity()) {
                    throw new IllegalStateException("Host " + host.getId() + " melebihi kapasitas RAM: "
                            + ramDemand + " > " + host.getRam().getCapacity());
                }
                if (bwDemand > host.getBw().getCapacity()) {
                    throw new IllegalStateException("Host " + host.getId() + " melebihi kapasitas bandwidth: "
                            + bwDemand + " > " + host.getBw().getCapacity());
                }
                if (storageDemand > host.getStorage().getCapacity()) {
                    throw new IllegalStateException("Host " + host.getId() + " melebihi kapasitas storage: "
                            + storageDemand + " > " + host.getStorage().getCapacity());
                }
                long actualRam = host.getVmList().stream().mapToLong(vm -> vm.getRam().getCapacity()).sum();
                long actualBw = host.getVmList().stream().mapToLong(vm -> vm.getBw().getCapacity()).sum();
                long actualStorage = host.getVmList().stream()
                    .mapToLong(vm -> ((VmSimple) vm).getStorage().getCapacity()).sum();
                double actualMips = host.getVmList().stream().mapToDouble(vm -> vm.getMips() * vm.getPesNumber()).sum();
                if (requireCreated && (actualMips > host.getTotalMipsCapacity()
                    || actualRam > host.getRam().getCapacity()
                    || actualBw > host.getBw().getCapacity()
                    || actualStorage > host.getStorage().getCapacity())) {
                    throw new IllegalStateException("Kapasitas aktual host " + host.getId()
                        + " terlampaui setelah alokasi CloudSim");
                }
            }
        }
    }

    public List<VmAllocationSnapshot> captureActualPlacement(List<Datacenter> datacenters, List<Vm> vms) {
        validateAggregateHostCapacity(datacenters, vms, true);
        List<VmAllocationSnapshot> snapshots = new ArrayList<>();
        for (Datacenter datacenter : datacenters) {
            for (Host host : datacenter.getHostList()) {
                for (Vm vm : host.getVmList()) {
                    snapshots.add(new VmAllocationSnapshot(datacenter.getId(), host.getId(), host.getPesNumber(),
                            host.getTotalMipsCapacity(), host.getRam().getCapacity(), host.getBw().getCapacity(),
                            host.getStorage().getCapacity(), vm.getId(), categoryOf.getOrDefault(vm, "UNKNOWN"),
                            vm.getPesNumber(), vm.getMips(), vm.getRam().getCapacity(), vm.getBw().getCapacity(),
                            vm.getStorage().getCapacity()));
                }
            }
        }
        if (snapshots.size() != vms.size()) {
            throw new IllegalStateException("Snapshot alokasi hanya memuat " + snapshots.size() + "/" + vms.size() + " VM");
        }
        return List.copyOf(snapshots);
    }

    public void validatePlannedPlacement(List<VmPlacement> placements) {
        Map<Integer, Integer> pePerHost = new HashMap<>();
        Map<Integer, Long> ramPerHost = new HashMap<>();
        Map<Integer, Long> bwPerHost = new HashMap<>();
        Map<Integer, Long> storagePerHost = new HashMap<>();

        for (VmPlacement placement : placements) {
            int hostKey = (placement.datacenterId() - 1) * 2 + placement.hostLocalIndex();
            int vmPe = placement.category().equals("LOW") ? 1 : placement.category().equals("MEDIUM") ? 2 : 4;
            long vmRam = placement.category().equals("LOW") ? 2 * 1024L : placement.category().equals("MEDIUM") ? 4 * 1024L : 8 * 1024L;
            pePerHost.merge(hostKey, vmPe, Integer::sum);
            ramPerHost.merge(hostKey, vmRam, Long::sum);
            bwPerHost.merge(hostKey, VM_BW_MBPS, Long::sum);
            storagePerHost.merge(hostKey, VM_STORAGE_MB, Long::sum);
        }

        for (int hostKey = 0; hostKey < HOSTS.size(); hostKey++) {
            HostSpec spec = HOSTS.get(hostKey);
            int peDemand = pePerHost.getOrDefault(hostKey, 0);
            long ramDemand = ramPerHost.getOrDefault(hostKey, 0L);
            long bwDemand = bwPerHost.getOrDefault(hostKey, 0L);
            long storageDemand = storagePerHost.getOrDefault(hostKey, 0L);
            if (peDemand > spec.pes()) {
                throw new IllegalStateException("Pemetaan deterministik melanggar kapasitas PE host " + (hostKey + 1)
                        + ": " + peDemand + " > " + spec.pes());
            }
            if (ramDemand > spec.ramMb()) {
                throw new IllegalStateException("Pemetaan deterministik melanggar kapasitas RAM host " + (hostKey + 1)
                        + ": " + ramDemand + " > " + spec.ramMb());
            }
            if (bwDemand > spec.bwMbps()) {
                throw new IllegalStateException("Pemetaan deterministik melanggar kapasitas bandwidth host " + (hostKey + 1)
                        + ": " + bwDemand + " > " + spec.bwMbps());
            }
            if (storageDemand > spec.storageMb()) {
                throw new IllegalStateException("Pemetaan deterministik melanggar kapasitas storage host " + (hostKey + 1)
                        + ": " + storageDemand + " > " + spec.storageMb());
            }
        }
    }

    private void validateHostCapacity(Host host, Vm vm) {
        double hostTotalMips = host.getTotalMipsCapacity();
        double vmDemandMips = vm.getMips() * vm.getPesNumber();
        if (vmDemandMips > hostTotalMips) {
            throw new IllegalStateException("VM " + vm.getId() + " melebihi kapasitas host " + host.getId() + ": "
                    + vmDemandMips + " > " + hostTotalMips + " MIPS");
        }
        if (vm.getRam().getCapacity() > host.getRam().getCapacity()) {
            throw new IllegalStateException("VM " + vm.getId() + " melebihi RAM host " + host.getId());
        }
        if (vm.getBw().getCapacity() > host.getBw().getCapacity()) {
            throw new IllegalStateException("VM " + vm.getId() + " melebihi bandwidth host " + host.getId());
        }
        if (((VmSimple) vm).getStorage().getCapacity() > host.getStorage().getCapacity()) {
            throw new IllegalStateException("VM " + vm.getId() + " melebihi storage host " + host.getId());
        }
    }

    public void printPlacement(List<Vm> vms) {
        System.out.println("\nPENEMPATAN VM -> HOST (deterministik)");
        for (Vm vm : vms) {
            String where = placementOf.getOrDefault(vm, "GAGAL DITEMPATKAN");
            System.out.printf("VM %-2d %-9s %d PE x %.0f MIPS, RAM %5d MB -> %s%n",
                    vm.getId(), categoryOf.get(vm), vm.getPesNumber(), vm.getMips(),
                    vm.getRam().getCapacity(), where);
        }
    }

    public void printActualPlacement(List<VmAllocationSnapshot> snapshots) {
        System.out.println("\nPEMETAAN VM AKTUAL SETELAH ALOKASI CLOUDSIM");
        for (VmAllocationSnapshot row : snapshots) {
            System.out.printf("VM %-2d %-9s %d PE x %.0f MIPS, RAM %5d MB, BW %d Mbps, storage %d MB -> DC %d / Host %d%n",
                    row.vmId(), row.vmCategory(), row.vmPes(), row.vmMips(), row.vmRamMb(), row.vmBwMbps(),
                    row.vmStorageMb(), row.datacenterId(), row.hostId());
        }
    }

    public List<String> printInfrastructureSummary() {
        List<String> lines = new ArrayList<>();
        lines.add("INFRASTRUKTUR DRRHA: 2 data center, 4 host heterogen, 8 VM deterministik");
        for (int dc = 1; dc <= 2; dc++) {
            StringBuilder sb = new StringBuilder();
            sb.append("Datacenter ").append(dc).append(" -> Hosts: ");
            for (int hostIndex = 0; hostIndex < 2; hostIndex++) {
                int index = (dc - 1) * 2 + hostIndex;
                HostSpec spec = HOSTS.get(index);
                sb.append("H").append(index + 1)
                        .append("(PE=").append(spec.pes())
                        .append(", RAM=").append(spec.ramMb() / 1024)
                        .append("GB, BW=").append(spec.bwMbps())
                        .append("Mbps, Storage=").append(spec.storageMb() / 1024)
                        .append("GB) ");
            }
            lines.add(sb.toString());
        }
        return lines;
    }
}
