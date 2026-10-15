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


import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;


/*******************************************************************************
 ** Runs the qbit's own bookkeeping on the cluster_ tables as the system user
 ** (never a person's work), then restores the caller's context.
 *******************************************************************************/
public final class ClusterSystem
{

   /*******************************************************************************
    ** A body that returns a value and may throw.
    *******************************************************************************/
   @FunctionalInterface
   public interface Body<T>
   {
      /*******************************************************************************
       ** Run the body.
       *******************************************************************************/
      T run() throws QException;
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ClusterSystem()
   {
   }



   /*******************************************************************************
    ** Run in the current instance as the system user.
    *******************************************************************************/
   public static <T> T call(Body<T> body) throws QException
   {
      QInstance qInstance = QContext.getQInstance();
      if(qInstance == null)
      {
         throw (new QException("The Cluster qbit needs a QContext with an instance"));
      }
      return (call(qInstance, body));
   }



   /*******************************************************************************
    ** Run in the given instance as the system user (no transaction).
    *******************************************************************************/
   public static <T> T call(QInstance qInstance, Body<T> body) throws QException
   {
      CapturedContext original = QContext.capture();
      try
      {
         QContext.init(qInstance, new QSystemUserSession(qInstance));
         return (body.run());
      }
      finally
      {
         if(original.qInstance() == null)
         {
            QContext.clear();
         }
         else
         {
            QContext.init(original);
         }
      }
   }
}
