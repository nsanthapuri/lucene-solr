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
package org.apache.solr.ltr.feature;

import java.security.SecureRandom;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.ltr.TestRerankBase;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;





public class TestMultiSegment extends TestRerankBase {
  static final String AB = "abcdefghijklmnopqrstuvwxyz";
  static SecureRandom rnd = new SecureRandom();
  
  static String randomString( int len ){
    StringBuilder sb = new StringBuilder( len );
    for( int i = 0; i < len; i++ ) 
       sb.append( AB.charAt( rnd.nextInt(AB.length()) ) );
    return sb.toString();
 }
  
  @BeforeClass
  public static void before() throws Exception {    
    setuptest("solrconfig-multiseg.xml", "schema-ltr-all-feature-test.xml");
    for(int i = 0; i<400;i=i+20) {
      assertU(adoc("id", new Integer(i).toString(), "global_categoryId", "201", "comp_description", "apple is a company " + randomString(i%6+3), "global_normHits", "0.1"));
      assertU(adoc("id", new Integer(i+1).toString(), "global_categoryId", "201", "comp_description", "d " + randomString(i%6+3), "global_normHits", "0.11"));
      
      assertU(adoc("id", new Integer(i+2).toString(), "global_categoryId", "201", "comp_description", "apple is a company too " + randomString(i%6+3), "global_normHits", "0.1"));
      assertU(adoc("id", new Integer(i+3).toString(), "global_categoryId", "201", "comp_description", "new york city is big apple " + randomString(i%6+3), "global_normHits", "0.11"));
      
      assertU(adoc("id", new Integer(i+6).toString(), "global_categoryId", "301", "func_title", "function name " + randomString(i%6+3)));
      assertU(adoc("id", new Integer(i+7).toString(), "global_categoryId", "301", "func_title", "function " + randomString(i%6+3)));
      
      assertU(adoc("id", new Integer(i+8).toString(), "global_categoryId", "301", 
                   "func_description", "This is a sample function for testing " + randomString(i%6+3),
                   "func_description_stemmed", "This is a sample function for testing " + randomString(i%6+3)));
      assertU(adoc("id", new Integer(i+9).toString(), "global_categoryId", "301", 
                   "func_description", "Function to check out stock prices "+randomString(i%6+3),
                   "func_description_stemmed", "Function to check out stock prices " + randomString(i%6+3)));
      assertU(adoc("id", new Integer(i+10).toString(), "global_categoryId", "301", 
                   "func_description", "Some descriptions "+randomString(i%6+3),
                   "func_description_stemmed", "Some descriptions "+randomString(i%6+3)));
      
      assertU(adoc("id", new Integer(i+11).toString(), "global_categoryId", "201",
                   "comp_description", "apple apple is a company " + randomString(i%6+3), "global_normHits", "0.1",
                   "func_displayDescription_posBoosted", "New New York New York is New York is Big New York is Big Apple. "+ randomString(i%6+3),
                   "func_displayDescription_edge", "New York is Big Apple. "+ randomString(i%6+3)));
      assertU(adoc("id", new Integer(i+12).toString(), "global_categoryId", "201", 
                   "comp_description", "Big Apple is New York.", "global_normHits", "0.01",
                   "func_displayDescription_posBoosted", "Big Big Apple Big Apple is Big Apple is New Big Apple is New York. "+ randomString(i%6+3),
                   "func_displayDescription_edge", "Big Apple is New York. " + randomString(i%6+3)));
      assertU(adoc("id", new Integer(i+13).toString(), "global_categoryId", "201", 
                   "comp_description", "New some York is Big. "+ randomString(i%6+3),
                   "func_displayDescription_posBoosted", "New New some New some York New some York is New some York is Big. "+randomString(i%6+3),
                   "func_displayDescription_edge", "New some York is Big. "+randomString(i%6+3)));
      
      assertU(adoc("id", new Integer(i+14).toString(), "global_categoryId", "201",
          "comp_description", "apple apple is a company " + randomString(i%6+3), "global_normHits", "0.1",
          "func_displayDescription_posBoosted", "New New York New York is New York is Big New York is Big Apple. "+ randomString(i%6+3),
          "func_displayDescription_edge", "New York is Big Apple. "+ randomString(i%6+3)));
      assertU(adoc("id", new Integer(i+15).toString(), "global_categoryId", "201", 
          "comp_description", "Big Apple is New York.", "global_normHits", "0.01",
          "func_displayDescription_posBoosted", "Big Big Apple Big Apple is Big Apple is New Big Apple is New York. "+ randomString(i%6+3),
          "func_displayDescription_edge", "Big Apple is New York. " + randomString(i%6+3)));
      assertU(adoc("id", new Integer(i+16).toString(), "global_categoryId", "401", "ppl_normNewsScore", "0.0", 
                   "global_normHits", "0.0", "ppl_last_name", "OBAMA " + randomString(i%6+3), "ppl_first_name", "barack h"));
      assertU(adoc("id", new Integer(i+17).toString(), "global_categoryId", "201", "comp_description", "red delicious apple " + randomString(i%6+3), "global_normHits", "0.1"));
      assertU(adoc("id", new Integer(i+18).toString(), "global_categoryId", "201", "comp_description", "nyc " + randomString(i%6+3), "global_normHits", "0.11"));
    }
    
    assertU(commit());
    
    loadFeatures("comp_features.json");
  }
  
  @AfterClass
  public static void after() throws Exception {
    aftertest();
  }
  
  @Test
  public void testPeopleFirstAndLastMatch() throws Exception {
    
    final SolrQuery query = new SolrQuery();
    query.setQuery("{!edismax qf='comp_description^1' boost='sum(product(pow(global_normHits, 0.7), 1600), .1)' v='apple'}");
    query.add("rows", "20");
    query.add("wt", "json");
    query.add("fq", "global_categoryId:201");
    query.add("fl", "*, score,id,global_normHits,comp_description,fv:[features store='feature-store-6' format='dense' efi.user_text='apple']");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[19]/fv=='origScore:394.61035;userTextNameMatch:1.2356983;descriptionTermFreq:2.0;popularity:0.1;isCompany:1.0;queryPartialMatch2:1.2356983;queryPartialMatch2.1:1.2356983'");
  }
}
