package soka.metrics;

import org.cloudsimplus.cloudlets.Cloudlet;

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
                                    Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("makespan,energy_wh,utilization,avg_waiting_time,load_balancing_degree,"
                    + "sla_violations,total_cloudlets,normalized_makespan,normalized_energy,weighted_score,"
                    + "median_task_length_mi,short_task_count,long_task_count,short_task_percent,long_task_percent");
            writer.newLine();
            writer.write(String.format(Locale.US,
                    "%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%.6f,%.6f,%.8f,%.1f,%d,%d,%.6f,%.6f",
                    evaluation.getMakespan(),
                    evaluation.getEnergyWh(),
                    evaluation.getUtilization(),
                    evaluation.getAvgWaitingTime(),
                    evaluation.getLoadBalancingDegree(),
                    evaluation.getSlaViolations(),
                    evaluation.getTotalCloudlets(),
                    evaluation.getNormalizedMakespan(),
                    evaluation.getNormalizedEnergy(),
                    evaluation.getWeightedScore(),
                    taskDistribution.getMedianLength(),
                    taskDistribution.getShortTaskCount(),
                    taskDistribution.getLongTaskCount(),
                    taskDistribution.getShortTaskPercent(),
                    taskDistribution.getLongTaskPercent()));
            writer.newLine();
        }
    }

    public void writeCloudletsCsv(List<Cloudlet> cloudlets, Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("cloudlet_id,status,vm_id,length_mi,finished_length_mi,start_time,finish_time,execution_time");
            writer.newLine();
            for (Cloudlet cloudlet : cloudlets) {
                double executionTime = cloudlet.getFinishTime() - cloudlet.getStartTime();
                long vmId = cloudlet.getVm() == null ? -1 : cloudlet.getVm().getId();
                writer.write(String.format(Locale.US,
                        "%d,%s,%d,%d,%d,%.6f,%.6f,%.6f",
                        cloudlet.getId(), cloudlet.getStatus(), vmId,
                        cloudlet.getLength(), cloudlet.getFinishedLengthSoFar(),
                        cloudlet.getStartTime(), cloudlet.getFinishTime(), executionTime));
                writer.newLine();
            }
        }
    }
}
