package org.biojava.spark;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.biojava.nbio.alignment.Alignments;
import org.biojava.nbio.alignment.Alignments.PairwiseSequenceAlignerType;
import org.biojava.nbio.alignment.SimpleGapPenalty;
import org.biojava.nbio.alignment.template.GapPenalty;
import org.biojava.nbio.alignment.template.PairwiseSequenceAligner;
import org.biojava.nbio.core.alignment.matrices.SubstitutionMatrixHelper;
import org.biojava.nbio.core.alignment.template.SubstitutionMatrix;
import org.biojava.nbio.core.exceptions.CompoundNotFoundException;
import org.biojava.nbio.core.sequence.ProteinSequence;
import org.biojava.nbio.core.sequence.compound.AminoAcidCompound;
import org.biojava.nbio.structure.AminoAcidImpl;
import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.AtomImpl;
import org.biojava.nbio.structure.Chain;
import org.biojava.nbio.structure.ChainImpl;
import org.biojava.nbio.structure.Element;
import org.biojava.nbio.structure.Group;
import org.biojava.nbio.structure.Structure;
import org.biojava.nbio.structure.contact.AtomContact;
import org.biojava.nbio.structure.contact.AtomContactSet;
import org.biojava.nbio.structure.contact.Grid;
import org.biojava.nbio.structure.io.mmcif.model.ChemComp;
import org.biojava.nbio.structure.io.mmtf.MmtfStructureReader;
import org.biojava.spark.data.AtomContactRDD;
import org.biojava.spark.data.AtomDataRDD;
import org.biojava.spark.mappers.CalculateContacts;
import org.biojava.spark.mappers.CalculateFrequency;
import org.rcsb.mmtf.api.StructureDataInterface;
import org.rcsb.mmtf.dataholders.MmtfStructure;
import org.rcsb.mmtf.decoder.DefaultDecoder;
import org.rcsb.mmtf.decoder.ReaderUtils;
import org.rcsb.mmtf.decoder.StructureDataToAdapter;
import org.rcsb.mmtf.serialization.MessagePackSerialization;
import org.rcsb.mmtf.spark.utils.SparkUtils;
import org.rcsb.mmtf.spark.data.AtomSelectObject;
import org.rcsb.mmtf.spark.data.SegmentDataRDD;
import org.rcsb.mmtf.spark.data.StructureDataRDD;

import scala.Tuple2;

/**
 * A class of Biojava related Spark utility methods. These extend {@link SparkUtils}.
 * @author Anthony Bradley
 *
 */
public class BiojavaSparkUtils {

	/**
	 * Find the contacts for each structure in the PDB.
	 * @param selectObjectOne the first type of atoms
	 * @param selectObjectTwo the second type of atoms
	 * @param cutoff the cutoff distance (max) in Angstrom
	 * @return the {@link JavaPairRDD} of {@link AtomContact} objects
	 */
	public static AtomContactRDD findContacts(StructureDataRDD structureDataRDD, AtomSelectObject selectObjectOne, AtomSelectObject selectObjectTwo, double cutoff) {
		return new AtomContactRDD(structureDataRDD.getJavaRdd().flatMap(new CalculateContacts(selectObjectOne, selectObjectTwo, cutoff)));
	}

	/**
	 * Find the contacts for each structure in the PDB.
	 * @param selectObjectOne the type of atoms
	 * @param cutoff the cutoff distance (max) in Angstrom
	 * @return the {@link JavaPairRDD} of {@link AtomContact} objects
	 */
	public static AtomContactRDD findContacts(StructureDataRDD structureDataRDD, AtomSelectObject selectObjectOne, double cutoff) {
		return new AtomContactRDD(structureDataRDD.getJavaRdd().flatMap(new CalculateContacts(selectObjectOne, selectObjectOne, cutoff)));
	}

	/**
	 * Find the contacts for each structure in the PDB.
	 * @param cutoff the cutoff distance (max) in Angstrom
	 * @return the {@link JavaPairRDD} of {@link AtomContact} objects
	 */
	public static AtomContactRDD findContacts(StructureDataRDD structureDataRDD, double cutoff) {
		return new AtomContactRDD(structureDataRDD.getJavaRdd().flatMap(new CalculateContacts(new AtomSelectObject(), new AtomSelectObject(), cutoff)));
	}


	/**
	 * Find the given type of atoms for each structure in the PDB.
	 * @param selectObjectOne the type of atom to find
	 * @return the {@link JavaRDD} of {@link Atom} objects
	 */
	public static AtomDataRDD findAtoms(StructureDataRDD structureDataRDD, AtomSelectObject selectObjectOne) {
		return new AtomDataRDD(structureDataRDD.getJavaRdd().flatMap(new CalculateFrequency(selectObjectOne)));
	}

