/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.curator.announcement;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.curator.framework.api.transaction.CuratorOp;
import org.apache.curator.framework.api.transaction.CuratorTransactionResult;
import org.apache.curator.test.KillSession;
import org.apache.curator.utils.ZKPaths;
import org.apache.druid.curator.CuratorTestBase;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 *
 */
public class PathChildrenAnnouncerTest extends CuratorTestBase
{
  private static final Logger log = new Logger(PathChildrenAnnouncerTest.class);
  private ExecutorService exec;

  @Before
  public void setUp() throws Exception
  {
    setupServerAndCurator();
    exec = Execs.singleThreaded("test-announcer-sanity-%s");
    curator.start();
    curator.blockUntilConnected();
  }

  @After
  public void tearDown()
  {
    tearDownServerAndCurator();
  }

  @Test(timeout = 60_000L)
  public void testSanity() throws Exception
  {
    PathChildrenAnnouncer announcer = new PathChildrenAnnouncer(curator, exec);
    announcer.initializeAddedChildren();

    final byte[] billy = StringUtils.toUtf8("billy");
    final String testPath1 = "/test1";
    final String testPath2 = "/somewhere/test2";
    announcer.announce(testPath1, billy);

    Assert.assertNull("/test1 does not exists", curator.checkExists().forPath(testPath1));
    Assert.assertNull("/somewhere/test2 does not exists", curator.checkExists().forPath(testPath2));

    announcer.start();
    while (!announcer.getAddedChildren().contains("/test1")) {
      Thread.sleep(100);
    }

    try {
      Assert.assertArrayEquals("/test1 has data", billy, curator.getData().decompressed().forPath(testPath1));
      Assert.assertNull("/somewhere/test2 still does not exist", curator.checkExists().forPath(testPath2));

      announcer.announce(testPath2, billy);

      Assert.assertArrayEquals("/test1 still has data", billy, curator.getData().decompressed().forPath(testPath1));
      Assert.assertArrayEquals(
          "/somewhere/test2 has data",
          billy,
          curator.getData().decompressed().forPath(testPath2)
      );

      final CountDownLatch latch = createCountdownLatchForPaths(testPath1);

      final CuratorOp deleteOp = curator.transactionOp().delete().forPath(testPath1);
      final Collection<CuratorTransactionResult> results = curator.transaction().forOperations(deleteOp);
      Assert.assertEquals(1, results.size());
      final CuratorTransactionResult result = results.iterator().next();
      Assert.assertEquals(Code.OK.intValue(), result.getError()); // assert delete

      Assert.assertTrue("Wait for /test1 to be created", timing.forWaiting().awaitLatch(latch));

      Assert.assertArrayEquals(
          "expect /test1 data is restored",
          billy,
          curator.getData().decompressed().forPath(testPath1)
      );
      Assert.assertArrayEquals(
          "expect /somewhere/test2 is still there",
          billy,
          curator.getData().decompressed().forPath(testPath2)
      );

      announcer.unannounce(testPath1);
      Assert.assertNull("expect /test1 unannounced", curator.checkExists().forPath(testPath1));
      Assert.assertArrayEquals(
          "expect /somewhere/test2 is still still there",
          billy,
          curator.getData().decompressed().forPath(testPath2)
      );
    }
    finally {
      announcer.stop();
    }

    Assert.assertNull("expect /test1 remains unannounced", curator.checkExists().forPath(testPath1));
    Assert.assertNull("expect /somewhere/test2 unannounced", curator.checkExists().forPath(testPath2));
  }

