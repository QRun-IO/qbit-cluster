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


import com.kingsrook.qbits.cluster.ClusterQBitConfig;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QRuntimeServiceInterface;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;


/*******************************************************************************
 ** Starts and stops this node's cluster runtime with the application.
 ** QApplicationLauncher runs it from the instance's runtime services (the
 ** producer registers it); a host that starts services itself (for example
 ** with QApplicationJavalinServer) calls start(qInstance) after the server
 ** starts, and stop() at shutdown.
 *******************************************************************************/
public class ClusterRuntimeService implements QRuntimeServiceInterface
{
   private static final QLogger LOG = QLogger.getLogger(ClusterRuntimeService.class);

   private ClusterRuntime runtime;



   /*******************************************************************************
    ** Name of the service.
    *******************************************************************************/
   @Override
   public String getName()
   {
      return ("cluster");
   }



   /*******************************************************************************
    ** Register this node and start heartbeats.
    *******************************************************************************/
   @Override
   public void start(QInstance qInstance) throws QException
   {
      ClusterQBitConfig config = findConfig(qInstance);
      if(config == null)
      {
         LOG.warn("Cluster runtime service started, but no produced Cluster QBit was found in the QInstance");
         return;
      }
      runtime = new ClusterRuntime(qInstance, config);
      runtime.start();
   }



   /*******************************************************************************
    ** Mark this node stopped.
    *******************************************************************************/
   @Override
   public void stop()
   {
      if(runtime != null)
      {
         runtime.stop();
      }
   }



   /*******************************************************************************
    ** Getter for the runtime (null before start).
    *******************************************************************************/
   public ClusterRuntime getRuntime()
   {
      return (runtime);
   }



   /*******************************************************************************
    ** The config of the instance's Cluster qbit, or null.
    *******************************************************************************/
   static ClusterQBitConfig findConfig(QInstance qInstance)
   {
      if(qInstance == null || qInstance.getQBits() == null)
      {
         return (null);
      }
      for(QBitMetaData qBit : qInstance.getQBits().values())
      {
         if(qBit.getConfig() instanceof ClusterQBitConfig config)
         {
            return (config);
         }
      }
      return (null);
   }
}
