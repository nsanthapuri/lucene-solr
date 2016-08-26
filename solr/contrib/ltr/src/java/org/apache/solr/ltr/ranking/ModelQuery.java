/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.ltr.ranking;

import java.util.concurrent.Future;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.Semaphore;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DisiPriorityQueue;
import org.apache.lucene.search.DisiWrapper;
import org.apache.lucene.search.DisjunctionDISIApproximation;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.solr.ltr.feature.LTRScoringAlgorithm;
import org.apache.solr.ltr.feature.norm.Normalizer;
import org.apache.solr.ltr.feature.norm.impl.IdentityNormalizer;
import org.apache.solr.ltr.log.FeatureLogger;
import org.apache.solr.ltr.ranking.Feature.FeatureWeight;
import org.apache.solr.ltr.ranking.Feature.FeatureWeight.FeatureScorer;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.ltr.ranking.LTRThreadInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ranking query that is run, reranking results using the
 * LTRScoringAlgorithm algorithm
 */
public class ModelQuery extends Query {

  // contains a description of the model
  protected LTRScoringAlgorithm meta;
  // feature logger to output the features.
  protected FeatureLogger<?> fl;
  // Map of external parameters, such as query intent, that can be used by
  // features
  protected Map<String,String> efi;
  // Original solr query used to fetch matching documents
  protected Query originalQuery;
  // Original solr request
  protected SolrQueryRequest request;
  protected boolean extractAllFeatures;
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public ModelQuery(LTRScoringAlgorithm meta) {
    this(meta, false);
  }
  
  public ModelQuery(LTRScoringAlgorithm meta, boolean extractAllFeatures) {
    this.meta = meta;
    this.extractAllFeatures = extractAllFeatures; 
  }

  public LTRScoringAlgorithm getMetadata() {
    return meta;
  }

  public void setFeatureLogger(FeatureLogger fl) {
    this.fl = fl;
  }

  public FeatureLogger getFeatureLogger() {
    return fl;
  }

  public String getFeatureStoreName(){
    return meta.getFeatureStoreName();
  }

  public void setOriginalQuery(Query mainQuery) {
    originalQuery = mainQuery;
  }

  public void setExternalFeatureInfo(Map<String,String> externalFeatureInfo) {
    efi = externalFeatureInfo;
  }

  public Map<String,String> getExternalFeatureInfo() {
    return efi;
  }

  public void setRequest(SolrQueryRequest request) {
    this.request = request;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = classHash();
    result = (prime * result) + ((meta == null) ? 0 : meta.hashCode());
    result = (prime * result)
        + ((originalQuery == null) ? 0 : originalQuery.hashCode());
    result = (prime * result) + ((efi == null) ? 0 : efi.hashCode());
    result = (prime * result) + this.toString().hashCode();
    return result;
  }
@Override
  public boolean equals(Object o) {
    return sameClassAs(o) &&  equalsTo(getClass().cast(o));
  }

  private boolean equalsTo(ModelQuery other) {
    if (meta == null) {
      if (other.meta != null) {
        return false;
      }
    } else if (!meta.equals(other.meta)) {
      return false;
    }
    if (originalQuery == null) {
      if (other.originalQuery != null) {
        return false;
      }
    } else if (!originalQuery.equals(other.originalQuery)) {
      return false;
    }
    if (efi == null) {
      if (other.efi != null) {
        return false;
      }
    } else if (!efi.equals(other.efi)) {
      return false;
    }
    return true;
  }

  public SolrQueryRequest getRequest() {
    return request;
  }

