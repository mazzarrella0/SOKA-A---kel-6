package soka.metrics;

import org.cloudsimplus.cloudlets.Cloudlet;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ResultReporter {

	public void writeEvaluationCsv(MultiObjectiveEvaluator.Evaluation evaluation,
								   Path outputPath) throws IOException {
		if (outputPath.getParent() != null) {
			Files.createDirectories(outputPath.getParent());
		}

		try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
			writer.write("makespan,energy_wh,utilization,normalized_makespan,normalized_energy,weighted_score");
			writer.newLine();
			writer.write(String.format(java.util.Locale.US,
					"%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
					evaluation.getMakespan(),
					evaluation.getEnergyWh(),
					evaluation.getUtilization(),
					evaluation.getNormalizedMakespan(),
					evaluation.getNormalizedEnergy(),
					evaluation.getWeightedScore()));
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

				writer.write(String.format(java.util.Locale.US,
						"%d,%s,%d,%d,%d,%.6f,%.6f,%.6f",
						cloudlet.getId(),
						cloudlet.getStatus(),
						vmId,
						cloudlet.getLength(),
						cloudlet.getFinishedLengthSoFar(),
						cloudlet.getStartTime(),
						cloudlet.getFinishTime(),
						executionTime));
				writer.newLine();
			}
		}
	}
}
