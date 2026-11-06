package com.example.jobprocessor.recovery.scheduler;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.common.util.FixedDelayLoop;
import com.example.jobprocessor.recovery.service.RecoveryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Runs the Recovery Manager periodically on its own thread; stops together with the Dispatcher. */
@Component
public class RecoveryScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final boolean enabled;
    private final FixedDelayLoop loop;

    public RecoveryScheduler(RecoveryManager recoveryManager, JobProcessorProperties properties) {
        this.enabled = properties.recovery().enabled();
        this.loop = new FixedDelayLoop("job-recovery", properties.recovery().pollInterval(),
                recoveryManager::recoverExpiredLeases);
    }

    @Override
    public void start() {
        if (!enabled) {
            log.info("Recovery Manager disabled on this instance");
            return;
        }
        loop.start();
    }

    @Override
    public void stop() {
        loop.stop();
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }
}
