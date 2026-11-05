package com.example.jobprocessor.job.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.job.dto.CreateJobRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher();

    @Test
    void producesSha256Hex() {
        assertThat(hasher.hash(request(Map.of("a", 1)))).matches("[0-9a-f]{64}");
    }

    @Test
    void fieldOrderDoesNotChangeTheHashAtAnyDepth() {
        Map<String, Object> innerA = new LinkedHashMap<>();
        innerA.put("x", 1);
        innerA.put("y", 2);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", "v");
        first.put("nested", innerA);
        first.put("list", List.of(Map.of("k1", 1, "k2", 2)));

        Map<String, Object> innerB = new LinkedHashMap<>();
        innerB.put("y", 2);
        innerB.put("x", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("list", List.of(reversed(Map.of("k1", 1, "k2", 2))));
        second.put("nested", innerB);
        second.put("a", "v");

        assertThat(hasher.hash(request(first))).isEqualTo(hasher.hash(request(second)));
        assertThat(hasher.canonicalJson(request(second)))
                .isEqualTo("{\"payload\":{\"a\":\"v\",\"list\":[{\"k1\":1,\"k2\":2}],\"nested\":{\"x\":1,\"y\":2}}}");
    }

    @Test
    void differentValuesProduceDifferentHashes() {
        assertThat(hasher.hash(request(Map.of("a", 1)))).isNotEqualTo(hasher.hash(request(Map.of("a", 2))));
    }

    @Test
    void arrayOrderMatters() {
        assertThat(hasher.hash(request(Map.of("a", List.of(1, 2)))))
                .isNotEqualTo(hasher.hash(request(Map.of("a", List.of(2, 1)))));
    }

    @Test
    void valueTypeMatters() {
        assertThat(hasher.hash(request(Map.of("a", 1)))).isNotEqualTo(hasher.hash(request(Map.of("a", "1"))));
    }

    @Test
    void handlesNullValues() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("a", null);

        assertThat(hasher.canonicalJson(request(payload))).isEqualTo("{\"payload\":{\"a\":null}}");
    }

    private static CreateJobRequest request(Map<String, Object> payload) {
        return new CreateJobRequest(payload);
    }

    private static Map<String, Object> reversed(Map<String, Integer> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.keySet().stream().sorted((a, b) -> b.compareTo(a)).forEach(k -> result.put(k, map.get(k)));
        return result;
    }
}
