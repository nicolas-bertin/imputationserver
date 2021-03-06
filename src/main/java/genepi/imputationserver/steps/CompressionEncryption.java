package genepi.imputationserver.steps;

import genepi.hadoop.HdfsUtil;
import genepi.hadoop.PreferenceStore;
import genepi.hadoop.command.Command;
import genepi.hadoop.common.WorkflowContext;
import genepi.hadoop.common.WorkflowStep;
import genepi.imputationserver.steps.vcf.MergedVcfFile;
import genepi.imputationserver.util.ExportObject;
import genepi.imputationserver.util.FileMerger;
import genepi.imputationserver.util.PasswordCreator;
import genepi.io.FileUtil;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public class CompressionEncryption extends WorkflowStep {

	public static final String DEFAULT_PASSWORD = "imputation@michigan";

	@Override
	public boolean run(WorkflowContext context) {

		String workingDirectory = getFolder(CompressionEncryption.class);

		String output = context.get("outputimputation");
		String localOutput = context.get("local");
		String aesEncryption = context.get("aesEncryption");

		// read config if mails should be sent
		String folderConfig = getFolder(CompressionEncryption.class);
		PreferenceStore store = new PreferenceStore(new File(FileUtil.path(folderConfig, "job.config")));

		String notification = "no";
		if (store.getString("minimac.sendmail") != null && !store.getString("minimac.sendmail").equals("")) {
			notification = store.getString("minimac.sendmail");
		}

		String serverUrl = "https://imputationserver.sph.umich.edu";
		if (store.getString("server.url") != null && !store.getString("server.url").isEmpty()) {
			serverUrl = store.getString("server.url");
		}

		String password = DEFAULT_PASSWORD;

		if (notification.equals("yes")) {
			password = PasswordCreator.createPassword();
		}

		try {

			context.beginTask("Export data...");

			// get sorted directories
			List<String> folders = HdfsUtil.getDirectories(output);

			Map<String, ExportObject> chromosomes = new HashMap<String, ExportObject>();

			for (String folder : folders) {

				String name = FileUtil.getFilename(folder);

				context.println("Find files " + name);

				List<String> data = findFiles(folder, ".data.dose.vcf.gz");
				List<String> header = findFiles(folder, ".header.dose.vcf.gz");
				List<String> info = findFiles(folder, ".info");

				// combine all X. to one folder
				if (name.startsWith("X.")) {
					name = "X";
				}

				ExportObject export = chromosomes.get(name);

				if (export == null) {
					export = new ExportObject();
				}

				ArrayList<String> currentDataList = export.getDataFiles();
				currentDataList.addAll(data);
				export.setDataFiles(currentDataList);

				ArrayList<String> currentHeaderList = export.getHeaderFiles();
				currentHeaderList.addAll(header);
				export.setHeaderFiles(currentHeaderList);

				ArrayList<String> currentInfoList = export.getInfoFiles();
				currentInfoList.addAll(info);
				export.setInfoFiles(currentInfoList);

				chromosomes.put(name, export);

			}

			for (String name : chromosomes.keySet()) {

				ExportObject entry = chromosomes.get(name);
				
				context.println("Export and merge chromosome " + name);

				// resort for chrX only
				if (name.equals("X")) {
					Collections.sort(entry.getDataFiles(), new ChrXComparator());
					Collections.sort(entry.getInfoFiles(), new ChrXComparator());
				}

				// create temp fir
				String temp = FileUtil.path(localOutput, "temp");
				FileUtil.createDirectory(temp);

				// output files
				String dosageOutput = FileUtil.path(temp, "chr" + name + ".dose.vcf.gz");

				String infoOutput = FileUtil.path(temp, "chr" + name + ".info.gz");

				FileMerger.mergeAndGzInfo(entry.getInfoFiles(), infoOutput);

				MergedVcfFile vcfFile = new MergedVcfFile(dosageOutput);

				// add one header
				// TODO: check number of samples per chunk....
				String header = entry.getHeaderFiles().get(0);
				vcfFile.addFile(HdfsUtil.open(header));

				// add data files
				for (String file : entry.getDataFiles()) {
					context.println("Read file " + file);
					vcfFile.addFile(HdfsUtil.open(file));
				}

				vcfFile.close();

				// verify if valid vcf.gz
				if (name.contains("22")) {
					Command tabix = new Command(FileUtil.path(workingDirectory, "bin", "tabix"));
					tabix.setSilent(false);
					tabix.setParams("-f", dosageOutput);
					if (tabix.execute() != 0) {
						context.endTask("Error during index creation: " + tabix.getStdOut(), WorkflowContext.ERROR);
						return false;
					}
				}

				ZipParameters param = new ZipParameters();
				param.setEncryptFiles(true);
				param.setPassword(password);
				param.setEncryptionMethod(Zip4jConstants.ENC_METHOD_STANDARD);

				if (aesEncryption != null && aesEncryption.equals("yes")) {
					param.setEncryptionMethod(Zip4jConstants.ENC_METHOD_AES);
					param.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_256);
					param.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
					param.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
				}

				// create zip file
				ArrayList<File> files = new ArrayList<File>();
				files.add(new File(dosageOutput));
				// files.add(new File(vcfOutput + ".tbi"));
				files.add(new File(infoOutput));

				ZipFile file = new ZipFile(new File(FileUtil.path(localOutput, "chr_" + name + ".zip")));
				file.createZipFile(files, param);

				// delete temp dir
				FileUtil.deleteDirectory(temp);

			}

			// delete temporary files
			HdfsUtil.delete(output);

			context.endTask("Exported data.", WorkflowContext.OK);

		} catch (Exception e) {
			e.printStackTrace();
			context.endTask("Data compression failed: " + e.getMessage(), WorkflowContext.ERROR);
			return false;
		}

		// submit counters!
		context.submitCounter("samples");
		context.submitCounter("genotypes");
		context.submitCounter("chromosomes");
		context.submitCounter("runs");
		// submit panel and phasing method counters
		String reference = context.get("refpanel");
		String phasing = context.get("phasing");
		context.submitCounter("refpanel_" + reference);
		context.submitCounter("phasing_" + phasing);
		context.submitCounter("23andme-input");

		// send email
		if (notification.equals("yes")) {

			Object mail = context.getData("cloudgene.user.mail");
			Object name = context.getData("cloudgene.user.name");

			if (mail != null) {

				String subject = "Job " + context.getJobId() + " is complete.";
				String message = "Dear " + name + ",\nthe password for the imputation results is: " + password
						+ "\n\nThe results can be downloaded from " + serverUrl + "/start.html#!jobs/"
						+ context.getJobId() + "/results";

				try {
					context.sendMail(subject, message);
					context.ok("We have sent an email to <b>" + mail + "</b> with the password.");
					return true;
				} catch (Exception e) {
					context.error("Data compression failed: " + e.getMessage());
					return false;
				}

			} else {
				context.error("No email address found. Please enter your email address (Account -> Profile).");
				return false;
			}

		} else {
			context.ok(
					"Email notification (and therefore encryption) is disabled. All results are encrypted with password <b>"
							+ password + "</b>");
			return true;
		}

	}

	private List<String> findFiles(String folder, String pattern) throws IOException {

		Configuration conf = HdfsUtil.getConfiguration();

		FileSystem fileSystem = FileSystem.get(conf);
		Path pathFolder = new Path(folder);
		FileStatus[] files = fileSystem.listStatus(pathFolder);

		List<String> dataFiles = new Vector<String>();
		for (FileStatus file : files) {
			if (!file.isDir() && !file.getPath().getName().startsWith("_")
					&& file.getPath().getName().endsWith(pattern)) {
				dataFiles.add(file.getPath().toString());
			}
		}
		Collections.sort(dataFiles);
		return dataFiles;
	}

	class ChrXComparator implements Comparator<String> {

		List<String> definedOrder = Arrays.asList("X.PAR1", "X.nonPAR", "X.PAR2");

		@Override
		public int compare(String o1, String o2) {
			
			String region = o1.substring(o1.lastIndexOf("/") + 1).split("_")[1];
			
			String region2 = o2.substring(o2.lastIndexOf("/") + 1).split("_")[1];
			
			return Integer.valueOf(definedOrder.indexOf(region))
					.compareTo(Integer.valueOf(definedOrder.indexOf(region2)));
		}

	}

}
