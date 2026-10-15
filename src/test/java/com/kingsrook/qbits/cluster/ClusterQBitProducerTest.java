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


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.cluster.health.ClusterHeartbeatHealthCheck;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qbits.cluster.processes.ClusterNodeActions;
import com.kingsrook.qbits.cluster.runtime.ClusterAlerts;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntimeService;
import com.kingsrook.qbits.cluster.widgets.ClusterNodesWidgetRenderer;
import com.kingsrook.qqq.backend.core.alerts.QAlertTypeMetaData;
import com.kingsrook.qqq.backend.core.health.HealthChecksMetaData;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfigValidationException;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.testing.HelpBundleAssertions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The producer defines the tables, actions, widget, app, alert type, health
 ** check, runtime service and help, and the result validates.
 *******************************************************************************/
class ClusterQBitProducerTest extends BaseClusterTest
{

   /*******************************************************************************
    ** Everything is defined.
    *******************************************************************************/
   @Test
   void definesEverything()
   {
      assertThat(qInstance.getTable(ClusterTables.NODE)).isNotNull();
      assertThat(qInstance.getTable(ClusterTables.LEASE)).isNotNull();
      assertThat(qInstance.getTable(ClusterTables.SETTING)).isNotNull();
      assertThat(qInstance.getTable(ClusterTables.NODE).getFields()).containsKeys(ClusterTables.NODE_ID, ClusterTables.POD_NAME, ClusterTables.K8S_NODE_NAME,
         ClusterTables.ZONE, ClusterTables.DEPLOYMENT, ClusterTables.ROLES, ClusterTables.VERSION, ClusterTables.STARTED_AT, ClusterTables.HEARTBEAT_AT,
         ClusterTables.STATUS, ClusterTables.WORKER_THREADS, ClusterTables.SCHEDULER_ACTIVE, ClusterTables.ESB_TRIGGERS_ACTIVE, ClusterTables.ASYNC_JOBS_RUNNING,
         ClusterTables.HEAP_USED_MB, ClusterTables.HEAP_MAX_MB, ClusterTables.DRAIN_REQUESTED, ClusterTables.DRAIN_REQUESTED_BY, ClusterTables.DRAIN_REASON,
         ClusterTables.CREATE_DATE, ClusterTables.MODIFY_DATE);
      assertThat(qInstance.getTable(ClusterTables.NODE).getField(ClusterTables.K8S_NODE_NAME).getBackendName()).isEqualTo("k8s_node_name");
      assertThat(qInstance.getTable(ClusterTables.NODE).getField(ClusterTables.STATUS).getPossibleValueSourceName()).isEqualTo(NodeStatus.NAME);
      assertThat(qInstance.getPossibleValueSource(NodeStatus.NAME)).isNotNull();
      assertThat(qInstance.getTable(ClusterTables.NODE).isCapabilityEnabled(qInstance.getBackend(BACKEND), Capability.TABLE_INSERT)).isFalse();
      assertThat(qInstance.getTable(ClusterTables.SETTING).isCapabilityEnabled(qInstance.getBackend(BACKEND), Capability.TABLE_UPDATE)).isTrue();

      assertThat(qInstance.getProcess(ClusterNodeActions.DRAIN_NODE).getTableName()).isEqualTo(ClusterTables.NODE);
      assertThat(qInstance.getProcess(ClusterNodeActions.CANCEL_DRAIN).getTableName()).isEqualTo(ClusterTables.NODE);
      assertThat(qInstance.getWidget(ClusterNodesWidgetRenderer.NAME).getGridColumns()).isEqualTo(12);

      QAppMetaData app = qInstance.getApp("cluster");
      assertThat(app.getWidgets()).containsExactly(ClusterNodesWidgetRenderer.NAME);
      assertThat(app.getChildren()).extracting(child -> child.getName()).containsExactly(ClusterTables.NODE, ClusterTables.LEASE, ClusterTables.SETTING);
      assertThat(qInstance.getApp("admin").getChildren()).extracting(child -> child.getName()).contains("cluster");

      assertThat(QAlertTypeMetaData.of(qInstance, ClusterAlerts.NODE_LOST)).isNotNull();
      assertThat(HealthChecksMetaData.ofOrWithNew(qInstance).getChecks()).anyMatch(check -> ClusterHeartbeatHealthCheck.NAME.equals(check.getName()));
      assertThat(qInstance.getRuntimeServices()).extracting(reference -> reference.getName()).containsOnlyOnce(ClusterRuntimeService.class.getName());
      ////////////////////////////////////////////////////////////////////
      // the version comes from the build (filtered qbit-cluster.properties) //
      ////////////////////////////////////////////////////////////////////
      assertThat(ClusterQBitProducer.version()).matches("[0-9]+[.][0-9]+[.][0-9]+(-[A-Za-z0-9.]+)?");
      if(System.getProperty("qbit.artifactVersion") != null)
      {
         assertThat(ClusterQBitProducer.version()).isEqualTo(System.getProperty("qbit.artifactVersion"));
      }
   }



