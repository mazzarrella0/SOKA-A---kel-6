package soka.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Prototipe cooperative DRRHA scheduler di luar CloudSim.
 *
 * Ini cooperative time slicing, bukan OS-level CPU preemption. Nilai MI GoCJ adalah estimasi
 * instruksi abstrak simulator; prototype memetakan setiap 1000 MI menjadi satu batch kerja
 * hash deterministik agar benar-benar ada komputasi, bukan mengklaim MI setara instruksi CPU.
 */
public final class RealDrrhaScheduler {

    public record TaskExecution(String taskId, long initialLengthMi, long remainingMi, long sliceWorkMi,
                               double startTimeMs, double finishTimeMs, double responseTimeMs, double waitingTimeMs,
                               double executionTimeMs, double turnaroundTimeMs, boolean completed) {
    }

    public static final class Task {
        private final String id;
        private long initialLengthMi;
        private long remainingMi;
        private double startTimeMs;
        private double finishTimeMs;
        private double waitingTimeMs;
        private double executionTimeMs;
        private double turnaroundTimeMs;
        private boolean completed;
        private boolean started;

        public Task(String id, long initialLengthMi) {
            this.id = id;
            this.initialLengthMi = initialLengthMi;
            this.remainingMi = initialLengthMi;
        }

        public String getId() { return id; }
        public long getInitialLengthMi() { return initialLengthMi; }
        public long getRemainingMi() { return remainingMi; }
        public void setRemainingMi(long remainingMi) { this.remainingMi = remainingMi; }
        public double getStartTimeMs() { return startTimeMs; }
        public void setStartTimeMs(double startTimeMs) { this.startTimeMs = startTimeMs; }
        public double getFinishTimeMs() { return finishTimeMs; }
        public void setFinishTimeMs(double finishTimeMs) { this.finishTimeMs = finishTimeMs; }
        public double getWaitingTimeMs() { return waitingTimeMs; }
        public void setWaitingTimeMs(double waitingTimeMs) { this.waitingTimeMs = waitingTimeMs; }
        public double getExecutionTimeMs() { return executionTimeMs; }
        public void setExecutionTimeMs(double executionTimeMs) { this.executionTimeMs = executionTimeMs; }
        public double getTurnaroundTimeMs() { return turnaroundTimeMs; }
        public void setTurnaroundTimeMs(double turnaroundTimeMs) { this.turnaroundTimeMs = turnaroundTimeMs; }
        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }
        public boolean isStarted() { return started; }
        public void setStarted(boolean started) { this.started = started; }
    }

    public List<TaskExecution> run(List<Long> lengths) {
        if (lengths == null || lengths.isEmpty()) {
            return List.of();
        }

        ArrayDeque<Task> queue = new ArrayDeque<>();
        for (int i = 0; i < lengths.size(); i++) {
            long length = Math.max(0L, lengths.get(i));
            if (length <= 0L) {
                continue;
            }
            queue.addLast(new Task("T" + (i + 1), length));
        }
        if (queue.isEmpty()) {
            return List.of();
        }

        List<TaskExecution> results = new ArrayList<>();
        long runStartNs = System.nanoTime();
        while (!queue.isEmpty()) {
            List<Task> ready = new ArrayList<>(queue);
            ready.sort(Comparator.comparingLong(Task::getRemainingMi));
            Task task = ready.get(0);
            queue.remove(task);

            double meanRemaining = ready.stream().mapToDouble(Task::getRemainingMi).average().orElse(task.getRemainingMi());
            double quantum = calculateQuantum(meanRemaining, task.getRemainingMi());
            long sliceWorkMi = Math.max(1L, Math.min(task.getRemainingMi(), (long) Math.ceil(quantum)));

            long sliceStartNs = System.nanoTime();
            if (!task.isStarted()) {
                task.setStarted(true);
                task.setStartTimeMs((sliceStartNs - runStartNs) / 1_000_000.0);
            }
            executeWork(sliceWorkMi, task.getRemainingMi());
            long sliceElapsedNs = Math.max(1L, System.nanoTime() - sliceStartNs);
            task.setExecutionTimeMs(task.getExecutionTimeMs() + sliceElapsedNs / 1_000_000.0);
            task.setRemainingMi(Math.max(0L, task.getRemainingMi() - sliceWorkMi));
            long nowNs = System.nanoTime();
            double turnaroundTimeMs = (nowNs - runStartNs) / 1_000_000.0;
            double waitingTimeMs = Math.max(0.0, turnaroundTimeMs - task.getExecutionTimeMs());
            task.setWaitingTimeMs(waitingTimeMs);
            task.setTurnaroundTimeMs(turnaroundTimeMs);

            if (task.getRemainingMi() <= 0L) {
                task.setFinishTimeMs(turnaroundTimeMs);
                task.setCompleted(true);
                results.add(new TaskExecution(task.getId(), task.getInitialLengthMi(), 0L, sliceWorkMi,
                    task.getStartTimeMs(), task.getFinishTimeMs(), task.getStartTimeMs(), task.getWaitingTimeMs(),
                        task.getExecutionTimeMs(), task.getTurnaroundTimeMs(), true));
            } else {
                queue.addLast(task);
                results.add(new TaskExecution(task.getId(), task.getInitialLengthMi(), task.getRemainingMi(), sliceWorkMi,
                    task.getStartTimeMs(), turnaroundTimeMs, task.getStartTimeMs(), task.getWaitingTimeMs(),
                        task.getExecutionTimeMs(), task.getTurnaroundTimeMs(), false));
            }
        }
        return results;
    }

    public double calculateQuantum(double meanBurstTime, long ownRemainingLength) {
        double mean = Math.max(1.0, meanBurstTime);
        double bt = Math.max(1.0, ownRemainingLength);
        double quantum = (mean / 2.0) + ((mean / 2.0) / bt);
        return Math.max(1.0, quantum);
    }

    private static volatile long workSink;

    private void executeWork(long sliceWorkMi, long remainingMi) {
        long workBatches = Math.max(1L, (sliceWorkMi + 999L) / 1000L);
        long state = remainingMi ^ sliceWorkMi;
        for (long batch = 0; batch < workBatches; batch++) {
            for (int operation = 0; operation < 64; operation++) {
                state ^= (state << 13);
                state ^= (state >>> 7);
                state ^= (state << 17);
                state += operation + batch;
            }
        }
        workSink = state;
    }
}
