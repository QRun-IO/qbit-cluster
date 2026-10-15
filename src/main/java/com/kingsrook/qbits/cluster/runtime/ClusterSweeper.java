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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.cluster.model.ClusterSettings;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qqq.backend.core.actions.tables.ConditionalUpdateAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.ConditionalUpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import static com.kingsrook.qbits.cluster.model.ClusterTables.HEARTBEAT_AT;
import static com.kingsrook.qbits.cluster.model.ClusterTables.ID;
import static com.kingsrook.qbits.cluster.model.ClusterTables.MODIFY_DATE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.NODE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.NODE_ID;
import static com.kingsrook.qbits.cluster.model.ClusterTables.STATUS;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The lost-node sweep, run by the one node holding the "cluster-sweeper" lease:
 ** - a node that is UP or DRAINING with a heartbeat older than
 **   lost_after_seconds becomes LOST (compare-and-set on its status and
 **   heartbeat, so a node that beats in between stays UP), and raises the
 **   cluster.node.lost alert when alert_on_lost is true;
 ** - STOPPED and LOST rows whose last heartbeat is older than
 **   retain_stopped_days are deleted (their alerts resolved).
 ** Every step is idempotent: a sweep interrupted by a crash is simply redone
 ** by the next leader, so the purge needs no other durable mechanism.
 *******************************************************************************/
public class ClusterSweeper
{
   public static final String LEASE_NAME = "cluster-sweeper";

   private static final QLogger LOG = QLogger.getLogger(ClusterSweeper.class);

   private static final List<String> LIVE_STATUSES  = List.of(NodeStatus.UP.name(), NodeStatus.DRAINING.name());
   private static final List<String> ENDED_STATUSES = List.of(NodeStatus.STOPPED.name(), NodeStatus.LOST.name());

   private final QInstance qInstance;



   /*******************************************************************************
    ** What one sweep did.
    **
    ** @param lostNodeIds nodes marked LOST
    ** @param purged rows deleted
    *******************************************************************************/
   public record Result(List<String> lostNodeIds, int purged)
   {
   }



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public ClusterSweeper(QInstance qInstance)
   {
      this.qInstance = qInstance;
   }



   /*******************************************************************************
    ** One sweep with the given settings (the caller holds the lease).
    *******************************************************************************/
   public Result sweep(ClusterSettings settings) throws QException
   {
      return (ClusterSystem.call(qInstance, () ->
      {
         Instant now = new ConditionalUpdateAction().databaseNow(NODE);
         return (new Result(markLost(settings, now), purge(settings, now)));
      }));
   }



   /*******************************************************************************
    ** Mark stale live nodes LOST.
    *******************************************************************************/
   private List<String> markLost(ClusterSettings settings, Instant now) throws QException
   {
      Instant cutoff = now.minusSeconds(settings.lostAfterSeconds());
      QQueryFilter stale = new QQueryFilter()
         .withCriteria(new QFilterCriteria(STATUS, QCriteriaOperator.IN, LIVE_STATUSES))
         .withCriteria(new QFilterCriteria(HEARTBEAT_AT, QCriteriaOperator.LESS_THAN, cutoff));

      List<String> lost = new ArrayList<>();
      for(QRecord node : new QueryAction().execute(new QueryInput(NODE).withFilter(stale)).getRecords())
      {
         QQueryFilter guard = new QQueryFilter()
            .withCriteria(new QFilterCriteria(ID, QCriteriaOperator.EQUALS, node.getValue(ID)))
            .withCriteria(new QFilterCriteria(STATUS, QCriteriaOperator.IN, LIVE_STATUSES))
            .withCriteria(new QFilterCriteria(HEARTBEAT_AT, QCriteriaOperator.LESS_THAN, cutoff));
         int count = new ConditionalUpdateAction().execute(new ConditionalUpdateInput(NODE).withFilter(guard)
            .withValue(STATUS, NodeStatus.LOST.name())
            .withValue(MODIFY_DATE, now));
         if(count == 1)
         {
            String  nodeId        = node.getValueString(NODE_ID);
            Instant lastHeartbeat = node.getValueInstant(HEARTBEAT_AT);
            long    seconds       = lastHeartbeat == null ? settings.lostAfterSeconds() : Duration.between(lastHeartbeat, now).toSeconds();
            LOG.warn("Cluster node lost: no heartbeat", logPair("nodeId", nodeId), logPair("secondsSinceHeartbeat", seconds), logPair("previousStatus", node.getValueString(STATUS)));
            lost.add(nodeId);
            if(settings.alertOnLost())
            {
               ClusterAlerts.raiseLost(node, seconds);
            }
         }
      }
      return (lost);
   }



   /*******************************************************************************
    ** Delete old STOPPED / LOST rows.
    *******************************************************************************/
   private int purge(ClusterSettings settings, Instant now) throws QException
   {
      Instant cutoff = now.minus(Duration.ofDays(settings.retainStoppedDays()));
      QQueryFilter old = new QQueryFilter()
         .withCriteria(new QFilterCriteria(STATUS, QCriteriaOperator.IN, ENDED_STATUSES))
         .withCriteria(new QFilterCriteria(HEARTBEAT_AT, QCriteriaOperator.LESS_THAN, cutoff));

      List<Serializable> ids = new ArrayList<>();
      for(QRecord node : new QueryAction().execute(new QueryInput(NODE).withFilter(old)).getRecords())
      {
         ids.add(node.getValue(ID));
         if(NodeStatus.LOST.name().equals(node.getValueString(STATUS)))
         {
            ClusterAlerts.resolveLost(node.getValueString(NODE_ID), "The lost node's row was removed after " + settings.retainStoppedDays() + " days.");
         }
      }
      if(ids.isEmpty())
      {
         return (0);
      }
      new DeleteAction().execute(new DeleteInput(NODE).withPrimaryKeys(ids));
      LOG.info("Purged old cluster node rows", logPair("count", ids.size()), logPair("retainDays", settings.retainStoppedDays()));
      return (ids.size());
   }
}
