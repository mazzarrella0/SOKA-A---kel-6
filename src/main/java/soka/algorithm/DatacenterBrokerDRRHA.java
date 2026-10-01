package soka.algorithm;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.core.CloudSimPlus;

/**
 * Broker pemetaan Cloudlet -> VM. ASUMSI DESAIN: pemilihan VM untuk tiap
 * task memakai kebijakan default DatacenterBrokerSimple (round-robin di
 * antara VM yang tersedia); DRRHA sendiri bekerja di LEVEL PER-VM lewat
 * CloudletSchedulerDRRHA (lihat kelas itu) yang mengatur ready queue dan
 * preemption di dalam satu VM. Jadi "VM = CPU" pada analogi paper DRRHA
 * (algoritma CPU scheduling single-processor), dan broker ini yang membagi
 * task independen (Draft 1.1) ke VM mana pun yang tersedia.
 */
public class DatacenterBrokerDRRHA extends DatacenterBrokerSimple {
    public DatacenterBrokerDRRHA(CloudSimPlus simulation) {
        super(simulation, "DRRHA-Broker");
    }
}
