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


import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import com.kingsrook.qbits.cluster.model.ClusterSettings;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qbits.cluster.runtime.ClusterAlerts;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntime;
import com.kingsrook.qbits.cluster.runtime.ClusterSweeper;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Lost-node detection, the alert, and the purge of old rows.
 *******************************************************************************/
class ClusterSweeperTest extends BaseClusterTest
{

   /*******************************************************************************
    ** A silent node becomes LOST and raises the alert; a fresh one stays UP;
    ** a second sweep changes nothing more.
    *******************************************************************************/
   @Test
   void staleNodeBecomesLost() throws Exception
   {
      Instant now = Instant.now();
      otherNode("node-b", NodeStatus.UP.name(), now.minus(5, ChronoUnit.MINUTES), "WEB");
      otherNode("node-c", NodeStatus.DRAINING.name(), now.minus(2, ChronoUnit.MINUTES), "WORKER");
      otherNode("node-d", NodeStatus.UP.name(), now.minus(10, ChronoUnit.SECONDS), "WEB");

      ClusterSweeper.Result result = new ClusterSweeper(qInstance).sweep(ClusterSettings.defaultSettings());
      assertThat(result.lostNodeIds()).containsExactlyInAnyOrder("node-b", "node-c");
      assertThat(node("node-b").getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.LOST.name());
      assertThat(node("node-c").getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.LOST.name());
      assertThat(node("node-d").getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.UP.name());

      assertThat(RecordingAlertSink.RAISED).hasSize(2);
      assertThat(RecordingAlertSink.RAISED).allMatch(signal -> ClusterAlerts.NODE_LOST.equals(signal.getAlertTypeName()));
      assertThat(RecordingAlertSink.RAISED).extracting(signal -> signal.getDedupKey()).containsExactlyInAnyOrder("node-b", "node-c");
      assertThat(RecordingAlertSink.RAISED.get(0).getRecordRefs()).hasSize(1);

      assertThat(new ClusterSweeper(qInstance).sweep(ClusterSettings.defaultSettings()).lostNodeIds()).isEmpty();
      assertThat(RecordingAlertSink.RAISED).hasSize(2);
   }



   /*******************************************************************************
    ** alert_on_lost = false marks LOST without an alert.
    *******************************************************************************/
   @Test
   void noAlertWhenTurnedOff() throws Exception
   {
      otherNode("node-b", NodeStatus.UP.name(), Instant.now().minus(5, ChronoUnit.MINUTES), "WEB");
      new ClusterSweeper(qInstance).sweep(ClusterSettings.from(Map.of(ClusterSettings.ALERT_ON_LOST, "false")));
      assertThat(node("node-b").getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.LOST.name());
      assertThat(RecordingAlertSink.RAISED).isEmpty();
   }



   /*******************************************************************************
    ** STOPPED and LOST rows older than retain_stopped_days are deleted (the lost
    ** one's alert resolved); younger ones and live ones stay.
    *******************************************************************************/
   @Test
   void purgesOldRows() throws Exception
   {
      Instant now = Instant.now();
      otherNode("old-stopped", NodeStatus.STOPPED.name(), now.minus(8, ChronoUnit.DAYS), "WEB");
      otherNode("old-lost", NodeStatus.LOST.name(), now.minus(9, ChronoUnit.DAYS), "WEB");
      otherNode("young-stopped", NodeStatus.STOPPED.name(), now.minus(12, ChronoUnit.HOURS), "WEB");
      otherNode("live", NodeStatus.UP.name(), now, "WEB");

      ClusterSweeper.Result result = new ClusterSweeper(qInstance).sweep(ClusterSettings.defaultSettings());
      assertThat(result.purged()).isEqualTo(2);
      assertThat(all(ClusterTables.NODE)).extracting(r -> r.getValueString(ClusterTables.NODE_ID)).containsExactlyInAnyOrder("young-stopped", "live");
      assertThat(RecordingAlertSink.RESOLVED).anyMatch(r -> "old-lost".equals(r.getDedupKey()));

      assertThat(new ClusterSweeper(qInstance).sweep(ClusterSettings.from(Map.of(ClusterSettings.RETAIN_STOPPED_DAYS, "1"))).purged()).isEqualTo(0);
      setFields(ClusterTables.NODE, node("young-stopped").getValue(ClusterTables.ID), Map.of(ClusterTables.HEARTBEAT_AT, now.minus(2, ChronoUnit.DAYS)));
      assertThat(new ClusterSweeper(qInstance).sweep(ClusterSettings.from(Map.of(ClusterSettings.RETAIN_STOPPED_DAYS, "1"))).purged()).isEqualTo(1);
   }



   /*******************************************************************************
    ** The tick sweeps on the node that holds the sweeper lease, and only there.
    *******************************************************************************/
   @Test
   void onlyTheLeaderSweeps() throws Exception
   {
      ClusterRuntime runtime = runtime();
      insert(ClusterTables.LEASE, new QRecord().withValue(ClusterTables.NAME, ClusterSweeper.LEASE_NAME).withValue(ClusterTables.HOLDER_NODE_ID, "node-z")
         .withValue(ClusterTables.EXPIRES_AT, Instant.now().plus(1, ChronoUnit.HOURS)).withValue(ClusterTables.LEASE_VERSION, 1));
      otherNode("node-b", NodeStatus.UP.name(), Instant.now().minus(5, ChronoUnit.MINUTES), "WEB");

      runtime.tick();
      assertThat(node("node-b").getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.UP.name());

      QRecord lease = all(ClusterTables.LEASE).get(0);
      setFields(ClusterTables.LEASE, lease.getValue(ClusterTables.ID), Map.of(ClusterTables.EXPIRES_AT, Instant.now().minus(1, ChronoUnit.SECONDS)));
      runtime.tick();
      assertThat(node("node-b").getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.LOST.name());
      assertThat(all(ClusterTables.LEASE).get(0).getValueString(ClusterTables.HOLDER_NODE_ID)).isEqualTo(NODE_A);

      runtime.stop();
      assertThat(all(ClusterTables.LEASE).get(0).getValueString(ClusterTables.HOLDER_NODE_ID)).isNull();
   }
}
