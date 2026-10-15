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


import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cluster.model.ClusterSettings;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qbits.cluster.runtime.ClusterNodeInfo;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntime;
import com.kingsrook.qbits.cluster.runtime.ClusterSweeper;
import com.kingsrook.qqq.backend.core.cluster.QNodeLifecycle;
import com.kingsrook.qqq.backend.core.cluster.QNodeRoles;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The shipped changelogs through real Liquibase on H2 (PostgreSQL mode), then
 ** the runtime and the lease compare-and-set as real SQL through the RDBMS
 ** module against the tables they created (so the DDL and the metadata agree).
 *******************************************************************************/
class ClusterChangelogTest
{
   static final String SCHEMA   = "db/liquibase/qbit-cluster/cluster.changelog.sql";
   static final String DEFAULTS = "db/liquibase/qbit-cluster/cluster-defaults.changelog.sql";



   /*******************************************************************************
    ** Clear statics.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      QNodeLifecycle.resetForTests();
      QNodeRoles.set(null);
      QContext.clear();
   }



   /*******************************************************************************
    ** Schema then defaults, twice: tables, the seeded settings (equal to the
    ** runtime defaults); a second update runs nothing; an edit survives.
    *******************************************************************************/
   @Test
   void schemaAndDefaultsAreIdempotentAndInsertOnly() throws Exception
   {
      try(Connection connection = DriverManager.getConnection(url("seed")))
      {
         update(connection, SCHEMA);
         update(connection, DEFAULTS);
         for(Map.Entry<String, List<String>> setting : ClusterSettings.DEFAULTS.entrySet())
         {
            assertThat(scalar(connection, "SELECT setting_value FROM cluster_setting WHERE setting_key = '" + setting.getKey() + "'")).isEqualTo(setting.getValue().get(0));
            assertThat(scalar(connection, "SELECT description FROM cluster_setting WHERE setting_key = '" + setting.getKey() + "'")).isEqualTo(setting.getValue().get(1));
         }
         try(Statement statement = connection.createStatement())
         {
            statement.execute("UPDATE cluster_setting SET setting_value = '30' WHERE setting_key = 'heartbeat_seconds'");
            statement.execute("DELETE FROM cluster_setting WHERE setting_key = 'alert_on_lost'");
         }
         update(connection, SCHEMA);
         update(connection, DEFAULTS);
         assertThat(scalar(connection, "SELECT setting_value FROM cluster_setting WHERE setting_key = 'heartbeat_seconds'")).isEqualTo("30");
         assertThat(scalar(connection, "SELECT COUNT(*) FROM cluster_setting")).isEqualTo("3");
         assertThat(scalar(connection, "SELECT COUNT(*) FROM databasechangelog")).isEqualTo("8");
         assertThat(scalar(connection, "SELECT COUNT(*) FROM cluster_node")).isEqualTo("0");
         assertThat(scalar(connection, "SELECT COUNT(*) FROM cluster_lease")).isEqualTo("0");
      }
   }



   /*******************************************************************************
    ** Defaults on a database without the tables mark their changesets ran.
    *******************************************************************************/
   @Test
   void defaultsWithoutTablesMarkRan() throws Exception
   {
      try(Connection connection = DriverManager.getConnection(url("empty")))
      {
         update(connection, DEFAULTS);
         assertThat(scalar(connection, "SELECT COUNT(*) FROM databasechangelog WHERE exectype = 'MARK_RAN'")).isEqualTo("4");
      }
   }



   /*******************************************************************************
    ** Every defaults changeset is guarded, insert-only, with an empty rollback;
    ** the schema has no foreign keys and nothing a non-owner role cannot run.
    *******************************************************************************/
   @Test
   void changelogRules() throws Exception
   {
      String defaults = text(DEFAULTS);
      for(String part : defaults.split("(?m)^(?=-- changeset )"))
      {
         if(part.startsWith("-- changeset "))
         {
            assertThat(part).contains("-- preconditions onFail:MARK_RAN onError:MARK_RAN").contains("NOT EXISTS").contains("-- rollback empty").doesNotContain("UPDATE ");
         }
      }
      String schema = statements(text(SCHEMA)).toString().toUpperCase();
      assertThat(schema).doesNotContain("FOREIGN KEY", "REFERENCES", "EXTENSION", "GRANT ", "OWNER TO", "SUPERUSER");
      assertThat(schema).contains("CREATE TABLE IF NOT EXISTS CLUSTER_NODE", "CREATE TABLE IF NOT EXISTS CLUSTER_LEASE", "CREATE TABLE IF NOT EXISTS CLUSTER_SETTING");
   }