  @Override
  public ModelWeight createWeight(IndexSearcher searcher, boolean needsScores, float boost)
      throws IOException {   
    long  start = System.currentTimeMillis();
    final Collection<Feature> modelFeatures = meta.getFeatures();
    final Collection<Feature> allFeatures = meta.getAllFeatures();

    int modelFeatSize = modelFeatures.size();
    int allFeatSize = this.extractAllFeatures ? allFeatures.size() : modelFeatSize;

    final FeatureWeight[] extractedFeatureWeights = new FeatureWeight[allFeatSize];
    final FeatureWeight[] modelFeaturesWeights = new FeatureWeight[modelFeatSize];
    if(LTRThreadInterface.maxThreads <= 1){
        createWeightsSelectively(allFeatures,modelFeatures,searcher, needsScores, extractedFeatureWeights,modelFeaturesWeights);
    }
    else{
        createWeightsSelectivelyParallel(allFeatures,modelFeatures,searcher, needsScores, extractedFeatureWeights,modelFeaturesWeights);
    }

    int modelFeatIndex = 0;
    Normalizer[] modelFeatureNorms = new Normalizer[modelFeatSize]; // store the featureNorms for modelFeatures to use later for normalization
    for (final Feature modelFeature: modelFeatures){
      modelFeatureNorms[modelFeatIndex++]= modelFeature.getNorm();
    }
    long  end = System.currentTimeMillis();
    log.info("In ModelQuery.java::createWeight() NEW!  createWeight time:{} ms", (end-start));
    return new ModelWeight(searcher, modelFeaturesWeights, extractedFeatureWeights, modelFeatureNorms, allFeatures.size());
  }
  
  private void createWeightsSelectively(Collection<Feature> allFeatures, 
      Collection<Feature> modelFeatures,
      IndexSearcher searcher, boolean needsScores, FeatureWeight[] allFeatureWeights,
      FeatureWeight[] modelFeaturesWeights) throws IOException {
    long  start = System.currentTimeMillis();
    int i = 0, j = 0;
    final SolrQueryRequest req = getRequest();
    // since the feature store is a linkedhashmap order is preserved
    if (this.extractAllFeatures) {
      boolean[] modelFeatMap = new boolean[allFeatures.size()];
      for (final Feature f: modelFeatures){
        modelFeatMap[f.getId()] =  true;
      }
      for (final Feature f : allFeatures) {
        try{
        FeatureWeight fw = f.createWeight(searcher, needsScores, req, originalQuery, efi);
        allFeatureWeights[i++] = fw;
        if (modelFeatMap[f.getId()]){  
          // Note: by assigning fw created using allFeatures, we lose the normalizer associated with the modelFeature, for this reason, 
          // we store it in the modelFeatureNorms, ahead of time, to use in normalize() and explain()
          modelFeaturesWeights[j++] = fw; 
       }
       }catch (final Exception e) {
          throw new FeatureException("Exception from createWeight for " + f.toString() + " "
              + e.getMessage(), e);
        }
      }
    }
    else{
      for (final Feature f : modelFeatures){
        try {
        FeatureWeight fw = f.createWeight(searcher, needsScores, req, originalQuery, efi);
        allFeatureWeights[i++] = fw;
        modelFeaturesWeights[j++] = fw; 
        }catch (final Exception e) {
          throw new FeatureException("Exception from createWeight for " + f.toString() + " "
              + e.getMessage(), e);
        }
      }
    }
    long  end = System.currentTimeMillis();
    log.info("\tIn ModelQuery.java::createWeightsSelectively()  createWeights time:{} ms totalModelFeatsFound = {} total allFeatureWeights: {}", (end-start), j, i);
  }
  
  class CreateWeightCallable implements Callable<FeatureWeight>{
    private Feature f;
    IndexSearcher searcher;
    boolean needsScores;
    SolrQueryRequest req;
    
    public CreateWeightCallable(Feature f, IndexSearcher searcher, boolean needsScores, SolrQueryRequest req){
       this.f = f;
       this.searcher = searcher;
       this.needsScores = needsScores;
       this.req = req;
    }
    
