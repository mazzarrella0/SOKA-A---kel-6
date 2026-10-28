package soka.algorithm;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Broker pemetaan Cloudlet -> VM dengan dua kebijakan:
 *  - ROUND_ROBIN (useRoundRobinMapping): task ke-i (urutan di dataset) dikirim ke VM
 *    ke-(i mod jumlah VM) dari daftar VM yang disubmit. Jumlah task sama rata ke tiap VM,
 *    tidak memperhatikan MIPS. Dibuat eksplisit karena default DatacenterBrokerSimple
 *    berputar mengikuti urutan internal VM yang sudah terbuat, dan urutan itu bisa
 *    berbeda antar run/algoritma sehingga hasil perbandingan tidak adil.
 *  - MIPS_AWARE (useMipsAwareMapping): tiap task dikirim ke VM dengan estimasi
 *    waktu selesai paling awal = busyUntil(VM) + length / MIPS(VM)
 *    (Earliest Finish Time, deterministik; seri -> VM urutan pertama).
 */
public class DatacenterBrokerDRRHA extends DatacenterBrokerSimple {
    public DatacenterBrokerDRRHA(CloudSimPlus simulation) {
        super(simulation, "DRRHA-Broker");
    }

    public void useRoundRobinMapping(List<Vm> vms, List<Cloudlet> cloudlets) {
        Map<Cloudlet, Vm> assignment = new IdentityHashMap<>();
        for (int i = 0; i < cloudlets.size(); i++) {
            assignment.put(cloudlets.get(i), vms.get(i % vms.size()));
        }
        setVmMapper(cloudlet -> {
            Vm vm = assignment.get(cloudlet);
            return (vm != null && vm.isCreated()) ? vm : Vm.NULL;
        });
    }

    public void useMipsAwareMapping(List<Vm> candidates) {
        Map<Vm, Double> busyUntil = new IdentityHashMap<>();
        Map<Cloudlet, Vm> chosen = new IdentityHashMap<>();
        setVmMapper(cloudlet -> {
            Vm cached = chosen.get(cloudlet);
            if (cached != null) {
                return cached;
            }
            Vm best = Vm.NULL;
            double bestFinish = Double.MAX_VALUE;
            for (Vm vm : candidates) {
                if (!vm.isCreated() || vm.getMips() <= 0) {
                    continue;
                }
                double finish = busyUntil.getOrDefault(vm, 0.0) + cloudlet.getLength() / vm.getMips();
                if (finish < bestFinish) {
                    bestFinish = finish;
                    best = vm;
                }
            }
            if (best != Vm.NULL) {
                busyUntil.put(best, bestFinish);
                chosen.put(cloudlet, best);
            }
            return best;
        });
    }
}
