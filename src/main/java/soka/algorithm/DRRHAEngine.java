package soka.algorithm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class DRRHAEngine {

	public static final double MIN_QUANTUM = 1.0;

	public double calculateQuantum(Collection<Long> remainingLengths) {
		if (remainingLengths == null || remainingLengths.isEmpty()) {
			return 0.0;
		}

		long total = 0;
		for (long length : remainingLengths) {
			if (length > 0) {
				total += length;
			}
		}

		double mean = (double) total / remainingLengths.size();
		return Math.max(MIN_QUANTUM, mean);
	}

	public long calculateExecutionLength(long remainingLength, double quantum) {
		if (remainingLength <= 0 || quantum <= 0) {
			return 0;
		}

		return Math.min(remainingLength, Math.max(1L, (long) Math.ceil(quantum)));
	}

	public List<Long> sortByRemainingLength(Collection<Long> remainingLengths) {
		List<Long> sorted = new ArrayList<>(remainingLengths);
		sorted.sort(Comparator.naturalOrder());
		return sorted;
	}
}
