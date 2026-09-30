package soka.config;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.power.models.PowerModelHostSimple;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;

import java.util.ArrayList;
import java.util.List;

public class DatacenterFactory {
	public Datacenter create(CloudSimPlus simulation) {
		List<Host> hosts = new ArrayList<>();
		hosts.add(createHost(4, 8192, 500_000, 8000));
		hosts.add(createHost(8, 16384, 1_000_000, 8000));
		hosts.add(createHost(4, 16384, 1_000_000, 8000));
		hosts.add(createHost(8, 32768, 2_000_000, 8000));
		return new DatacenterSimple(simulation, hosts, new VmAllocationPolicySimple())
			.setSchedulingInterval(1.0);
	}

	private Host createHost(int peCount, long ram, long storage, long bandwidth) {
		List<Pe> peList = new ArrayList<>();
		for (int i = 0; i < peCount; i++) {
			peList.add(new PeSimple(4000));
		}
		Host host = new HostSimple(ram, bandwidth, storage, peList);
		host.setVmScheduler(new VmSchedulerTimeShared());
		host.setPowerModel(new PowerModelHostSimple(250, 150));
		host.setStateHistoryEnabled(true);
		return host;
	}
}
