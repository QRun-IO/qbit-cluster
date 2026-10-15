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


package com.kingsrook.qbits.cluster.health;


import java.time.Duration;
import java.time.Instant;
import com.kingsrook.qbits.cluster.model.ClusterSettings;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntime;
import com.kingsrook.qbits.cluster.runtime.ClusterSystem;
import com.kingsrook.qqq.backend.core.actions.tables.ConditionalUpdateAction;
import com.kingsrook.qqq.backend.core.health.HealthCheck;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.health.HealthDetails;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;


/*******************************************************************************
 ** cluster.node.heartbeat: this node's own heartbeat is current.
 ** - UP: the last heartbeat is within two heartbeat intervals;
 ** - DEGRADED: older than that, but not yet lost_after_seconds (writes are slow
 **   or failing);
 ** - DOWN: older than lost_after_seconds (other nodes will mark this one LOST),
 **   or the row is missing or cannot be read;
 ** - UNKNOWN: the cluster runtime is not running in this JVM.
 *******************************************************************************/
public class ClusterHeartbeatHealthCheck implements HealthCheck
{
   public static final String NAME = "cluster.node.heartbeat";



   /*******************************************************************************
    ** Check this node's row.
    *******************************************************************************/
   @Override
   public HealthCheckOutcome run(HealthCheckInput input)
   {
      ClusterRuntime runtime = ClusterRuntime.current();
      if(runtime == null)
      {
         return (HealthCheckOutcome.unknown("The cluster runtime is not running on this node"));
      }

      QInstance qInstance = input.getQInstance() == null ? runtime.getQInstance() : input.getQInstance();
      try
      {
         return (ClusterSystem.call(qInstance, () ->
         {
            QRecord node = runtime.getRegistry().read();
            if(node == null)
            {
               return (HealthCheckOutcome.down("This node is not registered in the cluster"));
            }
            Instant heartbeatAt = node.getValueInstant(ClusterTables.HEARTBEAT_AT);
            if(heartbeatAt == null)
            {
               return (HealthCheckOutcome.down("This node has no heartbeat"));
            }

            ClusterSettings settings = runtime.getSettings();
            long            age      = Math.max(0, Duration.between(heartbeatAt, new ConditionalUpdateAction().databaseNow(ClusterTables.NODE)).toSeconds());
            HealthDetails details = new HealthDetails()
               .withValue("nodeId", runtime.getRegistry().getNodeId())
               .withValue("status", node.getValueString(ClusterTables.STATUS))
               .withValue("heartbeatAgeSeconds", age)
               .withValue("heartbeatSeconds", settings.heartbeatSeconds());

            HealthCheckOutcome outcome;
            if(age <= 2L * settings.heartbeatSeconds())
            {
               outcome = HealthCheckOutcome.up("Heartbeat is current");
            }
            else if(age < settings.lostAfterSeconds())
            {
               outcome = HealthCheckOutcome.degraded("Heartbeat is late (" + age + " seconds old)");
            }
            else
            {
               outcome = HealthCheckOutcome.down("Heartbeat is " + age + " seconds old; other nodes treat this node as lost");
            }
            return (outcome.withDetails(details));
         }));
      }
      catch(Exception e)
      {
         return (HealthCheckOutcome.down("This node's heartbeat could not be read"));
      }
   }
}
