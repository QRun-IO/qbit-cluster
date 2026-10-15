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


import java.util.List;
import com.kingsrook.qbits.cluster.ClusterQBitConfig;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.DisplayFormat;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;


/*******************************************************************************
 ** The three cluster_ tables. Each is an ordinary QQQ table (query, view,
 ** export, API), secured by the read / write permissions of the configured
 ** permission base name rather than by hiding it:
 ** - cluster_node: one row per node, written by the nodes themselves (no insert
 **   or edit in the UI; Drain node / Cancel drain are record actions; old rows
 **   may be deleted);
 ** - cluster_lease: leadership leases (ClusterLeases); an administrator may
 **   edit a lease (for example clear its holder to force a hand-over);
 ** - cluster_setting: the cluster's settings, seeded with defaults.
 **
 ** Column names are the snake_case of the field names (the shipped Liquibase
 ** changelog db/liquibase/qbit-cluster/cluster.changelog.sql).
 *******************************************************************************/
public final class ClusterTables
{
   public static final String NODE    = "cluster_node";
   public static final String LEASE   = "cluster_lease";
   public static final String SETTING = "cluster_setting";

   public static final List<String> ALL = List.of(NODE, LEASE, SETTING);

   ///////////////////////
   // cluster_node      //
   ///////////////////////
   public static final String ID                  = "id";
   public static final String NODE_ID             = "nodeId";
   public static final String POD_NAME            = "podName";
   public static final String K8S_NODE_NAME       = "k8sNodeName";
   public static final String ZONE                = "zone";
   public static final String DEPLOYMENT          = "deployment";
   public static final String ROLES               = "roles";
   public static final String VERSION             = "version";
   public static final String STARTED_AT          = "startedAt";
   public static final String HEARTBEAT_AT        = "heartbeatAt";
   public static final String STATUS              = "status";
   public static final String WORKER_THREADS      = "workerThreads";
   public static final String SCHEDULER_ACTIVE    = "schedulerActive";
   public static final String ESB_TRIGGERS_ACTIVE = "esbTriggersActive";
   public static final String ASYNC_JOBS_RUNNING  = "asyncJobsRunning";
   public static final String HEAP_USED_MB        = "heapUsedMb";
   public static final String HEAP_MAX_MB         = "heapMaxMb";
   public static final String DRAIN_REQUESTED     = "drainRequested";
   public static final String DRAIN_REQUESTED_BY  = "drainRequestedBy";
   public static final String DRAIN_REASON        = "drainReason";
   public static final String CREATE_DATE         = "createDate";
   public static final String MODIFY_DATE         = "modifyDate";

   ///////////////////////
   // cluster_lease     //
   ///////////////////////
   public static final String NAME           = "name";
   public static final String HOLDER_NODE_ID = "holderNodeId";
   public static final String EXPIRES_AT     = "expiresAt";
   public static final String LEASE_VERSION  = "version";
   public static final String ACQUIRED_AT    = "acquiredAt";

   ///////////////////////
   // cluster_setting   //
   ///////////////////////
   public static final String SETTING_KEY   = "settingKey";
   public static final String SETTING_VALUE = "settingValue";
   public static final String DESCRIPTION   = "description";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ClusterTables()
   {
   }



   /*******************************************************************************
    ** The possible-value sources: node statuses, and nodes (for lease holders).
    *******************************************************************************/
   public static List<QPossibleValueSource> possibleValueSources()
   {
      return (List.of(QPossibleValueSource.newForEnum(NodeStatus.NAME, NodeStatus.values())));
   }



   /*******************************************************************************
    ** Every table.
    *******************************************************************************/
   public static List<QTableMetaData> tables(ClusterQBitConfig config)
   {
      return (List.of(nodeTable(config), leaseTable(config), settingTable(config)));
   }



   /*******************************************************************************
    ** Read / write permissions on the configured base name (cluster.read and
    ** cluster.write by default).
    *******************************************************************************/
   public static QPermissionRules tablePermissionRules(ClusterQBitConfig config)
   {
      return (new QPermissionRules().withLevel(PermissionLevel.READ_WRITE_PERMISSIONS).withPermissionBaseName(config.getPermissionBaseName()));
   }



