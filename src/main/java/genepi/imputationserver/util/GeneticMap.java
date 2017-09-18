package genepi.imputationserver.util;

import java.io.IOException;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import genepi.hadoop.HdfsUtil;

public class GeneticMap {

	private String id;

	private String mapPatternShapeIT;

	private String mapPatternHapiUR;

	private String mapShapeIT;

	private String mapHapiUR;

	private String mapEagle;

	private String refEagle;

	private String refPatternEagle;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getMapPatternShapeIT() {
		return mapPatternShapeIT;
	}

	public void setMapPatternShapeIT(String mapPatternShapeIT) {
		this.mapPatternShapeIT = mapPatternShapeIT;
	}

	public String getMapPatternHapiUR() {
		return mapPatternHapiUR;
	}

	public void setMapPatternHapiUR(String mapPatternHapiUR) {
		this.mapPatternHapiUR = mapPatternHapiUR;
	}

	public String getMapShapeIT() {
		return mapShapeIT;
	}

	public void setMapShapeIT(String mapShapeIT) {
		this.mapShapeIT = mapShapeIT;
	}

	public String getMapHapiUR() {
		return mapHapiUR;
	}

	public void setMapHapiUR(String mapHapiUR) {
		this.mapHapiUR = mapHapiUR;
	}

	public void setMapEagle(String mapEagle) {
		this.mapEagle = mapEagle;
	}

	public String getMapEagle() {
		return mapEagle;
	}

	public void setRefEagle(String refEagle) {
		this.refEagle = refEagle;
	}

	public String getRefEagle() {
		return refEagle;
	}

	public String getRefPatternEagle() {
		return refPatternEagle;
	}

	public void setRefPatternEagle(String refPatternEagle) {
		this.refPatternEagle = refPatternEagle;
	}

	public boolean checkEagle() {

		if (mapEagle == null){
			return false;
		}
		
		try {
			return FileSystem.get(HdfsUtil.getConfiguration()).exists(
					new Path(mapEagle))
					&& FileSystem.get(HdfsUtil.getConfiguration()).exists(
							new Path(refEagle));
		} catch (IOException e) {
			return false;
		}

	}

	public boolean checkHapiUR() {

		if (mapHapiUR == null){
			return false;
		}
		
		try {
			return FileSystem.get(HdfsUtil.getConfiguration()).exists(
					new Path(mapHapiUR));
		} catch (IOException e) {
			return false;
		}

	}

	public boolean checkShapeIT() {

		if (mapShapeIT == null){
			return false;
		}
		
		try {
			return FileSystem.get(HdfsUtil.getConfiguration()).exists(
					new Path(mapShapeIT));
		} catch (IOException e) {
			return false;
		}

	}

}
