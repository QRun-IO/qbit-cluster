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


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.cluster.model.ClusterTables;
import com.kingsrook.qbits.cluster.model.NodeStatus;
import com.kingsrook.qbits.cluster.runtime.ClusterSystem;
import com.kingsrook.qqq.backend.core.actions.tables.ConditionalUpdateAction;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.ConditionalUpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.utils.StringUtils;
import static com.kingsrook.qbits.cluster.model.ClusterTables.DRAIN_REASON;
import static com.kingsrook.qbits.cluster.model.ClusterTables.DRAIN_REQUESTED;
import static com.kingsrook.qbits.cluster.model.ClusterTables.DRAIN_REQUESTED_BY;
import static com.kingsrook.qbits.cluster.model.ClusterTables.ID;
import static com.kingsrook.qbits.cluster.model.ClusterTables.MODIFY_DATE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.NODE;
import static com.kingsrook.qbits.cluster.model.ClusterTables.NODE_ID;
import static com.kingsrook.qbits.cluster.model.ClusterTables.STATUS;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The steps of the two cluster_node record actions, each a standard streamed
 ** ETL (Review: what will happen and what is skipped and why; Result: what
 ** happened):
 ** - Drain node: sets the drain request (who, why) on nodes that are UP. The
 **   node picks it up on its next heartbeat and starts draining.
 ** - Cancel drain: clears a request the node has not acted on yet. A node that
 **   is already draining cannot be undrained.
 ** Both writes are compare-and-set on the node's status, so a node that starts
 ** draining between Review and Result is reported, never overwritten.
 *******************************************************************************/
public final class ClusterNodeActionSteps
{
   public static final String FIELD_REASON = "clusterDrainReason";

   private static final QLogger LOG = QLogger.getLogger(ClusterNodeActionSteps.class);



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ClusterNodeActionSteps()
   {
   }



   /*******************************************************************************
    ** Review for Drain node.
    *******************************************************************************/
   public static class DrainTransform extends BaseTransform
   {
      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      public DrainTransform()
      {
         super("node will be asked to drain", "nodes will be asked to drain");
      }



      /*******************************************************************************
       ** Only an UP node without a pending request can be drained.
       *******************************************************************************/
      @Override
      String skipReason(QRecord node)
      {
         if(!NodeStatus.UP.name().equals(node.getValueString(STATUS)))
         {
            return ("Only a node that is up can be drained; these are draining, lost or stopped.");
         }
         if(Boolean.TRUE.equals(node.getValueBoolean(DRAIN_REQUESTED)))
         {
            return ("A drain was already requested for these nodes.");
         }
         return (null);
      }



      /*******************************************************************************
       ** Warn when the drain would leave no node up.
       *******************************************************************************/
      @Override
      void addWarnings(List<ProcessSummaryLineInterface> lines, int willCount)
      {
         try
         {
            Integer up = ClusterSystem.call(() -> new CountAction().execute(new CountInput(NODE).withFilter(new QQueryFilter()
               .withCriteria(new QFilterCriteria(STATUS, QCriteriaOperator.EQUALS, NodeStatus.UP.name()))
               .withSubFilter(notRequested()))).getCount());
            if(up != null && willCount > 0 && up - willCount <= 0)
            {
               lines.add(new ProcessSummaryLine(Status.WARNING, null, "No other node is up: after this drain, no node will take new requests or work until one starts."));
            }
         }
         catch(QException e)
         {
            LOG.debug("Could not count the nodes that are up", e);
         }
      }
   }



   /*******************************************************************************
    ** Result for Drain node.
    *******************************************************************************/
   public static class DrainLoad extends BaseLoad
   {
      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      public DrainLoad()
      {
         super("node will be asked to drain", "nodes will be asked to drain", "node was asked to drain", "nodes were asked to drain");
      }



      /*******************************************************************************
       ** Set the request where the node is still UP with no request.
       *******************************************************************************/
      @Override
      boolean apply(RunBackendStepInput input, QRecord node, Instant now) throws QException
      {
         String reason = input.getValueString(FIELD_REASON);
         String by     = userLabel(QContext.getQSession());
         int count = ClusterSystem.call(() -> new ConditionalUpdateAction().execute(new ConditionalUpdateInput(NODE)
            .withFilter(new QQueryFilter()
               .withCriteria(new QFilterCriteria(ID, QCriteriaOperator.EQUALS, node.getValue(ID)))
               .withCriteria(new QFilterCriteria(STATUS, QCriteriaOperator.EQUALS, NodeStatus.UP.name()))
               .withSubFilter(notRequested()))
            .withValue(DRAIN_REQUESTED, true)
            .withValue(DRAIN_REQUESTED_BY, by)
            .withValue(DRAIN_REASON, StringUtils.hasContent(reason) ? truncate(reason.trim(), 500) : null)
            .withValue(MODIFY_DATE, now)));
         if(count == 1)
         {
            LOG.info("Cluster node drain requested", logPair("nodeId", node.getValueString(NODE_ID)), logPair("requestedBy", by), logPair("reason", reason));
         }
         return (count == 1);
      }



