package com.lockstep.report;

import com.lockstep.core.RunProgress;
import com.lockstep.util.Ansi;
import com.lockstep.util.Numbers;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LiveProgress implements AutoCloseable {
    private static final long TICK_SECONDS = 5;

    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "lockstep-progress");
                thread.setDaemon(true);
                return thread;
            });
    private final RunProgress progress;
    private final PrintStream out;
    private final long totalNanos;
    private final long startNanoTime;

    public LiveProgress(RunProgress progress, long totalNanos, PrintStream out) {
        this.progress = progress;
        this.totalNanos = totalNanos;
        this.out = out;
        this.startNanoTime = System.nanoTime();
    }

    public LiveProgress start() {
        ticker.scheduleAtFixedRate(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        return this;
    }

    private void tick() {
        out.println("  " + Ansi.dim(elapsedClock()) + segments());
    }

    public void printTotals() {
        out.println("[lockstep] done ·" + segments());
    }

    private String elapsedClock() {
        long elapsed = System.nanoTime() - startNanoTime;
        return Numbers.clock(Math.min(elapsed, totalNanos)) + "/" + Numbers.clock(totalNanos);
    }

    private String segments() {
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, RunProgress.Counts> entry : progress.snapshot().entrySet()) {
            RunProgress.Counts counts = entry.getValue();
            String errors = counts.errors() == 0
                    ? Ansi.dim("0 err")
                    : Ansi.error(Numbers.withSeparators(counts.errors()) + " err");
            line.append(' ').append(Ansi.dim("│")).append(' ').append(entry.getKey())
                    .append(' ').append(Ansi.accent(Numbers.withSeparators(counts.fired())))
                    .append(' ').append(errors);
        }
        return line.toString();
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}
