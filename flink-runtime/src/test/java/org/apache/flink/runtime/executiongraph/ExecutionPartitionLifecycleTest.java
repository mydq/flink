/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.executiongraph;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutorServiceAdapter;
import org.apache.flink.runtime.deployment.ResultPartitionDeploymentDescriptor;
import org.apache.flink.runtime.executiongraph.utils.SimpleAckingTaskManagerGateway;
import org.apache.flink.runtime.io.network.partition.JobMasterPartitionTracker;
import org.apache.flink.runtime.io.network.partition.NoOpJobMasterPartitionTracker;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.io.network.partition.TestingJobMasterPartitionTracker;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobGraphTestUtils;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobmanager.slots.TaskManagerGateway;
import org.apache.flink.runtime.scheduler.DefaultSchedulerBuilder;
import org.apache.flink.runtime.scheduler.SchedulerBase;
import org.apache.flink.runtime.scheduler.SchedulerTestingUtils;
import org.apache.flink.runtime.scheduler.TestingPhysicalSlot;
import org.apache.flink.runtime.scheduler.TestingPhysicalSlotProvider;
import org.apache.flink.runtime.shuffle.PartitionDescriptor;
import org.apache.flink.runtime.shuffle.ProducerDescriptor;
import org.apache.flink.runtime.shuffle.ShuffleDescriptor;
import org.apache.flink.runtime.shuffle.ShuffleMaster;
import org.apache.flink.runtime.shuffle.ShuffleTestUtils;
import org.apache.flink.runtime.taskmanager.LocalTaskManagerLocation;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.runtime.testtasks.NoOpInvokable;
import org.apache.flink.testutils.TestingUtils;
import org.apache.flink.testutils.executor.TestExecutorExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.annotation.Nonnull;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for the {@link Execution}. */
class ExecutionPartitionLifecycleTest {

    @RegisterExtension
    static final TestExecutorExtension<ScheduledExecutorService> EXECUTOR_RESOURCE =
            TestingUtils.defaultExecutorExtension();

    private Execution execution;
    private ResultPartitionDeploymentDescriptor descriptor;
    private ResourceID taskExecutorResourceId;
    private JobID jobId;

    @Test
    void testPartitionReleaseOnFinishWhileCanceling() throws Exception {
        testPartitionReleaseOnStateTransitionsAfterRunning(
                Execution::cancel, Execution::markFinished);
    }

    @Test
    void testPartitionReleaseOnCancelWhileFinished() throws Exception {
        testPartitionReleaseOnStateTransitionsAfterRunning(
                Execution::markFinished, Execution::cancel);
    }

    @Test
    void testPartitionReleaseOnSuspendWhileFinished() throws Exception {
        testPartitionReleaseOnStateTransitionsAfterRunning(
                Execution::markFinished, Execution::suspend);
    }

    private void testPartitionReleaseOnStateTransitionsAfterRunning(
            Consumer<Execution> stateTransition1, Consumer<Execution> stateTransition2)
            throws Exception {
        final SimpleAckingTaskManagerGateway taskManagerGateway =
                new SimpleAckingTaskManagerGateway();
        final CompletableFuture<Tuple2<JobID, Collection<ResultPartitionID>>>
                releasePartitionsCallFuture = new CompletableFuture<>();
        taskManagerGateway.setReleasePartitionsConsumer(
                ((jobID, partitionIds) ->
                        releasePartitionsCallFuture.complete(Tuple2.of(jobID, partitionIds))));

        final TestingShuffleMaster testingShuffleMaster = new TestingShuffleMaster();

        setupExecutionGraphAndStartRunningJob(
                ResultPartitionType.PIPELINED,
                NoOpJobMasterPartitionTracker.INSTANCE,
                taskManagerGateway,
                testingShuffleMaster);

        stateTransition1.accept(execution);
        assertThat(releasePartitionsCallFuture).isNotDone();

        stateTransition2.accept(execution);
        assertThat(releasePartitionsCallFuture).isDone();

        final Tuple2<JobID, Collection<ResultPartitionID>> releasePartitionsCall =
                releasePartitionsCallFuture.get();
        assertThat(releasePartitionsCall.f0).isEqualTo(jobId);

        assertThat(releasePartitionsCall.f1)
                .contains(descriptor.getShuffleDescriptor().getResultPartitionID());

        assertThat(testingShuffleMaster.externallyReleasedPartitions).hasSize(1);
        assertThat(descriptor.getShuffleDescriptor())
                .isEqualTo(testingShuffleMaster.externallyReleasedPartitions.poll());
    }

    private enum PartitionReleaseResult {
        NONE,
        STOP_TRACKING,
        STOP_TRACKING_AND_RELEASE
    }

