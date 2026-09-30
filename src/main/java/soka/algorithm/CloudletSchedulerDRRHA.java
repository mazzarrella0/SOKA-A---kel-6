package soka.algorithm;

import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.schedulers.MipsShare;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerAbstract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CloudletSchedulerDRRHA extends CloudletSchedulerAbstract {

	private final DRRHAEngine engine;
	private final double contextSwitchDelay;
	private double nextQuantumTime;
	private long contextSwitchCount;

	public CloudletSchedulerDRRHA() {
		this(new DRRHAEngine(), 0.0);
	}

	public CloudletSchedulerDRRHA(DRRHAEngine engine, double contextSwitchDelay) {
		this.engine = engine;
		this.contextSwitchDelay = Math.max(0.0, contextSwitchDelay);
	}

	public long getContextSwitchCount() {
		return contextSwitchCount;
	}

	public double getNextQuantumTime() {
		return nextQuantumTime;
	}

	@Override
	protected boolean canExecuteCloudletInternal(CloudletExecution cloudlet) {
		return getCloudletExecList().isEmpty();
	}

	@Override
	public double cloudletResume(org.cloudsimplus.cloudlets.Cloudlet cloudlet) {
		return findCloudletInList(cloudlet, getCloudletPausedList())
				.map(pausedCloudlet -> {
					getCloudletPausedList().remove(pausedCloudlet);
					addCloudletToExecList(pausedCloudlet);
					return cloudletEstimatedFinishTime(
							pausedCloudlet, getVm().getSimulation().clock());
				})
				.orElse(0.0);
	}

	@Override
	public double updateProcessing(double currentTime, MipsShare mipsShare) {
		sortWaitingCloudlets();
		boolean quantumExpired = nextQuantumTime > 0.0
			&& currentTime >= nextQuantumTime - 1.0e-9;

		CloudletExecution runningCloudlet = getRunningCloudlet();
		double nextEventTime = super.updateProcessing(currentTime, mipsShare);

		runningCloudlet = getRunningCloudlet();
		if (runningCloudlet == null) {
			nextQuantumTime = 0.0;
			return nextEventTime;
		}

		if (quantumExpired && runningCloudlet.getRemainingCloudletLength() > 0) {
			preempt(runningCloudlet);
			return currentTime + contextSwitchDelay;
		}

		if (nextQuantumTime <= currentTime) {
			double quantum = calculateCurrentQuantum(runningCloudlet);
			double allocatedMips = getAllocatedMipsForCloudlet(runningCloudlet, mipsShare.mips());
			if (allocatedMips > 0) {
				nextQuantumTime = currentTime + quantum / allocatedMips;
			}
		}

		return Math.min(nextEventTime, nextQuantumTime);
	}

	private double calculateCurrentQuantum(CloudletExecution runningCloudlet) {
		List<Long> remainingLengths = new ArrayList<>();
		remainingLengths.add(runningCloudlet.getRemainingCloudletLength());
		for (CloudletExecution waitingCloudlet : getCloudletWaitingList()) {
			remainingLengths.add(waitingCloudlet.getRemainingCloudletLength());
		}
		return engine.calculateQuantum(remainingLengths);
	}

	private void preempt(CloudletExecution cloudlet) {
		if (!cloudletPause(cloudlet.getCloudlet())) {
			return;
		}

		cloudletReady(cloudlet.getCloudlet());
		sortWaitingCloudlets();
		contextSwitchCount++;
		nextQuantumTime = 0.0;
	}

	private void sortWaitingCloudlets() {
		sortCloudletWaitingList(Comparator.comparingLong(CloudletExecution::getRemainingCloudletLength));
	}

	private CloudletExecution getRunningCloudlet() {
		return getCloudletExecList().isEmpty() ? null : getCloudletExecList().get(0);
	}
}
