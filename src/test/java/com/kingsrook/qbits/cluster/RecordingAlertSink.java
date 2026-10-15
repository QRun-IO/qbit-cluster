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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.kingsrook.qqq.backend.core.alerts.AlertResolution;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.AlertSinkInterface;


/*******************************************************************************
 ** An alerts sink that records what it receives (tests).
 *******************************************************************************/
public class RecordingAlertSink implements AlertSinkInterface
{
   public static final List<AlertSignal>     RAISED   = new CopyOnWriteArrayList<>();
   public static final List<AlertResolution> RESOLVED = new CopyOnWriteArrayList<>();



   /*******************************************************************************
    ** Forget everything.
    *******************************************************************************/
   public static void clear()
   {
      RAISED.clear();
      RESOLVED.clear();
   }



   /*******************************************************************************
    ** Record a raise.
    *******************************************************************************/
   @Override
   public void raise(AlertSignal signal)
   {
      RAISED.add(signal);
   }



   /*******************************************************************************
    ** Record a resolve.
    *******************************************************************************/
   @Override
   public void resolve(AlertResolution resolution)
   {
      RESOLVED.add(resolution);
   }



   /*******************************************************************************
    ** Ignore heartbeats.
    *******************************************************************************/
   @Override
   public void heartbeat(String heartbeatKey, Instant at)
   {
   }
}