    @Test
    void testPartitionTrackedAndNotReleasedWhenFinished() throws Exception {
        testPartitionTrackingForStateTransition(
                Execution::markFinished, PartitionReleaseResult.NONE);
    }

    @Test
    void testPartitionNotTrackedAndNotReleasedWhenCanceledByTM() throws Exception {
        testPartitionTrackingForStateTransition(
                execution -> {
                    execution.cancel();
                    execution.completeCancelling(
                            Collections.emptyMap(), new IOMetrics(0, 0, 0, 0, 0, 0, 0), false);
                },
                PartitionReleaseResult.STOP_TRACKING);
    }

    @Test
    void testPartitionNotTrackedAndReleasedWhenCanceledByJM() throws Exception {
        testPartitionTrackingForStateTransition(
                execution -> {
                    execution.cancel();
                    execution.completeCancelling();
                },
                PartitionReleaseResult.STOP_TRACKING_AND_RELEASE);
    }

    @Test
    void testPartitionNotTrackedAndNotReleasedWhenFailedByTM() throws Exception {
        testPartitionTrackingForStateTransition(
                execution ->
                        execution.markFailed(
                                new Exception("Test exception"),
                                false,
                                Collections.emptyMap(),
                                new IOMetrics(0, 0, 0, 0, 0, 0, 0),
                                false,
                                true),
                PartitionReleaseResult.STOP_TRACKING);
    }

    @Test
    void testPartitionNotTrackedAndReleasedWhenFailedByJM() throws Exception {
        testPartitionTrackingForStateTransition(
                execution -> execution.markFailed(new Exception("Test exception")),
                PartitionReleaseResult.STOP_TRACKING_AND_RELEASE);
    }

    private void testPartitionTrackingForStateTransition(
            final Consumer<Execution> stateTransition,
            final PartitionReleaseResult partitionReleaseResult)
            throws Exception {
        CompletableFuture<Tuple2<ResourceID, ResultPartitionDeploymentDescriptor>>
                partitionStartTrackingFuture = new CompletableFuture<>();
        CompletableFuture<Collection<ResultPartitionID>> partitionStopTrackingFuture =
                new CompletableFuture<>();
        CompletableFuture<Collection<ResultPartitionID>> partitionStopTrackingAndReleaseFuture =
                new CompletableFuture<>();
        final TestingJobMasterPartitionTracker partitionTracker =
                new TestingJobMasterPartitionTracker();
        partitionTracker.setStartTrackingPartitionsConsumer(
                (resourceID, resultPartitionDeploymentDescriptor) ->
                        partitionStartTrackingFuture.complete(
                                Tuple2.of(resourceID, resultPartitionDeploymentDescriptor)));
        partitionTracker.setStopTrackingPartitionsConsumer(partitionStopTrackingFuture::complete);
        partitionTracker.setStopTrackingAndReleasePartitionsConsumer(
                partitionStopTrackingAndReleaseFuture::complete);

        setupExecutionGraphAndStartRunningJob(
                ResultPartitionType.BLOCKING,
                partitionTracker,
                new SimpleAckingTaskManagerGateway(),
                ShuffleTestUtils.DEFAULT_SHUFFLE_MASTER);

        Tuple2<ResourceID, ResultPartitionDeploymentDescriptor> startTrackingCall =
                partitionStartTrackingFuture.get();
        assertThat(startTrackingCall.f0).isEqualTo(taskExecutorResourceId);
        assertThat(startTrackingCall.f1).isEqualTo(descriptor);

        stateTransition.accept(execution);

        switch (partitionReleaseResult) {
            case NONE:
                assertThat(partitionStopTrackingFuture).isNotDone();
                assertThat(partitionStopTrackingAndReleaseFuture).isNotDone();
                break;
            case STOP_TRACKING:
                assertThat(partitionStopTrackingFuture).isDone();
                assertThat(partitionStopTrackingAndReleaseFuture).isNotDone();
                final Collection<ResultPartitionID> stopTrackingCall =
                        partitionStopTrackingFuture.get();
                assertThat(
                                Collections.singletonList(
                                        descriptor.getShuffleDescriptor().getResultPartitionID()))
                        .isEqualTo(stopTrackingCall);
                break;
            case STOP_TRACKING_AND_RELEASE:
                assertThat(partitionStopTrackingFuture).isNotDone();
                assertThat(partitionStopTrackingAndReleaseFuture).isDone();
                final Collection<ResultPartitionID> stopTrackingAndReleaseCall =
                        partitionStopTrackingAndReleaseFuture.get();
                assertThat(
                                Collections.singletonList(
                                        descriptor.getShuffleDescriptor().getResultPartitionID()))
                        .isEqualTo(stopTrackingAndReleaseCall);
                break;
        }
    }

