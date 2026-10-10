package soka.metrics;

import org.cloudsimplus.cloudlets.Cloudlet;

import java.util.List;

/**
 * Analisis panjang task (revisi poin 5): rata-rata panjang task, lalu
 * klasifikasi SHORT (length <= rata-rata) dan LONG (length > rata-rata).
 * Median tetap dicatat sebagai informasi tambahan.
 */
public final class TaskLengthDistribution {
    private final double meanLength;
    private final double medianLength;
    private final int shortTaskCount;
    private final int longTaskCount;

    private TaskLengthDistribution(double meanLength, double medianLength,
                                   int shortTaskCount, int longTaskCount) {
        this.meanLength = meanLength;
        this.medianLength = medianLength;
        this.shortTaskCount = shortTaskCount;
        this.longTaskCount = longTaskCount;
    }

    public static TaskLengthDistribution from(List<Cloudlet> cloudlets) {
        long[] lengths = cloudlets.stream()
                .mapToLong(Cloudlet::getLength)
                .filter(length -> length > 0)
                .sorted()
                .toArray();
        if (lengths.length == 0) {
            throw new IllegalArgumentException("Dataset harus memiliki task dengan panjang positif.");
        }

        double sum = 0.0;
        for (long length : lengths) {
            sum += length;
        }
        double mean = sum / lengths.length;

        int middle = lengths.length / 2;
        double median = lengths.length % 2 == 0
                ? (lengths[middle - 1] / 2.0) + (lengths[middle] / 2.0)
                : lengths[middle];

        int shortCount = 0;
        for (long length : lengths) {
            if (length <= mean) {
                shortCount++;
            }
        }
        return new TaskLengthDistribution(mean, median, shortCount, lengths.length - shortCount);
    }

    public double getMeanLength() { return meanLength; }
    public double getMedianLength() { return medianLength; }
    public int getShortTaskCount() { return shortTaskCount; }
    public int getLongTaskCount() { return longTaskCount; }

    public double getShortTaskPercent() {
        return shortTaskCount * 100.0 / (shortTaskCount + longTaskCount);
    }

    public double getLongTaskPercent() {
        return longTaskCount * 100.0 / (shortTaskCount + longTaskCount);
    }
}
