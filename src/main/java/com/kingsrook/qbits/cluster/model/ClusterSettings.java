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


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The cluster's settings (cluster_setting rows), with the defaults used when a
 ** row is missing or its value is not valid. The defaults are the same values
 ** the shipped defaults changelog seeds.
 **
 ** @param heartbeatSeconds how often each node writes its heartbeat
 ** @param lostAfterSeconds a node whose heartbeat is older than this is LOST
 ** @param retainStoppedDays STOPPED and LOST rows older than this are deleted
 ** @param alertOnLost whether a lost node raises the cluster.node.lost alert
 *******************************************************************************/
public record ClusterSettings(int heartbeatSeconds, int lostAfterSeconds, int retainStoppedDays, boolean alertOnLost)
{
   public static final String HEARTBEAT_SECONDS   = "heartbeat_seconds";
   public static final String LOST_AFTER_SECONDS  = "lost_after_seconds";
   public static final String RETAIN_STOPPED_DAYS = "retain_stopped_days";
   public static final String ALERT_ON_LOST       = "alert_on_lost";

   /** each known key, with its default value and its description (as seeded). */
   public static final Map<String, List<String>> DEFAULTS = defaults();

   private static final QLogger LOG = QLogger.getLogger(ClusterSettings.class);



   /*******************************************************************************
    ** The built-in defaults.
    *******************************************************************************/
   public static ClusterSettings defaultSettings()
   {
      return (new ClusterSettings(15, 60, 7, true));
   }



   /*******************************************************************************
    ** Read the settings (the caller supplies a session that may read the table).
    ** A missing or invalid row keeps its default.
    *******************************************************************************/
   public static ClusterSettings load() throws QException
   {
      Map<String, String> values = new LinkedHashMap<>();
      for(QRecord record : new QueryAction().execute(new QueryInput(ClusterTables.SETTING)).getRecords())
      {
         values.put(record.getValueString(ClusterTables.SETTING_KEY), record.getValueString(ClusterTables.SETTING_VALUE));
      }
      return (from(values));
   }



   /*******************************************************************************
    ** Settings from key/value pairs.
    *******************************************************************************/
   public static ClusterSettings from(Map<String, String> values)
   {
      ClusterSettings defaults  = defaultSettings();
      int             heartbeat = positive(values, HEARTBEAT_SECONDS, defaults.heartbeatSeconds());
      int             lostAfter = positive(values, LOST_AFTER_SECONDS, defaults.lostAfterSeconds());
      if(lostAfter <= heartbeat)
      {
         LOG.warn("Cluster setting lost_after_seconds must be greater than heartbeat_seconds; using three heartbeats", logPair("heartbeatSeconds", heartbeat), logPair("lostAfterSeconds", lostAfter));
         lostAfter = heartbeat * 3;
      }
      int     retain = positive(values, RETAIN_STOPPED_DAYS, defaults.retainStoppedDays());
      Boolean alert  = parseBoolean(values.get(ALERT_ON_LOST));
      return (new ClusterSettings(heartbeat, lostAfter, retain, alert == null ? defaults.alertOnLost() : alert));
   }



   /*******************************************************************************
    ** Why a value is not valid for a key (null when it is, or the key is not a
    ** known one).
    *******************************************************************************/
   public static String validate(String key, String value)
   {
      if(key == null || !DEFAULTS.containsKey(key))
      {
         return (null);
      }
      if(ALERT_ON_LOST.equals(key))
      {
         return (parseBoolean(value) == null ? "The value of " + key + " must be true or false." : null);
      }
      Integer number = parseInteger(value);
      return (number == null || number < 1 ? "The value of " + key + " must be a whole number of at least 1." : null);
   }



   /*******************************************************************************
    ** A positive integer setting, or the default.
    *******************************************************************************/
   private static int positive(Map<String, String> values, String key, int defaultValue)
   {
      String value = values.get(key);
      if(value == null)
      {
         return (defaultValue);
      }
      Integer number = parseInteger(value);
      if(number == null || number < 1)
      {
         LOG.warn("Ignoring a cluster setting that is not a positive whole number", logPair("key", key), logPair("value", value));
         return (defaultValue);
      }
      return (number);
   }



   /*******************************************************************************
    ** Parse an integer, or null.
    *******************************************************************************/
   private static Integer parseInteger(String value)
   {
      try
      {
         return (value == null ? null : Integer.valueOf(value.trim()));
      }
      catch(NumberFormatException e)
      {
         return (null);
      }
   }



   /*******************************************************************************
    ** Parse true / false (any case), or null.
    *******************************************************************************/
   private static Boolean parseBoolean(String value)
   {
      if(value == null)
      {
         return (null);
      }
      String trimmed = value.trim();
      if("true".equalsIgnoreCase(trimmed))
      {
         return (true);
      }
      return ("false".equalsIgnoreCase(trimmed) ? false : null);
   }



   /*******************************************************************************
    ** The seeded keys, values and descriptions (in seed order).
    *******************************************************************************/
   private static Map<String, List<String>> defaults()
   {
      Map<String, List<String>> map = new LinkedHashMap<>();
      map.put(HEARTBEAT_SECONDS, List.of("15", "How often, in seconds, each node records that it is alive."));
      map.put(LOST_AFTER_SECONDS, List.of("60", "A node with no heartbeat for this many seconds is marked Lost."));
      map.put(RETAIN_STOPPED_DAYS, List.of("7", "Days to keep the rows of stopped and lost nodes before they are deleted."));
      map.put(ALERT_ON_LOST, List.of("true", "Whether a lost node raises an alert (true or false)."));
      return (java.util.Collections.unmodifiableMap(map));
   }
}
