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


import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qqq.backend.core.actions.tables.ConditionalUpdateAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.cluster.QNodeLifecycle;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.ConditionalUpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.utils.CollectionUtils;
import com.kingsrook.qqq.backend.core.utils.StringUtils;
import static com.kingsrook.qbits.cluster.model.ClusterTables.DRAIN_REASON;
import static com.kingsrook.qbits.cluster.model.ClusterTables.DRAIN_REQUESTED;
import static com.kingsrook.qbits.cluster.model.ClusterTables.DRAIN_REQUESTED_BY;
import static com.kingsrook.qbits.cluster.model.ClusterTables.HEARTBEAT_AT;
import static com.kingsrook.qbits.cluster.model.ClusterTables.MODIFY_DATE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.NODE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.NODE_ID;
import static com.kingsrook.qbits.cluster.model.ClusterTables.STARTED_AT;
import static com.kingsrook.qbits.cluster.model.ClusterTables.STATUS;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** This node's row in cluster_node: registered (upserted) at start, refreshed by
 ** each heartbeat, and the place a drain request arrives. Writes are
 ** compare-and-set updates keyed by node id (no audits or record events: this
 ** is bookkeeping, every 15 seconds per node); the first registration inserts.
 ** Every method runs as the system user in the given instance.
 *******************************************************************************/
public class ClusterNodeRegistry
{
   private static final QLogger LOG = QLogger.getLogger(ClusterNodeRegistry.class);

   private final QInstance       qInstance;
   private final ClusterNodeInfo info;
   private final String          nodeId;

   private volatile boolean stopped = false;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public ClusterNodeRegistry(QInstance qInstance, ClusterNodeInfo info)
   {
      this.qInstance = qInstance;
      this.info = info;
      this.nodeId = info.nodeId();
   }



   /*******************************************************************************
    ** Register this node: insert or refresh its row with status UP (or DRAINING
    ** if it already is), a new start time, and any old drain request cleared (a
    ** new process does not inherit its predecessor's request).
    *******************************************************************************/
   public void register() throws QException
   {
      ClusterSystem.call(qInstance, () ->
      {
         Instant now      = now();
         QRecord existing = read();

         Map<String, Serializable> values = new LinkedHashMap<>(info.identity().getValues());
         values.putAll(info.live());
         values.put(STATUS, localStatus().name());
         values.put(STARTED_AT, now);
         values.put(HEARTBEAT_AT, now);
         values.put(DRAIN_REQUESTED, false);
         values.put(DRAIN_REQUESTED_BY, null);
         values.put(DRAIN_REASON, null);

         if(existing == null)
         {
            QRecord record = new QRecord();
            record.setValues(values);
            QRecord inserted = new InsertAction().execute(new InsertInput(NODE).withRecord(record)).getRecords().get(0);
            if(CollectionUtils.nullSafeHasContents(inserted.getErrors()))
            {
               throw (new QException("Could not register cluster node " + nodeId + ": " + inserted.getErrors().get(0).getMessage()));
            }
         }
         else
         {
            values.put(MODIFY_DATE, now);
            update(values);
            resolveIfWasLost(existing);
         }
         LOG.info("Registered cluster node", logPair("nodeId", nodeId), logPair("roles", values.get(ClusterTables.ROLES)), logPair("version", values.get(ClusterTables.VERSION)));
         return (null);
      });
   }



