package org.apache.ctakes.temporal.ae;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.ctakes.relationextractor.ae.RelationExtractorAnnotator;
import org.apache.ctakes.relationextractor.ae.features.PartOfSpeechFeaturesExtractor;
import org.apache.ctakes.relationextractor.ae.features.RelationFeaturesExtractor;
import org.apache.ctakes.relationextractor.ae.features.TokenFeaturesExtractor;
import org.apache.ctakes.temporal.ae.feature.CheckSpecialWordRelationExtractor;
import org.apache.ctakes.temporal.ae.feature.DependencyPathFeaturesExtractor;
import org.apache.ctakes.temporal.ae.feature.NearbyVerbTenseRelationExtractor;
import org.apache.ctakes.temporal.ae.feature.NearestFlagFeatureExtractor;
import org.apache.ctakes.temporal.ae.feature.SectionHeaderRelationExtractor;
//import org.apache.ctakes.temporal.ae.feature.TemporalAttributeFeatureExtractor;
//import org.apache.ctakes.temporal.ae.feature.treekernel.EventTimeFlatTreeFeatureExtractor;
//import org.apache.ctakes.temporal.ae.feature.treekernel.EventVerbRelationTreeExtractor;
import org.apache.ctakes.temporal.ae.feature.treekernel.TemporalPETExtractor;
//import org.apache.ctakes.temporal.ae.feature.treekernel.TemporalPathExtractor;
import org.apache.ctakes.typesystem.type.relation.BinaryTextRelation;
import org.apache.ctakes.typesystem.type.textsem.EventMention;
import org.apache.ctakes.typesystem.type.textsem.IdentifiedAnnotation;
import org.apache.ctakes.typesystem.type.textsem.TimeMention;
import org.apache.ctakes.typesystem.type.textspan.Sentence;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.cleartk.classifier.CleartkAnnotator;
import org.cleartk.classifier.DataWriter;
import org.cleartk.classifier.jar.DefaultDataWriterFactory;
import org.cleartk.classifier.jar.DirectoryDataWriterFactory;
import org.cleartk.classifier.jar.GenericJarClassifierFactory;
import org.uimafit.factory.AnalysisEngineFactory;
import org.uimafit.util.JCasUtil;

import com.google.common.collect.Lists;

public class EventTimeRelationAnnotator extends RelationExtractorAnnotator {

	public static AnalysisEngineDescription createDataWriterDescription(
			Class<? extends DataWriter<String>> dataWriterClass,
					File outputDirectory,
					double probabilityOfKeepingANegativeExample) throws ResourceInitializationException {
		return AnalysisEngineFactory.createPrimitiveDescription(
				EventTimeRelationAnnotator.class,
				CleartkAnnotator.PARAM_IS_TRAINING,
				true,
				DefaultDataWriterFactory.PARAM_DATA_WRITER_CLASS_NAME,
				dataWriterClass,
				DirectoryDataWriterFactory.PARAM_OUTPUT_DIRECTORY,
				outputDirectory,
				RelationExtractorAnnotator.PARAM_PROBABILITY_OF_KEEPING_A_NEGATIVE_EXAMPLE,
				// not sure why this has to be cast; something funny going on in uimaFIT maybe?
				(float) probabilityOfKeepingANegativeExample);
	}

	public static AnalysisEngineDescription createAnnotatorDescription(File modelDirectory)
			throws ResourceInitializationException {
		return AnalysisEngineFactory.createPrimitiveDescription(
				EventTimeRelationAnnotator.class,
				CleartkAnnotator.PARAM_IS_TRAINING,
				false,
				GenericJarClassifierFactory.PARAM_CLASSIFIER_JAR_PATH,
				new File(modelDirectory, "model.jar"));
	}

	@Override
	protected List<RelationFeaturesExtractor> getFeatureExtractors() {
		return Lists.newArrayList(
				new TokenFeaturesExtractor()
				, new PartOfSpeechFeaturesExtractor()
				//    						, new TemporalAttributeFeatureExtractor()
//				, new EventTimeFlatTreeFeatureExtractor()
				, new TemporalPETExtractor()
//				, new TemporalPathExtractor()
//				, new EventVerbRelationTreeExtractor()
				, new SectionHeaderRelationExtractor()
				, new NearbyVerbTenseRelationExtractor()
				, new CheckSpecialWordRelationExtractor()
				, new NearestFlagFeatureExtractor()
				, new DependencyPathFeaturesExtractor()
//				, new DependencyFeatureExtractor()
				);
	}

	@Override
	protected Class<? extends Annotation> getCoveringClass() {
		return Sentence.class;
	}

	@Override
	public List<IdentifiedAnnotationPair> getCandidateRelationArgumentPairs(
			JCas jCas,
			Annotation sentence) {
		List<IdentifiedAnnotationPair> pairs = Lists.newArrayList();
		for (EventMention event : JCasUtil.selectCovered(jCas, EventMention.class, sentence)) {
			// ignore subclasses like Procedure and Disease/Disorder
			if (event.getClass().equals(EventMention.class)) {
				for (TimeMention time : JCasUtil.selectCovered(jCas, TimeMention.class, sentence)) {
					pairs.add(new IdentifiedAnnotationPair(event, time));
				}
			}
		}
		
		//only use gold pairs:
//		for (BinaryTextRelation relation : JCasUtil.select(jCas, BinaryTextRelation.class)) {
//			Annotation arg1 = relation.getArg1().getArgument();
//			Annotation arg2 = relation.getArg2().getArgument();
//			EventMention event = null;
//			TimeMention time = null;
//			if(arg1 instanceof EventMention){
//				 event = (EventMention) arg1;
//			}else if(arg1 instanceof TimeMention){
//				time = (TimeMention) arg1;
//			}
//			if(arg2 instanceof EventMention){
//				 event = (EventMention) arg2;
//			}else if(arg2 instanceof TimeMention){
//				time = (TimeMention) arg2;
//			}
//			if(event != null && time != null){
//				pairs.add(new IdentifiedAnnotationPair(event, time));
//			}
//		}
		
		return pairs;
	}


	@Override
	protected String getRelationCategory(
			Map<List<Annotation>, BinaryTextRelation> relationLookup,
			IdentifiedAnnotation arg1,
			IdentifiedAnnotation arg2) {
		BinaryTextRelation relation = relationLookup.get(Arrays.asList(arg1, arg2));
		String category = null;
		if (relation != null) {
			category = relation.getCategory();
		} else {
			relation = relationLookup.get(Arrays.asList(arg2, arg1));
			if (relation != null) {
				if(relation.getCategory().equals("OVERLAP")){
					category = relation.getCategory();
				}else{
					category = relation.getCategory() + "-1";
				}
			}
		}
		if (category == null && coin.nextDouble() <= this.probabilityOfKeepingANegativeExample) {
			category = NO_RELATION_CATEGORY;
		}
		return category;
	}
}
