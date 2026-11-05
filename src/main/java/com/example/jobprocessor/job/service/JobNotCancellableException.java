package com.example.jobprocessor.job.service;

import com.example.jobprocessor.common.exception.ApiException;
import com.example.jobprocessor.job.domain.JobStatus;
import org.springframework.http.HttpStatus;

public class JobNotCancellableException extends ApiException {

    public JobNotCancellableException(JobStatus status) {
        super(HttpStatus.CONFLICT, "JOB_NOT_CANCELLABLE", "Job is already " + status + " and cannot be cancelled");
    }
}
