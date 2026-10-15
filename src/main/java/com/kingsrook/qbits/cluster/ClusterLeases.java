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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.runtime.ClusterSystem;
import com.kingsrook.qqq.backend.core.actions.tables.ConditionalUpdateAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.cluster.QNodeRoles;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.ConditionalUpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.CollectionUtils;
import static com.kingsrook.qbits.cluster.model.ClusterTables.ACQUIRED_AT;
import static com.kingsrook.qbits.cluster.model.ClusterTables.EXPIRES_AT;
import static com.kingsrook.qbits.cluster.model.ClusterTables.HOLDER_NODE_ID;
import static com.kingsrook.qbits.cluster.model.ClusterTables.LEASE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.LEASE_VERSION;
import static com.kingsrook.qbits.cluster.model.ClusterTables.MODIFY_DATE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.NAME;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Lease-based leadership for singletons that are not Quartz jobs: at most one
 ** node holds a named lease at a time, until it expires.
 **
 ** <pre>
 ** if(ClusterLeases.tryAcquire("my-singleton", Duration.ofSeconds(60)))
 ** {
 **    // this node is the leader for (at most) the next 60 seconds;
 **    // call tryAcquire (or renew) again well before then to keep it
 ** }
 ** </pre>
 **
 ** Every change is ONE atomic compare-and-set (core ConditionalUpdateAction;
 ** for an RDBMS a single UPDATE cluster_lease SET ... WHERE name = ? AND
 ** version = ? AND (expires_at &lt; now OR holder_node_id = me OR holder_node_id
 ** IS NULL)), so two nodes can never both win. Times come from the backend's
 ** clock (the database clock on an RDBMS), not each pod's own. The version
 ** counter goes up on every change.
 **
 ** The static methods act for this node (QNodeRoles.nodeId()); as(nodeId) acts
 ** for a given node id (tests, tools). They run as the system user, in the
 ** current QContext's instance.
 *******************************************************************************/
public final class ClusterLeases
{
   private static final QLogger LOG = QLogger.getLogger(ClusterLeases.class);



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ClusterLeases()
   {
   }



   /*******************************************************************************
    ** Acquire (or extend) the named lease for this node. True when this node
    ** holds it for the next ttl.
    *******************************************************************************/
   public static boolean tryAcquire(String name, Duration ttl) throws QException
   {
      return (as(QNodeRoles.nodeId()).tryAcquire(name, ttl));
   }



   /*******************************************************************************
    ** Extend a lease this node already holds. False when another node has it.
    *******************************************************************************/
   public static boolean renew(String name, Duration ttl) throws QException
   {
      return (as(QNodeRoles.nodeId()).renew(name, ttl));
   }



   /*******************************************************************************
    ** Give up a lease this node holds (a no-op otherwise), so another node can
    ** take it at once.
    *******************************************************************************/
   public static boolean release(String name) throws QException
   {
      return (as(QNodeRoles.nodeId()).release(name));
   }



   /*******************************************************************************
    ** Whether this node holds the named lease and it has not expired.
    *******************************************************************************/
   public static boolean isLeader(String name) throws QException
   {
      return (as(QNodeRoles.nodeId()).isLeader(name));
   }



   /*******************************************************************************
    ** Leases for a given node id.
    *******************************************************************************/
   public static Holder as(String nodeId)
   {
      return (new Holder(nodeId));
   }



   /*******************************************************************************
    ** The lease operations for one node id.
    *******************************************************************************/
   public static final class Holder
   {
      private final String nodeId;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      private Holder(String nodeId)
      {
         if(nodeId == null || nodeId.isBlank())
         {
            throw (new IllegalArgumentException("A lease holder needs a node id"));
         }
         this.nodeId = nodeId;
      }



      /*******************************************************************************
       ** Acquire, or extend, the lease: wins when it is free, expired or already
       ** ours.
       *******************************************************************************/
      public boolean tryAcquire(String name, Duration ttl) throws QException
      {
         requireTtl(ttl);
         return (ClusterSystem.call(() ->
         {
            Instant now   = now();
            QRecord lease = read(name);
            if(lease == null)
            {
               return (insert(name, now, ttl));
            }

            boolean ours = nodeId.equals(lease.getValueString(HOLDER_NODE_ID));
            QQueryFilter freeOrOurs = new QQueryFilter()
               .withBooleanOperator(QQueryFilter.BooleanOperator.OR)
               .withCriteria(new QFilterCriteria(EXPIRES_AT, QCriteriaOperator.LESS_THAN, now))
               .withCriteria(new QFilterCriteria(HOLDER_NODE_ID, QCriteriaOperator.EQUALS, nodeId))
               .withCriteria(new QFilterCriteria(HOLDER_NODE_ID, QCriteriaOperator.IS_BLANK));
            boolean won = compareAndSet(lease, freeOrOurs, nodeId, now.plus(ttl), ours && lease.getValueInstant(ACQUIRED_AT) != null ? lease.getValueInstant(ACQUIRED_AT) : now, now);
            if(won && !ours)
            {
               LOG.info("Acquired cluster lease", logPair("lease", name), logPair("nodeId", nodeId), logPair("previousHolder", lease.getValueString(HOLDER_NODE_ID)));
            }
            return (won);
         }));
      }



