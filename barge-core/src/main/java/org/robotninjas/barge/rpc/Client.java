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

package org.robotninjas.barge.rpc;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;
import org.robotninjas.barge.Replica;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.robotninjas.barge.proto.ClientProto.CommitOperation;
import static org.robotninjas.barge.proto.ClientProto.CommitOperationResponse;
import static org.robotninjas.barge.proto.RaftProto.*;

@Immutable
public class Client {

  @Nonnull
  private final RpcClientProvider clientProvider;

  @Inject
  public Client(@Nonnull RpcClientProvider clientProvider) {
    checkNotNull(clientProvider);
    this.clientProvider = clientProvider;
  }

  @Nonnull
  public ListenableFuture<RequestVoteResponse> requestVote(@Nonnull Replica replica, @Nonnull RequestVote request) {
    checkNotNull(replica);
    checkNotNull(request);
    RaftClient client = clientProvider.get(replica);
    return client.requestVote(request);
  }

  @Nonnull
  public ListenableFuture<AppendEntriesResponse> appendEntries(@Nonnull Replica replica, @Nonnull AppendEntries request) {
    checkNotNull(replica);
    checkNotNull(request);
    RaftClient client = clientProvider.get(replica);
    return client.appendEntries(request);
  }

  @Nonnull
  public ListenableFuture<CommitOperationResponse> commitOperation(@Nonnull Replica replica, @Nonnull CommitOperation request) {
    checkNotNull(replica);
    checkNotNull(request);
    RaftClient client = clientProvider.get(replica);
    return client.commitOperation(request);
  }

}