    private void setupExecutionGraphAndStartRunningJob(
            ResultPartitionType resultPartitionType,
            JobMasterPartitionTracker partitionTracker,
            TaskManagerGateway taskManagerGateway,
            ShuffleMaster<?> shuffleMaster)
            throws Exception {
        final JobVertex producerVertex = createNoOpJobVertex();
        final JobVertex consumerVertex = createNoOpJobVertex();
        consumerVertex.connectNewDataSetAsInput(
                producerVertex, DistributionPattern.ALL_TO_ALL, resultPartitionType);

        final TaskManagerLocation taskManagerLocation = new LocalTaskManagerLocation();
        final TestingPhysicalSlotProvider physicalSlotProvider =
                TestingPhysicalSlotProvider.create(
                        (resourceProfile) ->
                                CompletableFuture.completedFuture(
                                        TestingPhysicalSlot.builder()
                                                .withTaskManagerGateway(taskManagerGateway)
                                                .withTaskManagerLocation(taskManagerLocation)
                                                .build()));

        final JobGraph jobGraph = JobGraphTestUtils.batchJobGraph(producerVertex, consumerVertex);
        final SchedulerBase scheduler =
                new DefaultSchedulerBuilder(
                                jobGraph,
                                ComponentMainThreadExecutorServiceAdapter.forMainThread(),
                                EXECUTOR_RESOURCE.getExecutor())
                        .setExecutionSlotAllocatorFactory(
                                SchedulerTestingUtils.newSlotSharingExecutionSlotAllocatorFactory(
                                        physicalSlotProvider))
                        .setShuffleMaster(shuffleMaster)
                        .setPartitionTracker(partitionTracker)
                        .build();

        final ExecutionGraph executionGraph = scheduler.getExecutionGraph();

        final ExecutionJobVertex executionJobVertex =
                executionGraph.getJobVertex(producerVertex.getID());
        final ExecutionVertex executionVertex = executionJobVertex.getTaskVertices()[0];
        execution = executionVertex.getCurrentExecutionAttempt();

        scheduler.startScheduling();
        execution.switchToRecovering();
        execution.switchToRunning();

        final IntermediateResultPartitionID expectedIntermediateResultPartitionId =
                executionJobVertex.getProducedDataSets()[0].getPartitions()[0].getPartitionId();

        descriptor =
                execution
                        .getResultPartitionDeploymentDescriptor(
                                expectedIntermediateResultPartitionId)
                        .get();
        taskExecutorResourceId = taskManagerLocation.getResourceID();
        jobId = executionGraph.getJobID();
    }

    @Nonnull
    private JobVertex createNoOpJobVertex() {
        final JobVertex jobVertex = new JobVertex("Test vertex", new JobVertexID());
        jobVertex.setInvokableClass(NoOpInvokable.class);

        return jobVertex;
    }

    private static class TestingShuffleMaster implements ShuffleMaster<ShuffleDescriptor> {

        final Queue<ShuffleDescriptor> externallyReleasedPartitions = new ArrayBlockingQueue<>(4);

        @Override
        public CompletableFuture<ShuffleDescriptor> registerPartitionWithProducer(
                JobID jobID,
                PartitionDescriptor partitionDescriptor,
                ProducerDescriptor producerDescriptor) {
            return CompletableFuture.completedFuture(
                    new TestingShuffleDescriptor(
                            partitionDescriptor.getPartitionId(),
                            producerDescriptor.getProducerExecutionId(),
                            producerDescriptor.getProducerLocation()));
        }

        @Override
        public void releasePartitionExternally(ShuffleDescriptor shuffleDescriptor) {
            externallyReleasedPartitions.add(shuffleDescriptor);
        }
    }

    private static class TestingShuffleDescriptor implements ShuffleDescriptor {

        private static final long serialVersionUID = 1819950291216655728L;

        private final ExecutionAttemptID producerExecutionId;
        private final IntermediateResultPartitionID producedPartitionId;
        private final ResourceID producerLocation;

        TestingShuffleDescriptor(
                IntermediateResultPartitionID producedPartitionId,
                ExecutionAttemptID producerExecutionId,
                ResourceID producerLocation) {
            this.producedPartitionId = producedPartitionId;
            this.producerExecutionId = producerExecutionId;
            this.producerLocation = producerLocation;
        }

        @Override
        public ResultPartitionID getResultPartitionID() {
            return new ResultPartitionID(producedPartitionId, producerExecutionId);
        }

        @Override
        public Optional<ResourceID> storesLocalResourcesOn() {
            return Optional.of(producerLocation);
        }
    }
}
