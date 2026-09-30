package soka.metrics;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostStateHistoryEntry;

import java.util.List;

public class MultiObjectiveEvaluator {

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

		double normalizedMakespan = normalize(makespan, makespanReference);
		double normalizedEnergy = normalize(energy, energyReference);
		double objectiveUtilization = 1.0 - utilization;
		double score = weightMakespan * normalizedMakespan
				+ weightEnergy * normalizedEnergy
				+ weightUtilization * objectiveUtilization;

		return new Evaluation(makespan, energy, utilization,
				normalizedMakespan, normalizedEnergy, score);
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

	private double normalize(double value, double reference) {
		return reference <= 0.0 ? 0.0 : Math.min(1.0, value / reference);
	}

	public static final class Evaluation {
		private final double makespan;
		private final double energyWh;
		private final double utilization;
		private final double normalizedMakespan;
		private final double normalizedEnergy;
		private final double weightedScore;

		public Evaluation(double makespan, double energyWh, double utilization,
						  double normalizedMakespan, double normalizedEnergy,
						  double weightedScore) {
			this.makespan = makespan;
			this.energyWh = energyWh;
			this.utilization = utilization;
			this.normalizedMakespan = normalizedMakespan;
			this.normalizedEnergy = normalizedEnergy;
			this.weightedScore = weightedScore;
		}

		public double getMakespan() { return makespan; }
		public double getEnergyWh() { return energyWh; }
		public double getUtilization() { return utilization; }
		public double getNormalizedMakespan() { return normalizedMakespan; }
		public double getNormalizedEnergy() { return normalizedEnergy; }
		public double getWeightedScore() { return weightedScore; }
	}
}
