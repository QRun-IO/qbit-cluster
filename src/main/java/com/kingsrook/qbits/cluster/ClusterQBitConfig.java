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


import java.util.List;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** Host configuration of the Cluster qbit.
 **
 ** Table names are fixed (cluster_node, cluster_lease, cluster_setting): the
 ** QRun qbit standard names a qbit's tables with the qbit's own prefix, and the
 ** shipped changelog creates exactly those. The app's place in the navigation
 ** is the host's choice: set parentAppName (for example "admin"), or attach the
 ** app yourself.
 *******************************************************************************/
public class ClusterQBitConfig implements QBitConfig
{
   private String  backendName;
   private String  appName                = "cluster";
   private String  appLabel               = "Cluster";
   private String  parentAppName;
   private String  permissionBaseName     = "cluster";
   private String  drainPermissionName    = "cluster.drain";
   private String  appVersion;
   private Integer widgetRefreshSeconds   = 15;
   private Boolean registerRuntimeService = true;



   /*******************************************************************************
    ** Report invalid settings before adding any metadata.
    *******************************************************************************/
   @Override
   public void validate(QInstance qInstance, List<String> errors)
   {
      assertCondition(StringUtils.hasContent(backendName), "backendName is required for the Cluster QBit", errors);
      assertCondition(backendName == null || qInstance.getBackend(backendName) != null, "backendName must name a backend in the instance", errors);
      assertCondition(appName != null && appName.matches("[A-Za-z][A-Za-z0-9_]*"), "appName must be a valid name", errors);
      assertCondition(parentAppName == null || (!parentAppName.equals(appName) && qInstance.getApp(parentAppName) != null), "parentAppName must name an existing, different app", errors);
      assertCondition(StringUtils.hasContent(permissionBaseName), "permissionBaseName is required", errors);
      assertCondition(StringUtils.hasContent(drainPermissionName), "drainPermissionName is required", errors);
      assertCondition(widgetRefreshSeconds != null && widgetRefreshSeconds > 0, "widgetRefreshSeconds must be positive", errors);
   }



   /*******************************************************************************
    ** Tables without their own backend use the configured backend.
    *******************************************************************************/
   @Override
   public String getDefaultBackendNameForTables()
   {
      return (backendName);
   }



   /*******************************************************************************
    ** The permission that reads the cluster tables, app and widget.
    *******************************************************************************/
   public String getReadPermissionName()
   {
      return (permissionBaseName + ".read");
   }



   /*******************************************************************************
    ** The permission that edits the cluster tables.
    *******************************************************************************/
   public String getWritePermissionName()
   {
      return (permissionBaseName + ".write");
   }



   /*******************************************************************************
    ** Getter for backendName
    *******************************************************************************/
   public String getBackendName()
   {
      return (this.backendName);
   }



   /*******************************************************************************
    ** Setter for backendName
    *******************************************************************************/
   public void setBackendName(String backendName)
   {
      this.backendName = backendName;
   }