	/**
	 * Find all the atoms in the RDD.
	 * @return the {@link JavaRDD} of {@link Atom} objects
	 */
	public static AtomDataRDD findAtoms(StructureDataRDD structureDataRDD) {
		return new AtomDataRDD(structureDataRDD.getJavaRdd().flatMap(new CalculateFrequency(new AtomSelectObject())));
	}
	
	/**
	 * Get an {@link JavaPairRDD} of {@link String} {@link Structure} from a file path.
	 * @param filePath the input path to the hadoop sequence file
	 * @param javaSparkContext the {@link JavaSparkContext} 
	 * @return the {@link JavaPairRDD} of {@link String} {@link Structure}
	 */
	public static JavaPairRDD<String, Structure> getBiojavaRdd(String filePath) {
		return SparkUtils.getSparkContext()
				.sequenceFile(filePath, Text.class, BytesWritable.class, 8)
				// Roughly thirty seconds
				.mapToPair(t -> new Tuple2<String, byte[]>(t._1.toString(), ReaderUtils.deflateGzip(t._2.getBytes())))
				// Roughly a minute 
				.mapToPair(t -> new Tuple2<String, MmtfStructure>(t._1, new MessagePackSerialization().deserialize(new ByteArrayInputStream(t._2))))
				// Roughly a minute
				.mapToPair(t -> new Tuple2<String, StructureDataInterface>(t._1,  new DefaultDecoder(t._2)))
				// Now convert to Biojava strcutre
				.mapToPair(t -> {
					MmtfStructureReader mmtfStructureReader = new MmtfStructureReader();
					new StructureDataToAdapter(t._2, mmtfStructureReader);
					return new Tuple2<String, Structure>(t._1, mmtfStructureReader.getStructure());
				});
	}
	
	/**
	 * Get all the atoms of a given name or in a given group in the structure using a {@link StructureDataInterface}.
	 * @param structure the input {@link StructureDataInterface}
	 * @param atomNames the list of allowed atom names
	 * @param elementNames the list of allowed atom elements
	 * @param groupNames the list of allowed group names
	 * @param charged whether the atom needs to be charged
	 * @return the list of atoms fitting the given criteria
	 */
	public static List<Atom> getAtoms(StructureDataInterface structure, AtomSelectObject atomSelectObject) {
		List<Atom> atomList = getAtoms(structure);
		Stream<Atom> atomStream = atomList.stream();
		// Generate the filters
		List<String> atomNames = atomSelectObject.getAtomNameList();
		List<String> elementNames = atomSelectObject.getElementNameList();
		List<String> groupNames = atomSelectObject.getGroupNameList();
		List<String> groupAtomNames = atomSelectObject.getGroupAtomNameList();
		boolean charged = atomSelectObject.isCharged();
		String groupType = atomSelectObject.getGroupType();

		if(atomNames!=null && atomNames.size()!=0){
			atomStream = atomStream.filter(atom -> atomNames.contains(atom.getName()));
		}
		if(elementNames!=null && elementNames.size()!=0){
			atomStream = atomStream.filter(atom -> elementNames.contains(atom.getElement().toString()));
		}
		if(groupNames!=null && groupNames.size()!=0){
			atomStream = atomStream.filter(atom -> groupNames.contains(atom.getGroup().getPDBName()));
		}
		if(charged){
			atomStream = atomStream.filter(atom -> atom.getCharge()!=0);
		}
		if(groupType!=null){
			atomStream = atomStream.filter(atom -> atom.getGroup().getChemComp().getType().equals(groupType));
		}
		if(groupAtomNames!=null && groupAtomNames.size()!=0){
			atomStream = atomStream.filter(atom -> groupAtomNames.contains(getGroupAtomName(atom)));
		}
		return atomStream.collect(Collectors.toList());
	}


	/**
	 * Get all the atom contacts in a list of atoms.
	 * @param atoms the list of {@link Atom}s
	 * @param cutoff the cutoff distance
	 * @return the {@link AtomContactSet} of the contacts
	 */
	public static AtomContactSet getAtomContacts(List<Atom> atoms, double cutoff) {
		Grid grid = new Grid(cutoff);
		Atom[] atomArray = atoms.toArray(new Atom[atoms.size()]);
		grid.addAtoms(atomArray);
		return grid.getContacts();
	}

