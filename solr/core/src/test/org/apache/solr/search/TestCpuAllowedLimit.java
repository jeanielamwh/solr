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
package org.apache.solr.search;

import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.index.NoMergePolicyFactory;
import org.apache.solr.util.TestInjection;
import org.apache.solr.util.ThreadCpuTimer;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestCpuAllowedLimit extends SolrCloudTestCase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String COLLECTION = "test";

  private static Path createConfigSet() throws Exception {
    Path configSet = createTempDir();
    copyMinConf(configSet.toFile());
    // insert an expensive search component
    Path solrConfig = configSet.resolve("conf/solrconfig.xml");
    Files.writeString(
        solrConfig,
        Files.readString(solrConfig)
            .replace(
                "<requestHandler",
                "<searchComponent name=\"expensiveSearchComponent\"\n"
                    + "                   class=\"org.apache.solr.search.ExpensiveSearchComponent\"/>\n"
                    + "\n"
                    + "  <requestHandler")
            .replace(
                "class=\"solr.SearchHandler\">",
                "class=\"solr.SearchHandler\">\n"
                    + "    <arr name=\"first-components\">\n"
                    + "      <str>expensiveSearchComponent</str>\n"
                    + "    </arr>\n"));
    return configSet.resolve("conf");
  }

  @BeforeClass
  public static void setupClass() throws Exception {
    // Using NoMergePolicy and 100 commits we should get 100 segments (across all shards).
    // At this point of writing MAX_SEGMENTS_PER_SLICE in lucene is 5, so we should be
    // ensured that any multithreaded testing will create 20 executable tasks for the
    // executor that was provided to index-searcher.
    systemSetPropertySolrTestsMergePolicyFactory(NoMergePolicyFactory.class.getName());
    System.setProperty(ThreadCpuTimer.ENABLE_CPU_TIME, "true");
    Path configset = createConfigSet();
    configureCluster(1).addConfig("conf", configset).configure();
    SolrClient solrClient = cluster.getSolrClient();
    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection(COLLECTION, "conf", 3, 2);
    create.process(solrClient);
    waitForState("active", COLLECTION, clusterShape(3, 6));
    for (int j = 0; j < 100; j++) {
      solrClient.add(COLLECTION, sdoc("id", "id-" + j, "val_i", j % 5));
      solrClient.commit(COLLECTION); // need to commit every doc to create many segments.
    }
  }

  @AfterClass
  public static void tearDownClass() {
    TestInjection.cpuTimerDelayInjectedNS = null;
    systemClearPropertySolrTestsMergePolicyFactory();
  }

  @Test
  public void testCompareToWallClock() throws Exception {
    Assume.assumeTrue("Thread CPU time monitoring is not available", ThreadCpuTimer.isSupported());
    long limitMs = 100;
    CpuAllowedLimit cpuLimit = new CpuAllowedLimit(limitMs);
    int[] randoms = new int[100];
    long startNs = System.nanoTime();
    int wakeups = 0;
    while (!cpuLimit.shouldExit()) {
      Thread.sleep(1);
      // do some busywork
      for (int i = 0; i < randoms.length; i++) {
        randoms[i] = random().nextInt();
      }
      wakeups++;
    }
    long endNs = System.nanoTime();
    long wallTimeDeltaMs = TimeUnit.MILLISECONDS.convert(endNs - startNs, TimeUnit.NANOSECONDS);
    log.info(
        "CPU limit: {} ms, elapsed wall-clock: {} ms, wakeups: {}",
        limitMs,
        wallTimeDeltaMs,
        wakeups);
    assertTrue(
        "Elapsed wall-clock time expected much larger than 100ms but was " + wallTimeDeltaMs,
        limitMs < wallTimeDeltaMs);
  }

  @Test
  public void testDistribLimit() throws Exception {
    Assume.assumeTrue("Thread CPU time monitoring is not available", ThreadCpuTimer.isSupported());

    SolrClient solrClient = cluster.getSolrClient();

    // no limits set - should eventually complete
    log.info("--- No limits, full results ---");
    long sleepMs = 1000;
    QueryResponse rsp =
        solrClient.query(
            COLLECTION,
            params(
                "q",
                "id:*",
                "sort",
                "id asc",
                ExpensiveSearchComponent.SLEEP_MS_PARAM,
                String.valueOf(sleepMs),
                "stages",
                "prepare,process"));
    // System.err.println("rsp=" + rsp.jsonStr());
    assertEquals(rsp.getHeader().get("status"), 0);
    Number qtime = (Number) rsp.getHeader().get("QTime");
    assertTrue("QTime expected " + qtime + " >> " + sleepMs, qtime.longValue() > sleepMs);
    assertNull("should not have partial results", rsp.getHeader().get("partialResults"));
    TestInjection.measureCpu();
    // 25 ms per 5 segments ~175ms each shard
    TestInjection.cpuTimerDelayInjectedNS = new AtomicInteger(25_000_000);
    // timeAllowed set, should return partial results
    log.info("--- timeAllowed, partial results ---");
    rsp =
        solrClient.query(
            COLLECTION,
            params(
                "q",
                "id:*",
                "sort",
                "id asc",
                ExpensiveSearchComponent.SLEEP_MS_PARAM,
                String.valueOf(sleepMs),
                "stages",
                "prepare,process",
                "multiThreaded",
                "false",
                "timeAllowed",
                "500"));
    // System.err.println("rsp=" + rsp.jsonStr());
    assertEquals("should have partial results", true, rsp.getHeader().get("partialResults"));

    log.info("--- timeAllowed, partial results, multithreading ---");

    rsp =
        solrClient.query(
            COLLECTION,
            params(
                "q",
                "id:*",
                "sort",
                "id asc",
                ExpensiveSearchComponent.SLEEP_MS_PARAM,
                String.valueOf(sleepMs),
                "stages",
                "prepare,process",
                "multiThreaded",
                "true",
                "timeAllowed",
                "500"));
    // System.err.println("rsp=" + rsp.jsonStr());
    assertNotNull("should have partial results", rsp.getHeader().get("partialResults"));

    // cpuAllowed set with large value, should return full results
    log.info("--- cpuAllowed, full results ---");
    rsp =
        solrClient.query(
            COLLECTION,
            params(
                "q",
                "id:*",
                "sort",
                "id desc",
                "stages",
                "prepare,process",
                "cpuAllowed",
                "100000"));
    System.err.println("rsp=" + rsp.jsonStr());
    assertNull("should have full results", rsp.getHeader().get("partialResults"));
    // cpuAllowed set, should return partial results
    log.info("--- cpuAllowed 100, partial results ---");
    rsp =
        solrClient.query(
            COLLECTION,
            params(
                "q",
                "id:*",
                "sort",
                "id desc",
                "stages",
                "prepare,process",
                "cpuAllowed",
                "100",
                "multiThreaded",
                "false"));
    // System.err.println("rsp=" + rsp.jsonStr());
    assertNotNull("should have partial results", rsp.getHeader().get("partialResults"));
    log.info("--- cpuAllowed 100, partial results omitted ---");
    rsp =
        solrClient.query(
            COLLECTION,
            params(
                "q",
                "id:*",
                "sort",
                "id desc",
                "stages",
                "prepare,process",
                "cpuAllowed",
                "100",
                "partialResults",
                "false",
                "multiThreaded",
                "false",
                "_",
                "foo"));
    String s = rsp.jsonStr();
    System.err.println("rsp=" + s);
    assertEquals(
        "should not have partial results", "omitted", rsp.getHeader().get("partialResults"));

    // cpuAllowed set, should return partial results
    log.info("--- cpuAllowed 100, partial results, multi-threaded ---");
    rsp =
        solrClient.query(
            COLLECTION,
            params(
                "q",
                "id:*",
                "sort",
                "id desc",
                "stages",
                "prepare,process",
                "cpuAllowed",
                "100",
                "multiThreaded",
                "true"));
    // System.err.println("rsp=" + rsp.jsonStr());
    assertNotNull("should have partial results", rsp.getHeader().get("partialResults"));
  }

  @Test
  public void testDistribLimit2() throws Exception {
    // This looks silly, but it actually guards against:
    // https://issues.apache.org/jira/browse/SOLR-17203
    testDistribLimit();
  }
}
