package com.example.jobprocessor.job.idempotency;

import com.example.jobprocessor.job.dto.CreateJobRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * SHA-256 of the request in canonical JSON: object keys sorted at every depth, no whitespace.
 * Reordering fields therefore yields the same hash; array order and value types still matter
 * ({@code 1} and {@code 1.0} are different requests).
 */
@Component
public class RequestHasher {

    // A private mapper so the canonical form never changes with the application's Jackson settings.
    private final ObjectMapper canonicalMapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String hash(CreateJobRequest request) {
        return sha256Hex(canonicalJson(request));
    }

    String canonicalJson(CreateJobRequest request) {
        try {
            return canonicalMapper.writeValueAsString(Map.of("payload", request.payload()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize request for hashing", e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
