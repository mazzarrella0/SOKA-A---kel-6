package soka.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Runs the cooperative Java prototype on a plain text workload of one MI length per line. */
public final class RuntimeMain {
    private RuntimeMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: RuntimeMain <workload-lengths.txt> [output.csv]");
        }
        Path input = Paths.get(args[0]);
        Path output = args.length == 2 ? Paths.get(args[1]) : Paths.get("results", "runtime-prototype.csv");
        List<Long> lengths = new ArrayList<>();
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            long length = Long.parseLong(value);
            if (length <= 0) throw new IllegalArgumentException("Task length harus positif: " + value);
            lengths.add(length);
        }
        if (lengths.isEmpty()) throw new IllegalArgumentException("Workload tidak berisi task length positif");

        long wallStartNs = System.nanoTime();
        List<RealDrrhaScheduler.TaskExecution> slices = new RealDrrhaScheduler().run(lengths);
        long wallElapsedNs = System.nanoTime() - wallStartNs;
        Map<String, RealDrrhaScheduler.TaskExecution> completed = new LinkedHashMap<>();
        for (RealDrrhaScheduler.TaskExecution slice : slices) {
            if (slice.completed()) completed.put(slice.taskId(), slice);
        }
        List<RealDrrhaScheduler.TaskExecution> rows = completed.values().stream()
                .sorted(Comparator.comparing(RealDrrhaScheduler.TaskExecution::taskId)).toList();
        if (rows.size() != lengths.size()) {
            throw new IllegalStateException("Runtime prototype hanya menyelesaikan " + rows.size() + "/" + lengths.size());
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        long contextSwitches = Math.max(0L, slices.size() - rows.size());
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("task_id,length_mi,status,response_time_ms,waiting_time_ms,execution_time_ms,turnaround_time_ms,finish_time_ms");
            writer.newLine();
            for (RealDrrhaScheduler.TaskExecution row : rows) {
                writer.write(String.format(Locale.US, "%s,%d,COMPLETED,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                        row.taskId(), row.initialLengthMi(), row.responseTimeMs(), row.waitingTimeMs(),
                        row.executionTimeMs(), row.turnaroundTimeMs(), row.finishTimeMs()));
            }
        }
            Path summary = output.resolveSibling(output.getFileName().toString().replaceFirst("\\.csv$", "-summary.csv"));
            try (BufferedWriter writer = Files.newBufferedWriter(summary, StandardCharsets.UTF_8)) {
                writer.write("tasks_completed,total_slices,cooperative_context_switches,wall_time_ms,mean_response_ms,mean_waiting_ms,mean_execution_ms,mean_turnaround_ms");
                writer.newLine();
                writer.write(String.format(Locale.US, "%d,%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                    rows.size(), slices.size(), contextSwitches, wallElapsedNs / 1_000_000.0,
                    rows.stream().mapToDouble(RealDrrhaScheduler.TaskExecution::responseTimeMs).average().orElse(0.0),
                    rows.stream().mapToDouble(RealDrrhaScheduler.TaskExecution::waitingTimeMs).average().orElse(0.0),
                    rows.stream().mapToDouble(RealDrrhaScheduler.TaskExecution::executionTimeMs).average().orElse(0.0),
                    rows.stream().mapToDouble(RealDrrhaScheduler.TaskExecution::turnaroundTimeMs).average().orElse(0.0)));
            }
            System.out.printf(Locale.US, "Runtime prototype completed %d tasks; slices=%d; cooperative context switches=%d; wall=%.3f ms; outputs=%s,%s%n",
                rows.size(), slices.size(), contextSwitches, wallElapsedNs / 1_000_000.0, output, summary);
    }
}
