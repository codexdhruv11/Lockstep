package com.lockstep.core;

public interface Runner extends AutoCloseable {
    String name();

    PacedLoop.LoopResult run(RunContext context);

    @Override
    void close();
}
