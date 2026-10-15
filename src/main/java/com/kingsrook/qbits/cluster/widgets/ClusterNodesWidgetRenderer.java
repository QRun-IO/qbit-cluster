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


package com.kingsrook.qbits.cluster.widgets;


import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.actions.tables.ConditionalUpdateAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.cluster.QNodeRoles;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** The Nodes widget (a standard TABLE widget, full width): every node that is
 ** up, draining or lost, grouped by role (a node with several roles appears
 ** under each), with its status and how long ago it last sent a heartbeat.
 ** Stopped nodes are left out; they stay in the Nodes table until purged.
 ** Reads in the viewer's own session (the widget needs the cluster read
 ** permission, which also reads the table).
 *******************************************************************************/
public class ClusterNodesWidgetRenderer extends AbstractWidgetRenderer
{
   public static final String NAME = "clusterNodes";

   static final String ROLE      = "role";
   static final String NODE      = "node";
   static final String STATUS    = "status";
   static final String HEARTBEAT = "heartbeat";
   static final String WHERE     = "where";
   static final String VERSION   = "version";



   /*******************************************************************************
    ** Render the table.
    *******************************************************************************/
   @Override
   public RenderWidgetOutput render(RenderWidgetInput input) throws QException
   {
      List<QRecord> nodes = new QueryAction().execute(new QueryInput(ClusterTables.NODE)
         .withFilter(new QQueryFilter(new QFilterCriteria(ClusterTables.STATUS, QCriteriaOperator.NOT_EQUALS, NodeStatus.STOPPED.name()))
            .withOrderBy(new QFilterOrderBy(ClusterTables.NODE_ID)))).getRecords();
      Instant now = new ConditionalUpdateAction().databaseNow(ClusterTables.NODE);

      List<Map<String, Object>> rows = new ArrayList<>();
      for(QNodeRoles.Role role : QNodeRoles.Role.values())
      {
         for(QRecord node : nodes)
         {
            if(roles(node).contains(role.name()))
            {
               rows.add(row(role, node, now));
            }
         }
      }

      TableData table = new TableData(null, List.of(
         column("Role", ROLE), column("Node", NODE), column("Status", STATUS), column("Last heartbeat", HEARTBEAT), column("Where", WHERE), column("Version", VERSION)), rows)
         .withNoRowsFoundHTML("No nodes are running. A node appears here once it starts with the Cluster qbit.")
         .withRowsPerPage(50)
         .withHidePaginationDropdown(true);
      return (new RenderWidgetOutput(table));
   }



   /*******************************************************************************
    ** One row.
    *******************************************************************************/
   static Map<String, Object> row(QNodeRoles.Role role, QRecord node, Instant now)
   {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put(ROLE, roleLabel(role));
      row.put(NODE, node.getValueString(ClusterTables.NODE_ID));
      row.put(STATUS, statusText(node));
      row.put(HEARTBEAT, age(node.getValueInstant(ClusterTables.HEARTBEAT_AT), now));
      row.put(WHERE, Stream.of(node.getValueString(ClusterTables.K8S_NODE_NAME), node.getValueString(ClusterTables.ZONE), node.getValueString(ClusterTables.DEPLOYMENT))
         .filter(StringUtils::hasContent).collect(Collectors.joining(", ")));
      row.put(VERSION, node.getValueString(ClusterTables.VERSION) == null ? "" : node.getValueString(ClusterTables.VERSION));
      return (row);
   }



   /*******************************************************************************
    ** "Up", "Draining", "Up, drain requested", "Lost".
    *******************************************************************************/
   static String statusText(QRecord node)
   {
      String status = node.getValueString(ClusterTables.STATUS);
      String label  = status;
      for(NodeStatus value : NodeStatus.values())
      {
         if(value.name().equals(status))
         {
            label = value.getPossibleValueLabel();
         }
      }
      if(NodeStatus.UP.name().equals(status) && Boolean.TRUE.equals(node.getValueBoolean(ClusterTables.DRAIN_REQUESTED)))
      {
         label += ", drain requested";
      }
      return (label == null ? "" : label);
   }



   /*******************************************************************************
    ** "12 seconds ago", "3 minutes ago", "2 hours ago", "4 days ago".
    *******************************************************************************/
   static String age(Instant at, Instant now)
   {
      if(at == null)
      {
         return ("never");
      }
      long seconds = Math.max(0, Duration.between(at, now).toSeconds());
      if(seconds < 120)
      {
         return (plural(seconds, "second"));
      }
      if(seconds < 7200)
      {
         return (plural(seconds / 60, "minute"));
      }
      if(seconds < 172800)
      {
         return (plural(seconds / 3600, "hour"));
      }
      return (plural(seconds / 86400, "day"));
   }



   /*******************************************************************************
    ** "1 second ago", "5 seconds ago".
    *******************************************************************************/
   private static String plural(long count, String unit)
   {
      return (count + " " + unit + (count == 1 ? "" : "s") + " ago");
   }



   /*******************************************************************************
    ** "Web", "Worker", "Scheduler", "ESB".
    *******************************************************************************/
   static String roleLabel(QNodeRoles.Role role)
   {
      return (role == QNodeRoles.Role.ESB ? "ESB" : role.name().charAt(0) + role.name().substring(1).toLowerCase(Locale.ROOT));
   }



   /*******************************************************************************
    ** The node's role names.
    *******************************************************************************/
   private static Set<String> roles(QRecord node)
   {
      String roles = node.getValueString(ClusterTables.ROLES);
      if(roles == null)
      {
         return (Set.of());
      }
      return (Stream.of(roles.split(",")).map(String::trim).map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toSet()));
   }



   /*******************************************************************************
    ** A flexible-width text column.
    *******************************************************************************/
   private static TableData.Column column(String header, String accessor)
   {
      return (new TableData.Column("default", header, accessor, null, "left"));
   }
}