   /*******************************************************************************
    ** One heartbeat: pick up a drain request (start draining), then write the
    ** live fields and the heartbeat time. A missing row (deleted, or purged
    ** while the node was paused) is registered again.
    *******************************************************************************/
   public void heartbeat() throws QException
   {
      if(stopped)
      {
         return;
      }

      QRecord existing = ClusterSystem.call(qInstance, this::read);
      if(existing == null)
      {
         LOG.info("This node's cluster row is missing; registering again", logPair("nodeId", nodeId));
         register();
         return;
      }

      if(Boolean.TRUE.equals(existing.getValueBoolean(DRAIN_REQUESTED)) && !QNodeLifecycle.isDraining())
      {
         String by     = StringUtils.hasContent(existing.getValueString(DRAIN_REQUESTED_BY)) ? existing.getValueString(DRAIN_REQUESTED_BY) : "an administrator";
         String reason = existing.getValueString(DRAIN_REASON);
         LOG.info("Drain requested for this node", logPair("nodeId", nodeId), logPair("requestedBy", by), logPair("reason", reason));
         QNodeLifecycle.startDraining("requested by " + by + (StringUtils.hasContent(reason) ? ": " + reason : ""));
      }

      ClusterSystem.call(qInstance, () ->
      {
         Map<String, Serializable> values = new LinkedHashMap<>(info.live());
         values.put(HEARTBEAT_AT, now());
         values.put(STATUS, localStatus().name());
         update(values);
         resolveIfWasLost(existing);
         return (null);
      });
   }



   /*******************************************************************************
    ** Record a status change of this node (DRAINING, STOPPED). STOPPED also
    ** records the time, which retention counts from, and stops heartbeats.
    *******************************************************************************/
   public void markStatus(NodeStatus status) throws QException
   {
      if(stopped)
      {
         return;
      }
      if(status == NodeStatus.STOPPED)
      {
         stopped = true;
      }

      ClusterSystem.call(qInstance, () ->
      {
         Instant                   now    = now();
         Map<String, Serializable> values = new LinkedHashMap<>();
         values.put(STATUS, status.name());
         values.put(MODIFY_DATE, now);
         if(status == NodeStatus.STOPPED)
         {
            values.put(HEARTBEAT_AT, now);
         }
         update(values);
         LOG.info("Cluster node status", logPair("nodeId", nodeId), logPair("status", status));
         return (null);
      });
   }



   /*******************************************************************************
    ** This node's row, or null (the caller runs as the system user).
    *******************************************************************************/
   public QRecord read() throws QException
   {
      List<QRecord> records = new QueryAction().execute(new QueryInput(NODE)
         .withFilter(new QQueryFilter(new QFilterCriteria(NODE_ID, QCriteriaOperator.EQUALS, nodeId)))).getRecords();
      return (records.isEmpty() ? null : records.get(0));
   }



   /*******************************************************************************
    ** Getter for nodeId
    *******************************************************************************/
   public String getNodeId()
   {
      return (nodeId);
   }



   /*******************************************************************************
    ** Whether this node was marked STOPPED.
    *******************************************************************************/
   public boolean isStopped()
   {
      return (stopped);
   }



   /*******************************************************************************
    ** UP, or DRAINING once core's lifecycle says so.
    *******************************************************************************/
   static NodeStatus localStatus()
   {
      return (QNodeLifecycle.isDraining() ? NodeStatus.DRAINING : NodeStatus.UP);
   }



   /*******************************************************************************
    ** The update of this node's row.
    *******************************************************************************/
   private void update(Map<String, Serializable> values) throws QException
   {
      new ConditionalUpdateAction().execute(new ConditionalUpdateInput(NODE)
         .withFilter(new QQueryFilter(new QFilterCriteria(NODE_ID, QCriteriaOperator.EQUALS, nodeId)))
         .withValues(values));
   }



   /*******************************************************************************
    ** A node marked LOST that beats again (a long pause, a partition) resolves
    ** its alert.
    *******************************************************************************/
   private void resolveIfWasLost(QRecord existing)
   {
      if(NodeStatus.LOST.name().equals(existing.getValueString(STATUS)))
      {
         LOG.info("A node marked lost is sending heartbeats again", logPair("nodeId", nodeId));
         ClusterAlerts.resolveLost(nodeId, "The node is sending heartbeats again.");
      }
   }



   /*******************************************************************************
    ** The backend clock.
    *******************************************************************************/
   private static Instant now() throws QException
   {
      return (new ConditionalUpdateAction().databaseNow(NODE));
   }
}
