package genepi.imputationserver.steps;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import genepi.hadoop.HadoopJob;
import genepi.hadoop.HdfsUtil;
import genepi.hadoop.PreferenceStore;
import genepi.hadoop.common.ContextLog;
import genepi.hadoop.common.WorkflowContext;
import genepi.hadoop.io.HdfsLineWriter;
import genepi.imputationserver.steps.imputationMinimac3.ImputationJobMinimac3;
import genepi.imputationserver.steps.vcf.VcfChunk;
import genepi.imputationserver.util.ParallelHadoopJobStep;
import genepi.imputationserver.util.RefPanel;
import genepi.imputationserver.util.RefPanelList;
import genepi.io.FileUtil;
import genepi.io.text.LineReader;

public class ImputationMinimac3 extends ParallelHadoopJobStep {

	Map<String, HadoopJob> jobs = null;

	boolean error = false;

	private WorkflowContext context;

	private String errorChr = "";

	private boolean running = true;

	public static int THREADS = 25;

	public ImputationMinimac3() {
		super(THREADS);
		jobs = new HashMap<String, HadoopJob>();
	}

	@Override
	public void setup(WorkflowContext context) {

		this.context = context;
	}

	@Override
	public boolean run(WorkflowContext context) {

		final String folder = getFolder(ImputationMinimac3.class);

		// inputs
		String input = context.get("chunkFileDir");
		String reference = context.get("refpanel");
		String phasing = context.get("phasing");
		String rounds = context.get("rounds");
		String window = context.get("window");
		String population = context.get("population");

		String queue = "default";
		if (context.get("queues") != null) {
			queue = context.get("queues");
		}

		boolean noCache = false;
		String minimacBin = "minimac";

		if (context.get("nocache") != null) {
			noCache = context.get("nocache").equals("yes");
		}

		if (context.get("minimacbin") != null) {
			minimacBin = context.get("minimacbin");
		}

		// outputs
		String output = context.get("outputimputation");
		String log = context.get("logfile");

		if (!(new File(input)).exists()) {
			context.error("No chunks passed the QC step.");
			return false;
		}

		// load reference panels

		RefPanelList panels = null;
		try {
			panels = RefPanelList.loadFromFile(FileUtil.path(folder, RefPanelList.FILENAME));
		} catch (Exception e) {
			panels = new RefPanelList();
		}

		RefPanel panel = panels.getById(reference, context.getData("refpanel"));
		if (panel == null) {
			context.error("reference panel '" + reference + "' not found.");
			return false;
		}

		context.println("Reference Panel: ");
		context.println("  Name: " + reference);
		context.println("  Location: " + panel.getHdfs());
		context.println("  Legend: " + panel.getLegend());
		context.println("  Version: " + panel.getVersion());

		if (phasing.equals("hapiur") && !panel.checkHapiUR()) {
			context.error("Map HapiUR  '" + panel.getMapHapiUR() + "' not found.");
			return false;
		}

		if (phasing.equals("shapeit") && !panel.checkShapeIT()) {
			context.error("Map ShapeIT  '" + panel.getMapShapeIT() + "' not found.");
			return false;
		}

		if (phasing.equals("eagle") && !panel.checkEagleMap()) {
			context.error("Eagle map file not found.");
			return false;
		}
	
		// execute one job per chromosome
		try {
			String[] chunkFiles = FileUtil.getFiles(input, "*.*");

			context.beginTask("Start Imputation...");

			for (String chunkFile : chunkFiles) {

				String[] tiles = chunkFile.split("/");
				String chr = tiles[tiles.length - 1];

				ChunkFileConverterResult result = convertChunkfile(chunkFile, context.getHdfsTemp());

				ImputationJobMinimac3 job = new ImputationJobMinimac3(context.getJobId() + "-chr-" + chr,
						new ContextLog(context), queue) {
					@Override
					protected void readConfigFile() {
						File file = new File(folder + "/" + CONFIG_FILE);
						if (file.exists()) {
							log.info("Loading distributed configuration file " + folder + "/" + CONFIG_FILE + "...");
							PreferenceStore preferenceStore = new PreferenceStore(file);
							preferenceStore.write(getConfiguration());
							for (Object key : preferenceStore.getKeys()) {
								log.info("  " + key + ": " + preferenceStore.getString(key.toString()));
							}

						} else {

							log.info("No distributed configuration file (" + CONFIG_FILE + ") available.");

						}
					}
				};
				job.setFolder(folder);

				String hdfsFilenameChromosome = resolvePattern(panel.getHdfs(), chr);
				job.setRefPanelHdfs(hdfsFilenameChromosome);

				job.setBuild(panel.getBuild());
				if (panel.getMapMinimac() != null) {
					context.println("Setting up minimac map file...");
					job.setMapMinimac(panel.getMapMinimac());
				} else {
					context.println("Reference panel has no minimac map file.");
				}

				if (result.needsPhasing) {
					context.println("Input data is unphased.");
					if (phasing.equals("shapeit")) {
						// shapeit
						context.println("  Setting up shapeit map files...");
						job.setMapShapeITHdfs(panel.getMapShapeIT());
						job.setMapShapeITPattern(panel.getMapPatternShapeIT());
					} else if (phasing.equals("hapiur")) {
						// hapiUR
						context.println("  Setting up hapiur map files...");
						job.setMapHapiURHdfs(panel.getMapHapiUR());
						job.setMapHapiURPattern(panel.getMapPatternHapiUR());
					} else if (phasing.equals("eagle")) {
						// eagle
						context.println("  Setting up eagle reference and map files...");
						job.setMapEagleHdfs(panel.getMapEagle());
						String refEagleFilenameChromosome = resolvePattern(panel.getRefEagle(), chr);
						job.setRefEagleHdfs(refEagleFilenameChromosome);
					}
					job.setPhasing(phasing);

				} else {
					context.println("Input data is phased.");
				}

				job.setInput(result.filename);
				job.setOutput(HdfsUtil.path(output, chr));
				job.setRefPanel(reference);
				job.setLogFilename(FileUtil.path(log, "chr_" + chr + ".log"));
				job.setPopulation(population);
				job.setRounds(rounds);
				job.setWindow(window);
				job.setNoCache(noCache);
				job.setMinimacBin(minimacBin);
				job.setJarByClass(ImputationJobMinimac3.class);

				executeJarInBackground(chr, context, job);
				jobs.put(chr, job);

			}

			waitForAll();
			running = false;
			context.println("All jobs terminated.");

			// one job was failed
			if (error) {
				context.println("Imputation on chromosome " + errorChr + " failed. Imputation was stopped.");
				updateProgress();

				String text = updateMessage();
				context.endTask(text, WorkflowContext.ERROR);

				printSummary();

				context.error("Imputation on chromosome " + errorChr + " failed. Imputation was stopped.");
				return false;

			}

			// canceled by user
			if (isCanceled()) {
				context.println("Canceled by user.");
				updateProgress();

				String text = updateMessage();
				context.endTask(text, WorkflowContext.ERROR);

				printSummary();

				context.error("Canceled by user.");

				return false;

			}

			// everthing fine

			updateProgress();
			printSummary();

			String text = updateMessage();
			context.endTask(text, WorkflowContext.OK);

			return true;

		} catch (Exception e) {

			// unexpected exception

			updateProgress();
			printSummary();
			e.printStackTrace();
			context.updateTask(e.getMessage(), WorkflowContext.ERROR);
			return false;

		}

	}

