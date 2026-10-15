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


import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qbits.cluster.processes.ClusterNodeActionSteps;
import com.kingsrook.qbits.cluster.processes.ClusterNodeActions;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntime;
import com.kingsrook.qbits.cluster.widgets.ClusterNodesWidgetRenderer;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.cluster.QNodeLifecycle;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Drain node and Cancel drain, end to end: the record action sets the
 ** request, the node's heartbeat picks it up and calls
 ** QNodeLifecycle.startDraining, and the row becomes DRAINING.
 *******************************************************************************/
class ClusterDrainTest extends BaseClusterTest
{
   private static final String DRAIN = "cluster.drain";



   /*******************************************************************************
    ** The request flows to QNodeLifecycle with who and why.
    *******************************************************************************/
   @Test
   void drainRequestReachesTheLifecycle() throws Exception
   {
      ClusterRuntime runtime = runtime();
      otherNode("node-b", NodeStatus.UP.name(), Instant.now(), "WEB");
      Serializable id = node(NODE_A).getValue(ClusterTables.ID);

      login("Pat Admin", DRAIN);
      List<String> lines = runAction(ClusterNodeActions.DRAIN_NODE, List.of(id), Map.of(ClusterNodeActionSteps.FIELD_REASON, "kernel patch"));
      assertThat(lines).anyMatch(line -> line.startsWith("OK: 1 ") && line.contains("asked to drain"));

      QRecord requested = node(NODE_A);
      assertThat(requested.getValueBoolean(ClusterTables.DRAIN_REQUESTED)).isTrue();
      assertThat(requested.getValueString(ClusterTables.DRAIN_REQUESTED_BY)).isEqualTo("Pat Admin");
      assertThat(requested.getValueString(ClusterTables.DRAIN_REASON)).isEqualTo("kernel patch");
      assertThat(requested.getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.UP.name());
      assertThat(QNodeLifecycle.isDraining()).isFalse();

      runtime.tick();
      assertThat(QNodeLifecycle.isDraining()).isTrue();
      assertThat(QNodeLifecycle.getDrainReason()).isEqualTo("requested by Pat Admin: kernel patch");
      assertThat(node(NODE_A).getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.DRAINING.name());

      lines = runAction(ClusterNodeActions.CANCEL_DRAIN, List.of(id), Map.of());
      assertThat(lines).anyMatch(line -> line.contains("already started draining"));
      assertThat(node(NODE_A).getValueBoolean(ClusterTables.DRAIN_REQUESTED)).isTrue();
   }



   /*******************************************************************************
    ** Cancel before the node acts: the request is cleared and nothing drains.
    *******************************************************************************/
   @Test
   void cancelBeforeTheNodeActs() throws Exception
   {
      ClusterRuntime runtime = runtime();
      otherNode("node-b", NodeStatus.UP.name(), Instant.now(), "WEB");
      Serializable id = node(NODE_A).getValue(ClusterTables.ID);
      login("Pat Admin", DRAIN);
      runAction(ClusterNodeActions.DRAIN_NODE, List.of(id), Map.of());
      assertThat(node(NODE_A).getValueString(ClusterTables.DRAIN_REASON)).isNull();

      List<String> lines = runAction(ClusterNodeActions.CANCEL_DRAIN, List.of(id), Map.of());
      assertThat(lines).anyMatch(line -> line.startsWith("OK: 1 ") && line.contains("cancelled"));
      assertThat(node(NODE_A).getValueBoolean(ClusterTables.DRAIN_REQUESTED)).isFalse();
      assertThat(node(NODE_A).getValueString(ClusterTables.DRAIN_REQUESTED_BY)).isNull();

      runtime.tick();
      assertThat(QNodeLifecycle.isDraining()).isFalse();
      assertThat(node(NODE_A).getValueString(ClusterTables.STATUS)).isEqualTo(NodeStatus.UP.name());
   }



