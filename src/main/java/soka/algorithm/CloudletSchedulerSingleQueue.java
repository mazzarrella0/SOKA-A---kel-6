package soka.algorithm;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.schedulers.MipsShare;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerAbstract;

import java.util.Comparator;

/**
 * Scheduler pembanding NON-preemptive, satu cloudlet berjalan per VM
 * (semantik sama dengan CloudletSchedulerDRRHA: "VM = satu CPU").
 *   - sortByLength=false -> FCFS (urutan kedatangan)
 *   - sortByLength=true  -> SJF  (antrean menunggu diurutkan panjang terkecil)
 * Catatan: cloudlet pertama yang tiba langsung berjalan (sama seperti DRRHA).
 */
public class CloudletSchedulerSingleQueue extends CloudletSchedulerAbstract {

    private final boolean sortByLength;

    public CloudletSchedulerSingleQueue(boolean sortByLength) {
        this.sortByLength = sortByLength;
    }

    @Override
    protected boolean canExecuteCloudletInternal(CloudletExecution cloudlet) {
        return getCloudletExecList().isEmpty();
    }

    @Override
    public double cloudletResume(Cloudlet cloudlet) {
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
        if (sortByLength) {
            sortCloudletWaitingList(Comparator.comparingLong(CloudletExecution::getRemainingCloudletLength));
        }
        return super.updateProcessing(currentTime, mipsShare);
    }
}
