package soka.dataset;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pembaca dataset GoCJ (Draft 1.2) dari classpath resource
 * src/main/resources/dataset/GoCJ_Dataset_<jumlah>.txt -- format file resmi
 * Mendeley maupun hasil soka.tools.GenerateGoCJDataset (Opsi B): satu angka
 * MI per baris, tanpa header.
 */
public final class GoCJDatasetReader {

    /**
     * Porsi RAM/BW VM yang dipakai TIAP cloudlet (bukan UtilizationModelFull).
     * Kalau 100%, cloudlet ke-2 di VM yang sama tidak kebagian RAM/BW dan
     * tidak akan pernah jalan -- ini bug yang sudah pernah ditemukan &
     * diperbaiki di Step 2 infrastruktur, sekarang diterapkan juga di sini.
     */
    private static final double RAM_BW_FRACTION = 0.05;

    /** @param numJobs dipakai untuk menyusun nama file: GoCJ_Dataset_{numJobs}.txt */
    public List<Cloudlet> readScenario(int numJobs) throws IOException {
        String resourcePath = "/dataset/GoCJ_Dataset_" + numJobs + ".txt";
        try (InputStream in = GoCJDatasetReader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Dataset tidak ditemukan di classpath: " + resourcePath
                        + "  -> kalau numJobs > 1000, jalankan dulu: "
                        + "generate-dataset.bat " + numJobs
                        + "  (atau generate-dataset.sh di Linux/WSL)");
            }
            return read(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private List<Cloudlet> read(java.io.Reader reader) throws IOException {
        List<Cloudlet> cloudlets = new ArrayList<>();
        UtilizationModelFull cpuFull = new UtilizationModelFull();
        UtilizationModelDynamic ramBw = new UtilizationModelDynamic(RAM_BW_FRACTION);

        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                long length = Long.parseLong(line);
                if (length <= 0) continue;

                Cloudlet cloudlet = new CloudletSimple(length, 1); // 1 PE per cloudlet (Draft 2.4/2.5)
                cloudlet.setFileSize(300).setOutputSize(300);
                cloudlet.setUtilizationModelCpu(cpuFull);
                cloudlet.setUtilizationModelRam(ramBw);
                cloudlet.setUtilizationModelBw(ramBw);
                cloudlets.add(cloudlet);
            }
        }

        if (cloudlets.isEmpty()) {
            throw new IOException("File dataset ditemukan tapi tidak berisi angka MI yang valid.");
        }
        return cloudlets;
    }
}
