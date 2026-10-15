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
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qbits.cluster.runtime.ClusterAlerts;
import com.kingsrook.qbits.cluster.runtime.ClusterNodeInfo;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntime;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntimeService;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.cluster.QNodeLifecycle;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The node row: register (upsert), heartbeat, status changes, stop.
 *******************************************************************************/
class ClusterRegistryTest extends BaseClusterTest
{

   /*******************************************************************************
    ** Start inserts the row with the environment, roles and live fields.
    *******************************************************************************/
   @Test
   void registerInsertsTheRow() throws Exception
   {
      runtime(Map.of(ClusterNodeInfo.ENV_POD_NAME, "web-7f9-abc", ClusterNodeInfo.ENV_K8S_NODE, "k8s-wk-21", ClusterNodeInfo.ENV_ZONE, "galaxy",
         ClusterNodeInfo.ENV_DEPLOYMENT, "website-backend"));

      QRecord node = node(NODE_A);
      assertThat(node.getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.UP.name());
      assertThat(node.getValueString(ClusterTables.POD_NAME)).isEqualTo("web-7f9-abc");
      assertThat(node.getValueString(ClusterTables.K8S_NODE_NAME)).isEqualTo("k8s-wk-21");
      assertThat(node.getValueString(ClusterTables.ZONE)).isEqualTo("galaxy");
      assertThat(node.getValueString(ClusterTables.DEPLOYMENT)).isEqualTo("website-backend");
      assertThat(node.getValueString(ClusterTables.ROLES)).isEqualTo("WEB,WORKER,SCHEDULER,ESB");
      assertThat(node.getValueString(ClusterTables.VERSION)).isEqualTo("1.2.3");
      assertThat(node.getValueInteger(ClusterTables.WORKER_THREADS)).isEqualTo(4);
      assertThat(node.getValueBoolean(ClusterTables.SCHEDULER_ACTIVE)).isTrue();
      assertThat(node.getValueInteger(ClusterTables.ASYNC_JOBS_RUNNING)).isZero();
      assertThat(node.getValueInteger(ClusterTables.HEAP_USED_MB)).isPositive();
      assertThat(node.getValueInstant(ClusterTables.STARTED_AT)).isNotNull();
      assertThat(node.getValueInstant(ClusterTables.HEARTBEAT_AT)).isNotNull();
      assertThat(node.getValueBoolean(ClusterTables.DRAIN_REQUESTED)).isFalse();
   }



   /*******************************************************************************
    ** A restart with the same node id updates the one row and clears an old
    ** drain request; no duplicate.
    *******************************************************************************/
   @Test
   void restartUpdatesTheSameRow() throws Exception
   {
      QRecord old = otherNode(NODE_A, NodeStatus.STOPPED.name(), Instant.now().minus(1, ChronoUnit.HOURS), "WEB");
      setFields(ClusterTables.NODE, old.getValue(ClusterTables.ID), Map.of(ClusterTables.DRAIN_REQUESTED, true, ClusterTables.DRAIN_REQUESTED_BY, "Pat"));

      runtime();
      assertThat(all(ClusterTables.NODE)).hasSize(1);
      QRecord node = node(NODE_A);
      assertThat(node.getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.UP.name());
      assertThat(node.getValueBoolean(ClusterTables.DRAIN_REQUESTED)).isFalse();
      assertThat(node.getValueString(ClusterTables.DRAIN_REQUESTED_BY)).isNull();
      assertThat(node.getValueInstant(ClusterTables.STARTED_AT)).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
   }



   /*******************************************************************************
    ** A heartbeat moves heartbeat_at forward and keeps the row UP.
    *******************************************************************************/
   @Test
   void heartbeatUpdatesTheRow() throws Exception
   {
      ClusterRuntime runtime = runtime();
      QRecord        node    = node(NODE_A);
      Instant        old     = Instant.now().minus(30, ChronoUnit.SECONDS);
      setFields(ClusterTables.NODE, node.getValue(ClusterTables.ID), Map.of(ClusterTables.HEARTBEAT_AT, old, ClusterTables.HEAP_USED_MB, 0));

      runtime.tick();
      QRecord after = node(NODE_A);
      assertThat(after.getValueInstant(ClusterTables.HEARTBEAT_AT)).isAfter(old);
      assertThat(after.getValueInteger(ClusterTables.HEAP_USED_MB)).isPositive();
      assertThat(after.getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.UP.name());
   }