      /*******************************************************************************
       ** Why a node could not be asked.
       *******************************************************************************/
      @Override
      String failedMessage()
      {
         return ("These nodes stopped being up, or were already asked, before the drain was saved.");
      }
   }



   /*******************************************************************************
    ** Review for Cancel drain.
    *******************************************************************************/
   public static class CancelTransform extends BaseTransform
   {
      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      public CancelTransform()
      {
         super("node's drain request will be cancelled", "nodes' drain requests will be cancelled");
      }



      /*******************************************************************************
       ** Only a pending request on an UP node can be cancelled.
       *******************************************************************************/
      @Override
      String skipReason(QRecord node)
      {
         if(!Boolean.TRUE.equals(node.getValueBoolean(DRAIN_REQUESTED)))
         {
            return ("No drain was requested for these nodes.");
         }
         if(!NodeStatus.UP.name().equals(node.getValueString(STATUS)))
         {
            return ("These nodes have already started draining (or stopped). Draining cannot be undone; restart the node instead.");
         }
         return (null);
      }
   }



   /*******************************************************************************
    ** Result for Cancel drain.
    *******************************************************************************/
   public static class CancelLoad extends BaseLoad
   {
      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      public CancelLoad()
      {
         super("node's drain request will be cancelled", "nodes' drain requests will be cancelled", "node's drain request was cancelled", "nodes' drain requests were cancelled");
      }



      /*******************************************************************************
       ** Clear the request while the node is still UP.
       *******************************************************************************/
      @Override
      boolean apply(RunBackendStepInput input, QRecord node, Instant now) throws QException
      {
         int count = ClusterSystem.call(() -> new ConditionalUpdateAction().execute(new ConditionalUpdateInput(NODE)
            .withFilter(new QQueryFilter()
               .withCriteria(new QFilterCriteria(ID, QCriteriaOperator.EQUALS, node.getValue(ID)))
               .withCriteria(new QFilterCriteria(STATUS, QCriteriaOperator.EQUALS, NodeStatus.UP.name()))
               .withCriteria(new QFilterCriteria(DRAIN_REQUESTED, QCriteriaOperator.EQUALS, true)))
            .withValue(DRAIN_REQUESTED, false)
            .withValue(DRAIN_REQUESTED_BY, null)
            .withValue(DRAIN_REASON, null)
            .withValue(MODIFY_DATE, now)));
         if(count == 1)
         {
            LOG.info("Cluster node drain request cancelled", logPair("nodeId", node.getValueString(NODE_ID)), logPair("by", userLabel(QContext.getQSession())));
         }
         return (count == 1);
      }



      /*******************************************************************************
       ** Why a request could not be cancelled.
       *******************************************************************************/
      @Override
      String failedMessage()
      {
         return ("These nodes started draining before the cancel was saved; draining cannot be undone.");
      }
   }



   /*******************************************************************************
    ** Review: sort the selected nodes into "will" and "skipped (why)".
    *******************************************************************************/
   abstract static class BaseTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine       will;
      private final List<ProcessSummaryLine> skipped = new ArrayList<>();



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      BaseTransform(String singular, String plural)
      {
         will = line(Status.OK, singular, plural, singular, plural);
      }



      /*******************************************************************************
       ** Why a node is skipped, or null.
       *******************************************************************************/
      abstract String skipReason(QRecord node);



      /*******************************************************************************
       ** Extra Review lines.
       *******************************************************************************/
      void addWarnings(List<ProcessSummaryLineInterface> lines, int willCount)
      {
      }



