package io.github.matejpopovski.ledgerline.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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

  /**
   * Racers are daemon threads and the wait is bounded, so a racer stuck in the database fails the
   * test with its stack trace instead of hanging the build.
   */
  public static <T> List<Outcome<T>> run(int racers, IntFunction<T> task) {
    CountDownLatch ready = new CountDownLatch(racers);
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(racers);
    List<Outcome<T>> outcomes = new ArrayList<>(racers);
    List<Thread> threads = new ArrayList<>(racers);
    for (int i = 0; i < racers; i++) {
      outcomes.add(null);
      int index = i;
      threads.add(
          Thread.ofPlatform()
              .daemon()
              .name("racer-" + i)
              .start(
                  () -> {
                    ready.countDown();
                    try {
                      go.await();
                      Outcome<T> outcome = new Outcome<>(task.apply(index), null);
                      synchronized (outcomes) {
                        outcomes.set(index, outcome);
                      }
                    } catch (RuntimeException | AssertionError e) {
                      synchronized (outcomes) {
                        outcomes.set(index, new Outcome<>(null, e));
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    } finally {
                      finished.countDown();
                    }
                  }));
    }
    try {
      ready.await();
      go.countDown();
      if (!finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("racers still running: " + stacks(threads));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    synchronized (outcomes) {
      return List.copyOf(outcomes);
    }
  }

  private static String stacks(List<Thread> threads) {
    StringBuilder out = new StringBuilder();
    for (Thread thread : threads) {
      if (thread.isAlive()) {
        out.append('\n').append(thread.getName()).append(' ').append(thread.getState());
        Arrays.stream(thread.getStackTrace())
            .limit(30)
            .forEach(frame -> out.append("\n  at ").append(frame));
      }
    }
    return out.toString();
  }
}
