package com.example.jobprocessor.security.authorization;

/**
 * Operation permissions, granted as Spring Security authorities with these exact names.
 * A permission never implies access to another user's job: ownership is checked separately.
 */
public enum Permission {
    JOB_CREATE,
    JOB_READ,
    JOB_CANCEL,
    /** Operational metrics: /actuator/metrics and the /actuator/prometheus scrape endpoint. */
    METRICS_READ
}