      /*******************************************************************************
       ** Sort one page.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output)
      {
         for(QRecord node : input.getRecords())
         {
            String reason = skipReason(node);
            if(reason == null)
            {
               will.incrementCountAndAddPrimaryKey(node.getValue(ID));
               output.addRecord(node);
            }
            else
            {
               skippedLine(reason).incrementCountAndAddPrimaryKey(node.getValue(ID));
            }
         }
      }



      /*******************************************************************************
       ** The Review lines (the skipped lines repeat on the Result screen).
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         if(!isForResultScreen)
         {
            will.addSelfToListIfAnyCount(lines);
            addWarnings(lines, will.getCount() == null ? 0 : will.getCount());
         }
         for(ProcessSummaryLine line : skipped)
         {
            line.addSelfToListIfAnyCount(lines);
         }
         return (lines);
      }



      /*******************************************************************************
       ** The skipped line for a reason.
       *******************************************************************************/
      private ProcessSummaryLine skippedLine(String reason)
      {
         for(ProcessSummaryLine line : skipped)
         {
            if(reason.equals(line.getMessage()))
            {
               return (line);
            }
         }
         ProcessSummaryLine line = new ProcessSummaryLine(Status.INFO, 0, reason);
         line.setSingularFutureMessage("node will be skipped. " + reason);
         line.setPluralFutureMessage("nodes will be skipped. " + reason);
         line.setSingularPastMessage("node was skipped. " + reason);
         line.setPluralPastMessage("nodes were skipped. " + reason);
         line.setMessage(reason);
         skipped.add(line);
         return (line);
      }
   }



   /*******************************************************************************
    ** Result: apply the change to each node.
    *******************************************************************************/
   abstract static class BaseLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine done;
      private final ProcessSummaryLine failed;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      BaseLoad(String singularFuture, String pluralFuture, String singularPast, String pluralPast)
      {
         done = line(Status.OK, singularFuture, pluralFuture, singularPast, pluralPast);
         failed = new ProcessSummaryLine(Status.ERROR, 0, null);
      }



      /*******************************************************************************
       ** Change one node; false when its state changed first.
       *******************************************************************************/
      abstract boolean apply(RunBackendStepInput input, QRecord node, Instant now) throws QException;



      /*******************************************************************************
       ** The message for nodes that could not be changed.
       *******************************************************************************/
      abstract String failedMessage();



      /*******************************************************************************
       ** Apply one page.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         Instant now = new ConditionalUpdateAction().databaseNow(NODE);
         for(QRecord node : input.getRecords())
         {
            if(apply(input, node, now))
            {
               done.incrementCountAndAddPrimaryKey(node.getValue(ID));
            }
            else
            {
               failed.incrementCountAndAddPrimaryKey(node.getValue(ID));
            }
         }
      }



      /*******************************************************************************
       ** The Result lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         done.addSelfToListIfAnyCount(lines);
         failed.setSingularPastMessage("node was not changed. " + failedMessage());
         failed.setPluralPastMessage("nodes were not changed. " + failedMessage());
         failed.addSelfToListIfAnyCount(lines);
         if(getTransformStep() instanceof ProcessSummaryProviderInterface transform)
         {
            lines.addAll(transform.getProcessSummary(output, true));
         }
         return (lines);
      }
   }



   /*******************************************************************************
    ** drain_requested is false or empty.
    *******************************************************************************/
   static QQueryFilter notRequested()
   {
      return (new QQueryFilter()
         .withBooleanOperator(QQueryFilter.BooleanOperator.OR)
         .withCriteria(new QFilterCriteria(DRAIN_REQUESTED, QCriteriaOperator.EQUALS, false))
         .withCriteria(new QFilterCriteria(DRAIN_REQUESTED, QCriteriaOperator.IS_BLANK)));
   }



   /*******************************************************************************
    ** Who the session is, for drain_requested_by.
    *******************************************************************************/
   static String userLabel(QSession session)
   {
      if(session == null || session.getUser() == null)
      {
         return ("an administrator");
      }
      String label = StringUtils.hasContent(session.getUser().getFullName()) ? session.getUser().getFullName() : session.getUser().getIdReference();
      return (StringUtils.hasContent(label) ? truncate(label, 250) : "an administrator");
   }



   /*******************************************************************************
    ** A counted summary line.
    *******************************************************************************/
   static ProcessSummaryLine line(Status status, String singularFuture, String pluralFuture, String singularPast, String pluralPast)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage(singularFuture);
      line.setPluralFutureMessage(pluralFuture);
      line.setSingularPastMessage(singularPast);
      line.setPluralPastMessage(pluralPast);
      return (line);
   }



   /*******************************************************************************
    ** Cut text to a column's length.
    *******************************************************************************/
   static String truncate(String value, int max)
   {
      return (value.length() <= max ? value : value.substring(0, max));
   }
}
