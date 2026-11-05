package com.example.jobprocessor.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.TestProperties;
import com.example.jobprocessor.common.exception.ProblemResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Limit from {@link TestProperties}: 64 KB. */
class RequestSizeLimitFilterTest {

    private static final int LIMIT = 64 * 1024;

    private final RequestSizeLimitFilter filter =
            new RequestSizeLimitFilter(TestProperties.create(false), new ProblemResponseWriter(new ObjectMapper()));

    @Test
    void rejectsDeclaredLengthOverLimitWithoutCallingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/jobs");
        request.setContent(new byte[LIMIT + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void passesBodiesWithinLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/jobs");
        request.setContent(new byte[LIMIT]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> assertThat(req.getInputStream().readAllBytes()).hasSize(LIMIT));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void stopsReadingChunkedBodyOverLimit() throws Exception {
        MockHttpServletRequest request = chunked(new byte[LIMIT + 10]);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThatThrownBy(() -> req.getInputStream().readAllBytes())
                        .isInstanceOf(RequestTooLargeException.class)
                        .hasMessageContaining(String.valueOf(LIMIT)));
    }

    @Test
    void readerIsAlsoLimited() throws Exception {
        byte[] body = "x".repeat(LIMIT + 1).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = chunked(body);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThatThrownBy(() -> req.getReader().lines().count())
                        .hasRootCauseInstanceOf(RequestTooLargeException.class));
    }

    /** A request without a declared Content-Length, as with chunked transfer encoding. */
    private static MockHttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/jobs") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }
}
