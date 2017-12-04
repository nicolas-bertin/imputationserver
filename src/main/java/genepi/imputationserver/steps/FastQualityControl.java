package genepi.imputationserver.steps;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.util.Arrays;

import genepi.hadoop.PreferenceStore;
import genepi.hadoop.common.WorkflowContext;
import genepi.hadoop.common.WorkflowStep;
import genepi.imputationserver.steps.fastqc.ITask;
import genepi.imputationserver.steps.fastqc.ITaskProgressListener;
import genepi.imputationserver.steps.fastqc.LiftOverTask;
import genepi.imputationserver.steps.fastqc.StatisticsTask;
import genepi.imputationserver.steps.fastqc.TaskResults;
import genepi.imputationserver.steps.vcf.VcfFileUtil;
import genepi.imputationserver.util.GenomicTools;
import genepi.imputationserver.util.RefPanel;
import genepi.imputationserver.util.RefPanelList;
import genepi.io.FileUtil;
import genepi.io.text.LineWriter;

public class FastQualityControl extends WorkflowStep {

	protected void setupTabix(String folder) {
		VcfFileUtil.setTabixBinary(FileUtil.path(folder, "bin", "tabix"));
	}

	@Override
	public boolean run(WorkflowContext context) {

		String folder = getFolder(FastQualityControl.class);
		setupTabix(folder);
		String inputFiles = context.get("files");
		String reference = context.get("refpanel");
		String population = context.get("population");
		int chunkSize = Integer.parseInt(context.get("chunksize"));

		String mafFile = context.get("mafFile");
		String chunkFileDir = context.get("chunkFileDir");
		String statDir = context.get("statisticDir");
		String chunksDir = context.get("chunksDir");
		String buildGwas = context.get("build");

		// set default build
		if (buildGwas == null) {
			buildGwas = "hg19";
		}

		PreferenceStore store = new PreferenceStore(new File(FileUtil.path(folder, "job.config")));
		int phasingWindow = Integer.parseInt(store.getString("phasing.window"));

		// load reference panels
		RefPanelList panels = null;
		try {
			panels = RefPanelList.loadFromFile(FileUtil.path(folder, RefPanelList.FILENAME));
		} catch (Exception e) {
			panels = new RefPanelList();
		}

		// check reference panel
		RefPanel panel = panels.getById(reference, context.getData("refpanel"));
		if (panel == null) {
			context.error("Reference '" + reference + "' not found.");
			context.error("Available references:");
			for (RefPanel p : panels.getPanels()) {
				context.error(p.getId());
			}

			return false;
		}

		int referenceSamples = GenomicTools.getPanelSize(reference);

		String[] vcfFilenames = FileUtil.getFiles(inputFiles, "*.vcf.gz$|*.vcf$");

		Arrays.sort(vcfFilenames);

		LineWriter excludedSnpsWriter = null;

		try {
			excludedSnpsWriter = new LineWriter(FileUtil.path(statDir, "snps-excluded.txt"));
			excludedSnpsWriter.write("#Position" + "\t" + "FilterType" + "\t" + " Info");
		} catch (Exception e) {
			context.error("Error creating file writer");
			return false;
		}

		// check if liftover is needed
		if (!buildGwas.equals(panel.getBuild())) {
			context.warning("Uploaded data is " + buildGwas + " and reference is " + panel.getBuild() + ".");
			String chainFile = store.getString(buildGwas + "To" + panel.getBuild());
			if (chainFile == null) {
				context.error("Currently we do not support liftOver from " + buildGwas + " to " + panel.getBuild());
				return false;
			}

			String fullPathChainFile = FileUtil.path(folder, chainFile);
			if (!new File(fullPathChainFile).exists()) {
				context.error("Chain file " + fullPathChainFile + " not found.");
				return false;
			}

			LiftOverTask task = new LiftOverTask();
			task.setVcfFilenames(vcfFilenames);
			task.setChainFile(fullPathChainFile);
			task.setChunksDir(chunksDir);
			task.setExcludedSnpsWriter(excludedSnpsWriter);

			TaskResults results = runTask(context, task);

			if (results.isSuccess()) {
				vcfFilenames = task.getNewVcfFilenames();
			} else {
				return false;
			}

		}

		// calculate statistics

		StatisticsTask task = new StatisticsTask();
		task.setVcfFilenames(vcfFilenames);
		task.setExcludedSnpsWriter(excludedSnpsWriter);
		task.setChunkSize(chunkSize);
		task.setPhasingWindow(phasingWindow);
		task.setPopulation(population);
		// support relative path
		String legend = panel.getLegend();
		if (!legend.startsWith("/")) {
			legend = FileUtil.path(folder, legend);
		}

		task.setLegendFile(legend);
		task.setRefSamples(referenceSamples);
		task.setMafFile(mafFile);
		task.setChunkFileDir(chunkFileDir);
		task.setChunksDir(chunksDir);
		task.setStatDir(statDir);
		task.setBuild(panel.getBuild());

		TaskResults results = runTask(context, task);

		if (!results.isSuccess()) {
			return false;

		}

		DecimalFormat df = new DecimalFormat("#.00");
		DecimalFormat formatter = new DecimalFormat("###,###.###");

		StringBuffer text = new StringBuffer();

		text.append("<b>Statistics:</b> <br>");
		text.append(
				"Alternative allele frequency > 0.5 sites: " + formatter.format(task.getAlternativeAlleles()) + "<br>");
		text.append("Reference Overlap: "
				+ df.format(
						task.getFoundInLegend() / (double) (task.getFoundInLegend() + task.getNotFoundInLegend()) * 100)
				+ " %" + "<br>");

		text.append("Match: " + formatter.format(task.getMatch()) + "<br>");
		text.append("Allele switch: " + formatter.format(task.getAlleleSwitch()) + "<br>");
		text.append("Strand flip: " + formatter.format(task.getStrandFlipSimple()) + "<br>");
		text.append("Strand flip and allele switch: " + formatter.format(task.getStrandFlipAndAlleleSwitch()) + "<br>");
		text.append("A/T, C/G genotypes: " + formatter.format(task.getComplicatedGenotypes()) + "<br>");

		text.append("<b>Filtered sites:</b> <br>");
		text.append("Filter flag set: " + formatter.format(task.getFilterFlag()) + "<br>");
		text.append("Invalid alleles: " + formatter.format(task.getInvalidAlleles()) + "<br>");
		text.append("Multiallelic sites: " + formatter.format(task.getMultiallelicSites()) + "<br>");
		text.append("Duplicated sites: " + formatter.format(task.getDuplicates()) + "<br>");
		text.append("NonSNP sites: " + formatter.format(task.getNoSnps()) + "<br>");
		text.append("Monomorphic sites: " + formatter.format(task.getMonomorphic()) + "<br>");
		text.append("Allele mismatch: " + formatter.format(task.getAlleleMismatch()) + "<br>");
		text.append("SNPs call rate < 90%: " + formatter.format(task.getLowCallRate()));

		context.ok(text.toString());

		text = new StringBuffer();

		text.append("Excluded sites in total: " + formatter.format(task.getFiltered()) + "<br>");
		text.append("Remaining sites in total: " + formatter.format(task.getOverallSnps()) + "<br>");
		text.append("See " + context.createLinkToFile("statisticDir", "snps-excluded.txt") + " for details" + "<br>");

		if (task.getNotFoundInLegend() > 0) {
			text.append("Typed only sites: " + formatter.format(task.getNotFoundInLegend()) +  "<br>");
			text.append("See " + context.createLinkToFile("statisticDir", "typed-only.txt") + " for details" + "<br>");
		}

		if (task.getRemovedChunksSnps() > 0) {

			text.append("<br><b>Warning:</b> " + formatter.format(task.getRemovedChunksSnps())

					+ " Chunk(s) excluded: < 3 SNPs (see "
					+ context.createLinkToFile("statisticDir", "chunks-excluded.txt") + "  for details).");
		}

		if (task.getRemovedChunksCallRate() > 0) {

			text.append("<br><b>Warning:</b> " + formatter.format(task.getRemovedChunksCallRate())

					+ " Chunk(s) excluded: at least one sample has a call rate < 50% (see "
					+ context.createLinkToFile("statisticDir", "chunks-excluded.txt") + " for details).");
		}

		if (task.getRemovedChunksOverlap() > 0) {

			text.append("<br><b>Warning:</b> " + formatter.format(task.getRemovedChunksOverlap())

					+ " Chunk(s) excluded: reference overlap < 50% (see "
					+ context.createLinkToFile("statisticDir", "chunks-excluded.txt") + " for details).");
		}

		long excludedChunks = task.getRemovedChunksSnps() + task.getRemovedChunksCallRate()
				+ task.getRemovedChunksOverlap();

		long overallChunks = task.getOverallChunks();

		if (excludedChunks > 0) {
			text.append("<br>Remaining chunk(s): " + formatter.format(overallChunks - excludedChunks));

		}

		if (excludedChunks == overallChunks) {

			text.append("<br><b>Error:</b> No chunks passed the QC step. Imputation cannot be started!");
			context.error(text.toString());

			return false;

		}
		// strand flips (normal flip & allele switch + strand flip)
		else if (task.getStrandFlipSimple() + task.getStrandFlipAndAlleleSwitch() > 100) {
			text.append(
					"<br><b>Error:</b> More than 100 obvious strand flips have been detected. Please check strand. Imputation cannot be started!");
			context.error(text.toString());

			return false;
		}

		else if (task.isChrXMissingRate()) {
			text.append("<br><b>Error:</b> Chromosome X nonPAR region includes > 10 % mixed genotypes. Imputation cannot be started!");
			context.error(text.toString());

			return false;
		}

		else if (task.isChrXPloidyError()) {
			text.append(
					"<br><b>Error:</b> ChrX nonPAR region includes ambiguous samples (haploid and diploid positions). Imputation cannot be started! See "
							+ context.createLinkToFile("statisticDir", "chrX-info.txt"));
			context.error(text.toString());

			return false;
		}

		else {

			text.append(results.getMessage());
			context.warning(text.toString());
			return true;

		}

	}