   /*******************************************************************************
    ** A deleted row comes back on the next heartbeat.
    *******************************************************************************/
   @Test
   void missingRowIsRegisteredAgain() throws Exception
   {
      ClusterRuntime runtime = runtime();
      new DeleteAction().execute(new DeleteInput(ClusterTables.NODE).withPrimaryKeys(java.util.List.of(node(NODE_A).getValue(ClusterTables.ID))));
      assertThat(node(NODE_A)).isNull();
      runtime.tick();
      assertThat(node(NODE_A)).isNotNull();
   }



   /*******************************************************************************
    ** A node marked LOST that beats again is UP and its alert resolved.
    *******************************************************************************/
   @Test
   void lostNodeThatBeatsAgainRecovers() throws Exception
   {
      ClusterRuntime runtime = runtime();
      setFields(ClusterTables.NODE, node(NODE_A).getValue(ClusterTables.ID), Map.of(ClusterTables.STATUS, NodeStatus.LOST.name()));
      runtime.tick();
      assertThat(node(NODE_A).getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.UP.name());
      assertThat(RecordingAlertSink.RESOLVED).anyMatch(r -> ClusterAlerts.NODE_LOST.equals(r.getAlertTypeName()) && NODE_A.equals(r.getDedupKey()));
   }



   /*******************************************************************************
    ** A drain from anywhere (SIGTERM) marks DRAINING; stop marks STOPPED and
    ** heartbeats stop.
    *******************************************************************************/
   @Test
   void drainThenStop() throws Exception
   {
      ClusterRuntime runtime = runtime();
      QNodeLifecycle.startDraining("shutdown");
      assertThat(node(NODE_A).getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.DRAINING.name());

      runtime.tick();
      assertThat(node(NODE_A).getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.DRAINING.name());

      runtime.stop();
      QRecord stopped = node(NODE_A);
      assertThat(stopped.getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.STOPPED.name());
      assertThat(runtime.getRegistry().isStopped()).isTrue();
      assertThat(ClusterRuntime.current()).isNull();

      Instant at = stopped.getValueInstant(ClusterTables.HEARTBEAT_AT);
      runtime.tick();
      runtime.stop();
      assertThat(node(NODE_A).getValueInstant(ClusterTables.HEARTBEAT_AT)).isEqualTo(at);
   }



   /*******************************************************************************
    ** The runtime service finds the config, starts a heartbeat thread and stops.
    *******************************************************************************/
   @Test
   void runtimeServiceStartsAndStops() throws Exception
   {
      ClusterRuntimeService service = new ClusterRuntimeService();
      assertThat(service.getName()).isEqualTo("cluster");
      service.start(qInstance);
      assertThat(service.getRuntime()).isNotNull();
      assertThat(ClusterRuntime.current()).isSameAs(service.getRuntime());
      assertThat(node(NODE_A).getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.UP.name());
      service.stop();
      assertThat(node(NODE_A).getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.STOPPED.name());

      ClusterRuntimeService without = new ClusterRuntimeService();
      without.start(new com.kingsrook.qqq.backend.core.model.metadata.QInstance());
      without.stop();
      assertThat(without.getRuntime()).isNull();
   }



   /*******************************************************************************
    ** The version falls back to QQQ_APP_VERSION, then the QQQ version.
    *******************************************************************************/
   @Test
   void versionFallbacks()
   {
      ClusterQBitConfig noVersion = new ClusterQBitConfig().withBackendName(BACKEND);
      assertThat(new ClusterNodeInfo(noVersion, Map.of(ClusterNodeInfo.ENV_APP_VERSION, "9.9.9")::get).version()).isEqualTo("9.9.9");
      assertThat(new ClusterNodeInfo(noVersion, name -> null).version()).startsWith("qqq ");
   }
}
