package soka;

import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import soka.algorithm.DatacenterBrokerDRRHA;
import org.junit.jupiter.api.Test;
import soka.algorithm.DRRHAEngine;
import soka.metrics.MultiObjectiveEvaluator;
import soka.metrics.ResultReporter;
import soka.metrics.TaskLengthDistribution;
import soka.runtime.RealDrrhaScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DRRHARegressionTest {

    @Test
    void dynamicQuantumShouldFollowTheFormula() {
        DRRHAEngine engine = new DRRHAEngine();
        double quantum = engine.calculateQuantum(List.of(10L, 30L, 50L), 10L);
        assertTrue(quantum > 0.0);
        assertEquals(16.5, quantum, 1.0e-9);
    }

    @Test
    void taskLengthDistributionShouldComputeAllStatistics() {
        CloudletSimple c1 = new CloudletSimple(10, 1);
        CloudletSimple c2 = new CloudletSimple(20, 1);
        CloudletSimple c3 = new CloudletSimple(30, 1);
        CloudletSimple c4 = new CloudletSimple(40, 1);
        CloudletSimple c5 = new CloudletSimple(50, 1);

        TaskLengthDistribution distribution = TaskLengthDistribution.from(List.of(c1, c2, c3, c4, c5));

        assertEquals(10.0, distribution.getMinLength(), 1.0e-9);
        assertEquals(50.0, distribution.getMaxLength(), 1.0e-9);
        assertEquals(30.0, distribution.getMeanLength(), 1.0e-9);
        assertEquals(30.0, distribution.getMedianLength(), 1.0e-9);
        assertEquals(14.142135623730951, distribution.getStdDevLength(), 1.0e-9);
        assertEquals(3, distribution.getShortTaskCount());
        assertEquals(2, distribution.getLongTaskCount());
        assertEquals(60.0, distribution.getShortTaskPercent(), 1.0e-9);
        assertEquals(40.0, distribution.getLongTaskPercent(), 1.0e-9);
        assertEquals(20.0, distribution.getFirstQuartileLength(), 1.0e-9);
        assertEquals(40.0, distribution.getThirdQuartileLength(), 1.0e-9);
        assertEquals(5, distribution.histogram(4).stream().mapToInt(TaskLengthDistribution.HistogramBin::count).sum());
        assertEquals(100.0, distribution.histogram(4).stream().mapToDouble(TaskLengthDistribution.HistogramBin::percentage).sum(), 1.0e-9);
    }

    @Test
    void objectiveScoreMustUseAValidReferenceAndWeightTotal() throws Exception {
        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        double score = evaluator.evaluate(List.of(), List.of(), 100.0, 100.0, 0.4, 0.3, 0.3).getWeightedScore();
        assertTrue(Double.isFinite(score));
        assertEquals(0.3, score, 1.0e-9);
        assertEquals(1.0, evaluator.validateWeights(0.4, 0.3, 0.3), 1.0e-9);
        assertEquals(0L, evaluator.countSlaViolations(List.of()));
    }

    @Test
    void objectiveScoreShouldPreserveValuesAboveReferenceAndUseEachComponent() {
        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        double normalizedMakespan = evaluator.normalize(150.0, 100.0);
        double normalizedEnergy = evaluator.normalize(250.0, 100.0);
        double score = evaluator.calculateWeightedScore(normalizedMakespan, normalizedEnergy, 0.5,
                0.4, 0.3, 0.3);

        assertEquals(1.5, normalizedMakespan, 1.0e-12);
        assertEquals(2.5, normalizedEnergy, 1.0e-12);
        assertEquals(1.5, score, 1.0e-12);
        assertNotEquals(normalizedMakespan, normalizedEnergy);
    }

    @Test
    void referenceSetShouldRejectMissingCapacityForNonemptyWorkload() {
        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.buildReferenceSet(List.of(new CloudletSimple(100, 1)), List.of()));
    }

    @Test
    void objectiveScoreCsvShouldRecomputeFromItsExportedComponentsAndWeights() throws Exception {
        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        double expectedScore = evaluator.calculateWeightedScore(1.5, 2.5, 0.5, 0.4, 0.3, 0.3);
        MultiObjectiveEvaluator.Evaluation evaluation = new MultiObjectiveEvaluator.Evaluation(
                150.0, 250.0, 0.5, 0.0, 0.0, 0L, 1, 1.5, 2.5, expectedScore);
        TaskLengthDistribution distribution = TaskLengthDistribution.from(List.of(new CloudletSimple(10, 1)));
        Path csv = Files.createTempFile("metrics", ".csv");

        new ResultReporter().writeEvaluationCsv(evaluation, distribution, 0.4, 0.3, 0.3, csv);
        String[] headers = Files.readAllLines(csv).get(0).split(",");
        String[] values = Files.readAllLines(csv).get(1).split(",");
        java.util.Map<String, Double> columns = new java.util.HashMap<>();
        for (int i = 0; i < headers.length; i++) columns.put(headers[i], Double.parseDouble(values[i]));
        double scoreFromCsv = columns.get("weight_makespan") * columns.get("normalized_makespan")
                + columns.get("weight_energy") * columns.get("normalized_energy")
                + columns.get("weight_utilization") * (1.0 - columns.get("utilization"));

        assertEquals(expectedScore, columns.get("weighted_score"), 1.0e-12);
        assertEquals(columns.get("weighted_score"), scoreFromCsv, 0.0);
    }

    @Test
    void cloudSimShouldCreateAllVmsUsingDeterministicHostAllocation() throws Exception {
        Config cfg = new Config();
        assertEquals(2, cfg.getInt("vm.count.low"));
        assertEquals(4, cfg.getInt("vm.count.medium"));
        assertEquals(2, cfg.getInt("vm.count.high"));
        CloudSimPlus simulation = new CloudSimPlus();
        InfraBuilder builder = new InfraBuilder(cfg);
        List<Datacenter> datacenters = builder.buildDatacenters(simulation);
        List<Vm> vms = builder.buildVms();
        DatacenterBrokerDRRHA broker = new DatacenterBrokerDRRHA(simulation);
        builder.configureDeterministicPlacement(vms, datacenters, broker);
        List<InfraBuilder.VmAllocationSnapshot> allocation = new java.util.ArrayList<>();
        broker.addOnVmsCreatedListener(info -> allocation.addAll(
            builder.captureActualPlacement(datacenters, info.getDatacenterBroker().getVmCreatedList())));
        broker.submitVmList(vms);
        broker.submitCloudletList(List.of(new CloudletSimple(1000, 1)));
        simulation.start();

        assertEquals(8, vms.size());
        assertEquals(2, datacenters.size());
        assertEquals(8, broker.getVmCreatedList().size());
        assertTrue(broker.getVmFailedList().isEmpty());
        assertEquals(1, broker.getCloudletFinishedList().size());
        assertEquals(8, allocation.size());
        assertTrue(allocation.stream().allMatch(row -> row.vmId() >= 0));
        assertEquals(2, allocation.stream().map(InfraBuilder.VmAllocationSnapshot::datacenterId).distinct().count());
        for (InfraBuilder.VmAllocationSnapshot row : allocation) {
                long hostPeDemand = allocation.stream().filter(other -> other.hostId() == row.hostId()
                        && other.datacenterId() == row.datacenterId())
                .mapToLong(other -> Math.round(other.vmMips() * other.vmPes())).sum();
                long hostRamDemand = allocation.stream().filter(other -> other.hostId() == row.hostId()
                        && other.datacenterId() == row.datacenterId())
                .mapToLong(InfraBuilder.VmAllocationSnapshot::vmRamMb).sum();
                long hostBwDemand = allocation.stream().filter(other -> other.hostId() == row.hostId()
                        && other.datacenterId() == row.datacenterId())
                .mapToLong(InfraBuilder.VmAllocationSnapshot::vmBwMbps).sum();
                long hostStorageDemand = allocation.stream().filter(other -> other.hostId() == row.hostId()
                        && other.datacenterId() == row.datacenterId())
                .mapToLong(InfraBuilder.VmAllocationSnapshot::vmStorageMb).sum();
            assertTrue(hostPeDemand <= Math.round(row.hostMips()));
            assertTrue(hostRamDemand <= row.hostRamMb());
            assertTrue(hostBwDemand <= row.hostBwMbps());
            assertTrue(hostStorageDemand <= row.hostStorageMb());
        }

        List<Host> hosts = datacenters.stream().flatMap(dc -> dc.getHostList().stream()).toList();
        assertFalse(hosts.isEmpty());
        assertTrue(hosts.stream().mapToDouble(Host::getTotalMipsCapacity).sum() > 0.0);
    }

    @Test
    void threeIdenticalCloudSimRunsShouldReproduceMappingTasksAndMetrics() throws Exception {
        List<TinyRun> runs = List.of(runTinyCloudSim(), runTinyCloudSim(), runTinyCloudSim());
        for (int index = 1; index < runs.size(); index++) {
            TinyRun baseline = runs.get(0);
            TinyRun current = runs.get(index);
            assertEquals(baseline.placement(), current.placement());
            assertEquals(baseline.cloudletSignature(), current.cloudletSignature());
            assertEquals(baseline.evaluation().getMakespan(), current.evaluation().getMakespan(), 0.0);
            assertEquals(baseline.evaluation().getEnergyWh(), current.evaluation().getEnergyWh(), 0.0);
            assertEquals(baseline.evaluation().getUtilization(), current.evaluation().getUtilization(), 0.0);
            assertEquals(baseline.evaluation().getAvgWaitingTime(), current.evaluation().getAvgWaitingTime(), 0.0);
            assertEquals(baseline.evaluation().getLoadBalancingDegree(), current.evaluation().getLoadBalancingDegree(), 0.0);
            assertEquals(baseline.evaluation().getSlaViolations(), current.evaluation().getSlaViolations(), 0.0);
            assertEquals(baseline.evaluation().getWeightedScore(), current.evaluation().getWeightedScore(), 0.0);
        }
    }

    private TinyRun runTinyCloudSim() throws Exception {
        Config cfg = new Config();
        CloudSimPlus simulation = new CloudSimPlus();
        InfraBuilder builder = new InfraBuilder(cfg);
        List<Datacenter> datacenters = builder.buildDatacenters(simulation);
        DatacenterBrokerDRRHA broker = new DatacenterBrokerDRRHA(simulation);
        List<Vm> vms = builder.buildVms();
        builder.configureDeterministicPlacement(vms, datacenters, broker);
        List<InfraBuilder.VmAllocationSnapshot> allocation = new java.util.ArrayList<>();
        broker.addOnVmsCreatedListener(info -> allocation.addAll(
                builder.captureActualPlacement(datacenters, info.getDatacenterBroker().getVmCreatedList())));
        List<Cloudlet> tasks = List.of(new CloudletSimple(100, 1), new CloudletSimple(2000, 1));
        broker.submitVmList(vms);
        broker.submitCloudletList(tasks);
        simulation.start();
        List<Host> hosts = datacenters.stream().flatMap(dc -> dc.getHostList().stream()).toList();
        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        MultiObjectiveEvaluator.ReferenceSet references = evaluator.buildReferenceSet(tasks, hosts);
        MultiObjectiveEvaluator.Evaluation evaluation = evaluator.evaluate(tasks, hosts,
                references.getMakespanReference(), references.getEnergyReference(), 0.4, 0.3, 0.3);
        String cloudletSignature = tasks.stream().map(task -> task.getId() + ":" + task.getLength() + ":"
                + task.getStatus() + ":" + (task.getVm() == null ? -1 : task.getVm().getId()))
                .collect(java.util.stream.Collectors.joining("|"));
        return new TinyRun(allocation, cloudletSignature, evaluation);
    }

    private record TinyRun(List<InfraBuilder.VmAllocationSnapshot> placement,
                           String cloudletSignature,
                           MultiObjectiveEvaluator.Evaluation evaluation) {}

    @Test
    void hostCapacityValidationShouldRejectOverCapacityMappings() throws Exception {
        Config cfg = new Config();
        CloudSimPlus simulation = new CloudSimPlus();
        InfraBuilder builder = new InfraBuilder(cfg);
        List<Datacenter> datacenters = builder.buildDatacenters(simulation);
        List<Vm> vms = builder.buildVms();
        Host h1 = datacenters.get(0).getHostList().get(0);
        Host h2 = datacenters.get(0).getHostList().get(1);

        ((VmSimple) vms.get(0)).setHost(h1);
        ((VmSimple) vms.get(1)).setHost(h1);
        ((VmSimple) vms.get(2)).setHost(h2);
        ((VmSimple) vms.get(3)).setHost(h2);
        ((VmSimple) vms.get(4)).setHost(h2);
        ((VmSimple) vms.get(5)).setHost(h3ForTest(datacenters));
        ((VmSimple) vms.get(6)).setHost(h2);
        ((VmSimple) vms.get(7)).setHost(h3ForTest(datacenters));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> builder.validateAggregateHostCapacity(datacenters, vms));
        assertTrue(ex.getMessage().contains("melebihi kapasitas") || ex.getMessage().contains("VM"));
    }

    @Test
    void infrastructureCsvShouldReflectCloudSimCreatedVmAssignments() throws Exception {
        Config cfg = new Config();
        CloudSimPlus simulation = new CloudSimPlus();
        InfraBuilder builder = new InfraBuilder(cfg);
        List<Datacenter> datacenters = builder.buildDatacenters(simulation);
        List<Vm> vms = builder.buildVms();
        DatacenterBrokerDRRHA broker = new DatacenterBrokerDRRHA(simulation);
        builder.configureDeterministicPlacement(vms, datacenters, broker);
        List<InfraBuilder.VmAllocationSnapshot> allocation = new java.util.ArrayList<>();
        broker.addOnVmsCreatedListener(info -> allocation.addAll(
            builder.captureActualPlacement(datacenters, info.getDatacenterBroker().getVmCreatedList())));
        broker.submitVmList(vms);
        broker.submitCloudletList(List.of(new CloudletSimple(1000, 1)));
        simulation.start();

        Path csv = Files.createTempFile("infra", ".csv");
        new ResultReporter().writeInfrastructureSnapshotCsv(100, allocation, csv);
        String content = Files.readString(csv);

        for (InfraBuilder.VmAllocationSnapshot vm : allocation) {
            assertTrue(content.contains(String.valueOf(vm.vmId())),
                "CSV harus mencantumkan ID VM aktual " + vm.vmId());
        }
        assertTrue(content.contains("host_bw_mbps,host_storage_mb"));
        assertTrue(content.contains("vm_bw_mbps,vm_storage_mb"));
    }

    @Test
    void runtimeSchedulerShouldTrackProgressAndExecutionDurations() {
        RealDrrhaScheduler scheduler = new RealDrrhaScheduler();
        List<RealDrrhaScheduler.TaskExecution> executions = scheduler.run(List.of(100L, 80L, 50L));

        assertFalse(executions.isEmpty());
        assertTrue(executions.stream().mapToLong(e -> e.remainingMi()).sum() >= 0L);
        assertTrue(executions.stream().allMatch(e -> e.waitingTimeMs() >= 0L));
        assertTrue(executions.stream().allMatch(e -> e.finishTimeMs() >= e.startTimeMs()));
        assertTrue(executions.stream().allMatch(e -> e.executionTimeMs() > 0.0));
        assertTrue(executions.stream().filter(RealDrrhaScheduler.TaskExecution::completed)
            .allMatch(e -> Math.abs(e.turnaroundTimeMs() - e.executionTimeMs() - e.waitingTimeMs()) < 1.0e-9));
        assertTrue(executions.stream().allMatch(e -> e.responseTimeMs() >= 0.0));
    }

    @Test
    void runtimeCommandShouldExecuteAPlainTextWorkloadAndWriteMetrics() throws Exception {
        Path workload = Files.createTempFile("runtime-workload", ".txt");
        Path output = Files.createTempFile("runtime-output", ".csv");
        Files.writeString(workload, "100\n200\n300\n");

        soka.runtime.RuntimeMain.main(new String[] {workload.toString(), output.toString()});

        List<String> lines = Files.readAllLines(output);
        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("response_time_ms,waiting_time_ms,execution_time_ms,turnaround_time_ms"));
        assertTrue(lines.subList(1, lines.size()).stream().allMatch(line -> line.contains(",COMPLETED,")));
        Path summary = output.resolveSibling(output.getFileName().toString().replaceFirst("\\.csv$", "-summary.csv"));
        String[] summaryValues = Files.readAllLines(summary).get(1).split(",");
        assertEquals(3, Integer.parseInt(summaryValues[0]));
        assertTrue(Long.parseLong(summaryValues[2]) >= 0L);
    }

    private Host h3ForTest(List<Datacenter> datacenters) {
        return datacenters.get(1).getHostList().get(0);
    }

    @Test
    void summaryShouldReportPerRunSlaAndMeanDeviation() {
        List<MultiObjectiveEvaluator.Evaluation> runs = List.of(
            new MultiObjectiveEvaluator.Evaluation(100.0, 120.0, 0.5, 9.0, 0.1, 1L, 10, 0.8, 0.7, 0.5),
            new MultiObjectiveEvaluator.Evaluation(110.0, 140.0, 0.6, 11.0, 0.2, 2L, 10, 0.9, 0.8, 0.6),
            new MultiObjectiveEvaluator.Evaluation(90.0, 130.0, 0.55, 10.0, 0.15, 2L, 10, 0.7, 0.75, 0.55)
        );

        MultiObjectiveEvaluator.Evaluation summary = Main.summarizeRuns(runs, 10, 0.4, 0.3, 0.3);
        assertEquals(100.0, summary.getMakespan(), 1.0e-9);
        assertEquals(5.0 / 3.0, summary.getSlaViolations(), 1.0e-9);
        assertEquals(5.0 / 3.0, summary.getMeanSlaViolations(), 1.0e-9);
        assertEquals(1.0 / 6.0, summary.getSlaViolationRate(), 1.0e-9);
        assertEquals(Math.sqrt(1.0 / 3.0), summary.getSlaViolationsStdDev(), 1.0e-9);
        assertEquals(Math.sqrt(100.0), summary.getMakespanStdDev(), 1.0e-9);
        assertTrue(summary.getWeightedScore() > 0.0);
        assertTrue(summary.getLoadBalancingDegree() >= 0.0);
    }

    @Test
    void slaViolationsShouldCountFailedOrUnfinishedTasksOnce() {
        MultiObjectiveEvaluator evaluator = new MultiObjectiveEvaluator();
        List<CloudletSimple> cloudlets = List.of(new CloudletSimple(100, 1),
                new CloudletSimple(200, 1), new CloudletSimple(300, 1));
        long violations = evaluator.countSlaViolations(new java.util.ArrayList<>(cloudlets));
        assertEquals(3L, violations);
        assertTrue(violations <= cloudlets.size());
    }
}