    public FeatureWeight call() throws Exception{
      try {
        FeatureWeight fw  = f.createWeight(searcher, needsScores, req, originalQuery, efi);
        return fw;
      } catch (final Exception se) {
        throw new FeatureException("Exception from createWeight for " + f.toString() + " "
        + se.getMessage(), se);
      } finally {
        LTRThreadInterface.ltrSemaphore.release();
      }
    }
} // end of call CreateWeightCallable
  
  private void createWeightsSelectivelyParallel(Collection<Feature> allFeatures, 
      Collection<Feature> modelFeatures,
      IndexSearcher searcher, boolean needsScores, FeatureWeight[] allFeatureWeights,
      FeatureWeight[] modelFeaturesWeights) throws IOException {
    
    long  time1 = System.currentTimeMillis();
    boolean[] modelFeatMap = new boolean[allFeatures.size()];
    for (final Feature f:modelFeatures){
       modelFeatMap[f.getId()] =  true;
    }
    final SolrQueryRequest req = getRequest();
     //= req.getParams().getInt("LTRThreads", 1);
    Executor executor = LTRThreadInterface.createWeightScoreExecutor;
    if  (LTRThreadInterface.ltrSemaphore == null ){
      LTRThreadInterface.ltrSemaphore = new Semaphore((LTRThreadInterface.maxThreads <=0) ? 10 : LTRThreadInterface.maxThreads);
    }
    Collection<Feature> features = null;
    // since the feature store is a linkedhashmap order is preserved
    if (this.extractAllFeatures) {
      features = allFeatures;
    }
    else{
      features =  modelFeatures;
    }
    List<Future<FeatureWeight> > futures = new ArrayList<>(features.size());
    List<FeatureWeight > featureWeights = new ArrayList<>(features.size());
    try{
      for (final Feature f : features) {
        CreateWeightCallable callable = new CreateWeightCallable(f, searcher, needsScores, req);
        RunnableFuture<FeatureWeight> runnableFuture = new FutureTask<>(callable);
        LTRThreadInterface.ltrSemaphore.acquire();//may block and/or interrupt
        executor.execute(runnableFuture);//releases semaphore when done
        futures.add(runnableFuture);
      }
      //Loop over futures to get the feature weight objects
      for (final Future<FeatureWeight> future : futures) {
        featureWeights.add(future.get());
      }
    } catch (InterruptedException e) {
       log.info("Error while creating weights in LTR: InterruptedException", e);
    } catch (ExecutionException ee) {
       Throwable e = ee.getCause();//unwrap
       if (e instanceof RuntimeException) {
         throw (RuntimeException) e;
       }
       log.info("Error while creating weights in LTR: " + e.toString(), e);
    }
    long  time2 = System.currentTimeMillis();
    int i=0, j = 0;
    for (final FeatureWeight fw : featureWeights) {
      allFeatureWeights[i++] = fw;
      if (this.extractAllFeatures) {
        if (modelFeatMap[fw.getId()]){  
          // Note: by assigning fw created using allFeatures, we lose the normalizer associated with the modelFeature, for this reason, 
          // we store it in the modelFeatureNorms, ahead of time, to use in normalize() and explain()
          modelFeaturesWeights[j++] = fw; 
        }
      }
      else{
        modelFeaturesWeights[j++] = fw; 
      }
    }
   
    log.info("maxthreads:{} numWeights: {} time for creating weights: {} ", LTRThreadInterface.maxThreads, features.size(),(time2-time1));
  }

  
  @Override
  public String toString(String field) {
    return field;
  }

  public class FeatureInfo {
      String name;
      float value;
      
      FeatureInfo(String n, float v){
        name = n; value = v; 
      }
      
      
      public void setScore(float score){
        this.value = score;
      }
      
      public String getName(){
        return name;
      }
      
      public float getValue(){
        return value;
      }
  }
  
  public class ModelWeight extends Weight {

    IndexSearcher searcher;