      /*******************************************************************************
       ** Extend the lease only if this node holds it (expired or not, as long as
       ** no other node took it meanwhile).
       *******************************************************************************/
      public boolean renew(String name, Duration ttl) throws QException
      {
         requireTtl(ttl);
         return (ClusterSystem.call(() ->
         {
            Instant now   = now();
            QRecord lease = read(name);
            if(lease == null || !nodeId.equals(lease.getValueString(HOLDER_NODE_ID)))
            {
               return (false);
            }
            Instant acquiredAt = lease.getValueInstant(ACQUIRED_AT) == null ? now : lease.getValueInstant(ACQUIRED_AT);
            return (compareAndSet(lease, new QQueryFilter(new QFilterCriteria(HOLDER_NODE_ID, QCriteriaOperator.EQUALS, nodeId)), nodeId, now.plus(ttl), acquiredAt, now));
         }));
      }



      /*******************************************************************************
       ** Release the lease if this node holds it.
       *******************************************************************************/
      public boolean release(String name) throws QException
      {
         return (ClusterSystem.call(() ->
         {
            QRecord lease = read(name);
            if(lease == null || !nodeId.equals(lease.getValueString(HOLDER_NODE_ID)))
            {
               return (false);
            }
            Instant now = now();
            return (compareAndSet(lease, new QQueryFilter(new QFilterCriteria(HOLDER_NODE_ID, QCriteriaOperator.EQUALS, nodeId)), null, now, null, now));
         }));
      }



      /*******************************************************************************
       ** Whether this node holds the lease and it has not expired.
       *******************************************************************************/
      public boolean isLeader(String name) throws QException
      {
         return (ClusterSystem.call(() ->
         {
            QRecord lease = read(name);
            if(lease == null || !nodeId.equals(lease.getValueString(HOLDER_NODE_ID)))
            {
               return (false);
            }
            Instant expiresAt = lease.getValueInstant(EXPIRES_AT);
            return (expiresAt != null && expiresAt.isAfter(now()));
         }));
      }



      /*******************************************************************************
       ** The CAS: the row must still have the version we read, and pass the guard.
       *******************************************************************************/
      private boolean compareAndSet(QRecord lease, QQueryFilter guard, String holder, Instant expiresAt, Instant acquiredAt, Instant now) throws QException
      {
         Integer version = lease.getValueInteger(LEASE_VERSION) == null ? 0 : lease.getValueInteger(LEASE_VERSION);
         QQueryFilter filter = new QQueryFilter()
            .withCriteria(new QFilterCriteria(NAME, QCriteriaOperator.EQUALS, lease.getValueString(NAME)))
            .withCriteria(lease.getValueInteger(LEASE_VERSION) == null
               ? new QFilterCriteria(LEASE_VERSION, QCriteriaOperator.IS_BLANK)
               : new QFilterCriteria(LEASE_VERSION, QCriteriaOperator.EQUALS, version))
            .withSubFilter(guard);
         int count = new ConditionalUpdateAction().execute(new ConditionalUpdateInput(LEASE)
            .withFilter(filter)
            .withValue(HOLDER_NODE_ID, holder)
            .withValue(EXPIRES_AT, expiresAt)
            .withValue(ACQUIRED_AT, acquiredAt)
            .withValue(LEASE_VERSION, version + 1)
            .withValue(MODIFY_DATE, now));
         return (count == 1);
      }



      /*******************************************************************************
       ** First holder: insert the row. A concurrent insert by another node fails
       ** on the unique name; then whoever's row is there decides.
       *******************************************************************************/
      private boolean insert(String name, Instant now, Duration ttl) throws QException
      {
         try
         {
            QRecord inserted = new InsertAction().execute(new InsertInput(LEASE).withRecord(new QRecord()
               .withValue(NAME, name)
               .withValue(HOLDER_NODE_ID, nodeId)
               .withValue(EXPIRES_AT, now.plus(ttl))
               .withValue(ACQUIRED_AT, now)
               .withValue(LEASE_VERSION, 1))).getRecords().get(0);
            if(CollectionUtils.nullSafeIsEmpty(inserted.getErrors()))
            {
               LOG.info("Acquired cluster lease", logPair("lease", name), logPair("nodeId", nodeId));
               return (true);
            }
         }
         catch(QException e)
         {
            LOG.debug("Lease insert lost a race; reading the winner", logPair("lease", name));
         }
         QRecord lease = read(name);
         return (lease != null && nodeId.equals(lease.getValueString(HOLDER_NODE_ID)));
      }



      /*******************************************************************************
       ** The lease row, or null.
       *******************************************************************************/
      private QRecord read(String name) throws QException
      {
         List<QRecord> records = new QueryAction().execute(new QueryInput(LEASE)
            .withFilter(new QQueryFilter(new QFilterCriteria(NAME, QCriteriaOperator.EQUALS, (Serializable) name)))).getRecords();
         return (records.isEmpty() ? null : records.get(0));
      }



      /*******************************************************************************
       ** The backend clock.
       *******************************************************************************/
      private Instant now() throws QException
      {
         return (new ConditionalUpdateAction().databaseNow(ClusterTables.LEASE));
      }



      /*******************************************************************************
       ** A lease must last a positive time.
       *******************************************************************************/
      private static void requireTtl(Duration ttl)
      {
         if(ttl == null || ttl.isNegative() || ttl.isZero())
         {
            throw (new IllegalArgumentException("A lease ttl must be positive"));
         }
      }
   }
}
