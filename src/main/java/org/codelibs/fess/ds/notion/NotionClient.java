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
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.exception.InterruptedRuntimeException;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.curl.Curl;
import org.codelibs.curl.CurlException;
import org.codelibs.curl.CurlRequest;
import org.codelibs.curl.CurlResponse;
import org.codelibs.fess.entity.DataStoreParams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A thin HTTP client for the subset of the Notion API this data store needs: authentication
 * headers, cursor-based pagination, request pacing, retry with typed errors.
 *
 * <p>
 * Every call returns a Jackson {@link JsonNode} tree rather than a typed response object.
 * Notion's block/property model has dozens of shapes (over two dozen block types alone, each
 * with its own nested payload), so a hand-written POJO per shape would be a large amount of
 * code for very little safety; {@link BlockTextExtractor} and {@link PropertyFormatter} read
 * the tree defensively with {@code path(...)} instead, which never throws on a missing or
 * differently-shaped field.
 * </p>
 *
 * <p>
 * {@link #setEndpoint(String)} overrides the API base URL process-wide, for tests to point this
 * class at a local mock server. Like {@code org.codelibs.fess.ds.slack.api.Request} in
 * fess-ds-slack, production code must never call it, and a test must reset it (see
 * {@link #resetEndpoint()}) even on failure.
 * </p>
 */
public class NotionClient implements Closeable {

    private static final Logger logger = LogManager.getLogger(NotionClient.class);

    /** The Notion API version this client speaks: 2026-03-11, the first version that uses {@code in_trash} and data sources throughout. */
    public static final String NOTION_VERSION = "2026-03-11";

    /** The default Notion API base URL. */
    public static final String DEFAULT_ENDPOINT = "https://api.notion.com/v1/";

    /** Default milliseconds to sleep before each API request, to stay under Notion's rate limit. */
    public static final long DEFAULT_REQUEST_INTERVAL = 350L;

    /** Default maximum number of retries for a retryable (429/5xx/529) response. */
    public static final int DEFAULT_MAX_RETRY_COUNT = 5;

    /** Default connection timeout in milliseconds. */
    public static final int DEFAULT_CONNECTION_TIMEOUT = 10000;

    /** Default read timeout in milliseconds. */
    public static final int DEFAULT_READ_TIMEOUT = 30000;

    /** Page size requested for every paginated call; the maximum Notion allows. */
    protected static final int DEFAULT_PAGE_SIZE = 100;

    /** Upper bound, in milliseconds, on the wait before a retry, whether from {@code Retry-After} or backoff. */
    protected static final long MAX_RETRY_WAIT_MILLIS = 60000L;

    /**
     * Base wait, in milliseconds, for the exponential backoff used when a retryable response
     * carries no {@code Retry-After} header. Not configurable: the design intentionally exposes
     * only {@code request_interval} (steady-state pacing) and {@code max_retry_count} as
     * parameters, not the backoff base itself.
     */
    protected static final long DEFAULT_RETRY_BASE_MILLIS = 1000L;

    /** The result cap {@code POST /v1/data_sources/{id}/query} enforces per query. */
    protected static final int MAX_QUERY_RESULTS = 10000;

    /** Parameter name for the Notion internal integration secret. */
    protected static final String TOKEN_PARAM = "token";

    /** Parameter name for the pacing delay, in milliseconds, applied before each API request. */
    protected static final String REQUEST_INTERVAL_PARAM = "request_interval";

    /** Parameter name for the maximum number of retries for a retryable response. */
    protected static final String MAX_RETRY_COUNT_PARAM = "max_retry_count";

    /** Parameter name for the connection timeout, in milliseconds. */
    protected static final String CONNECTION_TIMEOUT_PARAM = "connection_timeout";

    /** Parameter name for the read timeout, in milliseconds. */
    protected static final String READ_TIMEOUT_PARAM = "read_timeout";

    /** The Notion API base URL in effect. Overridable for testing via {@link #setEndpoint(String)}. */
    private static volatile String notionApiEndpoint = DEFAULT_ENDPOINT;

    /** Jackson ObjectMapper for JSON parsing; Notion responses carry no polymorphic type Jackson needs configured for. */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Returns the Notion API base URL currently in effect.
     *
     * @return the endpoint URL, always ending with a slash
     */
    protected static String getEndpoint() {
        return notionApiEndpoint;
    }

    /**
     * Overrides the Notion API base URL. Intended for testing against a local stub server;
     * production code must not call this.
     *
     * @param endpoint the endpoint URL, which must end with a slash
     * @throws IllegalArgumentException if {@code endpoint} is {@code null} or does not end with a
     *             slash
     */
    public static void setEndpoint(final String endpoint) {
        if (endpoint == null || !endpoint.endsWith("/")) {
            throw new IllegalArgumentException("endpoint must be non-null and end with '/': " + endpoint);
        }
        notionApiEndpoint = endpoint;
    }

    /**
     * Restores the Notion API base URL to {@link #DEFAULT_ENDPOINT}.
     */
    public static void resetEndpoint() {
        notionApiEndpoint = DEFAULT_ENDPOINT;
    }

    /** The internal integration secret. */
    protected final String token;

    /** Milliseconds to sleep before each API request's first attempt. */
    protected final long requestInterval;

    /** Maximum number of retries for a retryable response. */
    protected final int maxRetryCount;

    /** Connection timeout, in milliseconds, for each API request. */
    protected final int connectionTimeout;

    /** Read timeout, in milliseconds, for each API request. */
    protected final int readTimeout;

    /**
     * Creates a new Notion client from the data store's configuration parameters.
     *
     * @param paramMap the configuration parameters, including {@code token}
     * @throws NotionDataStoreException if {@code token} is missing
     */
    public NotionClient(final DataStoreParams paramMap) {
        token = paramMap.getAsString(TOKEN_PARAM, StringUtil.EMPTY);
        if (token.isEmpty()) {
            throw new NotionDataStoreException("Parameter " + TOKEN_PARAM + " is required.");
        }
        // request_interval=0 is a legitimate "no pacing" choice, so only a negative value is
        // rejected; max_retry_count=0 is likewise legitimate ("never retry"). connection_timeout
        // and read_timeout have no such zero-is-valid reading -- curl4j/HttpURLConnection treats
        // 0 as "no timeout", not "fail fast" -- so those reject any non-positive value.
        requestInterval = getNonNegativeLongParam(paramMap, REQUEST_INTERVAL_PARAM, DEFAULT_REQUEST_INTERVAL);
        maxRetryCount = getNonNegativeIntParam(paramMap, MAX_RETRY_COUNT_PARAM, DEFAULT_MAX_RETRY_COUNT);
        connectionTimeout = getPositiveIntParam(paramMap, CONNECTION_TIMEOUT_PARAM, DEFAULT_CONNECTION_TIMEOUT);
        readTimeout = getPositiveIntParam(paramMap, READ_TIMEOUT_PARAM, DEFAULT_READ_TIMEOUT);
    }

    /**
     * Retrieves a page object.
     *
     * @param pageId the page id
     * @return the page object
     */
    public JsonNode getPage(final String pageId) {
        return get("pages/" + pageId, Map.of());
    }

    /**
     * Retrieves a database object, whose {@code data_sources} array names the data source(s) to
     * query for rows.
     *
     * @param databaseId the database id
     * @return the database object
     */
    public JsonNode getDatabase(final String databaseId) {
        return get("databases/" + databaseId, Map.of());
    }

    /**
     * Retrieves every direct child block of {@code blockId} (a page or a block), across as many
     * pages as needed, handing each to {@code consumer} as it arrives.
     *
     * @param blockId the parent block (or page) id
     * @param consumer called once per child block, in order
     */
    public void listBlockChildren(final String blockId, final Consumer<JsonNode> consumer) {
        paginateGet("blocks/" + blockId + "/children", Map.of(), consumer);
    }

    /**
     * Retrieves every row of a data source, across as many pages as needed, handing each row's
     * page object to {@code consumer} as it arrives.
     *
     * <p>
     * Warns once if the 10,000-row cap Notion enforces on a single query is reached: rows beyond
     * that cap are not visible through this API call at all, so this crawl silently misses them
     * regardless of how this method is written; the warning only makes that omission visible to
     * an operator.
     * </p>
     *
     * @param dataSourceId the data source id
     * @param consumer called once per row (a page object), in order
     */
    public void queryDataSource(final String dataSourceId, final Consumer<JsonNode> consumer) {
        String cursor = null;
        int total = 0;
        boolean warned = false;
        do {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("page_size", DEFAULT_PAGE_SIZE);
            if (cursor != null) {
                body.put("start_cursor", cursor);
            }
            final JsonNode page = post("data_sources/" + dataSourceId + "/query", body);
            for (final JsonNode row : page.path("results")) {
                consumer.accept(row);
                total++;
            }
            if (!warned && total >= MAX_QUERY_RESULTS) {
                logger.warn("Notion data source {} query reached the {}-result cap; rows beyond that cap were not retrieved.", dataSourceId,
                        MAX_QUERY_RESULTS);
                warned = true;
            }
            cursor = nextCursor(page);
        } while (cursor != null);
    }

    /**
     * Searches every {@code page} or {@code data_source} object shared with the integration,
     * handing each to {@code consumer} as it arrives. Used only to discover crawl roots when
     * neither {@code root_page_ids} nor {@code database_ids} is configured; Notion documents this
     * endpoint as not guaranteed to be exhaustive.
     *
     * @param filterValueType {@code "page"} or {@code "data_source"}
     * @param consumer called once per result object, in order
     */
    public void search(final String filterValueType, final Consumer<JsonNode> consumer) {
        String cursor = null;
        boolean warned = false;
        do {
            final Map<String, Object> body = new LinkedHashMap<>();
            final Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("value", filterValueType);
            filter.put("property", "object");
            body.put("filter", filter);
            body.put("page_size", DEFAULT_PAGE_SIZE);
            if (cursor != null) {
                body.put("start_cursor", cursor);
            }
            final JsonNode page = post("search", body);
            page.path("results").forEach(consumer);
            if (!warned && "incomplete".equals(page.path("request_status").path("type").asText(null))) {
                logger.warn("Notion /v1/search reported an incomplete result while discovering '{}' roots; "
                        + "some pages or databases shared with the integration may not have been found. "
                        + "Configure root_page_ids/database_ids to crawl them explicitly.", filterValueType);
                warned = true;
            }
            cursor = nextCursor(page);
        } while (cursor != null);
    }

    /**
     * Retrieves every comment on a page, across as many pages as needed, handing each to
     * {@code consumer} as it arrives. Notion returns only unresolved comments.
     *
     * @param pageId the page (block) id comments are attached to
     * @param consumer called once per comment, in order
     * @throws NotionApiException with {@link NotionApiException#getStatus()} {@code == 403} when
     *             the integration lacks the "Read comments" capability
     */
    public void listComments(final String pageId, final Consumer<JsonNode> consumer) {
        paginateGet("comments", Map.of("block_id", pageId), consumer);
    }

    /**
     * Retrieves the full value of a paginated page property -- a {@code relation} or
     * {@code people} property carrying more than the 25 references embedded directly in the page
     * object.
     *
     * @param pageId the page id
     * @param propertyId the property's internal id (the value node's {@code id} field, not its
     *            display name)
     * @return every result entry, across as many pages as needed
     */
    public List<JsonNode> getPropertyItems(final String pageId, final String propertyId) {
        final List<JsonNode> items = new ArrayList<>();
        paginateGet("pages/" + pageId + "/properties/" + propertyId, Map.of(), items::add);
        return items;
    }

    /**
     * The outcome of {@link #getFile(String, long)}: the HTTP status code, and the bytes actually
     * received for that status (the error body, for a non-2xx status -- the caller decides what
     * counts as success).
     *
     * @param statusCode the HTTP status code
     * @param content the response body
     */
    public record FileResponse(int statusCode, byte[] content) {
    }

    /**
     * Thrown by {@link #getFile(String, long)} when the download is abandoned because it exceeds
     * {@code maxFileSize} -- either a declared {@code Content-Length} over the limit (the body is
     * then never read at all) or, absent that header, the byte count actually read exceeding it
     * (the connection is closed at that point; the rest of the body is never read either).
     *
     * <p>
     * Carries only the observed size, deliberately never the URL: the download URL is a
     * time-limited Notion-hosted signed URL, which {@code NotionDataStore} must not let reach a
     * log or {@code FailureUrlService} record (see its attachment-handling javadoc).
     * </p>
     */
    public static final class AttachmentTooLargeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The observed size, in bytes, that triggered this exception. */
        private final long size;

        AttachmentTooLargeException(final long size) {
            super("Attachment exceeds the configured max_file_size (observed " + size + " bytes).");
            this.size = size;
        }

        /**
         * Returns the observed size, in bytes, that triggered this exception: the declared
         * {@code Content-Length} if one was present, otherwise the number of bytes read before
         * the cap was exceeded.
         *
         * @return the observed size in bytes
         */
        public long size() {
            return size;
        }
    }

    /**
     * Downloads a file from a signed URL (Notion-hosted {@code file} object), stopping as soon as
     * it is known to exceed {@code maxFileSize} rather than downloading it in full first. No
     * authentication header is sent: the URL itself is time-limited and pre-authorized, and it is
     * not a Notion API URL.
     *
     * <p>
     * Uses {@link CurlRequest#onConnect} to inspect the connection -- and, absent a declared
     * {@code Content-Length}, to read and count the body itself -- before curl4j's own response
     * processing would otherwise read the whole body unconditionally; see the Javadoc of
     * {@link #execute(CurlRequest, String)} for why {@code CurlResponse} cannot be used for this
     * on its own. A gzip-encoded response is not decompressed by this path (unlike the API calls
     * in {@link #execute}), since a Notion-hosted signed download URL is not documented to ever
     * send one.
     * </p>
     *
     * @param fileUrl the signed download URL
     * @param maxFileSize the maximum number of bytes to download
     * @return the status code and body received
     * @throws AttachmentTooLargeException if the download was abandoned for exceeding
     *             {@code maxFileSize}
     */
    public FileResponse getFile(final String fileUrl, final long maxFileSize) {
        final FileResponse[] holder = new FileResponse[1];
        final CurlRequest request = Curl.get(fileUrl).timeout(connectionTimeout, readTimeout);
        request.onConnect((req, con) -> {
            try {
                final int status = con.getResponseCode();
                final long declaredSize = con.getContentLengthLong();
                if (declaredSize >= 0 && declaredSize > maxFileSize) {
                    throw new AttachmentTooLargeException(declaredSize);
                }
                final InputStream body = status >= 400 ? con.getErrorStream() : con.getInputStream();
                if (body == null) {
                    holder[0] = new FileResponse(status, new byte[0]);
                    return;
                }
                // Deliberately not closed here: this callback runs before curl4j's own
                // RequestProcessor reads this same connection's body again (see this method's
                // javadoc), and closing it here made that second read throw, which surfaced as a
                // CurlException out of execute() below despite holder[0] already having the
                // right content -- a false failure. RequestProcessor is the one that closes it
                // (via the CurlResponse this method discards); reading an already-exhausted but
                // still-open stream a second time just returns EOF immediately, which is
                // harmless.
                holder[0] = new FileResponse(status, readCapped(body, maxFileSize));
            } catch (final IOException e) {
                throw new CurlException("Failed to read the attachment response.", e);
            }
        });
        CurlResponse response = null;
        try {
            response = request.execute();
        } catch (final CurlException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof final AttachmentTooLargeException tooLarge) {
                    throw tooLarge;
                }
            }
            throw e;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (final IOException e) {
                    // The content this response would carry was never read from it (see above);
                    // a failure to close it has nothing useful left to report.
                }
            }
        }
        return holder[0];
    }

    /**
     * Reads {@code in} fully, throwing {@link AttachmentTooLargeException} as soon as more than
     * {@code maxFileSize} bytes have been read -- used for the case {@link #getFile} cannot check
     * upfront, a response with no {@code Content-Length}.
     */
    private byte[] readCapped(final InputStream in, final long maxFileSize) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxFileSize) {
                throw new AttachmentTooLargeException(total);
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Releases this client. Nothing here holds an open resource of its own -- every call opens
     * and closes its own {@link CurlResponse} -- so this is a no-op, kept only so this class can
     * be used in try-with-resources the way {@code SlackClient} is.
     */
    @Override
    public void close() {
        // nothing to release
    }

    private void paginateGet(final String path, final Map<String, String> baseQuery, final Consumer<JsonNode> consumer) {
        String cursor = null;
        do {
            final Map<String, String> query = new LinkedHashMap<>(baseQuery);
            query.put("page_size", String.valueOf(DEFAULT_PAGE_SIZE));
            if (cursor != null) {
                query.put("start_cursor", cursor);
            }
            final JsonNode page = get(path, query);
            page.path("results").forEach(consumer);
            cursor = nextCursor(page);
        } while (cursor != null);
    }

    private String nextCursor(final JsonNode page) {
        if (!page.path("has_more").asBoolean(false)) {
            return null;
        }
        final JsonNode nextCursorNode = page.path("next_cursor");
        return nextCursorNode.isTextual() ? nextCursorNode.asText() : null;
    }

    private JsonNode get(final String path, final Map<String, String> query) {
        final CurlRequest request = Curl.get(getEndpoint() + path);
        query.forEach(request::param);
        return execute(request, path);
    }

    private JsonNode post(final String path, final Map<String, Object> body) {
        final CurlRequest request = Curl.post(getEndpoint() + path);
        request.header("Content-Type", "application/json");
        try {
            request.body(mapper.writeValueAsString(body));
        } catch (final Exception e) {
            throw new NotionDataStoreException("Failed to serialize a Notion API request body for " + path + ".", e);
        }
        return execute(request, path);
    }

    /**
     * Executes a prepared request, retrying on a retryable status or a transport-level failure,
     * and parsing the JSON body of whichever attempt finally returns.
     *
     * <p>
     * A {@link CurlException} thrown by {@code request.execute()} itself -- a connection timeout,
     * a reset, or any other I/O failure before an HTTP response was received at all -- is retried
     * with the same {@code max_retry_count} budget and backoff as a retryable status, sharing the
     * same {@code attempt} counter: there is nothing here to distinguish a flaky connection from a
     * persistent outage, so this class does not try to.
     * </p>
     *
     * @param request the prepared request; authentication and version headers are added here
     * @param path the request path, for pacing/log/exception messages
     * @return the parsed JSON body of a successful (2xx) response
     * @throws NotionApiException if every attempt (or the single attempt, for a non-retryable
     *             status) ended in a non-2xx response or a transport failure. Only a 401 or a 429
     *             naming {@code public_api_request_blocked} sets {@link
     *             NotionApiException#isFatal()}; exhausting every retry, status-based or
     *             transport, does not -- see that method's javadoc for why.
     */
    private JsonNode execute(final CurlRequest request, final String path) {
        request.header("Authorization", "Bearer " + token);
        request.header("Notion-Version", NOTION_VERSION);
        request.timeout(connectionTimeout, readTimeout);
        for (int attempt = 0;; attempt++) {
            if (attempt == 0) {
                pace();
            }
            final CurlResponse response;
            try {
                response = request.execute();
            } catch (final CurlException e) {
                if (attempt < maxRetryCount) {
                    final long waitMillis = backoffMillis(attempt + 1);
                    logger.warn("Retrying Notion API request '{}' (attempt {}) after a transport error: {}; waiting {} ms.", path,
                            attempt + 1, e.getMessage(), waitMillis);
                    sleep(waitMillis);
                    continue;
                }
                logger.warn("Exhausted {} {} on Notion API request '{}' after transport errors.", maxRetryCount,
                        maxRetryCount == 1 ? "retry" : "retries", path);
                throw new NotionApiException(path, 0, "transport_error", e.getMessage(), false);
            }
            try (response) {
                final int status = response.getHttpStatusCode();
                final String content = response.getContentAsString();
                if (status >= 200 && status < 300) {
                    return parseJson(content, path);
                }
                final JsonNode errorBody = tryParseJson(content);
                final String code = errorBody != null && errorBody.hasNonNull("code") ? errorBody.path("code").asText() : null;
                final String message = errorBody != null && errorBody.hasNonNull("message") ? errorBody.path("message").asText()
                        : StringUtil.isNotBlank(content) ? content : "(no body)";
                final boolean blocked = status == 429 && "public_api_request_blocked"
                        .equals(errorBody == null ? null : errorBody.path("additional_data").path("rate_limit_reason").asText(null));
                if (status == 401 || blocked) {
                    throw new NotionApiException(path, status, code, message, true);
                }
                if (isRetryableStatus(status)) {
                    if (attempt < maxRetryCount) {
                        sleepBeforeRetry(response, attempt + 1, status, path);
                        continue;
                    }
                    logger.warn("Exhausted {} {} on Notion API request '{}'; the last response was HTTP {}.", maxRetryCount,
                            maxRetryCount == 1 ? "retry" : "retries", path, status);
                    // A persistently failing status (429/5xx/529) after every retry could be a
                    // transient outage as easily as a permanent one -- this class cannot tell the
                    // difference -- so it is not fatal. Aborting the crawl on that guess would
                    // delete every not-yet-recrawled document of this config (see
                    // NotionApiException's javadoc); recorded as a per-item failure instead.
                    throw new NotionApiException(path, status, code, message, false);
                }
                throw new NotionApiException(path, status, code, message, false);
            } catch (final IOException e) {
                throw new NotionDataStoreException("Failed to close a Notion API response for " + path + ".", e);
            }
        }
    }

    private JsonNode parseJson(final String content, final String path) {
        try {
            return mapper.readTree(content);
        } catch (final Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unparseable Notion API response for {}: {}", path, content);
            }
            throw new NotionDataStoreException(
                    "Failed to parse a Notion API response for " + path + " (" + (content == null ? 0 : content.length()) + " characters).",
                    e);
        }
    }

    private JsonNode tryParseJson(final String content) {
        try {
            return mapper.readTree(content);
        } catch (final Exception e) {
            return null;
        }
    }

    private boolean isRetryableStatus(final int status) {
        return status == 429 || status == 529 || (status >= 500 && status < 600);
    }

    private void pace() {
        if (requestInterval > 0) {
            sleep(requestInterval);
        }
    }

    private void sleepBeforeRetry(final CurlResponse response, final int attempt, final int status, final String path) {
        final long waitMillis = Math.min(getRetryWaitMillis(response, attempt), MAX_RETRY_WAIT_MILLIS);
        logger.warn("Retrying Notion API request '{}' (attempt {}) after HTTP {}; waiting {} ms.", path, attempt, status, waitMillis);
        sleep(waitMillis);
    }

    private long getRetryWaitMillis(final CurlResponse response, final int attempt) {
        final String retryAfter = response.getHeaderValue("Retry-After");
        if (retryAfter != null) {
            try {
                final long seconds = Long.parseLong(retryAfter.trim());
                if (seconds >= 0) {
                    return seconds <= MAX_RETRY_WAIT_MILLIS / 1000L ? seconds * 1000L : MAX_RETRY_WAIT_MILLIS;
                }
            } catch (final NumberFormatException e) {
                // Not a number of seconds (e.g. an RFC 1123 date); fall back to the backoff below.
            }
        }
        return backoffMillis(attempt);
    }

    /**
     * The exponential backoff wait for a retry with no {@code Retry-After} header to honour --
     * every transport-level retry, and a status-based retry whose response carried none.
     *
     * @param attempt the 1-based retry attempt number
     * @return the wait in milliseconds, already capped at {@link #MAX_RETRY_WAIT_MILLIS}
     */
    private long backoffMillis(final int attempt) {
        final int shift = Math.min(attempt - 1, 20);
        return Math.min(DEFAULT_RETRY_BASE_MILLIS * (1L << shift), MAX_RETRY_WAIT_MILLIS);
    }

    private void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedRuntimeException(e);
        }
    }

    private int getIntParam(final DataStoreParams paramMap, final String paramName, final int defaultValue) {
        final String value = paramMap.getAsString(paramName);
        if (StringUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
    }

    private long getLongParam(final DataStoreParams paramMap, final String paramName, final long defaultValue) {
        final String value = paramMap.getAsString(paramName);
        if (StringUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
    }

    private int getNonNegativeIntParam(final DataStoreParams paramMap, final String paramName, final int defaultValue) {
        final int value = getIntParam(paramMap, paramName, defaultValue);
        if (value < 0) {
            logger.warn("Parameter '{}' must not be negative: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private long getNonNegativeLongParam(final DataStoreParams paramMap, final String paramName, final long defaultValue) {
        final long value = getLongParam(paramMap, paramName, defaultValue);
        if (value < 0) {
            logger.warn("Parameter '{}' must not be negative: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private int getPositiveIntParam(final DataStoreParams paramMap, final String paramName, final int defaultValue) {
        final int value = getIntParam(paramMap, paramName, defaultValue);
        if (value <= 0) {
            logger.warn("Parameter '{}' must be positive: {}. Falling back to {}.", paramName, value, defaultValue);
            return defaultValue;
        }
        return value;
    }

}
