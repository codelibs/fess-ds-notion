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

/**
 * Thrown by {@link NotionClient} when a Notion API call finishes with a non-2xx response, after
 * any retry this class performs on its own.
 *
 * <p>
 * Carries {@link #isFatal()} so the caller can tell the two cases the design distinguishes
 * apart:
 * </p>
 * <ul>
 * <li><b>Fatal</b> ({@code true}): HTTP 401 ({@code unauthorized} -- the token itself cannot
 * authenticate) or a 429 whose body names {@code additional_data.rate_limit_reason ==
 * "public_api_request_blocked"}. Both mean every further call this crawl makes would fail the
 * same way, so continuing is useless. This is meant to propagate out of {@code
 * NotionDataStore#storeData} uncaught -- it does not extend {@code CrawlingAccessException} --
 * aborting the whole crawl.
 * <p>
 * <b>Aborting is not free.</b> {@code DataIndexHelper.process()} (in {@code repos/fess}) runs
 * {@code commit()} and {@code deleteOldDocs()} in a {@code finally} block even when {@code
 * store()} throws, so an aborted crawl deletes every document of this data config that was not
 * re-indexed in this session -- active data loss for anything not yet re-crawled, not merely "no
 * partial index." {@code isFatal()} must therefore stay reserved for the two cases above, where
 * continuing genuinely cannot produce a better outcome; everything else -- including a retryable
 * status (429/5xx/529) or a transport-level failure whose retries were exhausted, which is just
 * as likely to be a transient outage as a permanent one -- must not set it.</li>
 * <li><b>Not fatal</b> ({@code false}): everything else, including retry exhaustion. Most
 * commonly a {@code 404 object_not_found} or {@code 403 restricted_resource} on one page,
 * database or comment thread. {@code NotionDataStore} catches this case, warns, records it via
 * {@code FailureUrlService}, and continues with the rest of the crawl. The one exception is a
 * failure of {@code POST /v1/search} while seeding crawl roots (see {@code
 * NotionDataStore#seedRoots}): with neither {@code root_page_ids} nor {@code database_ids}
 * configured, a broken search means no root can be discovered at all, so that failure is left to
 * propagate regardless of this flag.</li>
 * </ul>
 */
public class NotionApiException extends NotionDataStoreException {

    private static final long serialVersionUID = 1L;

    /** The HTTP status code of the failing response. */
    private final int status;

    /** The Notion error code, e.g. {@code "object_not_found"}; may be {@code null}. */
    private final String code;

    /** Whether this error should abort the whole crawl. */
    private final boolean fatal;

    /**
     * Creates a new exception for a Notion API error.
     *
     * @param path the request path that failed, e.g. {@code "pages/xxxx"}
     * @param status the HTTP status code of the response
     * @param code the Notion error code, or {@code null} if the body carried none
     * @param message the Notion error message, or a synthesized one if the body was not
     *            parseable JSON
     * @param fatal whether this error should abort the whole crawl
     */
    public NotionApiException(final String path, final int status, final String code, final String message, final boolean fatal) {
        super("Notion API \"" + path + "\" returned HTTP " + status + (code != null ? " (" + code + ")" : "") + ": " + message);
        this.status = status;
        this.code = code;
        this.fatal = fatal;
    }

    /**
     * Returns the HTTP status code of the failing response.
     *
     * @return the HTTP status code
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the Notion error code.
     *
     * @return the error code, e.g. {@code "object_not_found"}, or {@code null}
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns whether this error should abort the whole crawl. See the class javadoc.
     *
     * @return true if the crawl must be aborted
     */
    public boolean isFatal() {
        return fatal;
    }

}