    // List of the model's features used for scoring. This is a subset of the
    // features used for logging.
    FeatureWeight[] modelFeatureWeights;
   
    FeatureWeight[] extractedFeatureWeights;
    Normalizer[] modelFeatureNorms;
    int allFeaturesSize;

    
    /* 
     * @param modelFeatureWeights 
     *     - should be the same size as the number of features used by the model
     * @param extractedFeatureWeights
     *     - if features are requested from the same store as model feature store,
     *       this will be the size of total number of features in the model feature store
     *       else, this will be the size of the modelFeatureWeights  
     * @param modelFeatNorms
     *     - this should be the same size as modelFeatureWeights,
     *      each Normalizer maps to the corresponding featureWeight
     */
    public ModelWeight(IndexSearcher searcher, FeatureWeight[] modelFeatureWeights,
        FeatureWeight[] extractedFeatureWeights, Normalizer[] modelFeatNorms, int allFeaturesSize) {
      super(ModelQuery.this);
      this.searcher = searcher;
      this.extractedFeatureWeights = extractedFeatureWeights;
      this.modelFeatureWeights = modelFeatureWeights;
      this.allFeaturesSize = allFeaturesSize;
      if (modelFeatNorms == null){
        modelFeatureNorms = new Normalizer[modelFeatureWeights.length];
        int pos = 0;
        for (final FeatureWeight feature : modelFeatureWeights){
          modelFeatureNorms[pos++] = feature.getNorm();
        }
      }
      else{
         this.modelFeatureNorms = modelFeatNorms;
      }
    }

   

    @Override
    public Explanation explain(LeafReaderContext context, int doc)
        throws IOException {

      final List<Explanation> featureExplanations = new ArrayList<>();
      int pos = 0;
      for (final FeatureWeight f : modelFeatureWeights) {
        final Normalizer n = modelFeatureNorms[pos++];
        Explanation e = f.explain(context, doc); //explanations[f.getId()];
        if (n != IdentityNormalizer.INSTANCE) {
          e = n.explain(e);
        }
        featureExplanations.add(e);
      }
      // TODO this calls twice the scorers, could be optimized.
      final ModelScorer bs = scorer(context);
      bs.iterator().advance(doc);

      final float finalScore = bs.score();

      return meta.explain(context, doc, finalScore, featureExplanations);

    }

    @Override
    public void extractTerms(Set<Term> terms) {
      for (final FeatureWeight feature : extractedFeatureWeights) {
        feature.extractTerms(terms);
      }
    }

   
    
    
    @Override
    public ModelScorer scorer(LeafReaderContext context) throws IOException {
      
      final List<FeatureScorer> featureScorers = new ArrayList<FeatureScorer>(
          extractedFeatureWeights.length);
      long time1 = System.currentTimeMillis();
      for (final FeatureWeight featureWeight : extractedFeatureWeights) {
        final FeatureScorer scorer = featureWeight.scorer(context);
        if (scorer != null) {
          featureScorers.add(featureWeight.scorer(context));
        }
      }
      long time2 = System.currentTimeMillis();
      // Always return a ModelScorer, even if no features match, because we
      // always need to call
      // score on the model for every document, since 0 features matching could
      // return a
      // non 0 score for a given model.
      ModelScorer mscorer = new ModelScorer(this, featureScorers);
      long time3 = System.currentTimeMillis();
      log.info("\t time for populating featureScorers: {} time for creating ModelScorer: {}", (time2-time1), (time3-time2));
      return mscorer;
      
    }

    public class ModelScorer extends Scorer {
      protected HashMap<String,Object> docInfo;
      protected Scorer featureTraversalScorer;
       // List of all the feature names, values - used for both scoring and logging
      /*
       *     A set of arrays was used earlier and the elements were accessed using the featureId. 
       *     This array of objects helps in keeping track of this better  
      */
       FeatureInfo[] featuresInfo;
       float[] modelFeatureValuesNormalized;

