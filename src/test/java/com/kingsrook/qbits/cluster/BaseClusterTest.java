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


import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.runtime.ClusterNodeInfo;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntime;
import com.kingsrook.qqq.backend.core.actions.processes.QProcessCallback;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.alerts.AlertsInstanceMetaData;
import com.kingsrook.qqq.backend.core.cluster.QNodeLifecycle;
import com.kingsrook.qqq.backend.core.cluster.QNodeRoles;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** A memory-backend instance with the Cluster qbit (under an Admin app), a
 ** recording alerts sink, this JVM as node "node-a", and helpers.
 *******************************************************************************/
public class BaseClusterTest
{
   public static final String BACKEND = "memory";
   public static final String NODE_A  = "node-a";

   protected QInstance         qInstance;
   protected ClusterQBitConfig config;

   private final List<ClusterRuntime> runtimes = new ArrayList<>();



   /*******************************************************************************
    ** Fresh store, instance and node identity.
    *******************************************************************************/
   @BeforeEach
   void setUpCluster() throws Exception
   {
      MemoryRecordStore.fullReset();
      QNodeLifecycle.resetForTests();
      RecordingAlertSink.clear();
      setNode(NODE_A);

      qInstance = new QInstance();
      qInstance.addBackend(new QBackendMetaData().withName(BACKEND).withBackendType(MemoryBackendModule.class));
      qInstance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(QAuthenticationType.MOCK));
      qInstance.addApp(new QAppMetaData().withName("admin").withLabel("Admin"));
      AlertsInstanceMetaData.ofOrWithNew(qInstance).withSink(new QCodeReference(RecordingAlertSink.class));
      config = new ClusterQBitConfig().withBackendName(BACKEND).withParentAppName("admin").withAppVersion("1.2.3");
      new ClusterQBitProducer().withQBitConfig(config).produce(qInstance);
      new QInstanceEnricher(qInstance).enrich();
      QContext.init(qInstance, new QSystemUserSession(qInstance));
   }



   /*******************************************************************************
    ** Stop runtimes, clear thread locals and statics.
    *******************************************************************************/
   @AfterEach
   void tearDownCluster()
   {
      runtimes.forEach(ClusterRuntime::stop);
      QNodeLifecycle.resetForTests();
      QNodeRoles.set(null);
      QContext.clear();
      MemoryRecordStore.fullReset();
   }



   /*******************************************************************************
    ** Make this JVM the given node (all roles).
    *******************************************************************************/
   protected static void setNode(String nodeId)
   {
      QNodeRoles.set(new QNodeRoles.Settings(EnumSet.allOf(QNodeRoles.Role.class), true, true, true, 4, nodeId));
   }



   /*******************************************************************************
    ** A runtime for the current node id (registered, no background thread),
    ** stopped after the test.
    *******************************************************************************/
   protected ClusterRuntime runtime(Map<String, String> environment) throws QException
   {
      ClusterRuntime runtime = new ClusterRuntime(qInstance, new ClusterNodeInfo(config, environment::get));
      runtime.initialize();
      runtimes.add(runtime);
      return (runtime);
   }



   /*******************************************************************************
    ** A runtime with an empty environment.
    *******************************************************************************/
   protected ClusterRuntime runtime() throws QException
   {
      return (runtime(Map.of()));
   }



   /*******************************************************************************
    ** All rows of a table.
    *******************************************************************************/
   protected List<QRecord> all(String table) throws QException
   {
      return (new QueryAction().execute(new QueryInput(table)).getRecords());
   }



   /*******************************************************************************
    ** A node row by node id (or null).
    *******************************************************************************/
   protected QRecord node(String nodeId) throws QException
   {
      List<QRecord> records = new QueryAction().execute(new QueryInput(ClusterTables.NODE)
         .withFilter(new QQueryFilter(new QFilterCriteria(ClusterTables.NODE_ID, QCriteriaOperator.EQUALS, nodeId)))).getRecords();
      return (records.isEmpty() ? null : records.get(0));
   }



   /*******************************************************************************
    ** Insert a row.
    *******************************************************************************/
   protected QRecord insert(String table, QRecord record) throws QException
   {
      QRecord inserted = new InsertAction().execute(new InsertInput(table).withRecord(record)).getRecords().get(0);
      assertThat(inserted.getErrors()).isNullOrEmpty();
      return (inserted);
   }



   /*******************************************************************************
    ** A node row as another JVM would have written it.
    *******************************************************************************/
   protected QRecord otherNode(String nodeId, String status, Instant heartbeatAt, String roles) throws QException
   {
      return (insert(ClusterTables.NODE, new QRecord()
         .withValue(ClusterTables.NODE_ID, nodeId)
         .withValue(ClusterTables.STATUS, status)
         .withValue(ClusterTables.ROLES, roles)
         .withValue(ClusterTables.STARTED_AT, heartbeatAt)
         .withValue(ClusterTables.HEARTBEAT_AT, heartbeatAt)));
   }



   /*******************************************************************************
    ** Set fields on a row directly (simulating time passing, or another node).
    *******************************************************************************/
   protected void setFields(String table, Serializable id, Map<String, Serializable> values) throws QException
   {
      QRecord record = new QRecord().withValue(ClusterTables.ID, id);
      values.forEach(record::setValue);
      new UpdateAction().execute(new UpdateInput(table).withRecord(record));
   }



   /*******************************************************************************
    ** Log in as a person with the given permissions.
    *******************************************************************************/
   protected QSession login(String fullName, String... permissions)
   {
      QSession session = new QSession().withUser(new QUser().withIdReference(fullName.toLowerCase().replace(' ', '.')).withFullName(fullName));
      for(String permission : permissions)
      {
         session.withPermission(permission);
      }
      QContext.setQSession(session);
      return (session);
   }



   /*******************************************************************************
    ** Run a record action on the given ids; returns the Result lines as
    ** "STATUS: count message".
    *******************************************************************************/
   protected List<String> runAction(String processName, List<Serializable> ids, Map<String, Serializable> values) throws QException
   {
      RunProcessInput input = new RunProcessInput()
         .withProcessName(processName)
         .withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP)
         .withCallback(new QProcessCallback()
         {
            /*******************************************************************************
             ** The selected records.
             *******************************************************************************/
            @Override
            public QQueryFilter getQueryFilter()
            {
               return (new QQueryFilter().withCriteria(ClusterTables.ID, QCriteriaOperator.IN, ids));
            }



            /*******************************************************************************
             ** The form values.
             *******************************************************************************/
            @Override
            public Map<String, Serializable> getFieldValues(List<QFieldMetaData> fields)
            {
               return (values);
            }
         });
      values.forEach(input::addValue);
      RunProcessOutput output = new RunProcessAction().execute(input);
      if(output.getException().isPresent())
      {
         throw (new QException(output.getException().get()));
      }
      @SuppressWarnings("unchecked")
      List<ProcessSummaryLineInterface> lines = (List<ProcessSummaryLineInterface>) output.getValue(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      List<String> text = new ArrayList<>();
      for(ProcessSummaryLineInterface line : lines == null ? List.<ProcessSummaryLineInterface>of() : lines)
      {
         text.add(line.getStatus() + ": " + (line instanceof ProcessSummaryLine summary ? summary.getCount() + " " + summary.getMessage() : String.valueOf(line)));
      }
      return (text);
   }
}
