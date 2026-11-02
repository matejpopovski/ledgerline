package io.github.matejpopovski.ledgerline.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Runs tasks on real threads released at the same moment. */
public final class Race {

  private static final long TIMEOUT_SECONDS = 120;

  private Race() {}

  /** The outcome of one racer: a value or the exception it threw. */
  public record Outcome<T>(T value, Throwable failure) {
    public boolean succeeded() {
      return failure == null;
    }
  }

  public static <T> List<Outcome<T>> run(int racers, IntFunction<T> task) {
    CountDownLatch ready = new CountDownLatch(racers);
    CountDownLatch go = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
      List<Future<Outcome<T>>> futures = new ArrayList<>(racers);
      for (int i = 0; i < racers; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  try {
                    return new Outcome<T>(task.apply(index), null);
                  } catch (RuntimeException e) {
                    return new Outcome<T>(null, e);
                  }
                }));
      }
      ready.await();
      go.countDown();
      List<Outcome<T>> outcomes = new ArrayList<>(racers);
      for (Future<Outcome<T>> future : futures) {
        outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      return outcomes;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }
}
