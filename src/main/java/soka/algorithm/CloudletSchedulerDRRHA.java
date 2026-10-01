package soka.algorithm;

import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.schedulers.MipsShare;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerAbstract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CloudletScheduler per-VM yang menjalankan DRRHA (Dynamic Round Robin
 * Heuristic Algorithm) sesuai slide kelompok 6:
 *   1. Ready queue diurutkan SJF (sisa burst time terkecil duluan).
 *   2. Quantum dihitung per task lewat DRRHAEngine (QT = (M/2) + (M/2)/BT).
 *   3. Task dieksekusi; kalau sisa burst time > quantum -> dipreempt, balik
 *      ke ekor ready queue. Kalau <= quantum -> selesai total.
 *   4. Setiap ada task baru masuk / task selesai, mean & quantum dihitung ulang.
 *
 * Satu instance dipasang per VM lewat Vm.setCloudletScheduler(...), sehingga
 * tiap VM punya ready queue & siklus DRRHA masing-masing (lihat InfraBuilder).
 */
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

    /** M dihitung dari seluruh ready queue (running + waiting); BT = sisa task yang berjalan. */
    private double calculateCurrentQuantum(CloudletExecution runningCloudlet) {
        List<Long> remainingLengths = new ArrayList<>();
        long ownRemaining = runningCloudlet.getRemainingCloudletLength();
        remainingLengths.add(ownRemaining);
        for (CloudletExecution waitingCloudlet : getCloudletWaitingList()) {
            remainingLengths.add(waitingCloudlet.getRemainingCloudletLength());
        }
        return engine.calculateQuantum(remainingLengths, ownRemaining);
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

    /** Pengurutan SJF: sisa burst time terkecil di depan ready queue. */
    private void sortWaitingCloudlets() {
        sortCloudletWaitingList(Comparator.comparingLong(CloudletExecution::getRemainingCloudletLength));
    }

    private CloudletExecution getRunningCloudlet() {
        return getCloudletExecList().isEmpty() ? null : getCloudletExecList().get(0);
    }
}
