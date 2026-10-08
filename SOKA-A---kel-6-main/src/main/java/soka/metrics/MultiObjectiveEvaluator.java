package soka.metrics;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostStateHistoryEntry;
import org.cloudsimplus.vms.Vm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Menghitung kelima metrik Bagian 4 draft + fungsi objektif gabungan
 * (Bagian 3.4): F = w1*M + w2*E + w3*(1-U).
 *
 * ASUMSI DESAIN -- SLA deadline (Bagian 5, constraint 4): draft tidak
 * memberi nilai deadline eksplisit per task, jadi deadline dihitung sebagai
 * kelipatan dari waktu eksekusi ideal task tsb pada VM yang menjalankannya:
 *     idealTime = length_MI / vm.getMips()      (asumsi 1 PE per cloudlet)
 *     deadline  = SLA_FACTOR * idealTime
 * SLA_FACTOR = 3.0 artinya task dianggar wajar kalau selesai dalam waktu
 * <= 3x waktu eksekusi ideal; makin besar nilainya makin longgar. Ini
 * parameter eksperimen, boleh diubah sesuai kesepakatan tim.
 */
public class MultiObjectiveEvaluator {

    private static final double SLA_FACTOR = 3.0;

    public Evaluation evaluate(List<Cloudlet> cloudlets, List<? extends Host> hosts,
                                double weightMakespan, double weightEnergy,
                                double weightUtilization) {
        double makespan = calculateMakespan(cloudlets);
        double energy = calculateEnergy(hosts);
        return evaluate(cloudlets, hosts, Math.max(1.0, makespan),
                Math.max(1.0, energy), weightMakespan, weightEnergy, weightUtilization);
    }

    public Evaluation evaluate(List<Cloudlet> cloudlets, List<? extends Host> hosts,
                                double makespanReference, double energyReference,
                                double weightMakespan, double weightEnergy,
                                double weightUtilization) {
        double makespan = calculateMakespan(cloudlets);
        double energy = calculateEnergy(hosts);
        double utilization = calculateUtilization(hosts);
        double avgWaitingTime = calculateAverageWaitingTime(cloudlets);
        double loadBalancingDegree = calculateLoadBalancingDegree(cloudlets);
        long slaViolations = countSlaViolations(cloudlets);

        double normalizedMakespan = normalize(makespan, makespanReference);
        double normalizedEnergy = normalize(energy, energyReference);
        double objectiveUtilization = 1.0 - utilization;
        double score = weightMakespan * normalizedMakespan
                + weightEnergy * normalizedEnergy
                + weightUtilization * objectiveUtilization;

        return new Evaluation(makespan, energy, utilization, avgWaitingTime,
                loadBalancingDegree, slaViolations, cloudlets.size(),
                normalizedMakespan, normalizedEnergy, score);
    }

    /** Metrik 1 (Bagian 4): total waktu dari task pertama mulai sampai task terakhir selesai. */
    public double calculateMakespan(List<Cloudlet> cloudlets) {
        double firstStart = Double.MAX_VALUE;
        double lastFinish = 0.0;
        for (Cloudlet cloudlet : cloudlets) {
            if (cloudlet.getStartTime() >= 0) {
                firstStart = Math.min(firstStart, cloudlet.getStartTime());
            }
            if (cloudlet.getFinishTime() >= 0) {
                lastFinish = Math.max(lastFinish, cloudlet.getFinishTime());
            }
        }
        return firstStart == Double.MAX_VALUE ? 0.0 : Math.max(0.0, lastFinish - firstStart);
    }

    /** Metrik 3: rasio pemakaian PE/CPU Host, dirata-rata dari state history (butuh setStateHistoryEnabled(true)). */
    public double calculateUtilization(List<? extends Host> hosts) {
        double weightedUsage = 0.0;
        double weightedCapacity = 0.0;
        for (Host host : hosts) {
            List<HostStateHistoryEntry> history = host.getStateHistory();
            for (int i = 1; i < history.size(); i++) {
                HostStateHistoryEntry previous = history.get(i - 1);
                HostStateHistoryEntry current = history.get(i);
                double duration = Math.max(0.0, current.time() - previous.time());
                weightedUsage += previous.percentUsage() * duration;
                weightedCapacity += duration;
            }
        }
        return weightedCapacity == 0.0 ? 0.0 : weightedUsage / weightedCapacity;
    }

    /** Metrik 2: total energi (Wh) dari power model tiap Host, diintegralkan dari state history. */
    public double calculateEnergy(List<? extends Host> hosts) {
        double energyWh = 0.0;
        for (Host host : hosts) {
            List<HostStateHistoryEntry> history = host.getStateHistory();
            for (int i = 1; i < history.size(); i++) {
                HostStateHistoryEntry previous = history.get(i - 1);
                HostStateHistoryEntry current = history.get(i);
                double durationSeconds = Math.max(0.0, current.time() - previous.time());
                double watts = host.getPowerModel().getPower(previous.percentUsage());
                energyWh += watts * durationSeconds / 3600.0;
            }
        }
        return energyWh;
    }

