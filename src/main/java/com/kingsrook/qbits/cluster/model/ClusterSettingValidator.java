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


import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.statusmessages.BadInputStatusMessage;


/*******************************************************************************
 ** Rejects a cluster_setting value that the runtime could not use (for example
 ** heartbeat_seconds = "soon"), so a typo is caught on save rather than being
 ** silently replaced by the default. Unknown keys are allowed.
 *******************************************************************************/
public class ClusterSettingValidator implements TableCustomizerInterface
{

   /*******************************************************************************
    ** Validate inserts and updates (an update may carry only the value; the key
    ** then comes from the stored row).
    *******************************************************************************/
   @Override
   public List<QRecord> preInsertOrUpdate(AbstractActionInput input, List<QRecord> records, boolean isPreview, Optional<List<QRecord>> oldRecordList)
   {
      Map<Serializable, QRecord> oldById = new HashMap<>();
      oldRecordList.ifPresent(list -> list.forEach(old -> oldById.put(old.getValue(ClusterTables.ID), old)));

      for(QRecord record : records)
      {
         QRecord old   = oldById.get(record.getValue(ClusterTables.ID));
         String  key   = record.getValues().containsKey(ClusterTables.SETTING_KEY) ? record.getValueString(ClusterTables.SETTING_KEY) : old == null ? null : old.getValueString(ClusterTables.SETTING_KEY);
         String  value = record.getValues().containsKey(ClusterTables.SETTING_VALUE) ? record.getValueString(ClusterTables.SETTING_VALUE) : old == null ? null : old.getValueString(ClusterTables.SETTING_VALUE);
         String  error = ClusterSettings.validate(key, value);
         if(error != null)
         {
            record.addError(new BadInputStatusMessage(error));
         }
      }
      return (records);
   }
}
