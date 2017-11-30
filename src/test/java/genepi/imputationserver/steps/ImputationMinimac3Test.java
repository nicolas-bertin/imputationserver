package genepi.imputationserver.steps;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import genepi.hadoop.HdfsUtil;
import genepi.hadoop.common.WorkflowStep;
import genepi.imputationserver.steps.imputationMinimac3.ImputationJobMinimac3;
import genepi.imputationserver.steps.vcf.VcfFile;
import genepi.imputationserver.steps.vcf.VcfFileUtil;
import genepi.imputationserver.util.TestCluster;
import genepi.imputationserver.util.TestSFTPServer;
import genepi.imputationserver.util.WorkflowTestContext;
import genepi.io.FileUtil;
import genepi.io.text.LineReader;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;

public class ImputationMinimac3Test {

	public static final boolean VERBOSE = true;

	public final int TOTAL_REFPANEL_CHR20_B37 = 63402;
	public final int TOTAL_REFPANEL_CHR20_B38 = 63384;
	public final int TOTAL_REFPANEL_CHRX_B37 = 1479509;
	public final int TOTAL_REFPANEL_CHRX_B38 = 1077575;
	public final int ONLY_IN_INPUT = 78;

	@BeforeClass
	public static void setUp() throws Exception {
		TestCluster.getInstance().start();
	}

	@AfterClass
	public static void tearDown() throws Exception {
		TestCluster.getInstance().stop();
	}

