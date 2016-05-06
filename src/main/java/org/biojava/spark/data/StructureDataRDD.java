package org.biojava.spark.data;

import java.io.ByteArrayOutputStream;
import java.net.URL;

import javax.vecmath.Point3d;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.Chain;
import org.biojava.nbio.structure.contact.AtomContact;
import org.rcsb.mmtf.api.StructureDataInterface;
import org.rcsb.mmtf.encoder.DefaultEncoder;
import org.rcsb.mmtf.serialization.MessagePackSerialization;

import scala.Tuple2;

/**
 * A class to provide functions on a series of 
 * {@link StructureDataInterface} objects.
 * @author Anthony Bradley
 *
 */
public class StructureDataRDD {

	/** The RDD of the {@link StructureDataInterface} data. */
	private JavaPairRDD<String, StructureDataInterface> javaPairRdd;


	/**
	 * Empty constructor reads the sample data if {@link SparkUtils} has not been set 
	 * with a path.
	 */
	public StructureDataRDD() {
		String filePath = SparkUtils.getFilePath();
		if(filePath==null){
			URL inputPath = StructureDataRDD.class.getClassLoader().getResource("hadoop/subset");
			// Set the config for the spark context
			javaPairRdd = SparkUtils.getStructureDataRdd(inputPath.toString());
		}
		else{
			javaPairRdd = SparkUtils.getStructureDataRdd(SparkUtils.getFilePath());
		}
	}

	/**
	 * Constructor from a file. 
	 * @param inputPath the input path of the Hadoop sequence file to read
	 */
	public StructureDataRDD(String inputPath) {
		// Set the config for the spark context
		javaPairRdd = SparkUtils.getStructureDataRdd(inputPath);
	}

	/**
	 * Constructor from a {@link JavaPairRDD} of {@link String} and {@link StructureDataInterface}.
	 * @param javaPairRDD the input {@link JavaPairRDD} of 
	 * {@link String} {@link StructureDataInterface}
	 */
	public StructureDataRDD(JavaPairRDD<String, StructureDataInterface> javaPairRDD) {
		// Set the config for the spark context
		this.javaPairRdd = javaPairRDD;
	}

	/**
	 * Get the {@link JavaPairRDD} of {@link String} {@link StructureDataInterface}
	 * for this instance
	 * @return the {@link JavaPairRDD} of {@link String} {@link StructureDataInterface}
	 */
	public JavaPairRDD<String, StructureDataInterface> getJavaRdd() {
		return javaPairRdd;
	}

	/**
	 * Filter the {@link StructureDataRDD} based on R-free.
	 * @param maxRFree the maximum allowed R-free
	 * @return the filtered {@link StructureDataRDD}
	 */
	public StructureDataRDD filterRfree(double maxRFree) {
		return new StructureDataRDD(javaPairRdd.filter(t -> t._2.getRfree()<maxRFree));
	}
	/**
	 * Filter the {@link StructureDataRDD} based on resolution.
	 * @param maxRes the maximum allowed resolution (in Angstrom)
	 * @return the filtered {@link StructureDataRDD}
	 */
	public StructureDataRDD filterResolution(double maxRes) {
		return new StructureDataRDD(javaPairRdd.filter(t -> t._2.getResolution()<maxRes));
	}


	/**
	 * Find the contacts for each structure in the PDB.
	 * @param selectObjectOne the first type of atoms
	 * @param selectObjectTwo the second type of atoms
	 * @param cutoff the cutoff distance (max) in Angstrom
	 * @return the {@link JavaPairRDD} of {@link AtomContact} objects
	 */
	public AtomContactRDD findContacts(AtomSelectObject selectObjectOne, AtomSelectObject selectObjectTwo, double cutoff) {
		return new AtomContactRDD(javaPairRdd.flatMap(new CalculateContacts(selectObjectOne, selectObjectTwo, cutoff)));
	}


	/**
	 * Find the contacts for each structure in the PDB.
	 * @param selectObjectOne the type of atoms
	 * @param cutoff the cutoff distance (max) in Angstrom
	 * @return the {@link JavaPairRDD} of {@link AtomContact} objects
	 */
	public AtomContactRDD findContacts(AtomSelectObject selectObjectOne, double cutoff) {
		return new AtomContactRDD(javaPairRdd.flatMap(new CalculateContacts(selectObjectOne, selectObjectOne, cutoff)));
	}

	/**
	 * Find the contacts for each structure in the PDB.
	 * @param cutoff the cutoff distance (max) in Angstrom
	 * @return the {@link JavaPairRDD} of {@link AtomContact} objects
	 */
	public AtomContactRDD findContacts(double cutoff) {
		return new AtomContactRDD(javaPairRdd.flatMap(new CalculateContacts(new AtomSelectObject(), new AtomSelectObject(), cutoff)));
	}


	/**
	 * Find the given type of atoms for each structure in the PDB.
	 * @param selectObjectOne the type of atom to find
	 * @return the {@link JavaRDD} of {@link Atom} objects
	 */
	public AtomDataRDD findAtoms(AtomSelectObject selectObjectOne) {
		return new AtomDataRDD(javaPairRdd.flatMap(new CalculateFrequency(selectObjectOne)));
	}

	/**
	 * Find all the atoms in the RDD.
	 * @return the {@link JavaRDD} of {@link Atom} objects
	 */
	public AtomDataRDD findAtoms() {
		return new AtomDataRDD(javaPairRdd.flatMap(new CalculateFrequency(new AtomSelectObject())));
	}

	/**
	 * Get the chains for this list of {@link StructureDataInterface}.
	 * @return the chains as {@link ChainDataRDD} of {@link Chain}
	 */
	public ChainDataRDD findChains() {
		return new ChainDataRDD(
				javaPairRdd.flatMap(new GetChains()));
	}


	/**
	 * Get the {@link Point3d} of the C-alpha co-ordinate data
	 * as lightweight point 3d objects.
	 * @return the {@link JavaPairRDD} {@link String} {@link Point3d} 
	 * array
	 */
	public JavaPairRDD<String, Point3d[]> getCalphaPair() {	

		return javaPairRdd
				.flatMapToPair(new Point3dCalpha());
	}

	/**
	 * Get the number of entries in the RDD.
	 * @return the {@link Long} number of entries
	 */
	public Long size() {
		return javaPairRdd
				.count();
	}


	/**
	 * Save the data as a Hadoop sequence file.
	 * @param filePath the path to save the data to
	 */
	public void saveToFile(String filePath) {
		javaPairRdd
		.mapToPair( t -> {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			new  MessagePackSerialization().serialize(new DefaultEncoder(t._2).getMmtfEncodedStructure(), bos);
			return new Tuple2<String, byte[]>(t._1, SparkUtils.gzipCompress(
					bos.toByteArray()));
		})
		.mapToPair(t -> new Tuple2<Text, BytesWritable>(new Text(t._1), new BytesWritable(t._2)))
		.saveAsHadoopFile(filePath, Text.class, BytesWritable.class, SequenceFileOutputFormat.class);
	}


	/**
	 * Allow the user to sample the data.
	 * @param fraction the fraction of data 
	 * to be used (e.g. 0.1 retains 10%)
	 */
	public StructureDataRDD sample(double fraction) {
		return new StructureDataRDD(javaPairRdd.sample(false, fraction));
	}

}