   /*******************************************************************************
    ** Fluent setter for backendName: the backend holding the cluster_ tables
    ** (required). Lease compare-and-set needs its CONDITIONAL_UPDATE capability
    ** (the RDBMS and memory backends have it).
    *******************************************************************************/
   public ClusterQBitConfig withBackendName(String backendName)
   {
      this.backendName = backendName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appName
    *******************************************************************************/
   public String getAppName()
   {
      return (this.appName);
   }



   /*******************************************************************************
    ** Setter for appName
    *******************************************************************************/
   public void setAppName(String appName)
   {
      this.appName = appName;
   }



   /*******************************************************************************
    ** Fluent setter for appName (default "cluster").
    *******************************************************************************/
   public ClusterQBitConfig withAppName(String appName)
   {
      this.appName = appName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appLabel
    *******************************************************************************/
   public String getAppLabel()
   {
      return (this.appLabel);
   }



   /*******************************************************************************
    ** Setter for appLabel
    *******************************************************************************/
   public void setAppLabel(String appLabel)
   {
      this.appLabel = appLabel;
   }



   /*******************************************************************************
    ** Fluent setter for appLabel (default "Cluster").
    *******************************************************************************/
   public ClusterQBitConfig withAppLabel(String appLabel)
   {
      this.appLabel = appLabel;
      return (this);
   }



   /*******************************************************************************
    ** Getter for parentAppName
    *******************************************************************************/
   public String getParentAppName()
   {
      return (this.parentAppName);
   }



   /*******************************************************************************
    ** Setter for parentAppName
    *******************************************************************************/
   public void setParentAppName(String parentAppName)
   {
      this.parentAppName = parentAppName;
   }



   /*******************************************************************************
    ** Fluent setter for parentAppName: an existing app to attach the Cluster app
    ** under (for example "admin"). Null (the default) leaves placement to the host.
    *******************************************************************************/
   public ClusterQBitConfig withParentAppName(String parentAppName)
   {
      this.parentAppName = parentAppName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for permissionBaseName
    *******************************************************************************/
   public String getPermissionBaseName()
   {
      return (this.permissionBaseName);
   }



   /*******************************************************************************
    ** Setter for permissionBaseName
    *******************************************************************************/
   public void setPermissionBaseName(String permissionBaseName)
   {
      this.permissionBaseName = permissionBaseName;
   }



   /*******************************************************************************
    ** Fluent setter for permissionBaseName (default "cluster": the tables, app
    ** and widget use cluster.read and cluster.write).
    *******************************************************************************/
   public ClusterQBitConfig withPermissionBaseName(String permissionBaseName)
   {
      this.permissionBaseName = permissionBaseName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for drainPermissionName
    *******************************************************************************/
   public String getDrainPermissionName()
   {
      return (this.drainPermissionName);
   }



   /*******************************************************************************
    ** Setter for drainPermissionName
    *******************************************************************************/
   public void setDrainPermissionName(String drainPermissionName)
   {
      this.drainPermissionName = drainPermissionName;
   }



   /*******************************************************************************
    ** Fluent setter for drainPermissionName: the exact permission that runs Drain
    ** node and Cancel drain (default "cluster.drain").
    *******************************************************************************/
   public ClusterQBitConfig withDrainPermissionName(String drainPermissionName)
   {
      this.drainPermissionName = drainPermissionName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appVersion
    *******************************************************************************/
   public String getAppVersion()
   {
      return (this.appVersion);
   }



   /*******************************************************************************
    ** Setter for appVersion
    *******************************************************************************/
   public void setAppVersion(String appVersion)
   {
      this.appVersion = appVersion;
   }



   /*******************************************************************************
    ** Fluent setter for appVersion: the application version each node reports.
    ** Without it, the environment variable QQQ_APP_VERSION, else the QQQ version.
    *******************************************************************************/
   public ClusterQBitConfig withAppVersion(String appVersion)
   {
      this.appVersion = appVersion;
      return (this);
   }



   /*******************************************************************************
    ** Getter for widgetRefreshSeconds
    *******************************************************************************/
   public Integer getWidgetRefreshSeconds()
   {
      return (this.widgetRefreshSeconds);
   }



   /*******************************************************************************
    ** Setter for widgetRefreshSeconds
    *******************************************************************************/
   public void setWidgetRefreshSeconds(Integer widgetRefreshSeconds)
   {
      this.widgetRefreshSeconds = widgetRefreshSeconds;
   }



   /*******************************************************************************
    ** Fluent setter for widgetRefreshSeconds (the Nodes widget, default 15).
    *******************************************************************************/
   public ClusterQBitConfig withWidgetRefreshSeconds(Integer widgetRefreshSeconds)
   {
      this.widgetRefreshSeconds = widgetRefreshSeconds;
      return (this);
   }



   /*******************************************************************************
    ** Getter for registerRuntimeService
    *******************************************************************************/
   public Boolean getRegisterRuntimeService()
   {
      return (this.registerRuntimeService);
   }



   /*******************************************************************************
    ** Setter for registerRuntimeService
    *******************************************************************************/
   public void setRegisterRuntimeService(Boolean registerRuntimeService)
   {
      this.registerRuntimeService = registerRuntimeService;
   }



   /*******************************************************************************
    ** Fluent setter for registerRuntimeService: add ClusterRuntimeService to the
    ** instance's runtime services (default true), so QApplicationLauncher starts
    ** the heartbeat. A host that starts services itself starts it directly.
    *******************************************************************************/
   public ClusterQBitConfig withRegisterRuntimeService(Boolean registerRuntimeService)
   {
      this.registerRuntimeService = registerRuntimeService;
      return (this);
   }
}
