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
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.util.DefaultSolrThreadFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;


public class LTRThreadInterface {
  ThreadMXBean tmxb = ManagementFactory.getThreadMXBean();
  public static Semaphore ltrSemaphore = null; 
  public static int maxThreads = 1;
  public static int maxQueryThreads = 1;
  public static final int DEFAULT_MAX_THREADS = 0; // do not do threading if 'LTRMaxThreads' is not specified in the config file
  public static final int DEFAULT_MAX_QUERYTHREADS = 0; // do not do threading if 'LTRMaxQueryThreads' is not specified in the config file

   public static final Executor createWeightScoreExecutor = new ExecutorUtil.MDCAwareThreadPoolExecutor(
          0,
          Integer.MAX_VALUE,
          10, TimeUnit.SECONDS, // terminate idle threads after 10 sec
          new SynchronousQueue<Runnable>()  // directly hand off tasks
          , new DefaultSolrThreadFactory("ltrExecutor")
    );
   
}
