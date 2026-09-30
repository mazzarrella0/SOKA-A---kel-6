package soka.dataset;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

public class GoCJDatasetReader {
	public List<Cloudlet> read(Reader reader, int maximumTasks) throws IOException {
		List<Cloudlet> cloudlets = new ArrayList<>();
		UtilizationModelFull utilizationModel = new UtilizationModelFull();
		try (BufferedReader bufferedReader = new BufferedReader(reader)) {
			String line;
			while (cloudlets.size() < maximumTasks && (line = bufferedReader.readLine()) != null) {
				for (String token : line.split("[,;\\s]+")) {
					if (token.trim().isEmpty()) continue;
					long length = Long.parseLong(token.trim());
					if (length <= 0) continue;
					Cloudlet cloudlet = new CloudletSimple(length, 1);
					cloudlet.setFileSize(300).setOutputSize(300);
					cloudlet.setUtilizationModel(utilizationModel);
					cloudlets.add(cloudlet);
					if (cloudlets.size() == maximumTasks) break;
				}
			}
		}
		return cloudlets;
	}
}
