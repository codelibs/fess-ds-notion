/*
 * Copyright 2012-2025 CodeLibs Project and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.codelibs.fess.ds.notion;

import java.util.ArrayList;
import java.util.List;

import org.codelibs.fess.entity.DataStoreParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.fasterxml.jackson.databind.JsonNode;

public class NotionClientTest extends UnitDsTestCase {

    private NotionApiMockServer server;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new NotionApiMockServer();
        server.start();
    }

    @Override
    protected void tearDown(final TestInfo testInfo) throws Exception {
        try {
            if (server != null) {
                server.stop();
            }
        } finally {
            super.tearDown(testInfo);
        }
    }

    @Test
    public void test_constructor_requiresToken() {
        try {
            new NotionClient(new DataStoreParams());
            fail("a missing token must be rejected");
        } catch (final NotionDataStoreException e) {
            // expected
        }
    }

    @Test
    public void test_headers_authorizationAndVersion() {
        server.enqueue("/v1/pages/P1", NotionApiMockServer.json("{\"object\":\"page\",\"id\":\"P1\"}"));
        final NotionClient client = server.newClient("secret_abc");

        client.getPage("P1");

        assertEquals(List.of("Bearer secret_abc"), server.getAuthorizations("/v1/pages/P1"));
        assertEquals(List.of(NotionClient.NOTION_VERSION), server.getNotionVersions("/v1/pages/P1"));
    }

    @Test
    public void test_getDatabase() {
        server.enqueue("/v1/databases/D1",
                NotionApiMockServer.json("{\"object\":\"database\",\"id\":\"D1\",\"data_sources\":[{\"id\":\"DS1\",\"name\":\"Main\"}]}"));
        final NotionClient client = server.newClient("secret_abc");

        final JsonNode database = client.getDatabase("D1");

        assertEquals("D1", database.path("id").asText());
        assertEquals("DS1", database.path("data_sources").get(0).path("id").asText());
    }

    @Test
    public void test_listBlockChildren_followsCursor() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":true,"next_cursor":"CURSOR1","results":[{"id":"B1","type":"paragraph"}]}
                """));
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[{"id":"B2","type":"paragraph"}]}
                """));
        final NotionClient client = server.newClient("secret_abc");

        final List<String> ids = new ArrayList<>();
        client.listBlockChildren("PAGE1", block -> ids.add(block.path("id").asText()));

        assertEquals(List.of("B1", "B2"), ids);
        assertEquals(2, server.getRequestCount("/v1/blocks/PAGE1/children"));
        assertEquals("CURSOR1", server.getQueries("/v1/blocks/PAGE1/children").get(1).get("start_cursor"));
    }

    @Test
    public void test_queryDataSource_postsJsonBody_andFollowsCursor() {
        server.enqueue("/v1/data_sources/DS1/query", NotionApiMockServer.json("""
                {"object":"list","has_more":true,"next_cursor":"C2","results":[{"object":"page","id":"ROW1"}]}
                """));
        server.enqueue("/v1/data_sources/DS1/query", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[{"object":"page","id":"ROW2"}]}
                """));
        final NotionClient client = server.newClient("secret_abc");

        final List<String> ids = new ArrayList<>();
        client.queryDataSource("DS1", row -> ids.add(row.path("id").asText()));

        assertEquals(List.of("ROW1", "ROW2"), ids);
        final List<String> bodies = server.getRequestBodies("/v1/data_sources/DS1/query");
        assertEquals(2, bodies.size());
        assertTrue("first request has no start_cursor", !bodies.get(0).contains("start_cursor"));
        assertTrue("second request carries the cursor from the first response", bodies.get(1).contains("C2"));
    }

    @Test
    public void test_search_postsFilterValue() {
        server.enqueue("/v1/search", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[{"object":"page","id":"P1"}],
                 "request_status":{"type":"complete"}}
                """));
        final NotionClient client = server.newClient("secret_abc");

        final List<String> ids = new ArrayList<>();
        client.search("page", node -> ids.add(node.path("id").asText()));

        assertEquals(List.of("P1"), ids);
        assertTrue(server.getRequestBodies("/v1/search").get(0).contains("\"value\":\"page\""));
    }

    @Test
    public void test_retry_on5xx_thenSucceeds() {
        server.enqueue("/v1/pages/P1", NotionApiMockServer.status(503, "{\"object\":\"error\",\"status\":503,\"code\":\"internal\"}"));
        server.enqueue("/v1/pages/P1", NotionApiMockServer.json("{\"object\":\"page\",\"id\":\"P1\"}"));
        final NotionClient client = server.newClient("secret_abc");

        final JsonNode page = client.getPage("P1");

        assertEquals("P1", page.path("id").asText());
        assertEquals(2, server.getRequestCount("/v1/pages/P1"));
    }

    @Test
    public void test_retry_honoursRetryAfterHeader() {
        server.enqueue("/v1/pages/P1", NotionApiMockServer.rateLimited(0));
        server.enqueue("/v1/pages/P1", NotionApiMockServer.json("{\"object\":\"page\",\"id\":\"P1\"}"));
        final NotionClient client = server.newClient("secret_abc");

        final long start = System.currentTimeMillis();
        client.getPage("P1");
        final long elapsed = System.currentTimeMillis() - start;

        // Retry-After: 0 must not fall through to the (much longer) default backoff.
        assertTrue("a zero Retry-After must be honoured, not padded out to the backoff default", elapsed < 900L);
    }

    @Test
    public void test_retriesExhausted_isNotFatal() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "secret_abc");
        paramMap.put("request_interval", "0");
        paramMap.put("max_retry_count", "1");
        // The exponential-backoff base (DEFAULT_RETRY_BASE_MILLIS) is not configurable -- see
        // NotionClient's javadoc -- so this test pays one real ~1s sleep for the single retry.
        server.enqueue("/v1/pages/P1", NotionApiMockServer.status(503, "{\"object\":\"error\",\"status\":503,\"code\":\"internal\"}"));
        server.enqueue("/v1/pages/P1", NotionApiMockServer.status(503, "{\"object\":\"error\",\"status\":503,\"code\":\"internal\"}"));
        final NotionClient client = server.newClient(paramMap);

        try {
            client.getPage("P1");
            fail("exhausting every retry on a 503 must still throw");
        } catch (final NotionApiException e) {
            // Not fatal: NotionClient cannot tell a transient outage from a permanent one, and
            // aborting the crawl on that guess would delete every not-yet-recrawled document of
            // this config (see NotionApiException's javadoc). Recorded as a per-item failure by
            // NotionDataStore instead; see NotionDataStoreTest for that end-to-end behavior.
            assertFalse("exhausting retries on a 5xx must not be fatal", e.isFatal());
            assertEquals(503, e.getStatus());
        }
        assertEquals(2, server.getRequestCount("/v1/pages/P1"));
    }

    @Test
    public void test_unauthorized_isFatal() {
        server.enqueue("/v1/pages/P1", NotionApiMockServer.error(401, "unauthorized"));
        final NotionClient client = server.newClient("secret_abc");

        try {
            client.getPage("P1");
            fail("401 must be fatal");
        } catch (final NotionApiException e) {
            assertTrue(e.isFatal());
            assertEquals(401, e.getStatus());
            assertEquals("unauthorized", e.getCode());
        }
    }

    @Test
    public void test_blockedRateLimit_isFatal() {
        server.enqueue("/v1/pages/P1", NotionApiMockServer.blockedRateLimited());
        final NotionClient client = server.newClient("secret_abc");

        try {
            client.getPage("P1");
            fail("a public_api_request_blocked 429 must be fatal, not retried");
        } catch (final NotionApiException e) {
            assertTrue(e.isFatal());
            assertEquals(429, e.getStatus());
        }
        // A blocked rate limit must not be retried at all.
        assertEquals(1, server.getRequestCount("/v1/pages/P1"));
    }

    @Test
    public void test_notFound_isNotFatal() {
        server.enqueue("/v1/pages/P1", NotionApiMockServer.error(404, "object_not_found"));
        final NotionClient client = server.newClient("secret_abc");

        try {
            client.getPage("P1");
            fail("404 must still throw");
        } catch (final NotionApiException e) {
            assertFalse("404 object_not_found must not be fatal", e.isFatal());
            assertEquals(404, e.getStatus());
            assertEquals("object_not_found", e.getCode());
        }
    }

    @Test
    public void test_forbidden_isNotFatal() {
        server.enqueue("/v1/comments", NotionApiMockServer.error(403, "restricted_resource"));
        final NotionClient client = server.newClient("secret_abc");

        try {
            client.listComments("P1", n -> {});
            fail("403 must still throw");
        } catch (final NotionApiException e) {
            assertFalse("403 restricted_resource must not be fatal", e.isFatal());
            assertEquals(403, e.getStatus());
        }
    }

    /**
     * Regression test: a transport-level failure (no HTTP response received at all) must be
     * retried with the same budget as a retryable status, and exhausting it must not be fatal --
     * same as exhausting status-based retries (see {@link #test_retriesExhausted_isNotFatal}).
     * Points the client at {@code 127.0.0.1:1}, a loopback port nothing listens on, which refuses
     * the connection immediately rather than timing out.
     */
    @Test
    public void test_transportError_retriedThenExhausted_isNotFatal() {
        NotionClient.setEndpoint("http://127.0.0.1:1/");
        try {
            final DataStoreParams paramMap = new DataStoreParams();
            paramMap.put("token", "secret_abc");
            paramMap.put("request_interval", "0");
            paramMap.put("max_retry_count", "1");
            paramMap.put("connection_timeout", "1000");
            paramMap.put("read_timeout", "1000");
            final NotionClient client = new NotionClient(paramMap);

            try {
                client.getPage("P1");
                fail("a persistent transport error must still throw once retries are exhausted");
            } catch (final NotionApiException e) {
                assertFalse("a transport error must not be fatal", e.isFatal());
            }
        } finally {
            NotionClient.setEndpoint(server.getEndpoint());
        }
    }

    @Test
    public void test_negativeMaxRetryCount_fallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "secret_abc");
        paramMap.put("max_retry_count", "-1");
        final NotionClient client = new NotionClient(paramMap);
        assertEquals(NotionClient.DEFAULT_MAX_RETRY_COUNT, client.maxRetryCount);
    }

    @Test
    public void test_zeroMaxRetryCount_isAccepted() {
        // 0 ("never retry") is a legitimate choice, unlike a negative value.
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "secret_abc");
        paramMap.put("max_retry_count", "0");
        final NotionClient client = new NotionClient(paramMap);
        assertEquals(0, client.maxRetryCount);
    }

    @Test
    public void test_negativeRequestInterval_fallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "secret_abc");
        paramMap.put("request_interval", "-5");
        final NotionClient client = new NotionClient(paramMap);
        assertEquals(NotionClient.DEFAULT_REQUEST_INTERVAL, client.requestInterval);
    }

    @Test
    public void test_zeroRequestInterval_isAccepted() {
        // 0 ("no pacing") is a legitimate choice, unlike a negative value.
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "secret_abc");
        paramMap.put("request_interval", "0");
        final NotionClient client = new NotionClient(paramMap);
        assertEquals(0L, client.requestInterval);
    }

    @Test
    public void test_nonPositiveConnectionTimeout_fallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "secret_abc");
        paramMap.put("connection_timeout", "0");
        final NotionClient client = new NotionClient(paramMap);
        assertEquals(NotionClient.DEFAULT_CONNECTION_TIMEOUT, client.connectionTimeout);
    }

    @Test
    public void test_negativeReadTimeout_fallsBackToDefault() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", "secret_abc");
        paramMap.put("read_timeout", "-100");
        final NotionClient client = new NotionClient(paramMap);
        assertEquals(NotionClient.DEFAULT_READ_TIMEOUT, client.readTimeout);
    }

}
