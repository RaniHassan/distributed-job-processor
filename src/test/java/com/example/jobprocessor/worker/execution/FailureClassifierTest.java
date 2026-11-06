package com.example.jobprocessor.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.job.lifecycle.FailureType;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class FailureClassifierTest {

    @Test
    void retryableIsTransient() {
        assertThat(FailureClassifier.classify(new RetryableException("timeout"))).isEqualTo(FailureType.TRANSIENT);
    }

    @Test
    void nonRetryableIsPermanent() {
        assertThat(FailureClassifier.classify(new NonRetryableException("bad input"))).isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void uncategorizedExceptionsAreUnexpectedNotGuessed() {
        assertThat(FailureClassifier.classify(new IllegalStateException())).isEqualTo(FailureType.UNEXPECTED);
        assertThat(FailureClassifier.classify(new TimeoutException())).isEqualTo(FailureType.UNEXPECTED);
        assertThat(FailureClassifier.classify(new IOException())).isEqualTo(FailureType.UNEXPECTED);
    }

    @Test
    void wrappedCategoryIsKept() {
        assertThat(FailureClassifier.classify(new RuntimeException(new RetryableException("timeout"))))
                .isEqualTo(FailureType.TRANSIENT);
        assertThat(FailureClassifier.classify(new RuntimeException(new NonRetryableException("bad"))))
                .isEqualTo(FailureType.PERMANENT);
    }

    @Test
    void unexpectedFailuresHideTheirMessageFromClients() {
        assertThat(FailureClassifier.clientMessage(FailureType.UNEXPECTED, new IllegalStateException("db password=x")))
                .isEqualTo("Unexpected processing error");
        assertThat(FailureClassifier.clientMessage(FailureType.TRANSIENT, new RetryableException("Upstream timed out")))
                .isEqualTo("Upstream timed out");
    }
}