    /**
     * Metrik 4: rata-rata waktu tunggu = turnaround time - actual CPU time.
     * ASUMSI: semua task "tiba" di sistem pada t=0 (workload independen,
     * disubmit sekaligus di awal -- Draft 1.1), jadi turnaround time == finish time.
     * getTotalExecutionTime() (API CloudSim Plus 8.5.7; versi lama memakai nama getActualCpuTime()) menghitung total waktu EKSEKUSI NYATA,
     * sehingga preemption DRRHA (task berhenti lalu lanjut lagi) ikut terhitung benar.
     */
    public double calculateAverageWaitingTime(List<Cloudlet> cloudlets) {
        double totalWaiting = 0.0;
        int counted = 0;
        for (Cloudlet cloudlet : cloudlets) {
            if (cloudlet.getFinishTime() < 0) continue;
            double turnaround = cloudlet.getFinishTime();
            double executed = cloudlet.getTotalExecutionTime();
            totalWaiting += Math.max(0.0, turnaround - executed);
            counted++;
        }
        return counted == 0 ? 0.0 : totalWaiting / counted;
    }

    /**
     * Metrik 5: Load Balancing Degree = koefisien variasi (stddev / mean) dari
     * total panjang (MI) yang dieksekusi tiap VM. Nilai MENDEKATI 0 = beban
     * makin merata antar VM (sesuai tujuan "dioptimalkan mendekati merata"
     * di Bagian 4 draft). Indeks tanpa satuan.
     */
    public double calculateLoadBalancingDegree(List<Cloudlet> cloudlets) {
        Map<Long, Long> workloadPerVm = new HashMap<>();
        for (Cloudlet cloudlet : cloudlets) {
            Vm vm = cloudlet.getVm();
            if (vm == null) continue;
            workloadPerVm.merge(vm.getId(), cloudlet.getFinishedLengthSoFar(), Long::sum);
        }
        if (workloadPerVm.isEmpty()) return 0.0;

        double mean = workloadPerVm.values().stream().mapToLong(Long::longValue).average().orElse(0.0);
        if (mean == 0.0) return 0.0;

        double variance = workloadPerVm.values().stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);
        double stddev = Math.sqrt(variance);
        return stddev / mean;
    }

    /** Constraint 4 (Bagian 5): hitung berapa task yang melewati deadline SLA (lihat ASUMSI di atas kelas). */
    public long countSlaViolations(List<Cloudlet> cloudlets) {
        long violations = 0;
        for (Cloudlet cloudlet : cloudlets) {
            Vm vm = cloudlet.getVm();
            if (vm == null || cloudlet.getFinishTime() < 0 || vm.getMips() <= 0) continue;
            double idealTime = cloudlet.getLength() / vm.getMips();
            double deadline = SLA_FACTOR * idealTime;
            if (cloudlet.getFinishTime() > deadline) {
                violations++;
            }
        }
        return violations;
    }

    private double normalize(double value, double reference) {
        return reference <= 0.0 ? 0.0 : Math.min(1.0, value / reference);
    }

    public static final class Evaluation {
        private final double makespan;
        private final double energyWh;
        private final double utilization;
        private final double avgWaitingTime;
        private final double loadBalancingDegree;
        private final long slaViolations;
        private final int totalCloudlets;
        private final double normalizedMakespan;
        private final double normalizedEnergy;
        private final double weightedScore;

        public Evaluation(double makespan, double energyWh, double utilization,
                           double avgWaitingTime, double loadBalancingDegree,
                           long slaViolations, int totalCloudlets,
                           double normalizedMakespan, double normalizedEnergy,
                           double weightedScore) {
            this.makespan = makespan;
            this.energyWh = energyWh;
            this.utilization = utilization;
            this.avgWaitingTime = avgWaitingTime;
            this.loadBalancingDegree = loadBalancingDegree;
            this.slaViolations = slaViolations;
            this.totalCloudlets = totalCloudlets;
            this.normalizedMakespan = normalizedMakespan;
            this.normalizedEnergy = normalizedEnergy;
            this.weightedScore = weightedScore;
        }

        public double getMakespan() { return makespan; }
        public double getEnergyWh() { return energyWh; }
        public double getUtilization() { return utilization; }
        public double getAvgWaitingTime() { return avgWaitingTime; }
        public double getLoadBalancingDegree() { return loadBalancingDegree; }
        public long getSlaViolations() { return slaViolations; }
        public int getTotalCloudlets() { return totalCloudlets; }
        public double getNormalizedMakespan() { return normalizedMakespan; }
        public double getNormalizedEnergy() { return normalizedEnergy; }
        public double getWeightedScore() { return weightedScore; }
    }
}