   /*******************************************************************************
    ** cluster_node
    *******************************************************************************/
   public static QTableMetaData nodeTable(ClusterQBitConfig config)
   {
      QTableMetaData table = base(config, NODE, "Nodes", "dns")
         .withRecordLabelFormat("%s")
         .withRecordLabelFields(NODE_ID)
         .withUniqueKey(new UniqueKey(NODE_ID))
         .withField(field(NODE_ID, "node_id", QFieldType.STRING).withLabel("Node").withMaxLength(250).withIsRequired(true))
         .withField(field(POD_NAME, "pod_name", QFieldType.STRING).withLabel("Pod").withMaxLength(250))
         .withField(field(K8S_NODE_NAME, "k8s_node_name", QFieldType.STRING).withLabel("Kubernetes Node").withMaxLength(250))
         .withField(field(ZONE, "zone", QFieldType.STRING).withLabel("Zone").withMaxLength(100))
         .withField(field(DEPLOYMENT, "deployment", QFieldType.STRING).withLabel("Deployment").withMaxLength(250))
         .withField(field(ROLES, "roles", QFieldType.STRING).withLabel("Roles").withMaxLength(100))
         .withField(field(VERSION, "version", QFieldType.STRING).withLabel("Version").withMaxLength(100))
         .withField(field(STARTED_AT, "started_at", QFieldType.DATE_TIME).withLabel("Started"))
         .withField(field(HEARTBEAT_AT, "heartbeat_at", QFieldType.DATE_TIME).withLabel("Last Heartbeat"))
         .withField(field(STATUS, "status", QFieldType.STRING).withLabel("Status").withMaxLength(20).withPossibleValueSourceName(NodeStatus.NAME))
         .withField(field(WORKER_THREADS, "worker_threads", QFieldType.INTEGER).withLabel("Job Worker Threads"))
         .withField(field(SCHEDULER_ACTIVE, "scheduler_active", QFieldType.BOOLEAN).withLabel("Runs Schedules"))
         .withField(field(ESB_TRIGGERS_ACTIVE, "esb_triggers_active", QFieldType.BOOLEAN).withLabel("Runs ESB Triggers"))
         .withField(field(ASYNC_JOBS_RUNNING, "async_jobs_running", QFieldType.INTEGER).withLabel("Steps Running"))
         .withField(field(HEAP_USED_MB, "heap_used_mb", QFieldType.INTEGER).withLabel("Memory Used (MB)").withDisplayFormat(DisplayFormat.COMMAS))
         .withField(field(HEAP_MAX_MB, "heap_max_mb", QFieldType.INTEGER).withLabel("Memory Limit (MB)").withDisplayFormat(DisplayFormat.COMMAS))
         .withField(field(DRAIN_REQUESTED, "drain_requested", QFieldType.BOOLEAN).withLabel("Drain Requested"))
         .withField(field(DRAIN_REQUESTED_BY, "drain_requested_by", QFieldType.STRING).withLabel("Drain Requested By").withMaxLength(250))
         .withField(field(DRAIN_REASON, "drain_reason", QFieldType.STRING).withLabel("Drain Reason").withMaxLength(500))
         .withSection(new QFieldSection("identity", "Node", new QIcon("dns"), Tier.T1, List.of(ID, NODE_ID, STATUS, ROLES, HEARTBEAT_AT)))
         .withSection(new QFieldSection("placement", "Where It Runs", new QIcon("place"), Tier.T2, List.of(POD_NAME, K8S_NODE_NAME, ZONE, DEPLOYMENT, VERSION)))
         .withSection(new QFieldSection("work", "Work and Memory", new QIcon("memory"), Tier.T2,
            List.of(WORKER_THREADS, SCHEDULER_ACTIVE, ESB_TRIGGERS_ACTIVE, ASYNC_JOBS_RUNNING, HEAP_USED_MB, HEAP_MAX_MB)))
         .withSection(new QFieldSection("drain", "Drain", new QIcon("logout"), Tier.T2, List.of(DRAIN_REQUESTED, DRAIN_REQUESTED_BY, DRAIN_REASON)))
         .withSection(dates(STARTED_AT));
      table.getFields().values().forEach(field -> field.setIsEditable(false));
      table.withoutCapabilities(Capability.TABLE_INSERT, Capability.TABLE_UPDATE);
      return (table);
   }



