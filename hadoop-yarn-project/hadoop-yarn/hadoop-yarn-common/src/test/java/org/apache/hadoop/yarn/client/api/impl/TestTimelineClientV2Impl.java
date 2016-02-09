/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.client.api.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntities;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestTimelineClientV2Impl {
  private static final Log LOG =
      LogFactory.getLog(TestTimelineClientV2Impl.class);
  private TestV2TimelineClient client;
  private static long TIME_TO_SLEEP = 150;

  @Before
  public void setup() {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setBoolean(YarnConfiguration.TIMELINE_SERVICE_ENABLED, true);
    conf.setFloat(YarnConfiguration.TIMELINE_SERVICE_VERSION, 1.0f);
    conf.setInt(YarnConfiguration.NUMBER_OF_ASYNC_ENTITIES_TO_MERGE, 3);
    client = createTimelineClient(conf);
  }

  private TestV2TimelineClient createTimelineClient(YarnConfiguration conf) {
    ApplicationId id = ApplicationId.newInstance(0, 0);
    TestV2TimelineClient client = new TestV2TimelineClient(id);
    client.init(conf);
    client.start();
    return client;
  }

  private class TestV2TimelineClient extends TimelineClientImpl {
    private boolean sleepBeforeReturn;
    private boolean throwException;

    private List<TimelineEntities> publishedEntities;

    public TimelineEntities getPublishedEntities(int putIndex) {
      Assert.assertTrue("Not So many entities Published",
          putIndex < publishedEntities.size());
      return publishedEntities.get(putIndex);
    }

    public void setSleepBeforeReturn(boolean sleepBeforeReturn) {
      this.sleepBeforeReturn = sleepBeforeReturn;
    }

    public void setThrowException(boolean throwException) {
      this.throwException = throwException;
    }

    public int getNumOfTimelineEntitiesPublished() {
      return publishedEntities.size();
    }

    public TestV2TimelineClient(ApplicationId id) {
      super(id);
      publishedEntities = new ArrayList<TimelineEntities>();
    }

    protected void putObjects(String path,
        MultivaluedMap<String, String> params, Object obj)
            throws IOException, YarnException {
      if (throwException) {
        throw new YarnException("ActualException");
      }
      publishedEntities.add((TimelineEntities) obj);
      if (sleepBeforeReturn) {
        try {
          Thread.sleep(TIME_TO_SLEEP);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Test
  public void testPostEntities() throws Exception {
    try {
      client.putEntities(generateEntity("1"));
    } catch (YarnException e) {
      Assert.fail("Exception is not expected");
    }
  }

  @Test
  public void testASyncCallMerge() throws Exception {
    client.setSleepBeforeReturn(true);
    try {
      client.putEntitiesAsync(generateEntity("1"));
      Thread.sleep(TIME_TO_SLEEP / 2);
      // by the time first put response comes push 2 entities in the queue
      client.putEntitiesAsync(generateEntity("2"));
      client.putEntitiesAsync(generateEntity("3"));
    } catch (YarnException e) {
      Assert.fail("Exception is not expected");
    }
    for (int i = 0; i < 4; i++) {
      if (client.getNumOfTimelineEntitiesPublished() == 2) {
        break;
      }
      Thread.sleep(TIME_TO_SLEEP);
    }
    Assert.assertEquals("two merged TimelineEntities needs to be published", 2,
        client.getNumOfTimelineEntitiesPublished());
    TimelineEntities secondPublishedEntities = client.getPublishedEntities(1);
    Assert.assertEquals(
        "Merged TimelineEntities Object needs to 2 TimelineEntity Object", 2,
        secondPublishedEntities.getEntities().size());
    Assert.assertEquals("Order of Async Events Needs to be FIFO", "2",
        secondPublishedEntities.getEntities().get(0).getId());
    Assert.assertEquals("Order of Async Events Needs to be FIFO", "3",
        secondPublishedEntities.getEntities().get(1).getId());
  }

  @Test
  public void testSyncCall() throws Exception {
    try {
      // sync entity should not be be merged with Async
      client.putEntities(generateEntity("1"));
      client.putEntitiesAsync(generateEntity("2"));
      client.putEntitiesAsync(generateEntity("3"));
      // except for the sync call above 2 should be merged
      client.putEntities(generateEntity("4"));
    } catch (YarnException e) {
      Assert.fail("Exception is not expected");
    }
    for (int i = 0; i < 4; i++) {
      if (client.getNumOfTimelineEntitiesPublished() == 3) {
        break;
      }
      Thread.sleep(TIME_TO_SLEEP);
    }
    printReceivedEntities();
    Assert.assertEquals("TimelineEntities not published as desired", 3,
        client.getNumOfTimelineEntitiesPublished());
    TimelineEntities firstPublishedEntities = client.getPublishedEntities(0);
    Assert.assertEquals("sync entities should not be merged with async", 1,
        firstPublishedEntities.getEntities().size());

    // test before pushing the sync entities asyncs are merged and pushed
    TimelineEntities secondPublishedEntities = client.getPublishedEntities(1);
    Assert.assertEquals(
        "async entities should be merged before publishing sync", 2,
        secondPublishedEntities.getEntities().size());
    Assert.assertEquals("Order of Async Events Needs to be FIFO", "2",
        secondPublishedEntities.getEntities().get(0).getId());
    Assert.assertEquals("Order of Async Events Needs to be FIFO", "3",
        secondPublishedEntities.getEntities().get(1).getId());

    // test the last entity published is sync put
    TimelineEntities thirdPublishedEntities = client.getPublishedEntities(2);
    Assert.assertEquals("sync entities had to be published at the last", 1,
        thirdPublishedEntities.getEntities().size());
    Assert.assertEquals("Expected last sync Event is not proper", "4",
        thirdPublishedEntities.getEntities().get(0).getId());
  }

  @Test
  public void testExceptionCalls() throws Exception {
    client.setThrowException(true);
    try {
      client.putEntitiesAsync(generateEntity("1"));
    } catch (YarnException e) {
      Assert.fail("Async calls are not expected to throw exception");
    }

    try {
      client.putEntities(generateEntity("2"));
      Assert.fail("Sync calls are expected to throw exception");
    } catch (YarnException e) {
      Assert.assertEquals("Same exception needs to be thrown",
          "ActualException", e.getCause().getMessage());
    }
  }

  @Test
  public void testConfigurableNumberOfMerges() throws Exception {
    client.setSleepBeforeReturn(true);
    try {
      // At max 3 entities need to be merged
      client.putEntitiesAsync(generateEntity("1"));
      client.putEntitiesAsync(generateEntity("2"));
      client.putEntitiesAsync(generateEntity("3"));
      client.putEntitiesAsync(generateEntity("4"));
      client.putEntities(generateEntity("5"));
      client.putEntitiesAsync(generateEntity("6"));
      client.putEntitiesAsync(generateEntity("7"));
      client.putEntitiesAsync(generateEntity("8"));
      client.putEntitiesAsync(generateEntity("9"));
      client.putEntitiesAsync(generateEntity("10"));
    } catch (YarnException e) {
      Assert.fail("No exception expected");
    }
    // not having the same logic here as it doesn't depend on how many times
    // events are published.
    Thread.sleep(2 * TIME_TO_SLEEP);
    printReceivedEntities();
    for (TimelineEntities publishedEntities : client.publishedEntities) {
      Assert.assertTrue(
          "Number of entities should not be greater than 3 for each publish,"
              + " but was " + publishedEntities.getEntities().size(),
          publishedEntities.getEntities().size() <= 3);
    }
  }

  @Test
  public void testAfterStop() throws Exception {
    client.setSleepBeforeReturn(true);
    try {
      // At max 3 entities need to be merged
      client.putEntities(generateEntity("1"));
      for (int i = 2; i < 20; i++) {
        client.putEntitiesAsync(generateEntity("" + i));
      }
      client.stop();
      try {
        client.putEntitiesAsync(generateEntity("50"));
        Assert.fail("Exception expected");
      } catch (YarnException e) {
        // expected
      }
    } catch (YarnException e) {
      Assert.fail("No exception expected");
    }
    // not having the same logic here as it doesn't depend on how many times
    // events are published.
    for (int i = 0; i < 5; i++) {
      TimelineEntities publishedEntities =
          client.publishedEntities.get(client.publishedEntities.size() - 1);
      TimelineEntity timelineEntity = publishedEntities.getEntities()
          .get(publishedEntities.getEntities().size() - 1);
      if (!timelineEntity.getId().equals("19")) {
        Thread.sleep(2 * TIME_TO_SLEEP);
      }
    }
    printReceivedEntities();
    TimelineEntities publishedEntities =
        client.publishedEntities.get(client.publishedEntities.size() - 1);
    TimelineEntity timelineEntity = publishedEntities.getEntities()
        .get(publishedEntities.getEntities().size() - 1);
    Assert.assertEquals("", "19", timelineEntity.getId());
  }

  private void printReceivedEntities() {
    for (int i = 0; i < client.getNumOfTimelineEntitiesPublished(); i++) {
      TimelineEntities publishedEntities = client.getPublishedEntities(i);
      StringBuilder entitiesPerPublish = new StringBuilder();
      ;
      for (TimelineEntity entity : publishedEntities.getEntities()) {
        entitiesPerPublish.append(entity.getId());
        entitiesPerPublish.append(",");
      }
      LOG.info("Entities Published @ index " + i + " : "
          + entitiesPerPublish.toString());
    }
  }

  private static TimelineEntity generateEntity(String id) {
    TimelineEntity entity = new TimelineEntity();
    entity.setId(id);
    entity.setType("testEntity");
    entity.setCreatedTime(System.currentTimeMillis());
    return entity;
  }

  @After
  public void tearDown() {
    if (client != null) {
      client.stop();
    }
  }
}