  @Test(timeout = 60_000L)
  public void testSessionKilled() throws Exception
  {
    PathChildrenAnnouncer announcer = new PathChildrenAnnouncer(curator, exec);
    try {
      CuratorOp createOp = curator.transactionOp().create().forPath("/somewhere");
      curator.transaction().forOperations(createOp);
      announcer.start();

      final byte[] billy = StringUtils.toUtf8("billy");
      final String testPath1 = "/test1";
      final String testPath2 = "/somewhere/test2";
      final String[] paths = new String[]{testPath1, testPath2};
      announcer.announce(testPath1, billy);
      announcer.announce(testPath2, billy);

      Assert.assertArrayEquals(billy, curator.getData().decompressed().forPath(testPath1));
      Assert.assertArrayEquals(billy, curator.getData().decompressed().forPath(testPath2));

      final CountDownLatch latch = createCountdownLatchForPaths(paths);

      KillSession.kill(curator.getZookeeperClient().getZooKeeper(), server.getConnectString());

      Assert.assertTrue(timing.forWaiting().awaitLatch(latch));

      Assert.assertArrayEquals(billy, curator.getData().decompressed().forPath(testPath1));
      Assert.assertArrayEquals(billy, curator.getData().decompressed().forPath(testPath2));

      announcer.stop();

      while ((curator.checkExists().forPath(testPath1) != null) || (curator.checkExists().forPath(testPath2) != null)) {
        Thread.sleep(100);
      }

      Assert.assertNull(curator.checkExists().forPath(testPath1));
      Assert.assertNull(curator.checkExists().forPath(testPath2));
    }
    finally {
      announcer.stop();
    }
  }

  @Test
  public void testRemovesParentIfCreated() throws Exception
  {
    PathChildrenAnnouncer announcer = new PathChildrenAnnouncer(curator, exec);

    final byte[] billy = StringUtils.toUtf8("billy");
    final String testPath = "/somewhere/test2";
    final String parent = ZKPaths.getPathAndNode(testPath).getPath();

    announcer.start();
    try {
      Assert.assertNull(curator.checkExists().forPath(parent));

      awaitAnnounce(announcer, testPath, billy, true);

      Assert.assertNotNull(curator.checkExists().forPath(parent));
    }
    finally {
      announcer.stop();
    }

    Assert.assertNull(curator.checkExists().forPath(parent));
  }

  @Test
  public void testLeavesBehindParentPathIfAlreadyExists() throws Exception
  {
    PathChildrenAnnouncer announcer = new PathChildrenAnnouncer(curator, exec);

    final byte[] billy = StringUtils.toUtf8("billy");
    final String testPath = "/somewhere/test";
    final String parent = ZKPaths.getPathAndNode(testPath).getPath();

    curator.create().forPath(parent);
    final Stat initialStat = curator.checkExists().forPath(parent);

    announcer.start();
    try {
      Assert.assertEquals(initialStat.getMzxid(), curator.checkExists().forPath(parent).getMzxid());

      awaitAnnounce(announcer, testPath, billy, true);

      Assert.assertEquals(initialStat.getMzxid(), curator.checkExists().forPath(parent).getMzxid());
    }
    finally {
      announcer.stop();
    }

    Assert.assertEquals(initialStat.getMzxid(), curator.checkExists().forPath(parent).getMzxid());
  }

  @Test
  public void testLeavesParentPathsUntouchedWhenInstructed() throws Exception
  {
    PathChildrenAnnouncer announcer = new PathChildrenAnnouncer(curator, exec);

    final byte[] billy = StringUtils.toUtf8("billy");
    final String testPath = "/somewhere/test";
    final String parent = ZKPaths.getPathAndNode(testPath).getPath();

    announcer.start();
    try {
      Assert.assertNull(curator.checkExists().forPath(parent));

      awaitAnnounce(announcer, testPath, billy, false);

      Assert.assertNotNull(curator.checkExists().forPath(parent));
    }
    finally {
      announcer.stop();
    }

    Assert.assertNotNull(curator.checkExists().forPath(parent));
  }

  private void awaitAnnounce(
      final PathChildrenAnnouncer announcer,
      final String path,
      final byte[] bytes,
      boolean removeParentsIfCreated
  ) throws InterruptedException
  {
    CountDownLatch latch = createCountdownLatchForPaths(path);
    announcer.announce(path, bytes, removeParentsIfCreated);
    latch.await();
  }

  private CountDownLatch createCountdownLatchForPaths(String... path)
  {
    final CountDownLatch latch = new CountDownLatch(path.length);
    curator.getCuratorListenable().addListener(
        new CuratorListener()
        {
          @Override
          public void eventReceived(CuratorFramework client, CuratorEvent event)
          {
            if (event.getType() == CuratorEventType.CREATE && Arrays.asList(path).contains(event.getPath())) {
              latch.countDown();
            }
          }
        }
    );

    return latch;
  }
}