	/**
	 * Get the contacts between two lists of atoms
	 * @param atomListOne the first list of {@link Atom}s
	 * @param atomListTwo the second list of {@link Atom}s
	 * @param cutoff the cutoff to define a contact
	 * @return the {@link AtomContactSet} of the contacts
	 */
	public static AtomContactSet getAtomContacts(List<Atom> atomListOne, List<Atom> atomListTwo, double cutoff) {
		Grid grid = new Grid(cutoff);
		Atom[] atomArrayOne = atomListOne.toArray(new Atom[atomListOne.size()]);
		Atom[] atomArrayTwo = atomListTwo.toArray(new Atom[atomListTwo.size()]);
		grid.addAtoms(atomArrayOne, atomArrayTwo);
		return grid.getContacts();
	}



	/**
	 * Get all the atoms in the structure using a {@link StructureDataInterface}.
	 * @param structure the input {@link StructureDataInterface}
	 * @param isCharged whether you only want charged atoms
	 * @return the list of atoms
	 */
	public static List<Atom> getAtoms(StructureDataInterface structure) {
		List<Atom> atomList = new ArrayList<>();
		int lastNumGroup = 0;
		int atomCounter = 0;
		for(int chainInd=0; chainInd<structure.getChainsPerModel()[0]; chainInd++){

			// Set the type
			ChemComp cc = new ChemComp();
			cc.setType(SparkUtils.getType(structure, chainInd));
			int numGroups = structure.getGroupsPerChain()[chainInd];
			Chain chain = new ChainImpl();
			chain.setId(structure.getChainIds()[chainInd]);
			// Loop through the groups
			for(int i=0; i<numGroups; i++) {
				Group group = new AminoAcidImpl();
				group.setChemComp(cc);
				group.setResidueNumber(structure.getChainIds()[chainInd], i, '?');
				group.setChain(chain);
				int groupType = structure.getGroupTypeIndices()[i+lastNumGroup];
				group.setPDBName(structure.getGroupName(groupType));
				int[] atomCharges = structure.getGroupAtomCharges(groupType);
				for(int j=0; j<atomCharges.length; j++){
					Atom atom = new AtomImpl();
					atom.setX(structure.getxCoords()[atomCounter]);
					atom.setY(structure.getyCoords()[atomCounter]);
					atom.setZ(structure.getzCoords()[atomCounter]);
					atom.setName(structure.getGroupAtomNames(groupType)[j]);
					atom.setElement(Element.valueOfIgnoreCase(structure.getGroupElementNames(groupType)[j]));
					atom.setCharge((short) atomCharges[j]);
					atom.setPDBserial(structure.getAtomIds()[atomCounter]);
					atom.setGroup(group);
					atomList.add(atom);
					atomCounter++;
				}
			}
			lastNumGroup+=structure.getGroupsPerChain()[chainInd];
		}
		return atomList;
	}
	
	/**
	 * Filter the {@link SegmentDataRDD} based on minimum sequence similarity to a reference sequence.
	 * @param inputSequence the reference sequence to compare
	 * @param minSimilarity the minimum similarity (as a double between 0.00 and 1.00)
	 * @return the {@link SegmentDataRDD} after being filtered 
	 * @throws CompoundNotFoundException if Biojava cannot accurately convert the String sequence to a {@link ProteinSequence}
	 */
	public static SegmentDataRDD filterSequenceSimilar(SegmentDataRDD segmentDataRDD, String inputSequence, double minSimilarity) throws CompoundNotFoundException {
		ProteinSequence proteinSequence = new ProteinSequence(inputSequence);
		// First set up the environment
		int gop = 8;
		int extend = 1;
		GapPenalty penalty = new SimpleGapPenalty();
		penalty.setOpenPenalty(gop);
		penalty.setExtensionPenalty(extend);
		SubstitutionMatrix<AminoAcidCompound> matrix = SubstitutionMatrixHelper.getBlosum65();
		return new SegmentDataRDD(segmentDataRDD.getSegmentRDD().filter(t -> {
			ProteinSequence otherSequence = new ProteinSequence(t._2.getSequence());
			PairwiseSequenceAligner<ProteinSequence, AminoAcidCompound> smithWaterman =
					Alignments.getPairwiseAligner(proteinSequence, otherSequence, PairwiseSequenceAlignerType.LOCAL, penalty, matrix);
			if(smithWaterman.getSimilarity()<minSimilarity){
				return false;
			}
			return true;
		}));
	}

	/**
	 * Get a conjoined group atom name from an atom.
	 * @param atom the input atom
	 * @return the String describing the conjoined group atom name.
	 */
	public static String getGroupAtomName(Atom atom) {
		return atom.getGroup().getPDBName()+"_"+atom.getName();
	}
	

}
