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


import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qbits.cluster.ClusterLeases;
import com.kingsrook.qbits.cluster.ClusterQBitConfig;
import com.kingsrook.qbits.cluster.model.ClusterSettings;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qqq.backend.core.cluster.QNodeLifecycle;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** This node's cluster runtime: one daemon thread that
 ** 1. registers the node (status UP) at start;
 ** 2. every heartbeat_seconds writes the heartbeat and live fields, and picks
 **    up a drain request (QNodeLifecycle.startDraining);
 ** 3. on the node holding the "cluster-sweeper" lease, runs the lost-node
 **    sweep and purge (ClusterSweeper).
 ** A QNodeLifecycle drain listener marks the node DRAINING (whatever started
 ** the drain: a request, or SIGTERM); stop (the runtime service, or the JVM
 ** shutdown hook) marks it STOPPED and releases the lease. A failed tick is
 ** logged and retried on the next one; it never stops the thread.
 *******************************************************************************/
public class ClusterRuntime
{
   private static final QLogger LOG = QLogger.getLogger(ClusterRuntime.class);

   private static volatile ClusterRuntime current;

   private final QInstance           qInstance;
   private final ClusterNodeRegistry registry;
   private final ClusterSweeper      sweeper;

   private volatile ClusterSettings          settings = ClusterSettings.defaultSettings();
   private volatile ScheduledExecutorService executor;
   private volatile Thread                   shutdownHook;
   private volatile boolean                  stopped  = false;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public ClusterRuntime(QInstance qInstance, ClusterQBitConfig config)
   {
      this(qInstance, new ClusterNodeInfo(config));
   }



   /*******************************************************************************
    ** Constructor with a node-info source (tests).
    *******************************************************************************/
   public ClusterRuntime(QInstance qInstance, ClusterNodeInfo info)
   {
      this.qInstance = qInstance;
      this.registry = new ClusterNodeRegistry(qInstance, info);
      this.sweeper = new ClusterSweeper(qInstance);
   }



   /*******************************************************************************
    ** The runtime running in this JVM, or null.
    *******************************************************************************/
   public static ClusterRuntime current()
   {
      return (current);
   }



   /*******************************************************************************
    ** Register the node and the drain listener, without the background thread
    ** (start() does both; tests drive tick() themselves).
    *******************************************************************************/
   public void initialize() throws QException
   {
      settings = loadSettings();
      registry.register();
      QNodeLifecycle.onDrain(reason ->
      {
         try
         {
            registry.markStatus(NodeStatus.DRAINING);
            releaseLease();
         }
         catch(Exception e)
         {
            LOG.warn("Could not record that this node is draining", e, logPair("nodeId", registry.getNodeId()));
         }
      });
      current = this;
   }



   /*******************************************************************************
    ** Initialize, then heartbeat on a daemon thread, and mark the node STOPPED
    ** if the JVM shuts down first.
    *******************************************************************************/
   public void start() throws QException
   {
      initialize();
      executor = Executors.newSingleThreadScheduledExecutor(runnable ->
      {
         Thread thread = new Thread(runnable, "qqq-cluster-heartbeat");
         thread.setDaemon(true);
         return (thread);
      });
      scheduleNext();

      shutdownHook = new Thread(this::stop, "qqq-cluster-shutdown");
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      LOG.info("Cluster runtime started", logPair("nodeId", registry.getNodeId()), logPair("heartbeatSeconds", settings.heartbeatSeconds()));
   }



   /*******************************************************************************
    ** One heartbeat, then (on the leader) one sweep.
    *******************************************************************************/
   public void tick()
   {
      if(stopped)
      {
         return;
      }
      try
      {
         settings = loadSettings();
         registry.heartbeat();

         if(QNodeLifecycle.isDraining())
         {
            releaseLease();
            return;
         }

         ClusterLeases.Holder lease = ClusterLeases.as(registry.getNodeId());
         boolean leader = ClusterSystem.call(qInstance, () -> lease.tryAcquire(ClusterSweeper.LEASE_NAME, Duration.ofSeconds(settings.lostAfterSeconds())));
         if(leader)
         {
            sweeper.sweep(settings);
         }
      }
      catch(Exception e)
      {
         LOG.warn("Cluster heartbeat failed; retrying on the next one", e, logPair("nodeId", registry.getNodeId()));
      }
   }



   /*******************************************************************************
    ** Stop (idempotent): stop heartbeats, release the sweeper lease, mark the
    ** node STOPPED. Never throws.
    *******************************************************************************/
   public void stop()
   {
      synchronized(this)
      {
         if(stopped)
         {
            return;
         }
         stopped = true;
      }

      if(executor != null)
      {
         executor.shutdownNow();
      }
      try
      {
         releaseLease();
         registry.markStatus(NodeStatus.STOPPED);
      }
      catch(Exception e)
      {
         LOG.warn("Could not mark this cluster node stopped", e, logPair("nodeId", registry.getNodeId()));
      }
      removeShutdownHook();
      if(current == this)
      {
         current = null;
      }
   }



   /*******************************************************************************
    ** Getter for registry
    *******************************************************************************/
   public ClusterNodeRegistry getRegistry()
   {
      return (registry);
   }



   /*******************************************************************************
    ** The settings read on the last tick.
    *******************************************************************************/
   public ClusterSettings getSettings()
   {
      return (settings);
   }



   /*******************************************************************************
    ** Getter for qInstance
    *******************************************************************************/
   public QInstance getQInstance()
   {
      return (qInstance);
   }



   /*******************************************************************************
    ** Run a tick, then schedule the next at the current heartbeat interval.
    *******************************************************************************/
   private void scheduleNext()
   {
      ScheduledExecutorService service = executor;
      if(stopped || service == null || service.isShutdown())
      {
         return;
      }
      service.schedule(() ->
      {
         tick();
         scheduleNext();
      }, settings.heartbeatSeconds(), TimeUnit.SECONDS);
   }



   /*******************************************************************************
    ** Release the sweeper lease if this node holds it.
    *******************************************************************************/
   private void releaseLease() throws QException
   {
      ClusterLeases.Holder lease = ClusterLeases.as(registry.getNodeId());
      ClusterSystem.call(qInstance, () -> lease.release(ClusterSweeper.LEASE_NAME));
   }



   /*******************************************************************************
    ** The settings, or the last ones read when they cannot be read.
    *******************************************************************************/
   private ClusterSettings loadSettings()
   {
      try
      {
         return (ClusterSystem.call(qInstance, ClusterSettings::load));
      }
      catch(Exception e)
      {
         LOG.warn("Could not read the cluster settings; keeping the current ones", e);
         return (settings);
      }
   }



   /*******************************************************************************
    ** Remove the JVM hook, unless the JVM is already shutting down.
    *******************************************************************************/
   private void removeShutdownHook()
   {
      Thread hook = shutdownHook;
      if(hook != null && Thread.currentThread() != hook)
      {
         try
         {
            Runtime.getRuntime().removeShutdownHook(hook);
         }
         catch(IllegalStateException e)
         {
            LOG.debug("The JVM is shutting down; the cluster hook stays");
         }
      }
   }
}
