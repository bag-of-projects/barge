/**
 * Copyright 2013 David Rusek <dave dot rusek at gmail dot com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.robotninjas.barge.state;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import org.robotninjas.barge.RaftException;
import org.robotninjas.barge.Replica;
import org.robotninjas.barge.rpc.RaftScheduler;
import org.robotninjas.barge.log.RaftLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.Futures.transform;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.robotninjas.barge.proto.ClientProto.CommitOperation;
import static org.robotninjas.barge.proto.ClientProto.CommitOperationResponse;
import static org.robotninjas.barge.proto.RaftProto.*;
import static org.robotninjas.barge.state.Context.StateType.FOLLOWER;
import static org.robotninjas.barge.state.MajorityCollector.majorityResponse;
import static org.robotninjas.barge.state.Predicates.appendSuccessul;

@NotThreadSafe
class Leader implements State {

  private static final Logger LOGGER = LoggerFactory.getLogger(Leader.class);

  private final RaftLog log;
  private final ScheduledExecutorService scheduler;
  private final long timeout;
  private final Map<Replica, ReplicaManager> managers = Maps.newHashMap();
  private final ReplicaManagerFactory replicaManagerFactory;
  private ScheduledFuture<?> heartbeatTask;

  @Inject
  Leader(RaftLog log, @RaftScheduler ScheduledExecutorService scheduler,
         @ElectionTimeout @Nonnegative long timeout, ReplicaManagerFactory replicaManagerFactory) {

    this.log = checkNotNull(log);
    this.scheduler = checkNotNull(scheduler);
    checkArgument(timeout > 0);
    this.timeout = timeout;
    this.replicaManagerFactory = checkNotNull(replicaManagerFactory);
  }

  @Override
  public void init(@Nonnull Context ctx) {

    long nextIndex = log.lastLogIndex() + 1;
    long term = log.currentTerm();
    Replica self = log.self();

    for (Replica replica : log.members()) {
      managers.put(replica, replicaManagerFactory.create(term, nextIndex, replica, self));
    }

    sendRequests();
    resetTimeout(ctx);

  }

  @Nonnull
  @Override
  public RequestVoteResponse requestVote(@Nonnull Context ctx, @Nonnull RequestVote request) {

    LOGGER.debug("RequestVote received for term {}", request.getTerm());

    boolean voteGranted = false;

    if (request.getTerm() > log.currentTerm()) {

      log.updateCurrentTerm(request.getTerm());
      ctx.setState(FOLLOWER);
      heartbeatTask.cancel(false);

      Replica candidate = Replica.fromString(request.getCandidateId());
      voteGranted = Voting.shouldVoteFor(log, request);

      if (voteGranted) {
        log.updateVotedFor(Optional.of(candidate));
      }

    }

    return RequestVoteResponse.newBuilder()
      .setTerm(log.currentTerm())
      .setVoteGranted(voteGranted)
      .build();

  }

  @Nonnull
  @Override
  public AppendEntriesResponse appendEntries(@Nonnull Context ctx, @Nonnull AppendEntries request) {

    boolean success = false;

    if (request.getTerm() > log.currentTerm()) {
      log.updateCurrentTerm(request.getTerm());
      ctx.setState(FOLLOWER);
      heartbeatTask.cancel(false);
      success = log.append(request);
    }

    return AppendEntriesResponse.newBuilder()
      .setTerm(log.currentTerm())
      .setSuccess(success)
      .build();

  }

  void resetTimeout(@Nonnull final Context ctx) {

    if (null != heartbeatTask) {
      heartbeatTask.cancel(false);
    }

    heartbeatTask = scheduler.scheduleAtFixedRate(new Runnable() {
      @Override
      public void run() {
        LOGGER.debug("Sending heartbeat");
        sendRequests();
      }
    }, timeout, timeout, MILLISECONDS);

  }

  @Nonnull
  @Override
  public ListenableFuture<CommitOperationResponse> commitOperation(@Nonnull Context ctx, @Nonnull CommitOperation request) throws RaftException {

    resetTimeout(ctx);

    final long index = log.append(request);

    ListenableFuture<Boolean> commit = commit();

    addCallback(commit, new FutureCallback<Boolean>() {

      @Override
      public void onSuccess(@Nullable Boolean comitted) {
        if (comitted) {
          long oldCommitIndex = log.commitIndex();
          long newCommitIndex = Math.max(oldCommitIndex, index);
          log.updateCommitIndex(newCommitIndex);
          LOGGER.debug("CommitIndex: {}", newCommitIndex);
        }
      }

      @Override
      public void onFailure(Throwable t) {

      }

    });

    return transform(commit, new Function<Boolean, CommitOperationResponse>() {
      @Nullable
      @Override
      public CommitOperationResponse apply(@Nullable Boolean input) {
        checkNotNull(input);
        //noinspection ConstantConditions
        return CommitOperationResponse.newBuilder().setCommitted(input).build();
      }
    });

  }

  /**
   * Commit a new entry to the cluster
   *
   * @return a future with the result of the commit operation
   */
  @Nonnull
  @VisibleForTesting
  ListenableFuture<Boolean> commit() {
    List<ListenableFuture<AppendEntriesResponse>> responses = sendRequests();
    return majorityResponse(responses, appendSuccessul());
  }

  /**
   * Notify the {@link ReplicaManager} to send an update the next possible time it can
   *
   * @return futures with the result of the update
   */
  @Nonnull
  @VisibleForTesting
  List<ListenableFuture<AppendEntriesResponse>> sendRequests() {
    List<ListenableFuture<AppendEntriesResponse>> responses = Lists.newArrayList();
    for (ReplicaManager replicaManager : managers.values()) {
      responses.add(replicaManager.fireUpdate());
    }
    return responses;
  }

}
