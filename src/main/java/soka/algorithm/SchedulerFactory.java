package soka.algorithm;

import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;

import java.util.Locale;

/** Membuat scheduler per-VM berdasarkan nama algoritma di config (compare.algorithms). */
public final class SchedulerFactory {
    private SchedulerFactory() {}

    public static CloudletScheduler create(String algorithm) {
        switch (algorithm.trim().toUpperCase(Locale.ROOT)) {
            case "DRRHA": return new CloudletSchedulerDRRHA();
            case "FCFS":  return new CloudletSchedulerSingleQueue(false);
            case "SJF":   return new CloudletSchedulerSingleQueue(true);
            default:
                throw new IllegalArgumentException("Algoritma tidak dikenal: " + algorithm
                        + " (pilihan: DRRHA, FCFS, SJF)");
        }
    }
}