	// print summary and download log files from tasktracker

	private void printSummary() {
		context.println("Summary: ");
		String log = context.get("hadooplogs");

		for (String id : jobs.keySet()) {

			HadoopJob job = jobs.get(id);
			Integer state = getState(job);

			try {
				job.downloadFailedLogs(log);
			} catch (Exception e) {
				context.println("[INFO] Error while downloading log files");
			}

			if (state != null) {

				if (state == OK) {

					context.println("  [OK]   Chr " + id + " (" + job.getJobId() + ")");

				} else if (state == FAILED) {

					context.println("  [FAIL] Chr " + id + " (" + job.getJobId() + ")");

				} else {
					context.println("  [" + state + "]   Chr " + id + " (" + job.getJobId() + ")");
				}

			} else {

				context.println("  [??]   Chr " + id + " (" + job.getJobId() + ")");

			}

		}

	}

	// update message

	private synchronized String updateMessage() {

		String text = "";
		String text2 = "";

		int i = 1;

		for (String id : jobs.keySet()) {

			HadoopJob job = jobs.get(id);
			Integer state = getState(job);

			if (state != null) {

				if (id.equals("X.PAR1")) {
					text2 = "X1";
				} else if (id.equals("X.nonPAR")) {
					text2 = "X2";
				} else if (id.equals("X.PAR2")) {
					text2 = "X3";
				} else {
					text2 = id;
				}

				if (state == OK) {

					text += "<span class=\"badge badge-success\" style=\"width: 40px\">Chr " + text2 + "</span>";

				}
				if (state == RUNNING) {

					text += "<span class=\"badge badge-info\" style=\"width: 40px\">Chr " + text2 + "</span>";

				}
				if (state == FAILED) {

					text += "<span class=\"badge badge-important\" style=\"width: 40px\">Chr " + text2 + "</span>";

				}
				if (state == WAIT) {

					text += "<span class=\"badge\" style=\"width: 40px\">Chr " + text2 + "</span>";

				}
			} else {
				text += "<span class=\"badge\" style=\"width: 40px\">Chr " + text2 + "</span>";
			}
			if (i % 6 == 0) {
				text += "<br>";
			}

			i++;

		}

		text += "<br>";
		text += "<br>";
		text += "<span class=\"badge\" style=\"width: 8px\">&nbsp;</span> Waiting<br>";
		text += "<span class=\"badge badge-info\" style=\"width: 8px\">&nbsp;</span> Running<br>";
		text += "<span class=\"badge badge-success\" style=\"width: 8px\">&nbsp;</span> Complete";

		return text;

	}

