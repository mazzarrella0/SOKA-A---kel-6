package soka.metrics;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostStateHistoryEntry;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.vms.Vm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluator metrik multi-objective untuk DRRHA.
 *
 * Formula objective yang dipakai adalah:
 *   F = w1 * M + w2 * E + w3 * (1 - U)
 * dengan M, E, dan U dinormalisasi terhadap baseline kuantitatif yang dibangun dari total
 * panjang task dan kapasitas komputasi yang tersedia pada infrastruktur yang sama.
 */
public class MultiObjectiveEvaluator {

    private static final double SLA_FACTOR = 3.0;
    public record SlaAssessment(double idealExecutionTime, double deadline, double completionTime,
                                double lateness, boolean violation, String reason) {}

    public double validateWeights(double weightMakespan, double weightEnergy, double weightUtilization) {
        double total = weightMakespan + weightEnergy + weightUtilization;
        if (Math.abs(total - 1.0) > 1.0e-9) {
            throw new IllegalArgumentException(
                    "Bobot objective harus berjumlah 1.0, tetapi diterima: " + total);
        }
        return total;
    }

    public ReferenceSet buildReferenceSet(List<Cloudlet> cloudlets, List<? extends Host> hosts) {
        double totalWorkMi = cloudlets.stream().mapToDouble(Cloudlet::getLength).sum();
        double hostCapacityMips = hosts.stream()
                .mapToDouble(host -> host.getPeList().stream().mapToDouble(pe -> pe.getCapacity()).sum())
                .sum();
        if (totalWorkMi > 0.0 && hostCapacityMips <= 0.0) {
            throw new IllegalArgumentException("Tidak dapat membangun reference makespan tanpa kapasitas host positif");
        }
        double makespanReference = totalWorkMi <= 0.0 ? 1.0 : totalWorkMi / hostCapacityMips;
        double peakPowerWatts = hosts.stream()
                .mapToDouble(host -> host.getPowerModel() == null ? 0.0 : host.getPowerModel().getPower(1.0))
                .sum();
        if (totalWorkMi > 0.0 && peakPowerWatts <= 0.0) {
            throw new IllegalArgumentException("Tidak dapat membangun reference energi tanpa daya puncak host positif");
        }
        double energyReference = peakPowerWatts <= 0.0 ? 1.0 : (peakPowerWatts * makespanReference) / 3600.0;
        return new ReferenceSet(makespanReference, energyReference);
    }

    public Evaluation evaluate(List<Cloudlet> cloudlets, List<? extends Host> hosts,
                              double weightMakespan, double weightEnergy, double weightUtilization) {
        ReferenceSet referenceSet = buildReferenceSet(cloudlets, hosts);
        return evaluate(cloudlets, hosts, referenceSet.getMakespanReference(), referenceSet.getEnergyReference(),
                weightMakespan, weightEnergy, weightUtilization);
    }

    public Evaluation evaluate(List<Cloudlet> cloudlets, List<? extends Host> hosts,
                              double makespanReference, double energyReference,
                              double weightMakespan, double weightEnergy,
                              double weightUtilization) {
        validateWeights(weightMakespan, weightEnergy, weightUtilization);

        double makespan = calculateMakespan(cloudlets);
        double energy = calculateEnergy(hosts);
        double utilization = calculateUtilization(hosts);
        double avgWaitingTime = calculateAverageWaitingTime(cloudlets);
        double loadBalancingDegree = calculateLoadBalancingDegree(cloudlets);
        long slaViolations = countSlaViolations(cloudlets);

        double normalizedMakespan = normalize(makespan, makespanReference);
        double normalizedEnergy = normalize(energy, energyReference);
        double objectiveUtilization = 1.0 - utilization;
        double score = calculateWeightedScore(normalizedMakespan, normalizedEnergy, utilization,
            weightMakespan, weightEnergy, weightUtilization);

        return new Evaluation(makespan, energy, utilization, avgWaitingTime,
                loadBalancingDegree, slaViolations, cloudlets.size(),
                normalizedMakespan, normalizedEnergy, score, slaViolations, 0.0, 0.0, 0.0, 0.0, 0.0,
                makespanReference, energyReference);
    }

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

