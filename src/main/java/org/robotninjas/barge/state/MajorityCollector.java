package org.robotninjas.barge.state;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;


@ThreadSafe
class MajorityCollector<T> extends AbstractFuture<Boolean> implements FutureCallback<T> {

  private final ReentrantLock lock = new ReentrantLock();
  private final Predicate<T> isSuccess;
  private final int totalNum;
  @GuardedBy("lock")
  private int numSuccess = 0;
  @GuardedBy("lock")
  private int numFailed = 0;

  @VisibleForTesting
  MajorityCollector(@Nonnegative int totalNum, @Nonnull Predicate<T> isSuccess) {
    this.totalNum = totalNum;
    this.isSuccess = isSuccess;
  }

  @Nonnull
  public static <U> ListenableFuture<Boolean> majorityResponse(@Nonnull List<ListenableFuture<U>> responses, @Nonnull Predicate<U> isSuccess) {
    MajorityCollector collector = new MajorityCollector(responses.size(), isSuccess);
    for (ListenableFuture<U> response : responses) {
      Futures.addCallback(response, collector);
    }
    return collector;
  }

  private void checkComplete() {
    if (!isDone()) {
      final double half = totalNum / 2.0;
      if (numSuccess >= half) {
        set(true);
      } else if (numFailed > half) {
        set(false);
      }
    }
  }

  @Override
  public void onSuccess(@Nonnull T result) {

    checkNotNull(result);

    lock.lock();
    try {
      if (isSuccess.apply(result)) {
        numSuccess++;
      } else {
        numFailed++;
      }
      checkComplete();
    } finally {
      lock.unlock();
    }

  }

  @Override
  public void onFailure(@Nonnull Throwable t) {
    lock.lock();
    try {
      numFailed++;
      checkComplete();
    } finally {
      lock.unlock();
    }
  }

}