	@Override
	protected synchronized void onJobStart(String id, WorkflowContext context) {
		context.println("Running job chr_" + id + "....");
	}

	@Override
	protected synchronized void onJobFinish(String id, boolean successful, WorkflowContext context) {

		HadoopJob job = jobs.get(id);

		if (successful) {

			// everything fine

			context.println("Job chr_" + id + " (" + job.getJobId() + ") executed sucessful.");
		} else {

			// one job failed

			context.println("Job chr_" + id + " (" + job.getJobId() + ") failed.");

			// kill all running jobs

			if (!error && !isCanceled()) {
				error = true;
				errorChr = id;
				context.println("Kill all running jobs...");
				kill();
			}
		}

	}

	@Override
	public void updateProgress() {

		super.updateProgress();
		if (running) {
			String text = updateMessage();
			context.updateTask(text, WorkflowContext.RUNNING);
		}

	}

	class ChunkFileConverterResult {
		public String filename;

		public boolean needsPhasing;
	}

	private ChunkFileConverterResult convertChunkfile(String chunkFile, String output) throws IOException {

		String name = FileUtil.getFilename(chunkFile);
		String newChunkFile = HdfsUtil.path(output, name);

		LineReader reader = new LineReader(chunkFile);
		HdfsLineWriter writer = new HdfsLineWriter(newChunkFile);

		boolean phased = true;

		while (reader.next()) {
			VcfChunk chunk = new VcfChunk(reader.get());

			phased = phased && chunk.isPhased();

			// put vcf file
			String sourceVcf = chunk.getVcfFilename();
			String targetVcf = HdfsUtil.path(output, FileUtil.getFilename(sourceVcf));
			HdfsUtil.put(sourceVcf, targetVcf);
			chunk.setVcfFilename(targetVcf);

			// put index file
			String sourceIndex = chunk.getIndexFilename();
			String targetIndex = HdfsUtil.path(output, FileUtil.getFilename(sourceIndex));
			HdfsUtil.put(sourceIndex, targetIndex);
			chunk.setIndexFilename(targetIndex);
			writer.write(chunk.serialize());

		}
		reader.close();
		writer.close();

		ChunkFileConverterResult result = new ChunkFileConverterResult();
		result.filename = newChunkFile;
		result.needsPhasing = !phased;
		return result;

	}

	private String resolvePattern(String pattern, String chr) {
		return pattern.replaceAll("\\$chr", chr);
	}

}
