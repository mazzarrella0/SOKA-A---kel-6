package soka.config;

import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import soka.algorithm.CloudletSchedulerDRRHA;

import java.util.ArrayList;
import java.util.List;

public class VmFactory {
	public List<Vm> createAll() {
		List<Vm> vms = new ArrayList<>();
		for (int i = 0; i < 2; i++) vms.add(create(1000, 1, 2048));
		for (int i = 0; i < 4; i++) vms.add(create(2000, 2, 4096));
		for (int i = 0; i < 2; i++) vms.add(create(4000, 4, 8192));
		return vms;
	}

	private Vm create(long mips, int pes, long ram) {
		Vm vm = new VmSimple(mips, pes);
		vm.setRam(ram).setBw(1000).setSize(10_000);
		vm.setCloudletScheduler(new CloudletSchedulerDRRHA());
		return vm;
	}
}