      public ModelScorer(Weight weight, List<FeatureScorer> featureScorers) {
        super(weight);
        this.modelFeatureValuesNormalized = new float[modelFeatureWeights.length];
        this.featuresInfo = new FeatureInfo[allFeaturesSize];
        docInfo = new HashMap<String,Object>();
        for (final FeatureScorer subSocer : featureScorers) {
          subSocer.setDocInfo(docInfo);
        }
        if (featureScorers.size() <= 1) { // TODO: Allow the use of dense
          // features in other cases
          featureTraversalScorer = new DenseModelScorer(weight, featureScorers);
        } else {
          featureTraversalScorer = new SparseModelScorer(weight, featureScorers);
        }
      }

      @Override
      public Collection<ChildScorer> getChildren() {
        return featureTraversalScorer.getChildren();
      }
      
      /**
       * Goes through all the stored feature values, and calculates the normalized
       * values for all the features that will be used for scoring.
       */
      public void normalize() {
        int pos = 0;
        for (final FeatureWeight feature : modelFeatureWeights) {
          final int featureId = feature.getId();
          FeatureInfo fInfo = featuresInfo[featureId];
          if (fInfo != null) {
            final Normalizer norm = modelFeatureNorms[pos];
            modelFeatureValuesNormalized[pos] = norm.normalize(fInfo.getValue());
          } else {
            modelFeatureValuesNormalized[pos] = feature.getDefaultValue();
          }
          pos++;
        }
      }
      
      protected void reset() {
        for (int i = 0; i < featuresInfo.length;++i){
           featuresInfo[i] = null;
        }
     }

      public void setDocInfoParam(String key, Object value) {
        docInfo.put(key, value);
      }

      @Override
      public int docID() {
        return featureTraversalScorer.docID();
      }

      @Override
      public float score() throws IOException {
        return featureTraversalScorer.score();
      }

      @Override
      public int freq() throws IOException {
        return featureTraversalScorer.freq();
      }

      @Override
      public DocIdSetIterator iterator() {
        return featureTraversalScorer.iterator();
      }

      public class SparseModelScorer extends Scorer {
        protected DisiPriorityQueue subScorers;
        protected ModelQuerySparseIterator itr;

        protected int targetDoc = -1;
        protected int activeDoc = -1;

        protected SparseModelScorer(Weight weight,
            List<FeatureScorer> featureScorers) {
          super(weight);
          if (featureScorers.size() <= 1) {
            throw new IllegalArgumentException(
                "There must be at least 2 subScorers");
          }
          subScorers = new DisiPriorityQueue(featureScorers.size());
          for (final Scorer scorer : featureScorers) {
            final DisiWrapper w = new DisiWrapper(scorer);
            subScorers.add(w);
          }

          itr = new ModelQuerySparseIterator(subScorers);
        }

        @Override
        public int docID() {
          return itr.docID();
        }

        @Override
        public float score() throws IOException {
          final DisiWrapper topList = subScorers.topList();
          // If target doc we wanted to advance to matches the actual doc
          // the underlying features advanced to, perform the feature
          // calculations,
          // otherwise just continue with the model's scoring process with empty
          // features.
          reset();
          if (activeDoc == targetDoc) {
            for (DisiWrapper w = topList; w != null; w = w.next) {
              final Scorer subScorer = w.scorer;
              FeatureWeight scFW = (FeatureWeight) subScorer.getWeight();
              final int featureId = scFW.getId();
              featuresInfo[featureId] =  new FeatureInfo(scFW.getName(), subScorer.score());
            }
          }
          normalize();
          return meta.score(modelFeatureValuesNormalized);
        }

        @Override
        public int freq() throws IOException {
          final DisiWrapper subMatches = subScorers.topList();
          int freq = 1;
          for (DisiWrapper w = subMatches.next; w != null; w = w.next) {
            freq += 1;
          }
          return freq;
        }

