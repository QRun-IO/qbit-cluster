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


package com.kingsrook.qbits.cluster.runtime;


import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qqq.backend.core.alerts.AlertRecordRef;
import com.kingsrook.qqq.backend.core.alerts.AlertSeverity;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.AlertsInstanceMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlertTypeMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlerts;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;


/*******************************************************************************
 ** The cluster.node.lost alert, through core's alerts SPI (QAlerts). With
 ** qbit-alerts installed it becomes an Alerts record and notifications;
 ** without it, core logs it. Raising never fails the sweep (QAlerts catches).
 *******************************************************************************/
public final class ClusterAlerts
{
   public static final String NODE_LOST = "cluster.node.lost";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ClusterAlerts()
   {
   }



   /*******************************************************************************
    ** Declare the alert type (once per instance).
    *******************************************************************************/
   public static void register(QInstance qInstance, String qBitName)
   {
      if(QAlertTypeMetaData.of(qInstance, NODE_LOST) != null)
      {
         return;
      }
      AlertsInstanceMetaData.ofOrWithNew(qInstance).withAlertType(new QAlertTypeMetaData()
         .withName(NODE_LOST)
         .withLabel("Cluster node lost")
         .withDefaultSeverity(AlertSeverity.WARNING)
         .withSubjectTableName(ClusterTables.NODE)
         .withSourceQBitName(qBitName)
         .withDescription("A node of the application stopped sending heartbeats without shutting down cleanly.")
         .withHowToFix("Check why the pod or server stopped (crash, out of memory, eviction, network). The other nodes keep serving; "
            + "a replacement usually starts on its own. The alert resolves when the node's heartbeat returns or its row is purged."));
   }



   /*******************************************************************************
    ** Raise the alert for a node marked LOST (deduplicated by node id).
    *******************************************************************************/
   public static void raiseLost(QRecord node, long secondsSinceHeartbeat)
   {
      String nodeId = node.getValueString(ClusterTables.NODE_ID);
      QAlerts.raise(new AlertSignal(NODE_LOST)
         .withDedupKey(nodeId)
         .withTitle("Node " + nodeId + " stopped sending heartbeats")
         .withSummary("No heartbeat for " + secondsSinceHeartbeat + " seconds. Roles: " + node.getValueString(ClusterTables.ROLES) + ".")
         .withRecordRef(new AlertRecordRef(ClusterTables.NODE, node.getValue(ClusterTables.ID)))
         .withAttribute("nodeId", nodeId));
   }



   /*******************************************************************************
    ** Resolve a node's alert (a no-op when none is open).
    *******************************************************************************/
   public static void resolveLost(String nodeId, String note)
   {
      QAlerts.resolve(NODE_LOST, nodeId, note);
   }
}
