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


import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import com.kingsrook.qbits.cluster.health.ClusterHeartbeatHealthCheck;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.processes.ClusterNodeActions;
import com.kingsrook.qbits.cluster.runtime.ClusterAlerts;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntimeService;
import com.kingsrook.qbits.cluster.widgets.ClusterNodesWidgetRenderer;
import com.kingsrook.qqq.backend.core.actions.permissions.UseOtherPermissionNameCustomPermissionChecker;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.health.HealthCategory;
import com.kingsrook.qqq.backend.core.health.HealthCheckKind;
import com.kingsrook.qqq.backend.core.health.HealthCheckMetaData;
import com.kingsrook.qqq.backend.core.health.HealthChecksMetaData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfigValidationException;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaDataProducer;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.CollectionUtils;


/*******************************************************************************
 ** Produces the Cluster QBit into an instance:
 ** - tables cluster_node, cluster_lease, cluster_setting and the node status
 **   possible values;
 ** - record actions Drain node and Cancel drain on cluster_node;
 ** - the Nodes widget and the Cluster app (its home), attached under
 **   parentAppName when the config names one;
 ** - the cluster.node.lost alert type, the cluster.node.heartbeat health check
 **   and the help bundle (help/qbit-cluster.yaml);
 ** - ClusterRuntimeService among the instance's runtime services.
 **
 ** <pre>
 ** new ClusterQBitProducer()
 **    .withQBitConfig(new ClusterQBitConfig().withBackendName("rdbms").withParentAppName("admin"))
 **    .produce(qInstance);
 ** </pre>
 *******************************************************************************/
public class ClusterQBitProducer implements QBitMetaDataProducer<ClusterQBitConfig>
{
   public static final String GROUP_ID    = "com.kingsrook.qbits";
   public static final String ARTIFACT_ID = "qbit-cluster";
   public static final String NAMESPACE   = "cluster";
   public static final String HELP_BUNDLE = "help/qbit-cluster.yaml";

   private ClusterQBitConfig qBitConfig;



   /*******************************************************************************
    ** Fluent setter for the config.
    *******************************************************************************/
   public ClusterQBitProducer withQBitConfig(ClusterQBitConfig config)
   {
      this.qBitConfig = config;
      return (this);
   }



   /*******************************************************************************
    ** Getter for the config.
    *******************************************************************************/
   @Override
   public ClusterQBitConfig getQBitConfig()
   {
      return (qBitConfig);
   }



   /*******************************************************************************
    ** Stable namespace, independent of the host's navigation label.
    *******************************************************************************/
   @Override
   public String getNamespace()
   {
      return (NAMESPACE);
   }



   /*******************************************************************************
    ** The qbit's identity, with the Maven-filtered version.
    *******************************************************************************/
   @Override
   public QBitMetaData getQBitMetaData()
   {
      return (new QBitMetaData().withGroupId(GROUP_ID).withArtifactId(ARTIFACT_ID).withVersion(version()).withNamespace(NAMESPACE).withConfig(qBitConfig));
   }



   /*******************************************************************************
    ** The packaged version.
    *******************************************************************************/
   public static String version()
   {
      try(InputStream stream = ClusterQBitProducer.class.getResourceAsStream("/qbit-cluster.properties"))
      {
         Properties properties = new Properties();
         if(stream != null)
         {
            properties.load(stream);
         }
         return (properties.getProperty("version", "unknown"));
      }
      catch(IOException e)
      {
         throw (new IllegalStateException("Cannot read the qbit-cluster version resource", e));
      }
   }



   /*******************************************************************************
    ** Validate, then add everything to the instance. Returns empty output, as
    ** everything is already added.
    *******************************************************************************/
   @Override
   public MetaDataProducerMultiOutput produce(QInstance qInstance) throws QException
   {
      if(qBitConfig == null)
      {
         throw (new QException("ClusterQBitConfig is required"));
      }
      List<String> errors = new ArrayList<>();
      qBitConfig.validate(qInstance, errors);
      if(!errors.isEmpty())
      {
         throw (new QBitConfigValidationException(qBitConfig, errors));
      }

      QBitMetaData qBit = getQBitMetaData();
      qInstance.addQBit(qBit);

      for(QPossibleValueSource possibleValueSource : ClusterTables.possibleValueSources())
      {
         qInstance.addPossibleValueSource(possibleValueSource);
      }
      List<QTableMetaData> tables = ClusterTables.tables(qBitConfig);
      for(QTableMetaData table : tables)
      {
         table.setSourceQBitName(qBit.getName());
         qInstance.addTable(table);
      }
      for(QProcessMetaData process : ClusterNodeActions.processes(qBitConfig))
      {
         process.setSourceQBitName(qBit.getName());
         qInstance.addProcess(process);
      }

      qInstance.addWidget(new QWidgetMetaData()
         .withName(ClusterNodesWidgetRenderer.NAME)
         .withLabel("Nodes")
         .withType(WidgetType.TABLE.getType())
         .withGridColumns(12)
         .withPermissionRules(readRules())
         .withCodeReference(new QCodeReference(ClusterNodesWidgetRenderer.class))
         .withDefaultValue("refreshSeconds", qBitConfig.getWidgetRefreshSeconds()));

      QAppMetaData app = new QAppMetaData()
         .withName(qBitConfig.getAppName())
         .withLabel(qBitConfig.getAppLabel())
         .withIcon(new QIcon("hub"))
         .withPermissionRules(readRules())
         .withWidgets(new ArrayList<>(List.of(ClusterNodesWidgetRenderer.NAME)));
      tables.forEach(app::withChild);
      qInstance.addApp(app);
      if(qBitConfig.getParentAppName() != null)
      {
         QAppMetaData parent = qInstance.getApp(qBitConfig.getParentAppName());
         if(CollectionUtils.nonNullList(parent.getChildren()).stream().noneMatch(child -> app.getName().equals(child.getName())))
         {
            parent.withChild(app);
         }
      }

      ClusterAlerts.register(qInstance, qBit.getName());

      HealthChecksMetaData.ofOrWithNew(qInstance).withCheck(new HealthCheckMetaData()
         .withName(ClusterHeartbeatHealthCheck.NAME)
         .withLabel("Cluster heartbeat")
         .withComponent(NAMESPACE)
         .withKind(HealthCheckKind.DEPENDENCY)
         .withCategory(HealthCategory.CORE)
         .withIntervalSeconds(60)
         .withCheck(new QCodeReference(ClusterHeartbeatHealthCheck.class)));

      if(Boolean.TRUE.equals(qBitConfig.getRegisterRuntimeService())
         && CollectionUtils.nonNullList(qInstance.getRuntimeServices()).stream().noneMatch(reference -> ClusterRuntimeService.class.getName().equals(reference.getName())))
      {
         qInstance.withRuntimeService(new QCodeReference(ClusterRuntimeService.class));
      }

      HelpBundles.register(qInstance, HELP_BUNDLE);
      return (new MetaDataProducerMultiOutput());
   }



   /*******************************************************************************
    ** The app and widget need the exact read permission (cluster.read).
    *******************************************************************************/
   private QPermissionRules readRules()
   {
      return (new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION)
         .withCustomPermissionChecker(UseOtherPermissionNameCustomPermissionChecker.build(qBitConfig.getReadPermissionName())));
   }
}