   /*******************************************************************************
    ** The produced metadata passes the instance validator.
    *******************************************************************************/
   @Test
   void validates() throws Exception
   {
      new QInstanceValidator().validate(qInstance);
   }



   /*******************************************************************************
    ** No processes in the navigation: the app lists only the three tables.
    *******************************************************************************/
   @Test
   void actionsAreNotInTheNavigation()
   {
      List<String> appChildren = new ArrayList<>();
      qInstance.getApps().values().forEach(app -> app.getChildren().forEach(child -> appChildren.add(child.getName())));
      assertThat(appChildren).doesNotContain(ClusterNodeActions.DRAIN_NODE, ClusterNodeActions.CANCEL_DRAIN);
   }



   /*******************************************************************************
    ** The shipped help covers every table, field, process, widget and possible
    ** value source of the qbit.
    *******************************************************************************/
   @Test
   void helpIsComplete()
   {
      Set<String> owned = Set.of("cluster", ClusterTables.NODE, ClusterTables.LEASE, ClusterTables.SETTING, ClusterNodeActions.DRAIN_NODE, ClusterNodeActions.CANCEL_DRAIN);
      HelpBundleAssertions.forInstance(qInstance).bundles(ClusterQBitProducer.HELP_BUNDLE).owning(target -> owned.contains(target.name())).assertComplete();
   }



   /*******************************************************************************
    ** A bad config fails before anything is added.
    *******************************************************************************/
   @Test
   void invalidConfigFails()
   {
      QInstance instance = new QInstance();
      instance.addBackend(new QBackendMetaData().withName(BACKEND).withBackendType(MemoryBackendModule.class));
      assertThatThrownBy(() -> new ClusterQBitProducer().withQBitConfig(new ClusterQBitConfig()).produce(instance)).isInstanceOf(QBitConfigValidationException.class);
      assertThatThrownBy(() -> new ClusterQBitProducer().withQBitConfig(new ClusterQBitConfig().withBackendName(BACKEND).withParentAppName("nope")).produce(instance))
         .isInstanceOf(QBitConfigValidationException.class);
      assertThatThrownBy(() -> new ClusterQBitProducer().produce(instance)).hasMessageContaining("required");
      assertThat(instance.getTable(ClusterTables.NODE)).isNull();
   }



   /*******************************************************************************
    ** Without a parent app, placement is left to the host; the runtime service
    ** can be left out.
    *******************************************************************************/
   @Test
   void noParentNoService() throws Exception
   {
      QInstance instance = new QInstance();
      instance.addBackend(new QBackendMetaData().withName(BACKEND).withBackendType(MemoryBackendModule.class));
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(QAuthenticationType.MOCK));
      new ClusterQBitProducer().withQBitConfig(new ClusterQBitConfig().withBackendName(BACKEND).withAppName("nodes").withRegisterRuntimeService(false)).produce(instance);
      new QInstanceEnricher(instance).enrich();
      assertThat(instance.getApp("nodes")).isNotNull();
      assertThat(instance.getRuntimeServices()).noneMatch(reference -> ClusterRuntimeService.class.getName().equals(reference.getName()));
      assertThat(new ClusterQBitProducer().getNamespace()).isEqualTo("cluster");
   }
}
