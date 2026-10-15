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


package com.kingsrook.qbits.cluster.model;


import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.PossibleValueEnum;


/*******************************************************************************
 ** Where a node is in its life (cluster_node.status):
 ** - UP: running and sending heartbeats;
 ** - DRAINING: finishing in-flight work, not taking new requests or work;
 ** - LOST: stopped sending heartbeats without saying goodbye (crash, kill,
 **   network partition) - set by the sweeper;
 ** - STOPPED: shut down cleanly.
 *******************************************************************************/
public enum NodeStatus implements PossibleValueEnum<String>
{
   UP("Up"),
   DRAINING("Draining"),
   LOST("Lost"),
   STOPPED("Stopped");

   public static final String NAME = "cluster_node_status";

   private final String label;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   NodeStatus(String label)
   {
      this.label = label;
   }



   /*******************************************************************************
    ** Possible value id.
    *******************************************************************************/
   @Override
   public String getPossibleValueId()
   {
      return (name());
   }



   /*******************************************************************************
    ** Possible value label.
    *******************************************************************************/
   @Override
   public String getPossibleValueLabel()
   {
      return (label);
   }
}