   /*******************************************************************************
    ** Only UP nodes without a request are drained; others are skipped, with why.
    *******************************************************************************/
   @Test
   void skipsNodesThatCannotBeDrained() throws Exception
   {
      QRecord lost    = otherNode("node-lost", NodeStatus.LOST.name(), Instant.now(), "WEB");
      QRecord stopped = otherNode("node-stopped", NodeStatus.STOPPED.name(), Instant.now(), "WEB");
      QRecord up      = otherNode("node-up", NodeStatus.UP.name(), Instant.now(), "WEB");
      login("Pat Admin", DRAIN);

      List<String> lines = runAction(ClusterNodeActions.DRAIN_NODE, List.of(lost.getValue(ClusterTables.ID), stopped.getValue(ClusterTables.ID), up.getValue(ClusterTables.ID)), Map.of());
      assertThat(lines).anyMatch(line -> line.startsWith("OK: 1 "));
      assertThat(lines).anyMatch(line -> line.startsWith("INFO: 2 ") && line.contains("Only a node that is up"));
      assertThat(node("node-lost").getValueBoolean(ClusterTables.DRAIN_REQUESTED)).isNotEqualTo(true);

      lines = runAction(ClusterNodeActions.DRAIN_NODE, List.of(up.getValue(ClusterTables.ID)), Map.of());
      assertThat(lines).anyMatch(line -> line.contains("already requested"));

      lines = runAction(ClusterNodeActions.CANCEL_DRAIN, List.of(lost.getValue(ClusterTables.ID)), Map.of());
      assertThat(lines).anyMatch(line -> line.contains("No drain was requested"));
   }



   /*******************************************************************************
    ** The Review warns when no node would remain up.
    *******************************************************************************/
   @Test
   void warnsWhenNoNodeWouldRemainUp() throws Exception
   {
      runtime();
      login("Pat Admin", DRAIN);
      Serializable id = node(NODE_A).getValue(ClusterTables.ID);
      List<String> lines = new java.util.ArrayList<>();
      ClusterNodeActionSteps.DrainTransform transform = new ClusterNodeActionSteps.DrainTransform();
      com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput input = new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput();
      input.setRecords(List.of(node(NODE_A)));
      transform.runOnePage(input, new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput());
      transform.getProcessSummary(null, false).forEach(line -> lines.add(String.valueOf(((com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine) line).getMessage())));
      assertThat(lines).anyMatch(line -> line.contains("No other node is up"));
      assertThat(id).isNotNull();
   }



   /*******************************************************************************
    ** The actions need the drain permission; the app and widget need read.
    *******************************************************************************/
   @Test
   void permissions()
   {
      RunProcessInput input = new RunProcessInput();
      login("Read Only", "cluster.read");
      assertThat(PermissionsHelper.hasProcessPermission(input, ClusterNodeActions.DRAIN_NODE)).isFalse();
      assertThat(PermissionsHelper.hasProcessPermission(input, ClusterNodeActions.CANCEL_DRAIN)).isFalse();
      assertThat(PermissionsHelper.hasAppPermission(input, "cluster")).isTrue();
      assertThat(PermissionsHelper.hasWidgetPermission(input, ClusterNodesWidgetRenderer.NAME)).isTrue();
      assertThat(PermissionsHelper.hasTablePermission(input, ClusterTables.NODE, TablePermissionSubType.READ)).isTrue();
      assertThat(PermissionsHelper.hasTablePermission(input, ClusterTables.SETTING, TablePermissionSubType.EDIT)).isFalse();

      login("Operator", DRAIN);
      assertThat(PermissionsHelper.hasProcessPermission(input, ClusterNodeActions.DRAIN_NODE)).isTrue();
      assertThat(PermissionsHelper.hasAppPermission(input, "cluster")).isFalse();

      login("Admin", "cluster.read", "cluster.write");
      assertThat(PermissionsHelper.hasTablePermission(input, ClusterTables.SETTING, TablePermissionSubType.EDIT)).isTrue();
   }
}
