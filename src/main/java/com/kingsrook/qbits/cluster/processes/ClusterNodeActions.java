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


package com.kingsrook.qbits.cluster.processes;


import java.util.List;
import com.kingsrook.qbits.cluster.ClusterQBitConfig;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qqq.backend.core.actions.permissions.UseOtherPermissionNameCustomPermissionChecker;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** The cluster_node record actions (never in the navigation): Drain node and
 ** Cancel drain. Each is a standard streamed ETL process on cluster_node with
 ** selected records in, a Review screen (the confirmation) and a Result
 ** screen. Both need the configured drain permission (cluster.drain).
 *******************************************************************************/
public final class ClusterNodeActions
{
   public static final String DRAIN_NODE   = "clusterDrainNode";
   public static final String CANCEL_DRAIN = "clusterCancelDrain";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ClusterNodeActions()
   {
   }



   /*******************************************************************************
    ** Both processes.
    *******************************************************************************/
   public static List<QProcessMetaData> processes(ClusterQBitConfig config)
   {
      QProcessMetaData drain = action(config, DRAIN_NODE, "Drain node", "logout", ClusterNodeActionSteps.DrainTransform.class, ClusterNodeActionSteps.DrainLoad.class,
         "This node will stop taking new requests and work, finish what it is doing, and keep running until it is stopped or restarted.");
      drain.withStep(0, new QFrontendStepMetaData()
         .withName("input")
         .withLabel("Drain node")
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM))
         .withFormFields(List.of(new QFieldMetaData(ClusterNodeActionSteps.FIELD_REASON, QFieldType.STRING).withLabel("Reason").withMaxLength(500))));

      QProcessMetaData cancel = action(config, CANCEL_DRAIN, "Cancel drain", "login", ClusterNodeActionSteps.CancelTransform.class, ClusterNodeActionSteps.CancelLoad.class,
         "The drain request will be withdrawn before the node acts on it.");
      return (List.of(drain, cancel));
   }



   /*******************************************************************************
    ** One record action.
    *******************************************************************************/
   private static QProcessMetaData action(ClusterQBitConfig config, String name, String label, String icon, Class<? extends AbstractTransformStep> transform,
      Class<? extends AbstractLoadStep> load, String previewMessage)
   {
      QProcessMetaData process = StreamedETLWithFrontendProcess.processMetaDataBuilder()
         .withName(name)
         .withLabel(label)
         .withIcon(new QIcon(icon))
         .withTableName(ClusterTables.NODE)
         .withSourceTable(ClusterTables.NODE)
         .withDestinationTable(ClusterTables.NODE)
         .withExtractStepClass(ExtractViaQueryStep.class)
         .withTransformStepClass(transform)
         .withLoadStepClass(load)
         .withSupportsFullValidation(true)
         .withDoFullValidation(true)
         .withMinInputRecords(1)
         .withPreviewMessage(previewMessage)
         .getProcessMetaData();
      process.setPermissionRules(new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION)
         .withCustomPermissionChecker(UseOtherPermissionNameCustomPermissionChecker.build(config.getDrainPermissionName())));
      return (process);
   }
}
