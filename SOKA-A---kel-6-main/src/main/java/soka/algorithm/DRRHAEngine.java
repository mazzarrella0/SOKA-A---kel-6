package soka.algorithm;

import java.util.Collection;

/**
 * Perhitungan time quantum DRRHA sesuai slide "Algoritma Heuristik Task
 * Scheduling" (kelompok 6):
 *
 *   QT = (M/2) + (M/2)/BT
 *
 * dengan M = rata-rata sisa burst time SELURUH task di ready queue
 * (termasuk task yang sedang berjalan), dan BT = sisa burst time task yang
 * SEDANG dihitung quantum-nya.
 *
 * CATATAN DESAIN: rumus ini secara satuan sedikit tidak konsisten (suku
 * pertama berdimensi "waktu/panjang", suku kedua berdimensi rasio tanpa
 * satuan) -- tapi diimplementasikan PERSIS seperti di slide/paper acuan,
 * bukan disederhanakan, supaya kode bisa dipertanggungjawabkan 1:1 ke
 * dosen saat demo. M dan BT di sini memakai satuan MI (Million
 * Instructions), konsisten dengan Cloudlet Length (Draft 2.4).
 */
public class DRRHAEngine {

    /** Batas bawah quantum supaya simulasi tidak stuck di angka 0/negatif. */
    public static final double MIN_QUANTUM = 1.0;

    /**
     * @param readyQueueRemainingLengths sisa panjang (MI) SEMUA task di ready
     *                                    queue, termasuk task yang sedang berjalan.
     * @param ownRemainingLength          sisa panjang (MI) task yang sedang
     *                                    dihitung quantum-nya (BT pada rumus).
     */
    public double calculateQuantum(Collection<Long> readyQueueRemainingLengths, long ownRemainingLength) {
        if (readyQueueRemainingLengths == null || readyQueueRemainingLengths.isEmpty()) {
            return 0.0;
        }

        long total = 0;
        int count = 0;
        for (long length : readyQueueRemainingLengths) {
            if (length > 0) {
                total += length;
                count++;
            }
        }
        if (count == 0) {
            return 0.0;
        }

        double mean = (double) total / count; // M
        if (ownRemainingLength <= 0) {
            return Math.max(MIN_QUANTUM, mean / 2.0);
        }

        double quantum = (mean / 2.0) + (mean / 2.0) / ownRemainingLength; // QT = (M/2) + (M/2)/BT
        return Math.max(MIN_QUANTUM, quantum);
    }
}
