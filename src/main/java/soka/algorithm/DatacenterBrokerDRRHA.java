package soka.algorithm;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.core.CloudSimPlus;

public class DatacenterBrokerDRRHA extends DatacenterBrokerSimple {
	public DatacenterBrokerDRRHA(CloudSimPlus simulation) {
		super(simulation, "DRRHA-Broker");
	}
}
