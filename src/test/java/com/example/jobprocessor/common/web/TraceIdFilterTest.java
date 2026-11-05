package com.example.jobprocessor.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void reusesWellFormedClientRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "abc-123");

        assertThat(run(request)).isEqualTo("abc-123");
    }

    @Test
    void generatesIdWhenMissing() throws Exception {
        assertThat(run(new MockHttpServletRequest())).matches("[0-9a-f-]{36}");
    }

    @Test
    void replacesMalformedIdsToProtectLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "evil\nINJECTED log line");

        assertThat(run(request)).matches("[0-9a-f-]{36}");
    }

    @Test
    void replacesOverlongIds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "a".repeat(65));

        assertThat(run(request)).matches("[0-9a-f-]{36}");
    }

    /** Returns the id seen in the MDC during the request; also checks header and MDC cleanup. */
    private String run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(seen.get());
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
        return seen.get();
    }
}
