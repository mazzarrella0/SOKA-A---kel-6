package soka;

import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;
import soka.algorithm.DatacenterBrokerDRRHA;
import soka.dataset.GoCJDatasetReader;
import soka.metrics.MultiObjectiveEvaluator;
import soka.metrics.ResultReporter;
import soka.metrics.TaskLengthDistribution;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        System.out.println("Java versi: " + System.getProperty("java.version"));

        double wMakespan = cfg.getDouble("weight.makespan");
        double wEnergy = cfg.getDouble("weight.energy");
        double wUtilization = cfg.getDouble("weight.utilization");
        int repeatCount = Integer.getInteger("simulation.repeat.count", cfg.getInt("simulation.repeat.count"));
        if (repeatCount < 3) {
            throw new IllegalStateException("simulation.repeat.count harus minimal 3 untuk validasi eksperimen.");
        }

        String taskCounts = System.getProperty("task.counts", cfg.getString("task.counts"));
        int[] scenarios = parseScenarios(taskCounts);
        System.out.println("Skenario yang akan dijalankan: " + taskCounts);
        System.out.println("Jumlah pengulangan per dataset: " + repeatCount);

        List<Integer> completedScenarioIds = new ArrayList<>();
        List<MultiObjectiveEvaluator.Evaluation> scenarioEvaluations = new ArrayList<>();
        List<Integer> failedScenarioIds = new ArrayList<>();
        for (int numJobs : scenarios) {
            try {
                MultiObjectiveEvaluator.Evaluation evaluation = runScenario(cfg, numJobs, repeatCount,
                        wMakespan, wEnergy, wUtilization);
                completedScenarioIds.add(numJobs);
                scenarioEvaluations.add(evaluation);
            } catch (Exception ex) {
                System.err.printf("Scenario %d tidak menghasilkan agregat valid: %s%n", numJobs, ex.getMessage());
                failedScenarioIds.add(numJobs);
            }
        }
        new ResultReporter().writeExperimentSummaryCsv(completedScenarioIds, scenarioEvaluations,
                wMakespan, wEnergy, wUtilization, Paths.get("results", "drrha-experiment-summary.csv"));
        if (!failedScenarioIds.isEmpty()) {
            throw new IllegalStateException("Eksperimen tidak lengkap; skenario tanpa tiga run valid: " + failedScenarioIds);
        }
    }

    private static MultiObjectiveEvaluator.Evaluation runScenario(Config cfg, int numJobs, int repeatCount,
                                   double wMakespan, double wEnergy, double wUtilization) throws Exception {
        System.out.println("\n================================================");
        System.out.println(" SKENARIO: " + numJobs + " task (DRRHA)");
        System.out.println("================================================");

        List<Cloudlet> scenarioCloudlets = new GoCJDatasetReader().readScenario(numJobs);
        TaskLengthDistribution taskDistribution = TaskLengthDistribution.from(scenarioCloudlets);
        System.out.printf(Locale.US,
                "Dataset: %d task | min=%.1f MI | max=%.1f MI | mean=%.1f MI | median=%.1f MI | std=%.3f%n",
                scenarioCloudlets.size(), taskDistribution.getMinLength(), taskDistribution.getMaxLength(),
                taskDistribution.getMeanLength(), taskDistribution.getMedianLength(), taskDistribution.getStdDevLength());
        System.out.printf(Locale.US,
                "Kategori: pendek=%d (%.2f%%) | panjang=%d (%.2f%%)%n",
                taskDistribution.getShortTaskCount(), taskDistribution.getShortTaskPercent(),
                taskDistribution.getLongTaskCount(), taskDistribution.getLongTaskPercent());

        List<RunResult> runs = new ArrayList<>();
        for (int run = 1; run <= repeatCount; run++) {
            RunResult result = executeRun(cfg, numJobs, run, scenarioCloudlets, wMakespan, wEnergy, wUtilization);
            runs.add(result);
            ResultReporter reporter = new ResultReporter();
            Path runCloudletsCsv = Paths.get("results", "drrha-" + numJobs + "-run-" + run + "-cloudlets.csv");
            Path runMetricsCsv = Paths.get("results", "drrha-" + numJobs + "-run-" + run + "-metrics.csv");
            Path runSummaryCsv = Paths.get("results", "drrha-" + numJobs + "-run-" + run + "-summary.csv");
            reporter.writeCloudletsCsv(result.cloudlets, runCloudletsCsv);
                reporter.writeRunSummaryCsv(numJobs, run, "DRRHA", result.status, result.failureReason,
                        result.evaluation, result, runSummaryCsv);
                reporter.writeEvaluationCsv(result.evaluation, taskDistribution,
                    wMakespan, wEnergy, wUtilization, runMetricsCsv);
            System.out.printf(Locale.US,
                    "%nRun %d/%d - status=%s | makespan=%.3f s | energy=%.3f Wh | utilization=%.2f%% | SLA=%.0f | score=%.8f%n",
                    run, repeatCount, result.status, result.evaluation.getMakespan(),
                    result.evaluation.getEnergyWh(), result.evaluation.getUtilization() * 100,
                    result.evaluation.getSlaViolations(), result.evaluation.getWeightedScore());
        }

        List<MultiObjectiveEvaluator.Evaluation> successfulRuns = new ArrayList<>();
        for (RunResult runResult : runs) {
            if ("SUCCESS".equalsIgnoreCase(runResult.status)) {
                successfulRuns.add(runResult.evaluation);
            }
        }
        ResultReporter reporter = new ResultReporter();
        Path cloudletsCsv = Paths.get("results", "drrha-" + numJobs + "-cloudlets.csv");
        Path metricsCsv = Paths.get("results", "drrha-" + numJobs + "-metrics.csv");
        Path infraCsv = Paths.get("results", "drrha-" + numJobs + "-infrastructure.csv");
        Path datasetCsv = Paths.get("results", "drrha-" + numJobs + "-dataset-statistics.csv");
        Path runSummaryCsv = Paths.get("results", "drrha-" + numJobs + "-run-summary.csv");
        Path distributionCsv = Paths.get("results", "drrha-" + numJobs + "-task-distribution.csv");
        Path reproducibilityCsv = Paths.get("results", "drrha-" + numJobs + "-reproducibility.csv");

        if (!runs.isEmpty()) {
                reporter.writeScenarioRunSummaryCsv(numJobs, "DRRHA", runs,
                    wMakespan, wEnergy, wUtilization, runSummaryCsv);
        }
        RunResult latestSuccessfulRun = runs.stream().filter(run -> "SUCCESS".equals(run.status))
            .reduce((first, second) -> second).orElse(null);
        if (latestSuccessfulRun != null) {
            reporter.writeCloudletsCsv(latestSuccessfulRun.cloudlets, cloudletsCsv);
            reporter.writeInfrastructureSnapshotCsv(numJobs, latestSuccessfulRun.infrastructure, infraCsv);
        }
        reporter.writeDatasetStatisticsCsv(numJobs, taskDistribution, datasetCsv);
        reporter.writeTaskLengthHistogram(numJobs, taskDistribution, distributionCsv);
        reporter.writeReproducibilityCsv(numJobs, runs, reproducibilityCsv);

        if (successfulRuns.size() != repeatCount || runs.size() != repeatCount) {
            Files.deleteIfExists(cloudletsCsv);
            Files.deleteIfExists(metricsCsv);
            Files.deleteIfExists(infraCsv);
            throw new IllegalStateException("Dataset " + numJobs + " tidak memiliki " + repeatCount
                    + " run SUCCESS (valid=" + successfulRuns.size() + "). Penyebab tersimpan di " + runSummaryCsv);
        }

        MultiObjectiveEvaluator.Evaluation summary = summarizeRuns(successfulRuns, numJobs,
            wMakespan, wEnergy, wUtilization);
        System.out.printf(Locale.US,
            "Makespan=%.3f +/- %.3f s | Energy=%.3f +/- %.3f Wh | Utilization=%.2f +/- %.2f%% | "
                + "SLA violation(mean)=%.3f +/- %.3f (rate=%.4f) | Score(F)=%.10f%n",
            summary.getMakespan(), summary.getMakespanStdDev(), summary.getEnergyWh(), summary.getEnergyStdDev(),
            summary.getUtilization() * 100, summary.getUtilizationStdDev() * 100,
            summary.getMeanSlaViolations(), summary.getSlaViolationsStdDev(), summary.getSlaViolationRate(),
            summary.getWeightedScore());
        reporter.writeEvaluationCsv(summary, taskDistribution,
            wMakespan, wEnergy, wUtilization, metricsCsv);

        System.out.println("CSV detail cloudlet : " + cloudletsCsv);
        System.out.println("CSV metrik          : " + metricsCsv);
        System.out.println("CSV infrastruktur   : " + infraCsv);
        System.out.println("CSV dataset        : " + datasetCsv);
        System.out.println("CSV distribusi     : " + distributionCsv);
        System.out.println("CSV reproducibility: " + reproducibilityCsv);
        System.out.println("CSV ringkasan run   : " + runSummaryCsv);
        return summary;
    }

    static MultiObjectiveEvaluator.Evaluation summarizeRuns(List<MultiObjectiveEvaluator.Evaluation> evaluations, int numJobs,
                                                             double wMakespan, double wEnergy, double wUtilization) {
        if (evaluations.isEmpty()) {
            throw new IllegalStateException("Tidak ada run yang berhasil untuk dataset " + numJobs);
        }

        double makespan = average(evaluations, MultiObjectiveEvaluator.Evaluation::getMakespan);
        double energy = average(evaluations, MultiObjectiveEvaluator.Evaluation::getEnergyWh);
        double utilization = average(evaluations, MultiObjectiveEvaluator.Evaluation::getUtilization);
        double avgWaiting = average(evaluations, MultiObjectiveEvaluator.Evaluation::getAvgWaitingTime);
        double loadBalance = average(evaluations, MultiObjectiveEvaluator.Evaluation::getLoadBalancingDegree);
        double averageSlaViolations = average(evaluations, MultiObjectiveEvaluator.Evaluation::getSlaViolations);
        double normalizedMakespan = average(evaluations, MultiObjectiveEvaluator.Evaluation::getNormalizedMakespan);
        double normalizedEnergy = average(evaluations, MultiObjectiveEvaluator.Evaluation::getNormalizedEnergy);
        double makespanReference = average(evaluations, MultiObjectiveEvaluator.Evaluation::getMakespanReference);
        double energyReference = average(evaluations, MultiObjectiveEvaluator.Evaluation::getEnergyReference);
        double meanSla = average(evaluations, MultiObjectiveEvaluator.Evaluation::getSlaViolations);
        double weightedScore = new MultiObjectiveEvaluator().calculateWeightedScore(
            normalizedMakespan, normalizedEnergy, utilization, wMakespan, wEnergy, wUtilization);

        return new MultiObjectiveEvaluator.Evaluation(
                makespan,
                energy,
                utilization,
                avgWaiting,
                loadBalance,
                averageSlaViolations,
                evaluations.get(0).getTotalCloudlets(),
                normalizedMakespan,
                normalizedEnergy,
                weightedScore,
                meanSla,
                sampleStdDev(evaluations, MultiObjectiveEvaluator.Evaluation::getMakespan),
                sampleStdDev(evaluations, MultiObjectiveEvaluator.Evaluation::getEnergyWh),
                sampleStdDev(evaluations, MultiObjectiveEvaluator.Evaluation::getUtilization),
                sampleStdDev(evaluations, MultiObjectiveEvaluator.Evaluation::getAvgWaitingTime),
                sampleStdDev(evaluations, MultiObjectiveEvaluator.Evaluation::getSlaViolations),
                makespanReference, energyReference);
    }

    private static double average(List<MultiObjectiveEvaluator.Evaluation> evaluations,
                                  java.util.function.ToDoubleFunction<MultiObjectiveEvaluator.Evaluation> mapper) {
        return evaluations.stream().mapToDouble(mapper).average().orElse(0.0);
    }

    private static double sampleStdDev(List<MultiObjectiveEvaluator.Evaluation> evaluations,
                                       java.util.function.ToDoubleFunction<MultiObjectiveEvaluator.Evaluation> mapper) {
        if (evaluations.size() < 2) return 0.0;
        double mean = average(evaluations, mapper);
        double sumSquares = evaluations.stream().mapToDouble(evaluation -> Math.pow(mapper.applyAsDouble(evaluation) - mean, 2)).sum();
        return Math.sqrt(sumSquares / (evaluations.size() - 1));
    }

    private static RunResult executeRun(Config cfg, int numJobs, int runId,
                                       List<Cloudlet> scenarioCloudlets,
                                       double wMakespan, double wEnergy, double wUtilization) {
        List<Cloudlet> workload = List.of();
        List<InfraBuilder.VmAllocationSnapshot> infrastructure = List.of();
        try {
            CloudSimPlus simulation = new CloudSimPlus();
            InfraBuilder infra = new InfraBuilder(cfg);
            List<Datacenter> datacenters = infra.buildDatacenters(simulation);
            DatacenterBrokerDRRHA broker = new DatacenterBrokerDRRHA(simulation);
            List<Vm> vms = infra.buildVms();
            infra.configureDeterministicPlacement(vms, datacenters, broker);
            broker.submitVmList(vms);

            workload = new GoCJDatasetReader().readScenario(numJobs);
            broker.submitCloudletList(workload);
            List<InfraBuilder.VmAllocationSnapshot> allocationSnapshot = new ArrayList<>();
            broker.addOnVmsCreatedListener(info -> {
                List<Vm> created = info.getDatacenterBroker().getVmCreatedList();
                if (created.size() != vms.size() || !info.getDatacenterBroker().getVmFailedList().isEmpty()) {
                    throw new IllegalStateException("VM allocation incomplete at creation event: created="
                            + created.size() + "/" + vms.size() + ", failed="
                            + info.getDatacenterBroker().getVmFailedList().size());
                }
                allocationSnapshot.addAll(infra.captureActualPlacement(datacenters, created));
                infra.printActualPlacement(allocationSnapshot);
                System.out.printf("Host capacities validated at VM-created event: %d VM across %d DC; failures=0%n",
                        allocationSnapshot.size(), datacenters.size());
            });
            simulation.start();

            List<Cloudlet> finished = broker.getCloudletFinishedList();
                List<Vm> createdVms = broker.getVmCreatedList();
                if (createdVms.size() != vms.size() || !broker.getVmFailedList().isEmpty()) {
                throw new IllegalStateException("VM allocation incomplete: created=" + createdVms.size()
                    + "/" + vms.size() + ", failed=" + broker.getVmFailedList().size());
                }
                infrastructure = List.copyOf(allocationSnapshot);
                if (infrastructure.size() != vms.size()) {
                    throw new IllegalStateException("Tidak ada snapshot alokasi lengkap sebelum CloudSim shutdown: "
                            + infrastructure.size() + "/" + vms.size());
                }
                    System.out.printf("VM allocation verified: %d/%d created across %d datacenters; failures=0%n",
                    createdVms.size(), vms.size(), datacenters.size());
            List<Host> hosts = new ArrayList<>();
            for (Datacenter dc : datacenters) {
                hosts.addAll(dc.getHostList());
            }

            MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
            MultiObjectiveEvaluator.ReferenceSet refSet = evaluator.buildReferenceSet(scenarioCloudlets, hosts);
            MultiObjectiveEvaluator.Evaluation evaluation = evaluator.evaluate(workload, hosts,
                    refSet.getMakespanReference(), refSet.getEnergyReference(),
                    wMakespan, wEnergy, wUtilization);
                long incomplete = workload.size() - finished.size();
                String status = incomplete == 0 ? "SUCCESS" : "PARTIAL";
                String reason = incomplete == 0 ? "" : "Incomplete cloudlets=" + incomplete
                    + "; unfinished/failed/canceled tasks count as SLA violations, once per task.";
                    return new RunResult(numJobs, runId, "DRRHA", status, reason, evaluation, workload, datacenters,
                        infrastructure, hashWorkload(workload), hashInfrastructure(infrastructure),
                        configFingerprint(cfg, wMakespan, wEnergy, wUtilization));
        } catch (Exception ex) {
                String reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                System.err.printf("Run %d dataset %d FAILED: %s%n", runId, numJobs, reason);
            ex.printStackTrace(System.err);
            long slaViolations = new MultiObjectiveEvaluator().countSlaViolations(workload);
                return new RunResult(numJobs, runId, "DRRHA", "FAILED", reason,
                new MultiObjectiveEvaluator.Evaluation(0.0, 0.0, 0.0, 0.0, 0.0,
                    slaViolations, workload.size(), 0.0, 0.0, 0.0),
                        workload, List.of(), infrastructure, hashWorkload(workload),
                        hashInfrastructure(infrastructure), configFingerprint(cfg, wMakespan, wEnergy, wUtilization));
        }
    }

    private static int[] parseScenarios(String csv) {
        String[] parts = csv.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private static String hashWorkload(List<Cloudlet> cloudlets) {
        StringBuilder canonical = new StringBuilder();
        for (Cloudlet cloudlet : cloudlets) canonical.append(cloudlet.getLength()).append('\n');
        return sha256(canonical.toString());
    }

    private static String hashInfrastructure(List<InfraBuilder.VmAllocationSnapshot> rows) {
        StringBuilder canonical = new StringBuilder();
        rows.stream().sorted(java.util.Comparator.comparingLong(InfraBuilder.VmAllocationSnapshot::vmId))
                .forEach(row -> canonical.append(row.datacenterId()).append(',').append(row.hostId()).append(',')
                        .append(row.vmId()).append(',').append(row.vmCategory()).append(',').append(row.vmPes())
                        .append(',').append(row.vmMips()).append(',').append(row.vmRamMb()).append(',')
                        .append(row.vmBwMbps()).append(',').append(row.vmStorageMb()).append(',')
                        .append(row.hostPes()).append(',').append(row.hostMips()).append(',')
                        .append(row.hostRamMb()).append(',').append(row.hostBwMbps()).append(',')
                        .append(row.hostStorageMb()).append('\n'));
        return sha256(canonical.toString());
    }

    private static String configFingerprint(Config cfg, double wMakespan, double wEnergy, double wUtilization) {
        String configuration = String.join("|", "host.pe.mips=" + cfg.getDouble("host.pe.mips"),
                "vm.low=" + cfg.getInt("vm.count.low"), "vm.medium=" + cfg.getInt("vm.count.medium"),
                "vm.high=" + cfg.getInt("vm.count.high"), "seed=" + cfg.getLong("random.seed"),
                "weights=" + wMakespan + "," + wEnergy + "," + wUtilization);
        return sha256(configuration);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    public static final class RunResult {
        public final int datasetId;
        public final int runId;
        public final String algorithm;
        public final String status;
        public final String failureReason;
        public final MultiObjectiveEvaluator.Evaluation evaluation;
        public final List<Cloudlet> cloudlets;
        public final List<Datacenter> datacenters;
        public final List<InfraBuilder.VmAllocationSnapshot> infrastructure;
        public final String inputSha256;
        public final String placementSha256;
        public final String configSha256;

        RunResult(int datasetId, int runId, String algorithm, String status, String failureReason,
                  MultiObjectiveEvaluator.Evaluation evaluation,
              List<Cloudlet> cloudlets, List<Datacenter> datacenters,
                  List<InfraBuilder.VmAllocationSnapshot> infrastructure,
                  String inputSha256, String placementSha256, String configSha256) {
            this.datasetId = datasetId;
            this.runId = runId;
            this.algorithm = algorithm;
            this.status = status;
            this.failureReason = failureReason;
            this.evaluation = evaluation;
            this.cloudlets = cloudlets;
            this.datacenters = datacenters;
            this.infrastructure = infrastructure;
            this.inputSha256 = inputSha256;
            this.placementSha256 = placementSha256;
            this.configSha256 = configSha256;
        }

        public int submittedCount() { return cloudlets.size(); }
        public int finishedCount() { return (int) cloudlets.stream().filter(c -> c.getStatus() == Cloudlet.Status.SUCCESS).count(); }
        public int failedCount() { return (int) cloudlets.stream().filter(c -> c.getStatus() == Cloudlet.Status.FAILED
                || c.getStatus() == Cloudlet.Status.CANCELED).count(); }
        public int unfinishedCount() { return submittedCount() - finishedCount() - failedCount(); }
    }
}
