package com.example.jobprocessor.job.service;

import com.example.jobprocessor.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Used for missing jobs and for jobs owned by someone else, which must look the same. */
public class JobNotFoundException extends ApiException {

    public JobNotFoundException() {
        super(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found");
    }
}