        @Override
        public DocIdSetIterator iterator() {
          return itr;
        }

        @Override
        public final Collection<ChildScorer> getChildren() {
          final ArrayList<ChildScorer> children = new ArrayList<>();
          for (final DisiWrapper scorer : subScorers) {
            children.add(new ChildScorer(scorer.scorer, "SHOULD"));
          }
          return children;
        }

        protected class ModelQuerySparseIterator extends
            DisjunctionDISIApproximation {

          public ModelQuerySparseIterator(DisiPriorityQueue subIterators) {
            super(subIterators);
          }

          @Override
          public final int nextDoc() throws IOException {
            if (activeDoc == targetDoc) {
              activeDoc = super.nextDoc();
            } else if (activeDoc < targetDoc) {
              activeDoc = super.advance(targetDoc + 1);
            }
            return ++targetDoc;
          }

          @Override
          public final int advance(int target) throws IOException {
            // If target doc we wanted to advance to matches the actual doc
            // the underlying features advanced to, perform the feature
            // calculations,
            // otherwise just continue with the model's scoring process with
            // empty features.
            if (activeDoc < target) {
              activeDoc = super.advance(target);
            }
            targetDoc = target;
            return targetDoc;
          }
        }

      }

      public class DenseModelScorer extends Scorer {
        int activeDoc = -1; // The doc that our scorer's are actually at
        int targetDoc = -1; // The doc we were most recently told to go to
        int freq = -1;
        List<FeatureScorer> featureScorers;

        protected DenseModelScorer(Weight weight,
            List<FeatureScorer> featureScorers) {
          super(weight);
          this.featureScorers = featureScorers;
        }

        @Override
        public int docID() {
          return targetDoc;
        }

        @Override
        public float score() throws IOException {
          reset();
          freq = 0;
          if (targetDoc == activeDoc) {
            for (final Scorer scorer : featureScorers) {
              if (scorer.docID() == activeDoc) {
                freq++;
                FeatureWeight scFW = (FeatureWeight) scorer.getWeight();
                final int featureId = scFW.getId();
                featuresInfo[featureId] = new FeatureInfo(scFW.getName(), scorer.score());
              }
            }
          }
          normalize();
          return meta.score(modelFeatureValuesNormalized);
        }

        @Override
        public final Collection<ChildScorer> getChildren() {
          final ArrayList<ChildScorer> children = new ArrayList<>();
          for (final Scorer scorer : featureScorers) {
            children.add(new ChildScorer(scorer, "SHOULD"));
          }
          return children;
        }

        @Override
        public int freq() throws IOException {
          return freq;
        }

        @Override
        public DocIdSetIterator iterator() {
          return new DenseIterator();
        }

        class DenseIterator extends DocIdSetIterator {

          @Override
          public int docID() {
            return targetDoc;
          }

          @Override
          public int nextDoc() throws IOException {
            if (activeDoc <= targetDoc) {
              activeDoc = NO_MORE_DOCS;
              for (final Scorer scorer : featureScorers) {
                if (scorer.docID() != NO_MORE_DOCS) {
                  activeDoc = Math.min(activeDoc, scorer.iterator().nextDoc());
                }
              }
            }
            return ++targetDoc;
          }

          @Override
          public int advance(int target) throws IOException {
            if (activeDoc < target) {
              activeDoc = NO_MORE_DOCS;
              for (final Scorer scorer : featureScorers) {
                if (scorer.docID() != NO_MORE_DOCS) {
                  activeDoc = Math.min(activeDoc,
                      scorer.iterator().advance(target));
                }
              }
            }
            targetDoc = target;
            return target;
          }

          @Override
          public long cost() {
            long sum = 0;
            for (int i = 0; i < featureScorers.size(); i++) {
              sum += featureScorers.get(i).iterator().cost();
            }
            return sum;
          }

        }
      }
    }
  }

}
