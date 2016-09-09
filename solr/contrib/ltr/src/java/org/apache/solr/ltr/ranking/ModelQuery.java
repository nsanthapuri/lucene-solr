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
import org.apache.solr.ltr.log.FeatureLogger;
import org.apache.solr.ltr.ranking.Feature.FeatureWeight;
import org.apache.solr.ltr.ranking.Feature.FeatureWeight.FeatureScorer;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.ltr.ranking.LTRThreadModule;
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
  private Semaphore querySemaphore; // limits the number of threads per query, so that multiple requests can be serviced simultaneously

  public ModelQuery(LTRScoringAlgorithm meta) {
    this(meta, false);
  }

  public ModelQuery(LTRScoringAlgorithm meta, boolean extractAllFeatures) {
    this.meta = meta;
    this.extractAllFeatures = extractAllFeatures; 
    querySemaphore = new Semaphore((LTRThreadModule.getMaxQueryThreads() <=0) ? 1 : LTRThreadModule.getMaxQueryThreads());
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
    final Collection<Feature> modelFeatures = meta.getFeatures();
    final Collection<Feature> allFeatures = meta.getAllFeatures();
    int modelFeatSize = modelFeatures.size();
   
    Collection<Feature> features = null;
    if (this.extractAllFeatures) {
      features = allFeatures;
    }
    else{
      features =  modelFeatures;
    }
    final FeatureWeight[] extractedFeatureWeights = new FeatureWeight[features.size()];
    final FeatureWeight[] modelFeaturesWeights = new FeatureWeight[modelFeatSize];
    List<FeatureWeight > featureWeights = new ArrayList<>(features.size());
    
    if(LTRThreadModule.getMaxThreads() <= 1 || LTRThreadModule.getMaxQueryThreads() <= 1){
       createWeights(searcher, needsScores, boost, featureWeights, features);
    }
    else{
       createWeightsParallel(searcher, needsScores, boost, featureWeights, features);
    }
    int i=0, j = 0;
    if (this.extractAllFeatures) {
      for (final FeatureWeight fw : featureWeights) {
        extractedFeatureWeights[i++] = fw;
      }
      for (final Feature f: modelFeatures){
        modelFeaturesWeights[j++] = extractedFeatureWeights[f.getId()]; // we can lookup by featureid because all features will be extracted when this.extractAllFeatures is set
      }
    }
    else{
      for (final FeatureWeight fw: featureWeights){
        extractedFeatureWeights[i++] = fw;
        modelFeaturesWeights[j++] = fw; 
      }
    }
    return new ModelWeight(searcher, modelFeaturesWeights, extractedFeatureWeights, allFeatures.size());
  }

  private void createWeights(IndexSearcher searcher, boolean needsScores, float boost, 
      List<FeatureWeight > featureWeights, Collection<Feature> features) throws IOException {
    final SolrQueryRequest req = getRequest();
    // since the feature store is a linkedhashmap order is preserved
    for (final Feature f : features) {
        try{
          FeatureWeight fw = f.createWeight(searcher, needsScores, req, originalQuery, efi);
          featureWeights.add(fw);
        }catch (final Exception e) {
          throw new FeatureException("Exception from createWeight for " + f.toString() + " "
              + e.getMessage(), e);
        }
      }
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
        querySemaphore.release();
        LTRThreadModule.ltrSemaphore.release();
      }
    }
  } // end of call CreateWeightCallable

  private void createWeightsParallel(IndexSearcher searcher, boolean needsScores, float boost,
      List<FeatureWeight > featureWeights, Collection<Feature> features) throws IOException {

    final SolrQueryRequest req = getRequest();

    Executor executor = LTRThreadModule.createWeightScoreExecutor;
    if  (LTRThreadModule.ltrSemaphore == null ){
      LTRThreadModule.ltrSemaphore = new Semaphore(LTRThreadModule.getMaxThreads());
    }
    List<Future<FeatureWeight> > futures = new ArrayList<>(features.size());
    try{
      for (final Feature f : features) {
        CreateWeightCallable callable = new CreateWeightCallable(f, searcher, needsScores, req);
        RunnableFuture<FeatureWeight> runnableFuture = new FutureTask<>(callable);
        querySemaphore.acquire(); // always acquire before the ltrSemaphore is acquired, to guarantee a that the current query is within the limit for max. threads 
        LTRThreadModule.ltrSemaphore.acquire();//may block and/or interrupt
        
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
  }

  @Override
  public String toString(String field) {
    return field;
  }

  public class FeatureInfo {
    String name;
    float value;
    boolean used;

    FeatureInfo(String n, float v, boolean u){
      name = n; value = v; used = u; 
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

    public boolean isUsed(){
      return used;
    }

    public void setUsed(boolean used){
      this.used = used;
    }
  }

  public class ModelWeight extends Weight {

    IndexSearcher searcher;

    // List of the model's features used for scoring. This is a subset of the
    // features used for logging.
    FeatureWeight[] modelFeatureWeights;
    float[] modelFeatureValuesNormalized;
    FeatureWeight[] extractedFeatureWeights;

    // List of all the feature names, values - used for both scoring and logging
    /*
     *  What is the advantage of using a hashmap here instead of an array of objects?        
     *     A set of arrays was used earlier and the elements were accessed using the featureId. 
     *     With the updated logic to create weights selectively, 
     *     the number of elements in the array can be fewer than the total number of features. 
     *     When [features] are not requested, only the model features are extracted. 
     *     In this case, the indexing by featureId, fails. For this reason, 
     *     we need a map which holds just the features that were triggered by the documents in the result set. 
     *  
     */
    FeatureInfo[] featuresInfo;
    /* 
     * @param modelFeatureWeights 
     *     - should be the same size as the number of features used by the model
     * @param extractedFeatureWeights
     *     - if features are requested from the same store as model feature store,
     *       this will be the size of total number of features in the model feature store
     *       else, this will be the size of the modelFeatureWeights  
     * @param allFeaturesSize
     *     - total number of feature in the feature store used by this model
     */
    public ModelWeight(IndexSearcher searcher, FeatureWeight[] modelFeatureWeights,
        FeatureWeight[] extractedFeatureWeights, int allFeaturesSize) {
      super(ModelQuery.this);
      this.searcher = searcher;
      this.extractedFeatureWeights = extractedFeatureWeights;
      this.modelFeatureWeights = modelFeatureWeights;
      this.modelFeatureValuesNormalized = new float[modelFeatureWeights.length];
      this.featuresInfo = new FeatureInfo[allFeaturesSize];
      setFeaturesInfo();
    }

    private void setFeaturesInfo(){
      for (int i = 0; i < extractedFeatureWeights.length;++i){
        String featName = extractedFeatureWeights[i].getName();
        int featId = extractedFeatureWeights[i].getId();
        float value = extractedFeatureWeights[i].getDefaultValue();
        featuresInfo[featId] = new FeatureInfo(featName,value,false);
      } 
    }

    /**
     * Goes through all the stored feature values, and calculates the normalized
     * values for all the features that will be used for scoring.
     */
    private void makeNormalizedFeatures() {
      int pos = 0;
      for (final FeatureWeight feature : modelFeatureWeights) {
        final int featureId = feature.getId();
        FeatureInfo fInfo = featuresInfo[featureId];
        if (fInfo.isUsed()) { // not checking for finfo == null as that would be a bug we should catch 
          modelFeatureValuesNormalized[pos] = fInfo.getValue();
        } else {
          modelFeatureValuesNormalized[pos] = feature.getDefaultValue();
        }
        pos++;
      }
      meta.normalizeFeaturesInPlace(modelFeatureValuesNormalized);
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc)
        throws IOException {

      final Explanation[] explanations = new Explanation[this.featuresInfo.length];
      for (final FeatureWeight feature : extractedFeatureWeights) {
        explanations[feature.getId()] = feature.explain(context, doc);
      }
      final List<Explanation> featureExplanations = new ArrayList<>();
      for (int idx = 0 ;idx < modelFeatureWeights.length; ++idx) {
        final FeatureWeight f = modelFeatureWeights[idx]; 
        Explanation e = meta.getNormalizerExplanation(explanations[f.getId()], idx);
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

    protected void reset() {
      for (int i = 0; i < extractedFeatureWeights.length;++i){
        int featId = extractedFeatureWeights[i].getId();
        float value = extractedFeatureWeights[i].getDefaultValue();
        featuresInfo[featId].setScore(value); // need to set default value everytime as the default value is used in 'dense' mode even if used=false
        featuresInfo[featId].setUsed(false);
      }
    }

    @Override
    public ModelScorer scorer(LeafReaderContext context) throws IOException {

      final List<FeatureScorer> featureScorers = new ArrayList<FeatureScorer>(
          extractedFeatureWeights.length);
      for (final FeatureWeight featureWeight : extractedFeatureWeights) {
        final FeatureScorer scorer = featureWeight.scorer(context);
        if (scorer != null) {
          featureScorers.add(featureWeight.scorer(context));
        }
      }
      // Always return a ModelScorer, even if no features match, because we
      // always need to call
      // score on the model for every document, since 0 features matching could
      // return a
      // non 0 score for a given model.
      ModelScorer mscorer = new ModelScorer(this, featureScorers);
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


      public ModelScorer(Weight weight, List<FeatureScorer> featureScorers) {
        super(weight);
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
              featuresInfo[featureId].setScore(subScorer.score());
              featuresInfo[featureId].setUsed(true);
            }
          }
          makeNormalizedFeatures();
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
                featuresInfo[featureId].setScore(scorer.score());
                featuresInfo[featureId].setUsed(true);
              }
            }
          }
          makeNormalizedFeatures();
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
