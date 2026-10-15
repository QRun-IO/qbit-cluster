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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cluster.health.ClusterHeartbeatHealthCheck;
import com.kingsrook.qbits.cluster.model.ClusterSettings;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qbits.cluster.runtime.ClusterRuntime;
import com.kingsrook.qbits.cluster.widgets.ClusterNodesWidgetRenderer;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Settings (defaults, table values, validation), the Nodes widget and the
 ** heartbeat health check.
 *******************************************************************************/
class ClusterSettingsWidgetHealthTest extends BaseClusterTest
{

   /*******************************************************************************
    ** Defaults, values from the table, and bad values falling back.
    *******************************************************************************/
   @Test
   void settings() throws Exception
   {
      assertThat(ClusterSettings.load()).isEqualTo(ClusterSettings.defaultSettings());
      assertThat(ClusterSettings.defaultSettings()).isEqualTo(new ClusterSettings(15, 60, 7, true));
      ClusterSettings.DEFAULTS.forEach((key, valueAndDescription) -> assertThat(ClusterSettings.validate(key, valueAndDescription.get(0))).isNull());

      insert(ClusterTables.SETTING, new QRecord().withValue(ClusterTables.SETTING_KEY, ClusterSettings.HEARTBEAT_SECONDS).withValue(ClusterTables.SETTING_VALUE, "5"));
      insert(ClusterTables.SETTING, new QRecord().withValue(ClusterTables.SETTING_KEY, ClusterSettings.ALERT_ON_LOST).withValue(ClusterTables.SETTING_VALUE, "FALSE"));
      assertThat(ClusterSettings.load()).isEqualTo(new ClusterSettings(5, 60, 7, false));

      assertThat(ClusterSettings.from(Map.of(ClusterSettings.HEARTBEAT_SECONDS, "30", ClusterSettings.LOST_AFTER_SECONDS, "20")).lostAfterSeconds()).isEqualTo(90);
      assertThat(ClusterSettings.from(Map.of(ClusterSettings.RETAIN_STOPPED_DAYS, "x", ClusterSettings.ALERT_ON_LOST, "maybe"))).isEqualTo(ClusterSettings.defaultSettings());
      assertThat(ClusterSettings.from(Map.of(ClusterSettings.HEARTBEAT_SECONDS, "0")).heartbeatSeconds()).isEqualTo(15);
   }



   /*******************************************************************************
    ** The table rejects values the runtime could not use, on insert and update.
    *******************************************************************************/
   @Test
   void settingValuesAreValidated() throws Exception
   {
      QRecord bad = new InsertAction().execute(new InsertInput(ClusterTables.SETTING).withRecord(new QRecord()
         .withValue(ClusterTables.SETTING_KEY, ClusterSettings.LOST_AFTER_SECONDS).withValue(ClusterTables.SETTING_VALUE, "soon"))).getRecords().get(0);
      assertThat(bad.getErrors()).isNotEmpty();
      assertThat(all(ClusterTables.SETTING)).isEmpty();

      QRecord custom = insert(ClusterTables.SETTING, new QRecord().withValue(ClusterTables.SETTING_KEY, "my_note").withValue(ClusterTables.SETTING_VALUE, "anything"));
      assertThat(custom.getErrors()).isNullOrEmpty();

      QRecord good = insert(ClusterTables.SETTING, new QRecord().withValue(ClusterTables.SETTING_KEY, ClusterSettings.ALERT_ON_LOST).withValue(ClusterTables.SETTING_VALUE, "true"));
      QRecord updated = new UpdateAction().execute(new UpdateInput(ClusterTables.SETTING).withRecord(new QRecord()
         .withValue(ClusterTables.ID, good.getValue(ClusterTables.ID)).withValue(ClusterTables.SETTING_VALUE, "yes"))).getRecords().get(0);
      assertThat(updated.getErrors()).isNotEmpty();
   }



   /*******************************************************************************
    ** The widget lists live nodes by role (a node under each of its roles),
    ** leaves stopped ones out, and shows the heartbeat age.
    *******************************************************************************/
   @Test
   void widgetGroupsNodesByRole() throws Exception
   {
      Instant now = Instant.now();
      otherNode("web-1", NodeStatus.UP.name(), now.minus(5, ChronoUnit.SECONDS), "WEB");
      otherNode("worker-1", NodeStatus.UP.name(), now.minus(3, ChronoUnit.MINUTES), "WORKER,SCHEDULER,ESB");
      otherNode("gone", NodeStatus.STOPPED.name(), now, "WEB");
      QRecord lost = otherNode("lost-1", NodeStatus.LOST.name(), now.minus(5, ChronoUnit.HOURS), "WEB");
      setFields(ClusterTables.NODE, otherNode("web-2", NodeStatus.UP.name(), now, "WEB").getValue(ClusterTables.ID), Map.of(ClusterTables.DRAIN_REQUESTED, true));

      login("Viewer", "cluster.read");
      TableData table = (TableData) new ClusterNodesWidgetRenderer().render(new RenderWidgetInput()).getWidgetData();
      List<Map<String, Object>> rows = table.getRows();
      assertThat(rows).extracting(row -> row.get("role") + "/" + row.get("node"))
         .containsExactly("Web/lost-1", "Web/web-1", "Web/web-2", "Worker/worker-1", "Scheduler/worker-1", "ESB/worker-1");
      assertThat(rows.get(0).get("status")).isEqualTo("Lost");
      assertThat(rows.get(0).get("heartbeat")).isEqualTo("5 hours ago");
      assertThat(rows.get(2).get("status")).isEqualTo("Up, drain requested");
      assertThat(rows.get(3).get("heartbeat")).isEqualTo("3 minutes ago");
      assertThat(table.getColumns()).hasSize(6);
      assertThat(lost).isNotNull();

      assertThat(ClusterNodesWidgetRenderer.class).isNotNull();
   }



   /*******************************************************************************
    ** The health check: unknown without a runtime, up when current, degraded
    ** when late, down when lost or missing.
    *******************************************************************************/
   @Test
   void healthCheck() throws Exception
   {
      ClusterHeartbeatHealthCheck check = new ClusterHeartbeatHealthCheck();
      HealthCheckInput            input = new HealthCheckInput().withQInstance(qInstance).withDeadline(Instant.now().plusSeconds(30));
      assertThat(check.run(input).getStatus().name()).isEqualTo("UNKNOWN");

      ClusterRuntime runtime = runtime();
      assertThat(status(check.run(input))).isEqualTo("UP");

      Object id = node(NODE_A).getValue(ClusterTables.ID);
      setFields(ClusterTables.NODE, (java.io.Serializable) id, Map.of(ClusterTables.HEARTBEAT_AT, Instant.now().minusSeconds(45)));
      assertThat(status(check.run(input))).isEqualTo("DEGRADED");

      setFields(ClusterTables.NODE, (java.io.Serializable) id, Map.of(ClusterTables.HEARTBEAT_AT, Instant.now().minusSeconds(600)));
      assertThat(status(check.run(input))).isEqualTo("DOWN");

      runtime.tick();
      assertThat(status(check.run(input))).isEqualTo("UP");
      runtime.stop();
   }



   /*******************************************************************************
    ** An outcome's status name.
    *******************************************************************************/
   private static String status(HealthCheckOutcome outcome)
   {
      return (outcome.getStatus().name());
   }
}
