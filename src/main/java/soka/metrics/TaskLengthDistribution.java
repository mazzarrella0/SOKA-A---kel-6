package soka.metrics;

import org.cloudsimplus.cloudlets.Cloudlet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Mengklasifikasikan panjang task terhadap median dataset dan menghitung statistik dasar. */
public final class TaskLengthDistribution {
    public record HistogramBin(double lowerInclusive, double upperExclusive, int count, double percentage) {}

    private final double minLength;
    private final double maxLength;
    private final double meanLength;
    private final double medianLength;
    private final double firstQuartileLength;
    private final double thirdQuartileLength;
    private final double stdDevLength;
    private final int shortTaskCount;
    private final int longTaskCount;
    private final List<Long> histogramValues;

    private TaskLengthDistribution(double minLength, double maxLength, double meanLength,
                                  double medianLength, double firstQuartileLength, double thirdQuartileLength,
                                  double stdDevLength,
                                  int shortTaskCount, int longTaskCount, List<Long> histogramValues) {
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.meanLength = meanLength;
        this.medianLength = medianLength;
        this.firstQuartileLength = firstQuartileLength;
        this.thirdQuartileLength = thirdQuartileLength;
        this.stdDevLength = stdDevLength;
        this.shortTaskCount = shortTaskCount;
        this.longTaskCount = longTaskCount;
        this.histogramValues = List.copyOf(histogramValues);
    }

    public static TaskLengthDistribution from(List<Cloudlet> cloudlets) {
        List<Long> values = new ArrayList<>();
        for (Cloudlet cloudlet : cloudlets) {
            if (cloudlet != null && cloudlet.getLength() > 0) {
                values.add(cloudlet.getLength());
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Dataset harus memiliki task dengan panjang positif.");
        }

        values.sort(Comparator.naturalOrder());
        double min = values.get(0);
        double max = values.get(values.size() - 1);
        double mean = values.stream().mapToDouble(Long::doubleValue).average().orElse(0.0);

        double median;
        int middle = values.size() / 2;
        if (values.size() % 2 == 0) {
            median = (values.get(middle - 1) + values.get(middle)) / 2.0;
        } else {
            median = values.get(middle);
        }

        double firstQuartile = quantile(values, 0.25);
        double thirdQuartile = quantile(values, 0.75);

        double variance = values.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average()
                .orElse(0.0);
        double stdDev = Math.sqrt(variance);

        int shortCount = 0;
        for (Long value : values) {
            if (value <= median) {
                shortCount++;
            }
        }
        int longCount = values.size() - shortCount;

        return new TaskLengthDistribution(min, max, mean, median, firstQuartile, thirdQuartile,
            stdDev, shortCount, longCount, values);
    }

    private static double quantile(List<Long> sortedValues, double probability) {
        double position = probability * (sortedValues.size() - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        double fraction = position - lowerIndex;
        return sortedValues.get(lowerIndex) * (1.0 - fraction) + sortedValues.get(upperIndex) * fraction;
    }

    public List<HistogramBin> histogram(int binCount) {
        if (binCount < 1) throw new IllegalArgumentException("Jumlah bin histogram harus positif");
        double width = maxLength == minLength ? 1.0 : (maxLength - minLength) / binCount;
        int[] counts = new int[binCount];
        for (int bin = 0; bin < binCount; bin++) {
            double lower = minLength + bin * width;
            double upper = bin == binCount - 1 ? Double.POSITIVE_INFINITY : lower + width;
            final int index = bin;
            for (Long length : getValuesForHistogram()) {
                if (length >= lower && (index == binCount - 1 || length < upper)) counts[bin]++;
            }
        }
        int total = shortTaskCount + longTaskCount;
        List<HistogramBin> bins = new ArrayList<>();
        for (int bin = 0; bin < binCount; bin++) {
            double lower = minLength + bin * width;
            double upper = bin == binCount - 1 ? maxLength : lower + width;
            bins.add(new HistogramBin(lower, upper, counts[bin], total == 0 ? 0.0 : counts[bin] * 100.0 / total));
        }
        return List.copyOf(bins);
    }

    private List<Long> getValuesForHistogram() {
        return histogramValues;
    }

    public double getMinLength() { return minLength; }
    public double getMaxLength() { return maxLength; }
    public double getMeanLength() { return meanLength; }
    public double getMedianLength() { return medianLength; }
    public double getFirstQuartileLength() { return firstQuartileLength; }
    public double getThirdQuartileLength() { return thirdQuartileLength; }
    public double getStdDevLength() { return stdDevLength; }
    public int getShortTaskCount() { return shortTaskCount; }
    public int getLongTaskCount() { return longTaskCount; }
    public double getShortTaskPercent() {
        double total = shortTaskCount + longTaskCount;
        return total == 0 ? 0.0 : (shortTaskCount * 100.0) / total;
    }
    public double getLongTaskPercent() {
        double total = shortTaskCount + longTaskCount;
        return total == 0 ? 0.0 : (longTaskCount * 100.0) / total;
    }
}