	protected TaskResults runTask(final WorkflowContext context, ITask task) {
		context.beginTask("Running " + task.getName() + "...");
		TaskResults results;
		try {
			results = task.run(new ITaskProgressListener() {

				@Override
				public void progress(String message) {
					context.updateTask(message, WorkflowContext.RUNNING);

				}
			});

			if (results.isSuccess()) {
				context.endTask(task.getName(), WorkflowContext.OK);
			} else {
				context.endTask(task.getName() + "\n" + results.getMessage(), WorkflowContext.ERROR);
			}
			return results;
		} catch (Exception e) {
			e.printStackTrace();
			TaskResults result = new TaskResults();
			result.setSuccess(false);
			result.setMessage(e.getMessage());
			StringWriter s = new StringWriter();
			e.printStackTrace(new PrintWriter(s));
			context.println("Task '" + task.getName() + "' failed.\nException:" + s.toString());
			context.endTask(task.getName() + " failed.", WorkflowContext.ERROR);
			return result;
		} catch (Error e) {
			e.printStackTrace();
			TaskResults result = new TaskResults();
			result.setSuccess(false);
			result.setMessage(e.getMessage());
			StringWriter s = new StringWriter();
			e.printStackTrace(new PrintWriter(s));
			context.println("Task '" + task.getName() + "' failed.\nException:" + s.toString());
			context.endTask(task.getName() + " failed.", WorkflowContext.ERROR);
			return result;
		}

	}

}