   /*******************************************************************************
    ** cluster_lease
    *******************************************************************************/
   public static QTableMetaData leaseTable(ClusterQBitConfig config)
   {
      QTableMetaData table = base(config, LEASE, "Leases", "key")
         .withRecordLabelFormat("%s")
         .withRecordLabelFields(NAME)
         .withUniqueKey(new UniqueKey(NAME))
         .withField(field(NAME, "name", QFieldType.STRING).withLabel("Name").withMaxLength(100).withIsRequired(true).withIsEditable(false))
         .withField(field(HOLDER_NODE_ID, "holder_node_id", QFieldType.STRING).withLabel("Held By").withMaxLength(250))
         .withField(field(EXPIRES_AT, "expires_at", QFieldType.DATE_TIME).withLabel("Expires"))
         .withField(field(LEASE_VERSION, "version", QFieldType.INTEGER).withLabel("Version").withIsEditable(false))
         .withField(field(ACQUIRED_AT, "acquired_at", QFieldType.DATE_TIME).withLabel("Held Since").withIsEditable(false))
         .withSection(new QFieldSection("identity", "Lease", new QIcon("key"), Tier.T1, List.of(ID, NAME, HOLDER_NODE_ID, EXPIRES_AT)))
         .withSection(dates(ACQUIRED_AT, LEASE_VERSION));
      table.withoutCapabilities(Capability.TABLE_INSERT);
      return (table);
   }



   /*******************************************************************************
    ** cluster_setting
    *******************************************************************************/
   public static QTableMetaData settingTable(ClusterQBitConfig config)
   {
      QTableMetaData table = base(config, SETTING, "Settings", "tune")
         .withRecordLabelFormat("%s")
         .withRecordLabelFields(SETTING_KEY)
         .withUniqueKey(new UniqueKey(SETTING_KEY))
         .withField(field(SETTING_KEY, "setting_key", QFieldType.STRING).withLabel("Setting").withMaxLength(100).withIsRequired(true))
         .withField(field(SETTING_VALUE, "setting_value", QFieldType.STRING).withLabel("Value").withMaxLength(250).withIsRequired(true))
         .withField(field(DESCRIPTION, "description", QFieldType.STRING).withLabel("Description").withMaxLength(500))
         .withSection(new QFieldSection("identity", "Setting", new QIcon("tune"), Tier.T1, List.of(ID, SETTING_KEY, SETTING_VALUE, DESCRIPTION)))
         .withSection(dates());
      QCodeReference validator = new QCodeReference(ClusterSettingValidator.class);
      table.withCustomizer(TableCustomizers.PRE_INSERT_RECORD, validator);
      table.withCustomizer(TableCustomizers.PRE_UPDATE_RECORD, validator);
      return (table);
   }



   /*******************************************************************************
    ** The id, create and modify date fields and the table basics.
    *******************************************************************************/
   private static QTableMetaData base(ClusterQBitConfig config, String name, String label, String icon)
   {
      return (new QTableMetaData()
         .withName(name)
         .withLabel(label)
         .withIcon(new QIcon(icon))
         .withBackendName(config.getBackendName())
         .withPrimaryKeyField(ID)
         .withPermissionRules(tablePermissionRules(config))
         .withField(field(ID, "id", QFieldType.INTEGER).withLabel("Id").withIsEditable(false))
         .withField(field(CREATE_DATE, "create_date", QFieldType.DATE_TIME).withLabel("Create Date").withIsEditable(false))
         .withField(field(MODIFY_DATE, "modify_date", QFieldType.DATE_TIME).withLabel("Modify Date").withIsEditable(false)));
   }



   /*******************************************************************************
    ** A field with its column name.
    *******************************************************************************/
   private static QFieldMetaData field(String name, String column, QFieldType type)
   {
      return (new QFieldMetaData(name, type).withBackendName(column));
   }



   /*******************************************************************************
    ** The dates section (with any extra fields first).
    *******************************************************************************/
   private static QFieldSection dates(String... extraFields)
   {
      List<String> fields = new java.util.ArrayList<>(List.of(extraFields));
      fields.add(CREATE_DATE);
      fields.add(MODIFY_DATE);
      return (new QFieldSection("dates", "Dates", new QIcon("calendar_month"), Tier.T3, fields));
   }
}