    public double calculateUtilization(List<? extends Host> hosts) {
        double weightedUsage = 0.0;
        double weightedCapacity = 0.0;
        for (Host host : hosts) {
            List<HostStateHistoryEntry> history = host.getStateHistory();
            if (history == null || history.isEmpty()) {
                continue;
            }
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

    public double calculateEnergy(List<? extends Host> hosts) {
        double energyWh = 0.0;
        for (Host host : hosts) {
            List<HostStateHistoryEntry> history = host.getStateHistory();
            if (history == null || history.isEmpty()) {
                continue;
            }
            for (int i = 1; i < history.size(); i++) {
                HostStateHistoryEntry previous = history.get(i - 1);
                HostStateHistoryEntry current = history.get(i);
                double durationSeconds = Math.max(0.0, current.time() - previous.time());
                if (host.getPowerModel() == null) {
                    continue;
                }
                double watts = host.getPowerModel().getPower(previous.percentUsage());
                energyWh += watts * durationSeconds / 3600.0;
            }
        }
        return energyWh;
    }

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

    public long countSlaViolations(List<Cloudlet> cloudlets) {
        long violations = 0L;
        for (Cloudlet cloudlet : cloudlets) {
            if (cloudlet != null && assessSla(cloudlet).violation()) violations++;
        }
        return violations;
    }

    public SlaAssessment assessSla(Cloudlet cloudlet) {
        Vm vm = cloudlet.getVm();
        if (cloudlet.getStatus() == Cloudlet.Status.FAILED) {
            return new SlaAssessment(0.0, 0.0, -1.0, 0.0, true, "FAILED");
        }
        if (cloudlet.getStatus() == Cloudlet.Status.CANCELED) {
            return new SlaAssessment(0.0, 0.0, -1.0, 0.0, true, "CANCELED");
        }
        if (vm == null || cloudlet.getFinishTime() < 0.0 || vm.getMips() <= 0.0
                || cloudlet.getStatus() == Cloudlet.Status.INSTANTIATED) {
            return new SlaAssessment(0.0, 0.0, -1.0, 0.0, true, "NOT_COMPLETED_OR_UNASSIGNED");
        }

        double idealExecutionTime = Math.max(1.0, cloudlet.getLength() / vm.getMips());
        double deadline = SLA_FACTOR * idealExecutionTime;
        double completionTime = cloudlet.getFinishTime();
        double lateness = Math.max(0.0, completionTime - deadline);
        boolean violation = completionTime > deadline;
        return new SlaAssessment(idealExecutionTime, deadline, completionTime, lateness, violation,
                violation ? "DEADLINE_EXCEEDED" : "WITHIN_DEADLINE");
    }

    public double getSlaFactor() {
        return SLA_FACTOR;
    }

    public double normalize(double value, double reference) {
        if (Double.isNaN(value) || Double.isInfinite(value) || reference <= 0.0) {
            return 0.0;
        }
        double normalized = value / reference;
        return Double.isNaN(normalized) || Double.isInfinite(normalized) ? 0.0 : Math.max(0.0, normalized);
    }

    public double calculateWeightedScore(double normalizedMakespan, double normalizedEnergy,
                                         double utilization, double weightMakespan,
                                         double weightEnergy, double weightUtilization) {
        validateWeights(weightMakespan, weightEnergy, weightUtilization);
        return weightMakespan * normalizedMakespan
                + weightEnergy * normalizedEnergy
                + weightUtilization * (1.0 - utilization);
    }

    public static final class ReferenceSet {
        private final double makespanReference;
        private final double energyReference;

        public ReferenceSet(double makespanReference, double energyReference) {
            this.makespanReference = makespanReference;
            this.energyReference = energyReference;
        }

        public double getMakespanReference() { return makespanReference; }
        public double getEnergyReference() { return energyReference; }
    }

    public static final class Evaluation {
        private final double makespan;
        private final double energyWh;
        private final double utilization;
        private final double avgWaitingTime;
        private final double loadBalancingDegree;
        private final double slaViolations;
        private final int totalCloudlets;
        private final double normalizedMakespan;
        private final double normalizedEnergy;
        private final double weightedScore;
        private final double meanSlaViolations;
        private final double slaViolationRate;
        private final double makespanStdDev;
        private final double energyStdDev;
        private final double utilizationStdDev;
        private final double avgWaitingTimeStdDev;
        private final double slaViolationsStdDev;
        private final double makespanReference;
        private final double energyReference;

        public Evaluation(double makespan, double energyWh, double utilization,
                          double avgWaitingTime, double loadBalancingDegree,
                          double slaViolations, int totalCloudlets,
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
            this.meanSlaViolations = slaViolations;
            this.slaViolationRate = totalCloudlets == 0 ? 0.0 : (double) slaViolations / totalCloudlets;
            this.makespanStdDev = 0.0;
            this.energyStdDev = 0.0;
            this.utilizationStdDev = 0.0;
            this.avgWaitingTimeStdDev = 0.0;
            this.slaViolationsStdDev = 0.0;
            this.makespanReference = Double.NaN;
            this.energyReference = Double.NaN;
        }

        public Evaluation(double makespan, double energyWh, double utilization,
                          double avgWaitingTime, double loadBalancingDegree,
                          double slaViolations, int totalCloudlets, double normalizedMakespan,
                          double normalizedEnergy, double weightedScore, double meanSlaViolations,
                          double makespanStdDev, double energyStdDev, double utilizationStdDev,
                          double avgWaitingTimeStdDev, double slaViolationsStdDev) {
                    this(makespan, energyWh, utilization, avgWaitingTime, loadBalancingDegree, slaViolations,
                        totalCloudlets, normalizedMakespan, normalizedEnergy, weightedScore, meanSlaViolations,
                        makespanStdDev, energyStdDev, utilizationStdDev, avgWaitingTimeStdDev,
                        slaViolationsStdDev, Double.NaN, Double.NaN);
                }

                public Evaluation(double makespan, double energyWh, double utilization,
                          double avgWaitingTime, double loadBalancingDegree,
                          double slaViolations, int totalCloudlets, double normalizedMakespan,
                          double normalizedEnergy, double weightedScore, double meanSlaViolations,
                          double makespanStdDev, double energyStdDev, double utilizationStdDev,
                          double avgWaitingTimeStdDev, double slaViolationsStdDev,
                          double makespanReference, double energyReference) {
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
            this.meanSlaViolations = meanSlaViolations;
            this.slaViolationRate = totalCloudlets == 0 ? 0.0 : meanSlaViolations / totalCloudlets;
            this.makespanStdDev = makespanStdDev;
            this.energyStdDev = energyStdDev;
            this.utilizationStdDev = utilizationStdDev;
            this.avgWaitingTimeStdDev = avgWaitingTimeStdDev;
            this.slaViolationsStdDev = slaViolationsStdDev;
            this.makespanReference = makespanReference;
            this.energyReference = energyReference;
        }

        public double getMakespan() { return makespan; }
        public double getEnergyWh() { return energyWh; }
        public double getUtilization() { return utilization; }
        public double getAvgWaitingTime() { return avgWaitingTime; }
        public double getLoadBalancingDegree() { return loadBalancingDegree; }
        public double getSlaViolations() { return slaViolations; }
        public int getTotalCloudlets() { return totalCloudlets; }
        public double getNormalizedMakespan() { return normalizedMakespan; }
        public double getNormalizedEnergy() { return normalizedEnergy; }
        public double getWeightedScore() { return weightedScore; }
        public double getMeanSlaViolations() { return meanSlaViolations; }
        public double getSlaViolationRate() { return slaViolationRate; }
        public double getMakespanStdDev() { return makespanStdDev; }
        public double getEnergyStdDev() { return energyStdDev; }
        public double getUtilizationStdDev() { return utilizationStdDev; }
        public double getAvgWaitingTimeStdDev() { return avgWaitingTimeStdDev; }
        public double getSlaViolationsStdDev() { return slaViolationsStdDev; }
        public double getMakespanReference() { return makespanReference; }
        public double getEnergyReference() { return energyReference; }
    }
}
