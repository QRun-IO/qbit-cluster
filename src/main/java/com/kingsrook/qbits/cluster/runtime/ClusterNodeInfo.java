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


import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.kingsrook.qbits.cluster.ClusterQBitConfig;
import com.kingsrook.qqq.backend.core.actions.async.AsyncJobRegistry;
import com.kingsrook.qqq.backend.core.cluster.QNodeRoles;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.utils.StringUtils;
import static com.kingsrook.qbits.cluster.model.ClusterTables.ASYNC_JOBS_RUNNING;
import static com.kingsrook.qbits.cluster.model.ClusterTables.DEPLOYMENT;
import static com.kingsrook.qbits.cluster.model.ClusterTables.ESB_TRIGGERS_ACTIVE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.HEAP_MAX_MB;
import static com.kingsrook.qbits.cluster.model.ClusterTables.HEAP_USED_MB;
import static com.kingsrook.qbits.cluster.model.ClusterTables.K8S_NODE_NAME;
import static com.kingsrook.qbits.cluster.model.ClusterTables.NODE_ID;
import static com.kingsrook.qbits.cluster.model.ClusterTables.POD_NAME;
import static com.kingsrook.qbits.cluster.model.ClusterTables.ROLES;
import static com.kingsrook.qbits.cluster.model.ClusterTables.SCHEDULER_ACTIVE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.VERSION;
import static com.kingsrook.qbits.cluster.model.ClusterTables.WORKER_THREADS;
import static com.kingsrook.qbits.cluster.model.ClusterTables.ZONE;


/*******************************************************************************
 ** What this node reports about itself. Placement comes from the Kubernetes
 ** Downward API environment (each optional): QQQ_POD_NAME, QQQ_K8S_NODE_NAME,
 ** QQQ_ZONE, QQQ_DEPLOYMENT. Roles and role flags come from core QNodeRoles,
 ** running steps from AsyncJobRegistry, memory from the JVM.
 *******************************************************************************/
public class ClusterNodeInfo
{
   public static final String ENV_POD_NAME    = "QQQ_POD_NAME";
   public static final String ENV_K8S_NODE    = "QQQ_K8S_NODE_NAME";
   public static final String ENV_ZONE        = "QQQ_ZONE";
   public static final String ENV_DEPLOYMENT  = "QQQ_DEPLOYMENT";
   public static final String ENV_APP_VERSION = "QQQ_APP_VERSION";

   private static final long MB = 1024L * 1024L;

   private final ClusterQBitConfig        config;
   private final Function<String, String> environment;



   /*******************************************************************************
    ** Constructor (the process environment).
    *******************************************************************************/
   public ClusterNodeInfo(ClusterQBitConfig config)
   {
      this(config, System::getenv);
   }



   /*******************************************************************************
    ** Constructor with an environment lookup (tests).
    *******************************************************************************/
   public ClusterNodeInfo(ClusterQBitConfig config, Function<String, String> environment)
   {
      this.config = config;
      this.environment = environment;
   }



   /*******************************************************************************
    ** This node's id.
    *******************************************************************************/
   public String nodeId()
   {
      return (QNodeRoles.nodeId());
   }



   /*******************************************************************************
    ** The fixed fields, written when the node starts.
    *******************************************************************************/
   public QRecord identity()
   {
      return (new QRecord()
         .withValue(NODE_ID, nodeId())
         .withValue(POD_NAME, env(ENV_POD_NAME))
         .withValue(K8S_NODE_NAME, env(ENV_K8S_NODE))
         .withValue(ZONE, env(ENV_ZONE))
         .withValue(DEPLOYMENT, env(ENV_DEPLOYMENT))
         .withValue(ROLES, roles())
         .withValue(VERSION, version()));
   }



   /*******************************************************************************
    ** The live fields, written on every heartbeat.
    *******************************************************************************/
   public Map<String, java.io.Serializable> live()
   {
      QNodeRoles.Settings settings = QNodeRoles.get();
      Runtime             runtime  = Runtime.getRuntime();
      Map<String, java.io.Serializable> values = new java.util.LinkedHashMap<>();
      values.put(WORKER_THREADS, settings.workerThreads());
      values.put(SCHEDULER_ACTIVE, settings.scheduler());
      values.put(ESB_TRIGGERS_ACTIVE, settings.esbTriggers());
      values.put(ASYNC_JOBS_RUNNING, AsyncJobRegistry.runningCount());
      values.put(HEAP_USED_MB, (int) ((runtime.totalMemory() - runtime.freeMemory()) / MB));
      values.put(HEAP_MAX_MB, runtime.maxMemory() == Long.MAX_VALUE ? null : (int) (runtime.maxMemory() / MB));
      return (values);
   }



   /*******************************************************************************
    ** This node's roles as a comma list, in role order (WEB,WORKER,...).
    *******************************************************************************/
   public static String roles()
   {
      return (QNodeRoles.get().roles().stream().sorted().map(Enum::name).collect(Collectors.joining(",")));
   }



   /*******************************************************************************
    ** The application version: the config, else QQQ_APP_VERSION, else "qqq " and
    ** the QQQ version.
    *******************************************************************************/
   public String version()
   {
      if(StringUtils.hasContent(config.getAppVersion()))
      {
         return (config.getAppVersion());
      }
      String fromEnvironment = env(ENV_APP_VERSION);
      if(fromEnvironment != null)
      {
         return (fromEnvironment);
      }
      String qqqVersion = qqqVersion();
      return (qqqVersion == null ? null : "qqq " + qqqVersion);
   }



   /*******************************************************************************
    ** The QQQ (core) version, from its Maven metadata in the jar.
    *******************************************************************************/
   static String qqqVersion()
   {
      try(InputStream stream = QInstance.class.getResourceAsStream("/META-INF/maven/com.kingsrook.qqq/qqq-backend-core/pom.properties"))
      {
         if(stream == null)
         {
            return (QInstance.class.getPackage().getImplementationVersion());
         }
         Properties properties = new Properties();
         properties.load(stream);
         return (properties.getProperty("version"));
      }
      catch(Exception e)
      {
         return (null);
      }
   }



   /*******************************************************************************
    ** A trimmed environment value, or null.
    *******************************************************************************/
   private String env(String name)
   {
      String value = environment.apply(name);
      return (StringUtils.hasContent(value) ? value.trim() : null);
   }
}
