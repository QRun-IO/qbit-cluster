/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2026.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.kingsrook.qbits.cluster;


import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Lease acquire, renew, expire, steal and release (memory backend; the same
 ** compare-and-set runs as SQL in ClusterChangelogTest).
 *******************************************************************************/
class ClusterLeasesTest extends BaseClusterTest
{
   private static final Duration TTL = Duration.ofSeconds(60);



   /*******************************************************************************
    ** The full life of a lease between two nodes.
    *******************************************************************************/
   @Test
   void acquireRenewExpireStealRelease() throws Exception
   {
      ClusterLeases.Holder a = ClusterLeases.as("node-a");
      ClusterLeases.Holder b = ClusterLeases.as("node-b");

      assertThat(a.tryAcquire("job", TTL)).isTrue();
      assertThat(a.isLeader("job")).isTrue();
      assertThat(lease().getValueInteger(ClusterTables.LEASE_VERSION)).isEqualTo(1);

      assertThat(b.tryAcquire("job", TTL)).isFalse();
      assertThat(b.isLeader("job")).isFalse();
      assertThat(b.renew("job", TTL)).isFalse();
      assertThat(b.release("job")).isFalse();

      Instant firstExpiry = lease().getValueInstant(ClusterTables.EXPIRES_AT);
      Instant acquired    = lease().getValueInstant(ClusterTables.ACQUIRED_AT);
      Thread.sleep(5);
      assertThat(a.renew("job", TTL)).isTrue();
      assertThat(lease().getValueInstant(ClusterTables.EXPIRES_AT)).isAfter(firstExpiry);
      assertThat(lease().getValueInstant(ClusterTables.ACQUIRED_AT)).isEqualTo(acquired);
      assertThat(a.tryAcquire("job", TTL)).isTrue();
      assertThat(lease().getValueInteger(ClusterTables.LEASE_VERSION)).isEqualTo(3);

      expire();
      assertThat(a.isLeader("job")).isFalse();
      assertThat(b.tryAcquire("job", TTL)).isTrue();
      assertThat(lease().getValueString(ClusterTables.HOLDER_NODE_ID)).isEqualTo("node-b");
      assertThat(a.isLeader("job")).isFalse();
      assertThat(a.renew("job", TTL)).isFalse();
      assertThat(b.isLeader("job")).isTrue();

      assertThat(b.release("job")).isTrue();
      assertThat(lease().getValueString(ClusterTables.HOLDER_NODE_ID)).isNull();
      assertThat(b.isLeader("job")).isFalse();
      assertThat(a.tryAcquire("job", TTL)).isTrue();
      assertThat(all(ClusterTables.LEASE)).hasSize(1);
   }



   /*******************************************************************************
    ** Many nodes race for an expired lease at once: exactly one wins.
    *******************************************************************************/
   @Test
   void exactlyOneWinsARace() throws Exception
   {
      assertThat(ClusterLeases.as("node-a").tryAcquire("job", TTL)).isTrue();
      expire();

      int                  racers  = 12;
      AtomicInteger        winners = new AtomicInteger();
      CountDownLatch       go      = new CountDownLatch(1);
      ExecutorService      pool    = Executors.newFixedThreadPool(racers);
      List<Future<?>>      futures = new ArrayList<>();
      for(int i = 0; i < racers; i++)
      {
         String nodeId = "racer-" + i;
         futures.add(pool.submit(() ->
         {
            QContext.init(qInstance, new QSystemUserSession(qInstance));
            go.await();
            if(ClusterLeases.as(nodeId).tryAcquire("job", TTL))
            {
               winners.incrementAndGet();
            }
            return (null);
         }));
      }
      go.countDown();
      for(Future<?> future : futures)
      {
         future.get();
      }
      pool.shutdown();
      assertThat(winners.get()).isEqualTo(1);
      assertThat(lease().getValueString(ClusterTables.HOLDER_NODE_ID)).startsWith("racer-");
      assertThat(lease().getValueInteger(ClusterTables.LEASE_VERSION)).isEqualTo(2);
   }



   /*******************************************************************************
    ** The static API acts for this node.
    *******************************************************************************/
   @Test
   void staticApiUsesThisNode() throws Exception
   {
      assertThat(ClusterLeases.tryAcquire("singleton", TTL)).isTrue();
      assertThat(ClusterLeases.isLeader("singleton")).isTrue();
      assertThat(lease().getValueString(ClusterTables.HOLDER_NODE_ID)).isEqualTo(NODE_A);
      assertThat(ClusterLeases.renew("singleton", TTL)).isTrue();
      assertThat(ClusterLeases.release("singleton")).isTrue();
      assertThat(ClusterLeases.isLeader("singleton")).isFalse();
   }



   /*******************************************************************************
    ** Bad arguments.
    *******************************************************************************/
   @Test
   void badArguments()
   {
      assertThatThrownBy(() -> ClusterLeases.as(" ")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ClusterLeases.tryAcquire("x", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ClusterLeases.renew("x", null)).isInstanceOf(IllegalArgumentException.class);
   }



   /*******************************************************************************
    ** The one lease row.
    *******************************************************************************/
   private QRecord lease() throws Exception
   {
      return (all(ClusterTables.LEASE).get(0));
   }



   /*******************************************************************************
    ** Let the lease run out.
    *******************************************************************************/
   private void expire() throws Exception
   {
      setFields(ClusterTables.LEASE, lease().getValue(ClusterTables.ID), Map.of(ClusterTables.EXPIRES_AT, Instant.now().minusSeconds(1)));
   }
}