   /*******************************************************************************
    ** The runtime, settings, sweep and leases on the RDBMS module (real SQL)
    ** against the changelog's tables.
    *******************************************************************************/
   @Test
   void runtimeAndLeasesOnRealSql() throws Exception
   {
      String database = "rdbms" + System.nanoTime();
      try(Connection connection = DriverManager.getConnection(lowerCaseUrl(database), "sa", ""))
      {
         try(Statement statement = connection.createStatement())
         {
            for(String sql : statements(text(SCHEMA)))
            {
               statement.execute(sql);
            }
            for(String sql : statements(text(DEFAULTS)))
            {
               statement.execute(sql);
            }
         }

         RDBMSBackendMetaData backend = new RDBMSBackendMetaData().withName("rdbms").withVendor("h2").withHostName("mem").withDatabaseName(database).withUsername("sa");
         backend.setJdbcUrl(lowerCaseUrl(database));
         QInstance qInstance = new QInstance();
         qInstance.addBackend(backend);
         qInstance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(QAuthenticationType.MOCK));
         ClusterQBitConfig config = new ClusterQBitConfig().withBackendName("rdbms");
         new ClusterQBitProducer().withQBitConfig(config).produce(qInstance);
         new QInstanceEnricher(qInstance).enrich();
         QContext.init(qInstance, new QSystemUserSession(qInstance));
         QNodeRoles.set(new QNodeRoles.Settings(EnumSet.of(QNodeRoles.Role.WEB), true, false, false, 0, "sql-node"));

         ClusterRuntime runtime = new ClusterRuntime(qInstance, new ClusterNodeInfo(config, Map.of(ClusterNodeInfo.ENV_POD_NAME, "pod-1", ClusterNodeInfo.ENV_K8S_NODE, "wk-21")::get));
         runtime.initialize();
         assertThat(runtime.getSettings()).isEqualTo(ClusterSettings.defaultSettings());
         assertThat(scalar(connection, "SELECT status FROM cluster_node WHERE node_id = 'sql-node'")).isEqualTo("UP");
         assertThat(scalar(connection, "SELECT k8s_node_name FROM cluster_node WHERE node_id = 'sql-node'")).isEqualTo("wk-21");
         assertThat(scalar(connection, "SELECT roles FROM cluster_node WHERE node_id = 'sql-node'")).isEqualTo("WEB");

         try(Statement statement = connection.createStatement())
         {
            statement.execute("INSERT INTO cluster_node (node_id, status, roles, heartbeat_at) VALUES ('silent', 'UP', 'WORKER', DATEADD('MINUTE', -10, CURRENT_TIMESTAMP))");
         }
         runtime.tick();
         assertThat(scalar(connection, "SELECT status FROM cluster_node WHERE node_id = 'silent'")).isEqualTo(NodeStatus.LOST.name());
         assertThat(scalar(connection, "SELECT holder_node_id FROM cluster_lease WHERE name = '" + ClusterSweeper.LEASE_NAME + "'")).isEqualTo("sql-node");

         ClusterLeases.Holder other = ClusterLeases.as("other-node");
         assertThat(other.tryAcquire(ClusterSweeper.LEASE_NAME, Duration.ofSeconds(60))).isFalse();
         try(Statement statement = connection.createStatement())
         {
            statement.execute("UPDATE cluster_lease SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)");
         }
         assertThat(other.tryAcquire(ClusterSweeper.LEASE_NAME, Duration.ofSeconds(60))).isTrue();
         assertThat(ClusterLeases.isLeader(ClusterSweeper.LEASE_NAME)).isFalse();
         assertThat(scalar(connection, "SELECT version FROM cluster_lease")).isEqualTo("2");

         runtime.stop();
         assertThat(scalar(connection, "SELECT status FROM cluster_node WHERE node_id = 'sql-node'")).isEqualTo(NodeStatus.STOPPED.name());
      }
   }



   /*******************************************************************************
    ** An in-memory H2 in PostgreSQL mode for Liquibase, kept until the JVM ends.
    *******************************************************************************/
   private static String url(String name)
   {
      return ("jdbc:h2:mem:" + name + System.nanoTime() + ";MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
   }



   /*******************************************************************************
    ** An in-memory H2 in PostgreSQL mode with lower-case names, as PostgreSQL
    ** folds them (the RDBMS module quotes identifiers).
    *******************************************************************************/
   private static String lowerCaseUrl(String name)
   {
      return ("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
   }



   /*******************************************************************************
    ** The SQL statements of a formatted-SQL changelog (comments dropped).
    *******************************************************************************/
   private static List<String> statements(String changelog)
   {
      List<String>  statements = new java.util.ArrayList<>();
      StringBuilder current    = new StringBuilder();
      for(String line : changelog.split("\n"))
      {
         if(line.trim().startsWith("--") || line.isBlank())
         {
            continue;
         }
         current.append(line).append('\n');
         if(line.trim().endsWith(";"))
         {
            statements.add(current.toString().trim().replaceAll(";$", ""));
            current.setLength(0);
         }
      }
      return (statements);
   }



   /*******************************************************************************
    ** Run a changelog with real Liquibase (not closed: that would close the
    ** test connection).
    *******************************************************************************/
   private static void update(Connection connection, String changelog) throws Exception
   {
      Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
      new Liquibase(changelog, new ClassLoaderResourceAccessor(), database).update("");
      connection.commit();
   }



   /*******************************************************************************
    ** One value as text.
    *******************************************************************************/
   private static String scalar(Connection connection, String sql) throws Exception
   {
      try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
      {
         return (resultSet.next() ? resultSet.getString(1) : null);
      }
   }



   /*******************************************************************************
    ** A classpath resource as text.
    *******************************************************************************/
   private static String text(String resource) throws Exception
   {
      try(InputStream stream = ClusterChangelogTest.class.getClassLoader().getResourceAsStream(resource))
      {
         assertThat(stream).as(resource).isNotNull();
         return (new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
   }
}
