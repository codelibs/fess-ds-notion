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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.codelibs.fess.entity.DataStoreParams;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the Notion API, backed by the JDK's built-in HTTP server. Responses are
 * queued per path (the path only -- Notion's own ids already make each path unique, e.g.
 * {@code /v1/pages/xxx} -- query strings and request bodies are recorded but not matched on) and
 * consumed in order. Unlike {@code SlackApiMockServer} in fess-ds-slack, {@link NotionClient}'s
 * constructor makes no request of its own, so there is no implicit preload to account for.
 *
 * <p>
 * A path with no queued response answers with a well-formed, empty paginated list ({@code
 * DEFAULT_RESPONSE_BODY}), which satisfies every list-shaped endpoint this client calls ({@code
 * blocks/*\/children}, {@code data_sources/*\/query}, {@code search}, {@code comments}, {@code
 * pages/*\/properties/*}). It does not satisfy {@code GET pages/{id}} or {@code GET
 * databases/{id}}, whose response is a single object: always enqueue an explicit response for
 * those two. Call {@link #setStrict(boolean)} to turn the default off entirely and make an
 * unscripted request fail loudly instead.
 * </p>
 */
public class NotionApiMockServer implements AutoCloseable {

    /** The body returned for a path with no queued response; see the class javadoc for its scope. */
    static final String DEFAULT_RESPONSE_BODY = "{\"object\":\"list\",\"results\":[],\"next_cursor\":null,\"has_more\":false}";

    /** The body returned for an unscripted request while {@link #setStrict(boolean)} is on. */
    static final String UNSCRIPTED_RESPONSE_BODY = "{\"object\":\"error\",\"status\":400,\"code\":\"unscripted_request\","
            + "\"message\":\"No response was queued for this path.\"}";

    private HttpServer server;

    private volatile boolean strict;

    private final Map<String, List<MockResponse>> queued = new ConcurrentHashMap<>();

    private final Map<String, List<Map<String, String>>> receivedQueries = new ConcurrentHashMap<>();

    private final Map<String, List<String>> receivedBodies = new ConcurrentHashMap<>();

    private final Map<String, List<String>> receivedAuthorizations = new ConcurrentHashMap<>();

    private final Map<String, List<String>> receivedNotionVersions = new ConcurrentHashMap<>();

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    private final Map<String, String> fileContentTypes = new ConcurrentHashMap<>();

    /** A canned HTTP response. */
    public static class MockResponse {
        final int statusCode;
        final String body;
        final Map<String, String> headers;

        MockResponse(final int statusCode, final String body, final Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers;
        }
    }

    /**
     * Builds an HTTP 200 response carrying the given JSON body.
     *
     * @param body the JSON body
     * @return the response
     */
    public static MockResponse json(final String body) {
        return new MockResponse(200, body, Collections.emptyMap());
    }

    /**
     * Builds an HTTP 429 response carrying a {@code Retry-After} header, as Notion documents for
     * a rate-limited request.
     *
     * @param retryAfterSeconds the value of the {@code Retry-After} header, in seconds
     * @return the response
     */
    public static MockResponse rateLimited(final int retryAfterSeconds) {
        return rateLimited(Integer.toString(retryAfterSeconds));
    }

    /**
     * Builds an HTTP 429 response carrying a {@code Retry-After} header with an arbitrary raw
     * value, for exercising a malformed header {@link #rateLimited(int)} cannot express.
     *
     * @param retryAfterHeaderValue the raw value of the {@code Retry-After} header
     * @return the response
     */
    public static MockResponse rateLimited(final String retryAfterHeaderValue) {
        final Map<String, String> headers = new HashMap<>();
        headers.put("Retry-After", retryAfterHeaderValue);
        return new MockResponse(429, "{\"object\":\"error\",\"status\":429,\"code\":\"rate_limited\",\"message\":\"rate limited\"}",
                headers);
    }

    /**
     * Builds an HTTP 429 response whose body names {@code
     * additional_data.rate_limit_reason=public_api_request_blocked}, the one 429 reason
     * {@link NotionClient} treats as fatal rather than retryable.
     *
     * @return the response
     */
    public static MockResponse blockedRateLimited() {
        return new MockResponse(429, "{\"object\":\"error\",\"status\":429,\"code\":\"rate_limited\",\"message\":\"blocked\","
                + "\"additional_data\":{\"rate_limit_reason\":\"public_api_request_blocked\"}}", Collections.emptyMap());
    }

    /**
     * Builds a response with an arbitrary status code and body.
     *
     * @param code the HTTP status code
     * @param body the response body
     * @return the response
     */
    public static MockResponse status(final int code, final String body) {
        return new MockResponse(code, body, Collections.emptyMap());
    }

    /**
     * Builds a Notion-shaped error response with the given status and code.
     *
     * @param status the HTTP status code
     * @param code the Notion error code, e.g. {@code "object_not_found"}
     * @return the response
     */
    public static MockResponse error(final int status, final String code) {
        return new MockResponse(status,
                "{\"object\":\"error\",\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + code + "\"}",
                Collections.emptyMap());
    }

    /**
     * Starts the server on an ephemeral port and points {@link NotionClient} at it.
     *
     * @throws IOException if the server cannot bind
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", this::handle);
        server.createContext("/files/", this::handleFile);
        server.setExecutor(null);
        server.start();
        NotionClient.setEndpoint(getEndpoint());
    }

    /**
     * Stops the server and restores the production endpoint. Safe to call more than once, and
     * safe to call when the server was never started.
     */
    public void stop() {
        NotionClient.resetEndpoint();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Returns the base URL this server is listening on.
     *
     * @return the endpoint URL, ending with a slash
     * @throws IllegalStateException if the server has not been {@link #start() started}, or has
     *             already been {@link #stop() stopped}
     */
    public String getEndpoint() {
        final HttpServer current = server;
        if (current == null) {
            throw new IllegalStateException("NotionApiMockServer is not running: call start() first, or it has already been stop()ped");
        }
        return "http://127.0.0.1:" + current.getAddress().getPort() + "/v1/";
    }

    /**
     * Constructs a {@link NotionClient} against this server with {@code request_interval=0}, so
     * tests run at full speed.
     *
     * @param token the integration secret
     * @return a client constructed against this server's endpoint
     */
    public NotionClient newClient(final String token) {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", token);
        paramMap.put("request_interval", "0");
        return newClient(paramMap);
    }

    /**
     * Constructs a {@link NotionClient} against this server from a caller-built parameter map
     * (for a test that needs to set {@code max_retry_count} or similar). The caller is
     * responsible for setting {@code request_interval=0} if the test cares about speed.
     *
     * @param paramMap the configuration parameters, e.g. {@code token}
     * @return a client constructed against this server's endpoint
     */
    public NotionClient newClient(final DataStoreParams paramMap) {
        return new NotionClient(paramMap);
    }

    /**
     * Serves raw bytes (not a Notion API JSON response) at {@code /files/<name>}, standing in for
     * a Notion-hosted signed download URL, which is never a {@code /v1/*} path in production
     * either.
     *
     * @param name the file name, used verbatim as the path segment
     * @param content the raw content to serve
     * @param contentType the {@code Content-Type} to answer with
     * @return the absolute URL this file is now served at
     */
    public String putFile(final String name, final String content, final String contentType) {
        files.put("/files/" + name, content.getBytes(StandardCharsets.UTF_8));
        fileContentTypes.put("/files/" + name, contentType);
        final HttpServer current = server;
        if (current == null) {
            throw new IllegalStateException("NotionApiMockServer is not running: call start() first");
        }
        return "http://127.0.0.1:" + current.getAddress().getPort() + "/files/" + name;
    }

    private void handleFile(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        drain(exchange.getRequestBody());
        final byte[] body = files.get(path);
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        final String contentType = fileContentTypes.getOrDefault(path, "application/octet-stream");
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void drain(final InputStream in) throws IOException {
        final byte[] buf = new byte[1024];
        while (in.read(buf) != -1) {
            // discard
        }
    }

    /**
     * Queues one response for the given path. Responses are consumed in order.
     *
     * @param path the request path, for example {@code /v1/pages/xxx}
     * @param response the response to return
     */
    public void enqueue(final String path, final MockResponse response) {
        queued.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>())).add(response);
    }

    /**
     * Returns how many times the given path was requested.
     *
     * @param path the request path
     * @return the request count
     */
    public int getRequestCount(final String path) {
        return getQueries(path).size();
    }

    /**
     * Returns how many responses enqueued for the given path have not yet been consumed.
     *
     * @param path the request path
     * @return the number of responses still queued
     */
    public int getQueuedCount(final String path) {
        final List<MockResponse> responses = queued.get(path);
        if (responses == null) {
            return 0;
        }
        synchronized (responses) {
            return responses.size();
        }
    }

    /**
     * Asserts that every response enqueued via {@link #enqueue} was consumed by a request.
     *
     * @throws AssertionError if any path still has unconsumed responses
     */
    public void assertAllConsumed() {
        final StringBuilder message = new StringBuilder();
        for (final Map.Entry<String, List<MockResponse>> entry : queued.entrySet()) {
            final int remaining;
            synchronized (entry.getValue()) {
                remaining = entry.getValue().size();
            }
            if (remaining > 0) {
                if (message.length() > 0) {
                    message.append(", ");
                }
                message.append(entry.getKey()).append(" (").append(remaining).append(" left)");
            }
        }
        if (message.length() > 0) {
            throw new AssertionError("Unconsumed queued responses: " + message);
        }
    }

    /**
     * Sets whether an unscripted request is answered with an error instead of
     * {@link #DEFAULT_RESPONSE_BODY}. Off by default. See the class javadoc.
     *
     * @param strict whether unscripted requests should fail loudly
     */
    public void setStrict(final boolean strict) {
        this.strict = strict;
    }

    /**
     * Returns the query parameters of each request made to the given path, in arrival order.
     *
     * @param path the request path
     * @return the recorded query parameter maps
     */
    public List<Map<String, String>> getQueries(final String path) {
        return snapshot(receivedQueries, path);
    }

    /**
     * Returns the raw request body of each request made to the given path, in arrival order
     * (empty string for a GET).
     *
     * @param path the request path
     * @return the recorded request bodies
     */
    public List<String> getRequestBodies(final String path) {
        return snapshot(receivedBodies, path);
    }

    /**
     * Returns the {@code Authorization} header of each request made to the given path, in
     * arrival order; {@code null} for a request that sent no such header.
     *
     * @param path the request path
     * @return the recorded header values
     */
    public List<String> getAuthorizations(final String path) {
        return snapshot(receivedAuthorizations, path);
    }

    /**
     * Returns the {@code Notion-Version} header of each request made to the given path, in
     * arrival order.
     *
     * @param path the request path
     * @return the recorded header values
     */
    public List<String> getNotionVersions(final String path) {
        return snapshot(receivedNotionVersions, path);
    }

    private <T> List<T> snapshot(final Map<String, List<T>> store, final String path) {
        final List<T> values = store.get(path);
        if (values == null) {
            return Collections.emptyList();
        }
        synchronized (values) {
            return new ArrayList<>(values);
        }
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        receivedQueries.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(parseQuery(exchange.getRequestURI().getRawQuery()));
        receivedAuthorizations.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedNotionVersions.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(exchange.getRequestHeaders().getFirst("Notion-Version"));
        receivedBodies.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>())).add(readBody(exchange.getRequestBody()));

        final List<MockResponse> responses = queued.get(path);
        MockResponse response = null;
        if (responses != null) {
            synchronized (responses) {
                if (!responses.isEmpty()) {
                    response = responses.remove(0);
                }
            }
        }
        if (response == null) {
            response = strict ? status(400, UNSCRIPTED_RESPONSE_BODY) : json(DEFAULT_RESPONSE_BODY);
        }

        final byte[] payload = response.body.getBytes(StandardCharsets.UTF_8);
        response.headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.statusCode, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private String readBody(final InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        in.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private Map<String, String> parseQuery(final String rawQuery) {
        final Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (final String pair : rawQuery.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
