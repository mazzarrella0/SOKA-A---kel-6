package soka.metrics;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class ResultReporter {

    public void writeEvaluationCsv(MultiObjectiveEvaluator.Evaluation evaluation,
                                    TaskLengthDistribution taskDistribution,
                                    double weightMakespan, double weightEnergy, double weightUtilization,
                                    Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("makespan,energy_wh,utilization,avg_waiting_time,load_balancing_degree,"
                    + "sla_violations,total_cloudlets,makespan_reference,energy_reference,normalized_makespan,normalized_energy,weighted_score,"
                    + "mean_sla_violations,sla_violation_rate,makespan_stddev,energy_stddev,utilization_stddev,"
                    + "waiting_time_stddev,sla_violations_stddev,median_task_length_mi,short_task_count,long_task_count,"
                    + "short_task_percent,long_task_percent,weight_makespan,weight_energy,weight_utilization");
            writer.newLine();
            writer.write(String.format(Locale.US,
                    "%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g",
                    evaluation.getMakespan(),
                    evaluation.getEnergyWh(),
                    evaluation.getUtilization(),
                    evaluation.getAvgWaitingTime(),
                    evaluation.getLoadBalancingDegree(),
                    evaluation.getSlaViolations(),
                    evaluation.getTotalCloudlets(),
                    evaluation.getMakespanReference(),
                    evaluation.getEnergyReference(),
                    evaluation.getNormalizedMakespan(),
                    evaluation.getNormalizedEnergy(),
                    evaluation.getWeightedScore(),
                    evaluation.getMeanSlaViolations(),
                    evaluation.getSlaViolationRate(),
                    evaluation.getMakespanStdDev(),
                    evaluation.getEnergyStdDev(),
                    evaluation.getUtilizationStdDev(),
                    evaluation.getAvgWaitingTimeStdDev(),
                    evaluation.getSlaViolationsStdDev(),
                    taskDistribution.getMedianLength(),
                    taskDistribution.getShortTaskCount(),
                    taskDistribution.getLongTaskCount(),
                    taskDistribution.getShortTaskPercent(),
                    taskDistribution.getLongTaskPercent(),
                    weightMakespan,
                    weightEnergy,
                    weightUtilization));
            writer.newLine();
        }
    }

    public void writeCloudletsCsv(List<Cloudlet> cloudlets, Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                    writer.write("cloudlet_id,status,vm_id,length_mi,finished_length_mi,arrival_time,start_time,response_time,"
                        + "finish_time,execution_time,waiting_time,turnaround_time,ideal_execution_time,deadline,"
                        + "sla_completion_time,sla_lateness,sla_violation,sla_reason");
            writer.newLine();
                MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
            for (Cloudlet cloudlet : cloudlets) {
                if (cloudlet == null) {
                    continue;
                }
                double arrivalTime = 0.0;
                double executionTime = Math.max(0.0, cloudlet.getTotalExecutionTime());
                double responseTime = cloudlet.getStartTime() < 0.0 ? -1.0 : cloudlet.getStartTime() - arrivalTime;
                double waitingTime = cloudlet.getFinishTime() < 0.0 ? -1.0
                    : Math.max(0.0, cloudlet.getFinishTime() - arrivalTime - executionTime);
                double turnaroundTime = cloudlet.getFinishTime() < 0.0 ? -1.0 : cloudlet.getFinishTime() - arrivalTime;
                long vmId = cloudlet.getVm() == null ? -1 : cloudlet.getVm().getId();
                MultiObjectiveEvaluator.SlaAssessment sla = evaluator.assessSla(cloudlet);
                writer.write(String.format(Locale.US,
                        "%d,%s,%d,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%s,%s",
                        cloudlet.getId(), cloudlet.getStatus(), vmId,
                        cloudlet.getLength(), cloudlet.getFinishedLengthSoFar(),
                        arrivalTime, cloudlet.getStartTime(), responseTime, cloudlet.getFinishTime(),
                        executionTime, waitingTime, turnaroundTime,
                    sla.idealExecutionTime(), sla.deadline(), sla.completionTime(), sla.lateness(),
                    sla.violation(), sla.reason()));
                writer.newLine();
            }
        }
    }

    public void writeInfrastructureCsv(int scenarioId, List<Datacenter> datacenters, Path outputPath) throws IOException {
        List<soka.InfraBuilder.VmAllocationSnapshot> rows = new java.util.ArrayList<>();
        for (Datacenter datacenter : datacenters) {
            for (Host host : datacenter.getHostList()) {
                for (Vm vm : host.getVmList()) {
                    rows.add(new soka.InfraBuilder.VmAllocationSnapshot(datacenter.getId(), host.getId(),
                            host.getPesNumber(), host.getTotalMipsCapacity(), host.getRam().getCapacity(),
                            host.getBw().getCapacity(), host.getStorage().getCapacity(), vm.getId(),
                            inferVmCategory(vm), vm.getPesNumber(), vm.getMips(), vm.getRam().getCapacity(),
                            vm.getBw().getCapacity(), vm.getStorage().getCapacity()));
                }
            }
        }
        writeInfrastructureSnapshotCsv(scenarioId, rows, outputPath);
    }

    public void writeInfrastructureSnapshotCsv(int scenarioId,
                                               List<soka.InfraBuilder.VmAllocationSnapshot> rows,
                                               Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                writer.write("scenario_id,datacenter_id,host_id,host_pes,host_mips,host_ram_mb,host_bw_mbps,host_storage_mb,"
                    + "vm_id,vm_category,vm_pes,vm_mips,vm_ram_mb,vm_bw_mbps,vm_storage_mb");
            writer.newLine();
            for (soka.InfraBuilder.VmAllocationSnapshot row : rows) {
                writer.write(String.format(Locale.US,
                        "%d,%d,%d,%d,%.0f,%d,%d,%d,%d,%s,%d,%.0f,%d,%d,%d%n",
                        scenarioId, row.datacenterId(), row.hostId(), row.hostPes(), row.hostMips(),
                        row.hostRamMb(), row.hostBwMbps(), row.hostStorageMb(), row.vmId(), row.vmCategory(),
                        row.vmPes(), row.vmMips(), row.vmRamMb(), row.vmBwMbps(), row.vmStorageMb()));
            }
        }
    }

    public void writeRunSummaryCsv(int scenarioId, int runId, String algorithm, String status, String failureReason,
                                  MultiObjectiveEvaluator.Evaluation evaluation, soka.Main.RunResult runResult,
                                  Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                writer.write("scenario_id,dataset_id,algorithm,run_id,status,failure_reason,submitted,finished,failed,unfinished,"
                    + "input_sha256,placement_sha256,config_sha256,makespan,energy_wh,utilization,"
                    + "avg_waiting_time,load_balancing_degree,sla_violations,sla_violation_rate,total_cloudlets,weighted_score,"
                    + "makespan_reference,energy_reference,normalized_makespan,normalized_energy");
            writer.newLine();
            writer.write(String.format(Locale.US,
                    "%d,%d,%s,%d,%s,%s,%d,%d,%d,%d,%s,%s,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%d,%.17g,%.17g,%.17g,%.17g,%.17g",
                    scenarioId,
                    scenarioId,
                    algorithm,
                    runId,
                    status,
                    csvEscape(failureReason),
                    runResult.submittedCount(), runResult.finishedCount(), runResult.failedCount(), runResult.unfinishedCount(),
                    runResult.inputSha256, runResult.placementSha256, runResult.configSha256,
                    evaluation.getMakespan(),
                    evaluation.getEnergyWh(),
                    evaluation.getUtilization(),
                    evaluation.getAvgWaitingTime(),
                    evaluation.getLoadBalancingDegree(),
                    evaluation.getSlaViolations(),
                    evaluation.getSlaViolationRate(),
                    evaluation.getTotalCloudlets(),
                    evaluation.getWeightedScore(),
                    evaluation.getMakespanReference(), evaluation.getEnergyReference(),
                    evaluation.getNormalizedMakespan(), evaluation.getNormalizedEnergy()));
            writer.newLine();
        }
    }

    public void writeScenarioRunSummaryCsv(int scenarioId, String algorithm, List<soka.Main.RunResult> runResults,
                                           double weightMakespan, double weightEnergy, double weightUtilization,
                                           Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                writer.write("scenario_id,dataset_id,algorithm,run_id,status,failure_reason,makespan,energy_wh,utilization,"
                    + "avg_waiting_time,load_balancing_degree,sla_violations,mean_sla_violations,sla_violation_rate,"
                    + "submitted,finished,failed,unfinished,input_sha256,placement_sha256,config_sha256,total_cloudlets,"
                    + "makespan_reference,energy_reference,normalized_makespan,normalized_energy,weighted_score,"
                    + "weight_makespan,weight_energy,weight_utilization");
            writer.newLine();
            for (soka.Main.RunResult runResult : runResults) {
                MultiObjectiveEvaluator.Evaluation evaluation = runResult.evaluation;
                writer.write(String.format(Locale.US,
                        "%d,%d,%s,%d,%s,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%d,%d,%d,%d,%s,%s,%s,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                        scenarioId,
                        runResult.datasetId,
                        algorithm,
                        runResult.runId,
                        runResult.status,
                        csvEscape(runResult.failureReason),
                        evaluation.getMakespan(),
                        evaluation.getEnergyWh(),
                        evaluation.getUtilization(),
                        evaluation.getAvgWaitingTime(),
                        evaluation.getLoadBalancingDegree(),
                        evaluation.getSlaViolations(),
                        evaluation.getMeanSlaViolations(),
                        evaluation.getSlaViolationRate(),
                        runResult.submittedCount(), runResult.finishedCount(), runResult.failedCount(), runResult.unfinishedCount(),
                        runResult.inputSha256, runResult.placementSha256, runResult.configSha256,
                        evaluation.getTotalCloudlets(),
                        evaluation.getMakespanReference(), evaluation.getEnergyReference(),
                        evaluation.getNormalizedMakespan(),
                        evaluation.getNormalizedEnergy(),
                        evaluation.getWeightedScore(), weightMakespan, weightEnergy, weightUtilization));
            }
        }
    }

    public void writeReproducibilityCsv(int scenarioId, List<soka.Main.RunResult> runs,
                                        Path outputPath) throws IOException {
        if (outputPath.getParent() != null) Files.createDirectories(outputPath.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("dataset_id,run_a,run_b,status_a,status_b,input_identical,mapping_identical,"
                    + "task_order_status_vm_identical,metrics_identical,fully_reproducible");
            writer.newLine();
            for (int left = 0; left < runs.size(); left++) {
                for (int right = left + 1; right < runs.size(); right++) {
                    soka.Main.RunResult first = runs.get(left);
                    soka.Main.RunResult second = runs.get(right);
                    boolean inputSame = first.inputSha256.equals(second.inputSha256);
                    boolean mappingSame = first.placementSha256.equals(second.placementSha256);
                    boolean tasksSame = taskOutcomeHash(first.cloudlets).equals(taskOutcomeHash(second.cloudlets));
                    boolean metricsSame = metricsEqual(first.evaluation, second.evaluation);
                    boolean reproducible = "SUCCESS".equals(first.status) && "SUCCESS".equals(second.status)
                            && inputSame && mappingSame && tasksSame && metricsSame;
                    writer.write(String.format(Locale.US, "%d,%d,%d,%s,%s,%s,%s,%s,%s,%s%n",
                            scenarioId, first.runId, second.runId, first.status, second.status,
                            inputSame, mappingSame, tasksSame, metricsSame, reproducible));
                }
            }
        }
    }

    private String taskOutcomeHash(List<Cloudlet> cloudlets) {
        StringBuilder canonical = new StringBuilder();
        for (Cloudlet cloudlet : cloudlets) {
            canonical.append(cloudlet.getId()).append(',').append(cloudlet.getLength()).append(',')
                    .append(cloudlet.getStatus()).append(',')
                    .append(cloudlet.getVm() == null ? -1 : cloudlet.getVm().getId()).append(',')
                    .append(Double.toHexString(cloudlet.getStartTime())).append(',')
                    .append(Double.toHexString(cloudlet.getFinishTime())).append(',')
                    .append(Double.toHexString(cloudlet.getTotalExecutionTime())).append('\n');
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private boolean metricsEqual(MultiObjectiveEvaluator.Evaluation first,
                                 MultiObjectiveEvaluator.Evaluation second) {
        return Double.compare(first.getMakespan(), second.getMakespan()) == 0
                && Double.compare(first.getEnergyWh(), second.getEnergyWh()) == 0
                && Double.compare(first.getUtilization(), second.getUtilization()) == 0
                && Double.compare(first.getAvgWaitingTime(), second.getAvgWaitingTime()) == 0
                && Double.compare(first.getLoadBalancingDegree(), second.getLoadBalancingDegree()) == 0
                && Double.compare(first.getSlaViolations(), second.getSlaViolations()) == 0
                && Double.compare(first.getSlaViolationRate(), second.getSlaViolationRate()) == 0
                && first.getTotalCloudlets() == second.getTotalCloudlets()
                && Double.compare(first.getMakespanReference(), second.getMakespanReference()) == 0
                && Double.compare(first.getEnergyReference(), second.getEnergyReference()) == 0
                && Double.compare(first.getNormalizedMakespan(), second.getNormalizedMakespan()) == 0
                && Double.compare(first.getNormalizedEnergy(), second.getNormalizedEnergy()) == 0
                && Double.compare(first.getWeightedScore(), second.getWeightedScore()) == 0;
    }

    public void writeDatasetStatisticsCsv(int numJobs, TaskLengthDistribution distribution, Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
                writer.write("dataset_id,task_count,min_length_mi,max_length_mi,mean_length_mi,median_length_mi,q1_length_mi,q3_length_mi,std_length_mi,"
                    + "short_threshold_mi,short_threshold_rule,short_task_count,long_task_count,short_task_percentage,long_task_percentage");
            writer.newLine();
            writer.write(String.format(Locale.US,
                    "%d,%d,%.1f,%.1f,%.6f,%.1f,%.1f,%.1f,%.6f,%.1f,%s,%d,%d,%.6f,%.6f",
                    numJobs,
                    numJobs,
                    distribution.getMinLength(),
                    distribution.getMaxLength(),
                    distribution.getMeanLength(),
                    distribution.getMedianLength(),
                    distribution.getFirstQuartileLength(),
                    distribution.getThirdQuartileLength(),
                    distribution.getStdDevLength(),
                    distribution.getMedianLength(),
                    "short<=median;long>median",
                    distribution.getShortTaskCount(),
                    distribution.getLongTaskCount(),
                    distribution.getShortTaskPercent(),
                    distribution.getLongTaskPercent()));
            writer.newLine();
        }
    }

    public void writeTaskLengthHistogram(int numJobs, TaskLengthDistribution distribution,
                                         Path outputPath) throws IOException {
        if (outputPath.getParent() != null) Files.createDirectories(outputPath.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("dataset_id,bin_index,lower_bound_mi,upper_bound_mi,count,percentage");
            writer.newLine();
            List<TaskLengthDistribution.HistogramBin> bins = distribution.histogram(10);
            for (int i = 0; i < bins.size(); i++) {
                TaskLengthDistribution.HistogramBin bin = bins.get(i);
                writer.write(String.format(Locale.US, "%d,%d,%.17g,%.17g,%d,%.17g%n", numJobs, i + 1,
                        bin.lowerInclusive(), bin.upperExclusive(), bin.count(), bin.percentage()));
            }
        }
    }

    public void writeExperimentSummaryCsv(List<Integer> scenarioIds,
                                          List<MultiObjectiveEvaluator.Evaluation> evaluations,
                                          double weightMakespan, double weightEnergy, double weightUtilization,
                                          Path outputPath) throws IOException {
        if (scenarioIds.size() != evaluations.size()) {
            throw new IllegalArgumentException("Jumlah skenario dan evaluasi agregat harus sama");
        }
        if (outputPath.getParent() != null) Files.createDirectories(outputPath.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("dataset_id,aggregation,makespan_mean,energy_wh_mean,utilization_mean,waiting_time_mean,"
                    + "load_balancing_mean,sla_violations_mean,sla_violation_rate_mean,weighted_score,"
                    + "weight_makespan,weight_energy,weight_utilization,scenario_count");
            writer.newLine();
            for (int index = 0; index < evaluations.size(); index++) {
                MultiObjectiveEvaluator.Evaluation evaluation = evaluations.get(index);
                writeExperimentSummaryRow(writer, scenarioIds.get(index), "scenario", evaluation.getMakespan(),
                    evaluation.getEnergyWh(), evaluation.getUtilization(), evaluation.getAvgWaitingTime(),
                    evaluation.getLoadBalancingDegree(), evaluation.getMeanSlaViolations(),
                    evaluation.getSlaViolationRate(), evaluation.getWeightedScore(),
                    weightMakespan, weightEnergy, weightUtilization, 1);
            }
            if (!evaluations.isEmpty()) {
                double makespan = evaluations.stream().mapToDouble(MultiObjectiveEvaluator.Evaluation::getMakespan).average().orElse(0.0);
                double energy = evaluations.stream().mapToDouble(MultiObjectiveEvaluator.Evaluation::getEnergyWh).average().orElse(0.0);
                double utilization = evaluations.stream().mapToDouble(MultiObjectiveEvaluator.Evaluation::getUtilization).average().orElse(0.0);
                double waiting = evaluations.stream().mapToDouble(MultiObjectiveEvaluator.Evaluation::getAvgWaitingTime).average().orElse(0.0);
                double loadBalance = evaluations.stream().mapToDouble(MultiObjectiveEvaluator.Evaluation::getLoadBalancingDegree).average().orElse(0.0);
                double slaCount = evaluations.stream().mapToDouble(MultiObjectiveEvaluator.Evaluation::getMeanSlaViolations).average().orElse(0.0);
                double slaRate = evaluations.stream().mapToDouble(MultiObjectiveEvaluator.Evaluation::getSlaViolationRate).average().orElse(0.0);
                double normalizedMakespan = evaluations.stream().mapToDouble(MultiObjectiveEvaluator.Evaluation::getNormalizedMakespan).average().orElse(0.0);
                double normalizedEnergy = evaluations.stream().mapToDouble(MultiObjectiveEvaluator.Evaluation::getNormalizedEnergy).average().orElse(0.0);
                double score = new MultiObjectiveEvaluator().calculateWeightedScore(normalizedMakespan,
                        normalizedEnergy, utilization, weightMakespan, weightEnergy, weightUtilization);
                writeExperimentSummaryRow(writer, -1, "unweighted_macro_average_across_datasets",
                    makespan, energy, utilization, waiting, loadBalance, slaCount, slaRate, score,
                    weightMakespan, weightEnergy, weightUtilization, evaluations.size());
            }
        }
    }

    private void writeExperimentSummaryRow(BufferedWriter writer, int datasetId, String aggregation,
                                          double makespan, double energy, double utilization, double waiting,
                                          double loadBalance, double meanSlaViolations, double slaViolationRate,
                                          double weightedScore,
                                          double weightMakespan, double weightEnergy,
                                          double weightUtilization, int scenarioCount) throws IOException {
        writer.write(String.format(Locale.US, "%d,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%d%n",
                datasetId, aggregation, makespan, energy, utilization, waiting, loadBalance,
                meanSlaViolations, slaViolationRate, weightedScore,
                weightMakespan, weightEnergy, weightUtilization, scenarioCount));
    }

    private String inferVmCategory(Vm vm) {
        long pes = vm.getPesNumber();
        double mips = vm.getMips();
        if (mips <= 1000.0 && pes == 1L) return "LOW";
        if (mips <= 2000.0 && pes == 2L) return "MEDIUM";
        if (mips <= 4000.0 && pes == 4L) return "HIGH";
        return "UNKNOWN";
    }

    private String csvEscape(String value) {
        if (value == null || value.isEmpty()) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
