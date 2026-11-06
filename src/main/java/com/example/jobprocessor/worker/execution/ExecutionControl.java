package com.example.jobprocessor.worker.execution;

/** Lets a running job check, between units of work, whether it may continue. */
@FunctionalInterface
public interface ExecutionControl {

    ExecutionControl UNCONTROLLED = () -> { };

    /** @throws InterruptedException if the job must stop now */
    void checkpoint() throws InterruptedException;
}
