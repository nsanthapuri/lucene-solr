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
package org.apache.solr.ltr.feature.impl;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.ltr.ranking.RankSVMModel;
import org.junit.Test;

public class TestEdisMaxSolrFeature extends TestQueryFeature {
  @Test
  public void testEdisMaxSolrFeature() throws Exception {
    loadFeature(
        "SomeEdisMax",
        SolrFeature.class.getCanonicalName(),
        "{\"q\":\"{!edismax qf='title description' pf='description' mm=100% boost='pow(popularity, 0.1)' v='w1' tie=0.1}\"}");

    loadModel("EdisMax-model", RankSVMModel.class.getCanonicalName(),
        new String[] {"SomeEdisMax"}, "{\"weights\":{\"SomeEdisMax\":1.0}}");

    final SolrQuery query = new SolrQuery();
    query.setQuery("title:w1");
    query.add("fl", "*, score");
    query.add("rows", "4");

    query.add("rq", "{!ltr model=EdisMax-model reRankDocs=4}");
    query.set("debugQuery", "on");
    final String res = restTestHarness.query("/query" + query.toQueryString());
    System.out.println(res);
    assertJQ("/query" + query.toQueryString(), "/response/numFound/==4");
  }
}
