package com.example.jobprocessor.job.api;

import com.example.jobprocessor.common.response.PageResponse;
import com.example.jobprocessor.job.dto.CreateJobRequest;
import com.example.jobprocessor.job.dto.CreateJobResponse;
import com.example.jobprocessor.job.dto.JobAttemptResponse;
import com.example.jobprocessor.job.dto.JobResponse;
import com.example.jobprocessor.job.idempotency.IdempotencyKeys;
import com.example.jobprocessor.job.service.JobManagementService;
import com.example.jobprocessor.job.service.JobManagementService.CancellationResult;
import com.example.jobprocessor.job.service.JobManagementService.CreateJobResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** REST API for jobs (README §16). Translates HTTP to Job Management calls; no business logic here. */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    static final String REPLAYED_HEADER = "Idempotency-Replayed";
    private static final Set<String> SORTABLE = Set.of("createdAt");

    private final JobManagementService jobs;

    public JobController(JobManagementService jobs) {
        this.jobs = jobs;
    }

    /** 202: the job is accepted for asynchronous processing. A replay returns the original job. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateJobResponse> create(
            @RequestHeader(IdempotencyKeys.HEADER) String idempotencyKey,
            @Valid @RequestBody CreateJobRequest request) {
        CreateJobResult result = jobs.createJob(idempotencyKey, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(result.job().getId()).toUri();
        ResponseEntity.BodyBuilder response = ResponseEntity.accepted().location(location);
        if (result.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(CreateJobResponse.from(result.job()));
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobs.getJob(id));
    }

    /**
     * Page size defaults come from {@code spring.data.web.pageable.*} (20, max 100); only the sort is
     * defaulted here, since {@code @PageableDefault} would also override the size.
     */
    @GetMapping
    public PageResponse<JobResponse> list(
            @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new InvalidSortException(order.getProperty());
            }
        }
        return PageResponse.from(jobs.listJobs(pageable), JobResponse::from);
    }

    @GetMapping("/{id}/attempts")
    public List<JobAttemptResponse> attempts(@PathVariable UUID id) {
        return jobs.getAttempts(id).stream().map(JobAttemptResponse::from).toList();
    }

    /**
     * Requests cancellation; the job is never deleted. 200 when the job is (already) cancelled,
     * 202 when a running job has been asked to stop, 409 when it already finished.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<JobResponse> cancel(@PathVariable UUID id) {
        CancellationResult result = jobs.cancelJob(id);
        HttpStatus status = switch (result.outcome()) {
            case CANCELLED, ALREADY_CANCELLED -> HttpStatus.OK;
            case CANCEL_REQUESTED, ALREADY_CANCEL_REQUESTED -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).body(JobResponse.from(result.job()));
    }
}
