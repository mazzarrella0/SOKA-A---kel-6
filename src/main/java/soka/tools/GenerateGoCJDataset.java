package soka.tools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Generator dataset GoCJ (Opsi B).
 *
 * Sumber dasar: Original_DataSet.txt (50 nilai MI dari Hussain & Aleem, 2018),
 * juga dipakai untuk file resmi GoCJ_Dataset_100..1000 di Mendeley. Algoritma
 * di bawah adalah PORTING dari "GoCJ Java Generator.txt" (source resmi
 * penulis dataset): logikanya dipertahankan sama persis (dataTable per
 * indeks genap 0,2,4,...,98 dan fallback getJobSize untuk indeks ganjil),
 * hanya diubah agar:
 *   1) tidak hardcode path Windows lama (baca dari classpath resource),
 *   2) memakai Random BERSEED supaya hasilnya identik di semua laptop
 *      (source asli memakai Random tanpa seed -> hasil beda tiap run).
 *
 * Cara pakai (dari root project):
 *   mvn compile exec:java -Dexec.mainClass=soka.tools.GenerateGoCJDataset -Dexec.args="2000 3000"
 * atau lewat generate-dataset.bat / generate-dataset.sh
 */
public final class GenerateGoCJDataset {

    private static final String ORIGINAL_RESOURCE = "/dataset/Original_DataSet.txt";
    private static final String OUTPUT_DIR = "src/main/resources/dataset";
    private static final long SEED = 42L; // samakan dengan random.seed di config.properties

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Pemakaian: generate-dataset.bat 2000 3000");
            return;
        }

        List<Long> base = loadOriginalDataset();
        System.out.println("Original_DataSet.txt: " + base.size() + " nilai dasar dimuat.");

        Path outDir = Paths.get(OUTPUT_DIR);
        Files.createDirectories(outDir);

        for (String arg : args) {
            int numJobs = Integer.parseInt(arg.trim());
            long[] jobs = createGoCJ(base, numJobs, new Random(SEED + numJobs));
            Path outFile = outDir.resolve("GoCJ_Dataset_" + numJobs + ".txt");
            writeDataset(outFile, jobs);
            printSummary(numJobs, jobs, outFile);
        }
    }

    /** Baca 50 nilai dasar dari classpath (bukan path Windows hardcode seperti source asli). */
    private static List<Long> loadOriginalDataset() throws IOException {
        List<Long> values = new ArrayList<>();
        try (InputStream in = GenerateGoCJDataset.class.getResourceAsStream(ORIGINAL_RESOURCE)) {
            if (in == null) {
                throw new IOException("Resource tidak ditemukan di classpath: " + ORIGINAL_RESOURCE
                        + " (pastikan Original_DataSet.txt ada di src/main/resources/dataset/)");
            }
            try (Reader isr = new InputStreamReader(in, StandardCharsets.UTF_8);
                 java.io.BufferedReader br = new java.io.BufferedReader(isr)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        values.add(Long.parseLong(line));
                    }
                }
            }
        }
        return values;
    }

    /**
     * Porting algoritma dari GoCJ Java Generator.txt (createGoCJ + getJobSize),
     * TANPA mengubah logikanya. dataTable diisi di indeks genap 0,2,4,...
     * mengikuti "per += 2" pada source asli.
     */
    private static long[] createGoCJ(List<Long> base, int num, Random random) {
        java.util.Map<Integer, Long> dataTable = new java.util.HashMap<>();
        int per = 0;
        for (long size : base) {
            dataTable.put(per, size);
            per += 2;
        }

        long[] jobSizes = new long[num];
        for (int i = 0; i < num; i++) {
            int rand = random.nextInt(100);
            jobSizes[i] = (rand % 2 == 0) ? dataTable.get(rand) : getJobSize(dataTable, rand);
        }
        return jobSizes;
    }

    private static long getJobSize(java.util.Map<Integer, Long> dataTable, int rnd) {
        long jsize = 0;
        for (int i = 0; i < 100; i += 2) {
            if (rnd > i && dataTable.containsKey(i)) {
                jsize = dataTable.get(i);
            }
        }
        return jsize;
    }

    private static void writeDataset(Path outFile, long[] jobs) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            for (long j : jobs) {
                bw.write(Long.toString(j));
                bw.newLine();
            }
        }
    }

    private static void printSummary(int numJobs, long[] jobs, Path outFile) {
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE, sum = 0;
        for (long j : jobs) {
            min = Math.min(min, j);
            max = Math.max(max, j);
            sum += j;
        }
        double mean = sum / (double) jobs.length;
        System.out.printf(Locale.US, "-> %s  (n=%d, min=%d MI, max=%d MI, mean=%.1f MI)%n",
                outFile, numJobs, min, max, mean);
    }
}