	@Test
	public void testPipelineWithPhased() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-phased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());
		assertEquals(TOTAL_REFPANEL_CHR20_B37 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWithPhasedAndEmptyPhasing() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-phased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		FileUtil.deleteDirectory("test-data/tmp");

	}
	
	@Test
	public void testPipelineWithPhasedAndNoPhasingSelected() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-phased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "no_phasing");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());
		assertEquals(TOTAL_REFPANEL_CHR20_B37 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}
	
	@Test
	public void testPipelineWidthInvalidHttpUrl() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr1";
		String inputFolder = "https://imputationserver.sph.umich.edu/invalid-url/downloads/hapmap300.chr1.recode.vcf.gz";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "shapeit");

		// create step instance
		InputValidation inputValidation = new InputValidationMock(configFolder);

		// run and test
		boolean result = run(context, inputValidation);

		// check if step is failed
		assertEquals(false, result);

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWidthInvalidFileFormat() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr1";
		String inputFolder = "https://imputationserver.sph.umich.edu/static/images/impute.png";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "shapeit");

		// create step instance
		InputValidation inputValidation = new InputValidationMock(configFolder);

		// run and test
		boolean result = run(context, inputValidation);

		// check if step is failed
		assertEquals(false, result);

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWithHttpUrl() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr1";
		String inputFolder = "https://imputationserver.sph.umich.edu/static/downloads/hapmap300.chr1.recode.vcf.gz";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "shapeit");

		// create step instance
		InputValidation inputValidation = new InputValidationMock(configFolder);

		// run and test
		boolean result = run(context, inputValidation);

		// check if step is failed
		assertEquals(true, result);

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		result = run(context, qcStats);

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_1.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr1.dose.vcf.gz", 100000000, false);

		assertEquals("1", file.getChromosome());
		assertEquals(60, file.getNoSamples());
		assertEquals(true, file.isPhased());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWithSFTP() throws IOException, ZipException, InterruptedException {

		TestSFTPServer server = new TestSFTPServer("test-data/data");

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "sftp://localhost:8001/" + new File("test-data/data/chr20-phased").getAbsolutePath() + ";"
				+ TestSFTPServer.USERNAME + ";" + TestSFTPServer.PASSWORD;

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// create step instance
		InputValidation inputValidation = new InputValidationMock(configFolder);

		// run and test
		boolean result = run(context, inputValidation);

		// check if step is failed
		assertEquals(true, result);

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		result = run(context, qcStats);

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());
		assertEquals(TOTAL_REFPANEL_CHR20_B37 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

		server.stop();

	}

	@Test
	public void testPipelineWithWrongSFTPCredentials() throws IOException, ZipException, InterruptedException {

		TestSFTPServer server = new TestSFTPServer("test-data/data");

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "sftp://localhost:8001/" + new File("data/chr20-phased").getAbsolutePath() + ";"
				+ "WRONG_USERNAME" + ";" + TestSFTPServer.PASSWORD;

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// create step instance
		InputValidation inputValidation = new InputValidationMock(configFolder);

		// run and test
		boolean result = run(context, inputValidation);

		// check if step is failed
		assertEquals(false, result);

		server.stop();

	}

	@Test
	public void testPipelineWithEagle() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-unphased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());
		assertEquals(TOTAL_REFPANEL_CHR20_B37 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	
	@Test
	public void testPipelineWithEmptyPhasing() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-unphased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertFalse(result);
		
		FileUtil.deleteDirectory("test-data/tmp");


	}

	
	@Test
	public void testCompareInfoAndDosageSize() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-unphased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		LineReader readInfo = new LineReader("test-data/tmp/chr20.info.gz");

		int infoCount = 0;

		while (readInfo.next()) {
			infoCount++;
		}

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());
		assertEquals(TOTAL_REFPANEL_CHR20_B37 + ONLY_IN_INPUT, file.getNoSnps());

		// subtract header
		assertEquals(infoCount - 1, file.getNoSnps());
		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testWriteTypedSitesOnly() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-unphased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		LineReader reader = new LineReader("test-data/tmp/statisticDir/typed-only.txt");
		int count = 0;

		while (reader.next()) {
			count++;
		}
		reader.close();

		assertEquals(count, ONLY_IN_INPUT + 1);

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWithEagleAnd23AndMe() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/23andMe";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// create step instance
		InputValidation inputValidation = new InputValidationMock(configFolder);

		// run and test
		boolean result = run(context, inputValidation);

		// check if step is failed
		assertEquals(true, result);

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWithShapeIt() throws IOException, ZipException {

		if (!new File("files/bin/shapeit").exists()) {
			return;
		}

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-unphased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "shapeit");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(false, file.isPhased());
		assertEquals(TOTAL_REFPANEL_CHR20_B37 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWithHapiUr() throws IOException, ZipException {

		if (!new File("files/bin/hapi-ur").exists()) {
			return;
		}

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-unphased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "hapiur");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());
		assertEquals(TOTAL_REFPANEL_CHR20_B37 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testChrXPipelineWithEagle() throws IOException, ZipException {

		// maybe git large files?
		if (!new File(
				"test-data/configs/hapmap-chrX/ref-panels/ALL.chrX.nonPAR.phase1_v3.snps_indels_svs.genotypes.all.noSingleton.recode.bcf")
						.exists()) {
			System.out.println("chrX bcf nonPAR file not available");
			return;
		}

		String configFolder = "test-data/configs/hapmap-chrX";
		String inputFolder = "test-data/data/chrX-unphased";

		File file = new File("test-data/tmp");
		if (file.exists()) {
			FileUtil.deleteDirectory(file);
		}

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "phase1", "eagle");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_X.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile vcfFile = VcfFileUtil.load("test-data/tmp/chrX.dose.vcf.gz", 100000000, false);

		assertEquals("X", vcfFile.getChromosome());

		FileUtil.deleteDirectory(file);

	}

	@Test
	public void testChrXPipelinePhased() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chrX";
		String inputFolder = "test-data/data/chrX-phased";

		File file = new File("test-data/tmp");
		if (file.exists()) {
			FileUtil.deleteDirectory(file);
		}

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "phase1", "eagle-phasing");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_X.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile vcfFile = VcfFileUtil.load("test-data/tmp/chrX.dose.vcf.gz", 100000000, false);

		assertEquals("X", vcfFile.getChromosome());
		assertEquals(26, vcfFile.getNoSamples());
		assertEquals(true, vcfFile.isPhased());
		assertEquals(TOTAL_REFPANEL_CHRX_B37, vcfFile.getNoSnps());

		FileUtil.deleteDirectory(file);

	}

	@Test
	public void testChr23PipelinePhased() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chrX";
		String inputFolder = "test-data/data/chr23-phased";

		File file = new File("test-data/tmp");
		if (file.exists()) {
			FileUtil.deleteDirectory(file);
		}

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "phase1", "eagle-phasing");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_X.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile vcfFile = VcfFileUtil.load("test-data/tmp/chrX.dose.vcf.gz", 100000000, false);

		assertEquals("X", vcfFile.getChromosome());
		assertEquals(26, vcfFile.getNoSamples());
		assertEquals(true, vcfFile.isPhased());
		assertEquals(TOTAL_REFPANEL_CHRX_B37, vcfFile.getNoSnps());

		FileUtil.deleteDirectory(file);

	}

	@Test
	public void testChrXLeaveOneOutPipelinePhased() throws IOException, ZipException {

		// SNP 26963697 from input excluded and imputed!
		// true genotypes:
		// 1,1|1,1|1,1|1,1,1|1,1,1|1,1|1,1,0,1|1,1|0,1,1,1,1,1,1|1,1,1|1,1|1,1|1,1|1,1|1,1|0,

		String configFolder = "test-data/configs/hapmap-chrX";
		String inputFolder = "test-data/data/chrX-phased-loo";

		File file = new File("test-data/tmp");
		if (file.exists()) {
			FileUtil.deleteDirectory(file);
		}

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "phase1", "eagle-phasing");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_X.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile vcfFile = VcfFileUtil.load("test-data/tmp/chrX.dose.vcf.gz", 100000000, false);

		VCFFileReader vcfReader = new VCFFileReader(new File(vcfFile.getVcfFilename()), false);

		CloseableIterator<VariantContext> it = vcfReader.iterator();

		while (it.hasNext()) {

			VariantContext line = it.next();

			if (line.getStart() == 26963697) {
				assertEquals(2, line.getHetCount());
				assertEquals(1, line.getHomRefCount());
				assertEquals(23, line.getHomVarCount());

			}
		}

		vcfReader.close();

		FileUtil.deleteDirectory(file);

	}

	@Test
	public void testPipelineWithPhasedHg19ToHg38() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20-hg38";
		String inputFolder = "test-data/data/chr20-phased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());
		assertEquals(TOTAL_REFPANEL_CHR20_B38 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testChrXPipelineWithPhasedHg38() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chrX-hg38";
		String inputFolder = "test-data/data/chrX-phased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_X.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile vcfFile = VcfFileUtil.load("test-data/tmp/chrX.dose.vcf.gz", 100000000, false);

		assertEquals("X", vcfFile.getChromosome());
		assertEquals(26, vcfFile.getNoSamples());
		assertEquals(true, vcfFile.isPhased());
		assertEquals(TOTAL_REFPANEL_CHRX_B38, vcfFile.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWithEagleHg19ToHg38() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20-hg38";
		String inputFolder = "test-data/data/chr20-unphased";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());

		assertEquals(TOTAL_REFPANEL_CHR20_B38 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}
	
	@Test
	public void testPipelineWithEagleHg38ToHg38() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20-hg38";
		String inputFolder = "test-data/data/chr20-unphased-hg38";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");
		context.setInput("build", "hg38");
			
		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());

		assertEquals(TOTAL_REFPANEL_CHR20_B38 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testChrXPipelineWithEagleHg38() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chrX-hg38";
		String inputFolder = "test-data/data/chrX-unphased";

		// maybe git large files?
		if (!new File(
				"test-data/configs/hapmap-chrX-hg38/ref-panels/ALL.X.nonPAR.phase1_v3.snps_indels_svs.genotypes.all.noSingleton.recode.hg38.bcf")
						.exists()) {
			System.out.println("chrX bcf nonPAR file not available");
			return;
		}

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_X.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile vcfFile = VcfFileUtil.load("test-data/tmp/chrX.dose.vcf.gz", 100000000, false);

		assertEquals("X", vcfFile.getChromosome());
		assertEquals(26, vcfFile.getNoSamples());
		assertEquals(true, vcfFile.isPhased());
		assertEquals(TOTAL_REFPANEL_CHRX_B38, vcfFile.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWithPhasedHg38ToHg19() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-phased-hg38";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");
		context.setInput("build", "hg38");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());
		assertEquals(TOTAL_REFPANEL_CHR20_B37 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	@Test
	public void testPipelineWithEagleHg38ToHg19() throws IOException, ZipException {

		String configFolder = "test-data/configs/hapmap-chr20";
		String inputFolder = "test-data/data/chr20-unphased-hg38";

		// create workflow context
		WorkflowTestContext context = buildContext(inputFolder, "hapmap2", "eagle");
		context.setInput("build", "hg38");

		// run qc to create chunkfile
		QcStatisticsMock qcStats = new QcStatisticsMock(configFolder);
		boolean result = run(context, qcStats);

		assertTrue(result);
		assertTrue(context.hasInMemory("Remaining sites in total: 7,735"));

		// add panel to hdfs
		importRefPanel(FileUtil.path(configFolder, "ref-panels"));
		// importMinimacMap("test-data/B38_MAP_FILE.map");
		importBinaries("files/bin");

		// run imputation
		ImputationMinimac3Mock imputation = new ImputationMinimac3Mock(configFolder);
		result = run(context, imputation);
		assertTrue(result);

		// run export
		CompressionEncryptionMock export = new CompressionEncryptionMock("files");
		result = run(context, export);
		assertTrue(result);

		ZipFile zipFile = new ZipFile("test-data/tmp/local/chr_20.zip");
		if (zipFile.isEncrypted()) {
			zipFile.setPassword(CompressionEncryption.DEFAULT_PASSWORD);
		}
		zipFile.extractAll("test-data/tmp");

		VcfFile file = VcfFileUtil.load("test-data/tmp/chr20.dose.vcf.gz", 100000000, false);

		assertEquals("20", file.getChromosome());
		assertEquals(51, file.getNoSamples());
		assertEquals(true, file.isPhased());

		assertEquals(TOTAL_REFPANEL_CHR20_B37 + ONLY_IN_INPUT, file.getNoSnps());

		FileUtil.deleteDirectory("test-data/tmp");

	}

	protected boolean run(WorkflowTestContext context, WorkflowStep step) {
		step.setup(context);
		return step.run(context);
	}

	protected WorkflowTestContext buildContext(String folder, String refpanel, String phasing) {
		WorkflowTestContext context = new WorkflowTestContext();
		File file = new File("test-data/tmp");
		if (file.exists()) {
			FileUtil.deleteDirectory(file);
		}
		file.mkdirs();
		context.setVerbose(VERBOSE);
		context.setInput("files", folder);
		context.setInput("population", "eur");
		context.setInput("refpanel", refpanel);
		context.setInput("chunksize", "10000000");
		context.setInput("phasingsize", "5000000");
		context.setInput("rounds", "0");
		context.setInput("window", "500000");
		context.setInput("phasing", phasing);
		context.setInput("sample-limit", "0");
		context.setInput("minimacbin", "Minimac4");

		context.setOutput("mafFile", file.getAbsolutePath() + "/mafFile/mafFile.txt");
		FileUtil.createDirectory(file.getAbsolutePath() + "/mafFile");

		context.setOutput("chunkFileDir", file.getAbsolutePath() + "/chunkFileDir");
		FileUtil.createDirectory(file.getAbsolutePath() + "/chunkFileDir");

		context.setOutput("statisticDir", file.getAbsolutePath() + "/statisticDir");
		FileUtil.createDirectory(file.getAbsolutePath() + "/statisticDir");

		context.setOutput("chunksDir", file.getAbsolutePath() + "/chunksDir");
		FileUtil.createDirectory(file.getAbsolutePath() + "/chunksDir");

		context.setOutput("local", file.getAbsolutePath() + "/local");
		FileUtil.createDirectory(file.getAbsolutePath() + "/local");

		context.setOutput("logfile", file.getAbsolutePath() + "/logfile");
		FileUtil.createDirectory(file.getAbsolutePath() + "/logfile");

		context.setHdfsTemp("minimac-temp");
		HdfsUtil.createDirectory(context.getHdfsTemp());

		context.setOutput("outputimputation", "cloudgene-hdfs");

		context.setOutput("hadooplogs", file.getAbsolutePath() + "/hadooplogs");
		FileUtil.deleteDirectory(file.getAbsolutePath() + "/hadooplogs");
		FileUtil.createDirectory(file.getAbsolutePath() + "/hadooplogs");

		context.setLocalTemp("local-temp");
		FileUtil.deleteDirectory("local-temp");
		FileUtil.createDirectory("local-temp");

		return context;

	}

	private void importMinimacMap2(String file) {
		System.out.println("Import Minimac Map");
		String target = HdfsUtil.path("meta", FileUtil.getFilename(file));
		System.out.println("  Import " + file + " to " + target);
		HdfsUtil.put(file, target);
	}

	private void importRefPanel(String folder) {
		System.out.println("Import Reference Panels:");
		String[] files = FileUtil.getFiles(folder, "*.*");
		for (String file : files) {
			String target = HdfsUtil.path("ref-panels", FileUtil.getFilename(file));
			System.out.println("  Import " + file + " to " + target);
			HdfsUtil.put(file, target);
		}
	}

	private void importBinaries(String folder) {
		System.out.println("Import Binaries:");
		String[] files = FileUtil.getFiles(folder, "*.*");
		for (String file : files) {
			String target = HdfsUtil.path(ImputationJobMinimac3.DATA_FOLDER, FileUtil.getFilename(file));
			System.out.println("  Import " + file + " to " + target);
			HdfsUtil.put(file, target);
		}
	}

	class CompressionEncryptionMock extends CompressionEncryption {

		private String folder;

		public CompressionEncryptionMock(String folder) {
			super();
			this.folder = folder;
		}

		@Override
		public String getFolder(Class clazz) {
			// override folder with static folder instead of jar location
			return folder;
		}

	}

	class ImputationMinimac3Mock extends ImputationMinimac3 {

		private String folder;

		public ImputationMinimac3Mock(String folder) {
			super();
			this.folder = folder;
		}

		@Override
		public String getFolder(Class clazz) {
			// override folder with static folder instead of jar location
			return folder;
		}

	}

	class QcStatisticsMock extends FastQualityControl {

		private String folder;

		public QcStatisticsMock(String folder) {
			super();
			this.folder = folder;
		}

		@Override
		public String getFolder(Class clazz) {
			// override folder with static folder instead of jar location
			return folder;
		}

		@Override
		protected void setupTabix(String folder) {
			VcfFileUtil.setTabixBinary("files/bin/tabix");
		}

	}

	class InputValidationMock extends InputValidation {

		private String folder;

		public InputValidationMock(String folder) {
			super();
			this.folder = folder;
		}

		@Override
		public String getFolder(Class clazz) {
			// override folder with static folder instead of jar location
			return folder;
		}

		@Override
		protected void setupTabix(String folder) {
			VcfFileUtil.setTabixBinary("files/bin/tabix");
		}

	}
}
