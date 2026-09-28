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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.exception.InterruptedRuntimeException;
import org.codelibs.core.lang.StringUtil;
import org.codelibs.fess.Constants;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.crawler.exception.CrawlingAccessException;
import org.codelibs.fess.crawler.exception.MultipleCrawlingAccessException;
import org.codelibs.fess.crawler.filter.UrlFilter;
import org.codelibs.fess.crawler.helper.MimeTypeHelper;
import org.codelibs.fess.ds.AbstractDataStore;
import org.codelibs.fess.ds.callback.IndexUpdateCallback;
import org.codelibs.fess.ds.notion.BlockTextExtractor.AttachmentRef;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreCrawlingException;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsAction;
import org.codelibs.fess.helper.CrawlerStatsHelper.StatsKeyObject;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.util.ComponentUtil;
import org.lastaflute.di.core.exception.ComponentNotFoundException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Notion data store implementation that indexes everything shared with a Notion internal
 * integration: pages, database rows (with their property values), Notion-hosted file/PDF
 * attachments, and unresolved page comments.
 *
 * <p>
 * Crawling starts either from the configured {@code root_page_ids}/{@code database_ids}, or, when
 * neither is set, from {@code POST /v1/search} across everything shared with the integration.
 * From there this class walks a work queue of page and data-source ids -- a page's blocks are
 * walked by {@link BlockTextExtractor}, which reports {@code child_page}/{@code child_database}
 * references back for this class to enqueue, and a database resolves to one or more data sources
 * (see {@code GET /v1/databases/{id}}) whose rows ({@code POST
 * /v1/data_sources/{id}/query}) are themselves page objects, enqueued the same way. Processing is
 * strictly sequential: Notion's own rate limit (about 3 requests/second) is the bottleneck, so a
 * thread pool would only add complexity without raising throughput.
 * </p>
 *
 * <h2>Error handling</h2>
 * <p>
 * {@link NotionApiException#isFatal()} distinguishes two situations {@link NotionClient} cannot
 * tell apart on its own: an error that dooms the whole crawl (the token cannot authenticate, or
 * Notion's rate limiter is outright blocking this integration) versus one scoped to a single page,
 * data source, comment thread, or attachment. A fatal exception propagates out of
 * {@link #storeData} uncaught -- it does not extend {@code CrawlingAccessException} -- aborting the
 * crawl. See {@link NotionApiException}'s javadoc for why that set is kept deliberately small:
 * {@code DataIndexHelper.process()} deletes every document of this data config not re-indexed in
 * this session when {@code store()} throws, so aborting is reserved for cases where continuing
 * genuinely cannot help, not used as a general "something went wrong" response. A non-fatal
 * {@code NotionApiException}, and any {@code CrawlingAccessException} raised while building or
 * storing one document, is instead warned, recorded via {@link FailureUrlService}, and the crawl
 * continues with the next item. The one exception is {@code POST /v1/search} failing while
 * {@link #seedRoots seeding crawl roots}: with neither {@code root_page_ids} nor {@code
 * database_ids} configured, that failure is left to propagate regardless of {@code isFatal()},
 * since no root can be discovered at all without it.
 * </p>
 */
public class NotionDataStore extends AbstractDataStore {

    private static final Logger logger = LogManager.getLogger(NotionDataStore.class);

    /** Default maximum size, in bytes, of a downloaded attachment. */
    protected static final long DEFAULT_MAX_FILE_SIZE = 10485760L; // 10MB

    // parameters
    /** Parameter name for the comma-separated root page ids to crawl from. */
    protected static final String ROOT_PAGE_IDS_PARAM = "root_page_ids";

    /** Parameter name for the comma-separated database ids to crawl from. */
    protected static final String DATABASE_IDS_PARAM = "database_ids";

    /** Parameter name for whether to crawl page comments. */
    protected static final String INCLUDE_COMMENTS_PARAM = "include_comments";

    /** Parameter name for whether to download and extract file/PDF block attachments. */
    protected static final String INCLUDE_FILES_PARAM = "include_files";

    /** Parameter name for the maximum attachment size, in bytes, this data store will download. */
    protected static final String MAX_FILE_SIZE_PARAM = "max_file_size";

    /** Parameter name for a URL include-pattern regular expression, matched against {@code notion.url}. */
    protected static final String INCLUDE_PATTERN_PARAM = "include_pattern";

    /** Parameter name for a URL exclude-pattern regular expression, matched against {@code notion.url}. */
    protected static final String EXCLUDE_PATTERN_PARAM = "exclude_pattern";

    /** Parameter name for whether an attachment extraction failure is ignored (indexed with empty content) rather than failing that document. */
    protected static final String IGNORE_ERROR_PARAM = "ignore_error";

    /**
     * Parameter keys withheld from crawl scripts. See {@code SlackDataStore.SECRET_PARAMS} in
     * fess-ds-slack for the precedent this follows: a denylist over an otherwise-complete copy of
     * the parameter map, not an allowlist.
     */
    protected static final Set<String> SECRET_PARAMS = Set.of(NotionClient.TOKEN_PARAM);

    // script root
    /** Script root field name under which every document's fields are exposed. */
    protected static final String NOTION = "notion";

    private static final String NOTION_TYPE = "type";
    private static final String NOTION_ID = "id";
    private static final String NOTION_TITLE = "title";
    private static final String NOTION_CONTENT = "content";
    private static final String NOTION_URL = "url";
    private static final String NOTION_CREATED_TIME = "created_time";
    private static final String NOTION_LAST_EDITED_TIME = "last_edited_time";
    private static final String NOTION_CREATED_BY = "created_by";
    private static final String NOTION_LAST_EDITED_BY = "last_edited_by";
    private static final String NOTION_PARENT_ID = "parent_id";
    private static final String NOTION_PROPERTIES = "properties";
    private static final String NOTION_COMMENTS = "comments";
    private static final String NOTION_MIMETYPE = "mimetype";
    private static final String NOTION_PAGE_URL = "page_url";
    private static final String NOTION_PAGE_TITLE = "page_title";
    private static final String NOTION_SIZE = "size";

    private static final String TYPE_PAGE = "page";
    private static final String TYPE_FILE = "file";

    /** Name of the content extractor used for attachment text extraction. */
    protected String extractorName = "tikaExtractor";

    /**
     * Default constructor.
     */
    public NotionDataStore() {
        super();
    }

    @Override
    protected String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Sets the name of the content extractor used for attachment text extraction.
     *
     * @param extractorName the extractor name
     */
    public void setExtractorName(final String extractorName) {
        this.extractorName = extractorName;
    }

    /** One unit of crawl work: either a page to fetch (or already fetched, via search/query) and index, or a data source to query for rows. */
    private enum WorkItemType {
        PAGE, DATA_SOURCE
    }

    /**
     * @param type whether {@code id} names a page or a data source
     * @param id the page or data source id
     * @param preloaded the page object already returned by {@code /v1/search} or a data source
     *            query, so this item does not need its own {@code GET /v1/pages/{id}} call; {@code
     *            null} for a page discovered only by id (a configured root, or a {@code child_page}
     *            reference), and always {@code null} for a data source
     */
    private record WorkItem(WorkItemType type, String id, JsonNode preloaded) {
    }

    @Override
    protected void storeData(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
        final String scriptType = getScriptType(paramMap);
        final boolean ignoreError = isIgnoreError(paramMap);
        final boolean includeFiles = isIncludeFiles(paramMap);
        final long maxFileSize = getMaxFileSize(paramMap);
        final UrlFilter urlFilter = getUrlFilter(paramMap);

        final Deque<WorkItem> queue = new ArrayDeque<>();
        final Set<String> visitedPages = new HashSet<>();
        final Set<String> visitedDataSources = new HashSet<>();
        // Mutable crawl-wide flag: a single element so processPage's comment handling can flip it
        // off after the first 403 ("Read comments" capability missing) without threading a return
        // value back through every call in between. Safe as a plain (non-atomic) holder: this
        // data store never uses more than one thread (see the class javadoc).
        final boolean[] commentsEnabled = { isIncludeComments(paramMap) };
        final boolean[] commentsCapabilityWarned = { false };

        boolean running = true;
        try (final NotionClient client = new NotionClient(paramMap)) {
            seedRoots(dataConfig, client, paramMap, queue, visitedPages, visitedDataSources);

            // `alive` (AbstractDataStore) is the operator's admin-UI stop button; checking it here
            // is what makes stop() actually interrupt this crawl instead of running the whole
            // queue to completion regardless.
            while (!queue.isEmpty() && running && alive) {
                final WorkItem item = queue.poll();
                final StatsKeyObject statsKey = new StatsKeyObject(item.id());
                paramMap.put(Constants.CRAWLER_STATS_KEY, statsKey);
                try {
                    crawlerStatsHelper.begin(statsKey);
                    if (item.type() == WorkItemType.PAGE) {
                        processPage(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, item, queue, visitedPages,
                                visitedDataSources, urlFilter, commentsEnabled, commentsCapabilityWarned, includeFiles, maxFileSize,
                                ignoreError, scriptType, statsKey);
                    } else {
                        client.queryDataSource(item.id(), row -> enqueuePage(queue, visitedPages, textOrNull(row, "id"), row));
                    }
                    crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
                } catch (final InterruptedRuntimeException e) {
                    // A genuine thread interrupt, not a per-item failure: must stop the crawl, not
                    // be recorded and continued past.
                    throw e;
                } catch (final CrawlingAccessException e) {
                    if (recordAccessFailure(dataConfig, statsKey, item.id(), e, crawlerStatsHelper)) {
                        running = false;
                    }
                } catch (final NotionApiException e) {
                    if (e.isFatal()) {
                        throw e;
                    }
                    logger.warn("Skipping Notion {} {}: {}", item.type() == WorkItemType.PAGE ? "page" : "data source", item.id(),
                            e.getMessage());
                    ComponentUtil.getComponent(FailureUrlService.class).store(dataConfig, e.getClass().getCanonicalName(), item.id(), e);
                    crawlerStatsHelper.record(statsKey, StatsAction.ACCESS_EXCEPTION);
                } catch (final Exception e) {
                    recordUnexpectedFailure(dataConfig, statsKey, item.id(), e, crawlerStatsHelper);
                } finally {
                    crawlerStatsHelper.done(statsKey);
                }
            }
        }
    }

    /**
     * Seeds the work queue: from the configured roots when {@code root_page_ids} or
     * {@code database_ids} is set, otherwise from {@code POST /v1/search} across everything shared
     * with the integration.
     *
     * <p>
     * A {@code search} failure here is deliberately let propagate uncaught, whatever {@code
     * NotionApiException#isFatal()} says: it only runs when neither {@code root_page_ids} nor
     * {@code database_ids} is configured, so a broken search leaves no root to crawl from at all --
     * there is no smaller unit of work to skip the way there is for one configured database id.
     * </p>
     *
     * @param dataConfig the data configuration, for recording a failed root database via {@link
     *            FailureUrlService}
     * @param client the Notion client
     * @param paramMap the configuration parameters
     * @param queue the work queue to seed
     * @param visitedPages the visited-page-id set, so a root already seen is not queued twice
     * @param visitedDataSources the visited-data-source-id set, likewise
     */
    protected void seedRoots(final DataConfig dataConfig, final NotionClient client, final DataStoreParams paramMap,
            final Deque<WorkItem> queue, final Set<String> visitedPages, final Set<String> visitedDataSources) {
        final List<String> rootPageIds = splitIds(paramMap.getAsString(ROOT_PAGE_IDS_PARAM));
        final List<String> databaseIds = splitIds(paramMap.getAsString(DATABASE_IDS_PARAM));
        if (rootPageIds.isEmpty() && databaseIds.isEmpty()) {
            client.search(TYPE_PAGE, page -> enqueuePage(queue, visitedPages, textOrNull(page, "id"), page));
            client.search("data_source", dataSource -> enqueueDataSource(queue, visitedDataSources, textOrNull(dataSource, "id")));
            return;
        }
        for (final String pageId : rootPageIds) {
            enqueuePage(queue, visitedPages, pageId, null);
        }
        for (final String databaseId : databaseIds) {
            try {
                resolveDatabase(client, databaseId, queue, visitedDataSources);
            } catch (final NotionApiException e) {
                if (e.isFatal()) {
                    throw e;
                }
                recordDatabaseFailure(dataConfig, databaseId, null, e);
            }
        }
    }

    /**
     * Resolves a database to its data source(s) and enqueues each one.
     *
     * @param client the Notion client
     * @param databaseId the database id
     * @param queue the work queue
     * @param visitedDataSources the visited-data-source-id set
     */
    protected void resolveDatabase(final NotionClient client, final String databaseId, final Deque<WorkItem> queue,
            final Set<String> visitedDataSources) {
        final JsonNode database = client.getDatabase(databaseId);
        for (final JsonNode dataSource : database.path("data_sources")) {
            enqueueDataSource(queue, visitedDataSources, textOrNull(dataSource, "id"));
        }
    }

    private boolean enqueuePage(final Deque<WorkItem> queue, final Set<String> visitedPages, final String pageId,
            final JsonNode preloaded) {
        if (StringUtil.isBlank(pageId) || !visitedPages.add(normalize(pageId))) {
            return false;
        }
        queue.add(new WorkItem(WorkItemType.PAGE, pageId, preloaded));
        return true;
    }

    private boolean enqueueDataSource(final Deque<WorkItem> queue, final Set<String> visitedDataSources, final String dataSourceId) {
        if (StringUtil.isBlank(dataSourceId) || !visitedDataSources.add(normalize(dataSourceId))) {
            return false;
        }
        queue.add(new WorkItem(WorkItemType.DATA_SOURCE, dataSourceId, null));
        return true;
    }

    private String normalize(final String id) {
        return id.replace("-", "");
    }

    /**
     * Fetches (or reuses a preloaded copy of) a page, extracts and indexes it, and enqueues
     * whatever the page's own content references: {@code child_page} blocks, {@code
     * child_database} blocks (resolved to data sources), and, if {@code include_files} is set,
     * Notion-hosted file/PDF attachments (indexed immediately as their own documents).
     *
     * <p>
     * The block walk -- and the child-page/child-database enqueue it drives -- runs before the
     * include/exclude filter and before anything else that could fail (properties, comments,
     * {@code callback.store}). {@code queue.add} is visible immediately, so a child already
     * enqueued survives even if this page is then filtered out or fails outright: include/exclude
     * and a later failure decide only whether <em>this</em> page is indexed, never whether its
     * descendants are discovered. Discovering it first and then dropping it, the previous order,
     * silently lost every descendant of a filtered-out or failing page.
     * </p>
     */
    private void processPage(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap, final NotionClient client, final WorkItem item,
            final Deque<WorkItem> queue, final Set<String> visitedPages, final Set<String> visitedDataSources, final UrlFilter urlFilter,
            final boolean[] commentsEnabled, final boolean[] commentsCapabilityWarned, final boolean includeFiles, final long maxFileSize,
            final boolean ignoreError, final String scriptType, final StatsKeyObject statsKey) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
        final JsonNode page = item.preloaded() != null ? item.preloaded() : client.getPage(item.id());
        if (page.path("in_trash").asBoolean(false)) {
            crawlerStatsHelper.discard(statsKey);
            return;
        }

        final String pageId = textOrNull(page, "id") != null ? textOrNull(page, "id") : item.id();
        final String url = StringUtil.isNotBlank(textOrNull(page, "url")) ? textOrNull(page, "url") : "notion://page/" + pageId;
        statsKey.setUrl(url);

        final BlockTextExtractor.Result blocks = BlockTextExtractor.extract(client, pageId);
        for (final String childPageId : blocks.getChildPageIds()) {
            enqueuePage(queue, visitedPages, childPageId, null);
        }
        for (final String childDatabaseId : blocks.getChildDatabaseIds()) {
            try {
                resolveDatabase(client, childDatabaseId, queue, visitedDataSources);
            } catch (final NotionApiException e) {
                if (e.isFatal()) {
                    throw e;
                }
                recordDatabaseFailure(dataConfig, childDatabaseId, url, e);
            }
        }

        if (urlFilter != null && !urlFilter.match(url)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Not matched: {}", url);
            }
            crawlerStatsHelper.discard(statsKey);
            return;
        }
        logger.info("Crawling URL: {}", url);

        final JsonNode properties = page.path("properties");
        final String title = PropertyFormatter.getTitle(properties);
        final Map<String, String> propertiesText = PropertyFormatter.format(client, pageId, properties);

        final String commentsText =
                commentsEnabled[0] ? fetchComments(client, dataConfig, pageId, commentsEnabled, commentsCapabilityWarned)
                        : StringUtil.EMPTY;

        crawlerStatsHelper.record(statsKey, StatsAction.PREPARED);

        final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
        final Map<String, Object> resultMap = newResultMap(paramMap);
        final Map<String, Object> notionMap = new HashMap<>();
        notionMap.put(NOTION_TYPE, TYPE_PAGE);
        notionMap.put(NOTION_ID, pageId);
        notionMap.put(NOTION_TITLE, title);
        notionMap.put(NOTION_CONTENT, buildContent(propertiesText, blocks.getText(), commentsText));
        notionMap.put(NOTION_URL, url);
        notionMap.put(NOTION_CREATED_TIME, textOrNull(page, "created_time"));
        notionMap.put(NOTION_LAST_EDITED_TIME, textOrNull(page, "last_edited_time"));
        notionMap.put(NOTION_CREATED_BY, PropertyFormatter.formatUserRef(page.path("created_by")));
        notionMap.put(NOTION_LAST_EDITED_BY, PropertyFormatter.formatUserRef(page.path("last_edited_by")));
        notionMap.put(NOTION_PARENT_ID, getParentId(page.path("parent")));
        notionMap.put(NOTION_PROPERTIES, propertiesText);
        notionMap.put(NOTION_COMMENTS, commentsText);
        notionMap.put(NOTION_MIMETYPE, "text/html");
        resultMap.put(NOTION, notionMap);

        for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
            final Object convertValue = convertValue(scriptType, entry.getValue(), resultMap);
            if (convertValue != null) {
                dataMap.put(entry.getKey(), convertValue);
            }
        }

        callback.store(paramMap, dataMap);

        if (includeFiles) {
            for (final AttachmentRef ref : blocks.getAttachments()) {
                processAttachment(dataConfig, callback, paramMap, scriptMap, defaultDataMap, client, url, title, ref, urlFilter,
                        maxFileSize, ignoreError, scriptType);
            }
        }
    }

    /**
     * Fetches every comment on a page and joins their text. A {@code 403} (the integration lacks
     * the "Read comments" capability) disables comment crawling for the rest of this crawl, warning
     * only once; any other non-fatal failure is warned and skipped for this page only.
     */
    private String fetchComments(final NotionClient client, final DataConfig dataConfig, final String pageId,
            final boolean[] commentsEnabled, final boolean[] commentsCapabilityWarned) {
        final StringBuilder text = new StringBuilder();
        try {
            client.listComments(pageId, comment -> {
                final String plain = plainText(comment.path("rich_text"));
                if (StringUtil.isNotBlank(plain)) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(plain);
                }
            });
        } catch (final NotionApiException e) {
            if (e.isFatal()) {
                throw e;
            }
            if (e.getStatus() == 403) {
                commentsEnabled[0] = false;
                if (!commentsCapabilityWarned[0]) {
                    logger.warn("The Notion integration lacks the \"Read comments\" capability; disabling comment crawling "
                            + "for the rest of this crawl. ({})", e.getMessage());
                    commentsCapabilityWarned[0] = true;
                }
            } else {
                logger.warn("Failed to get comments for Notion page {}: {}", pageId, e.getMessage());
                ComponentUtil.getComponent(FailureUrlService.class).store(dataConfig, e.getClass().getCanonicalName(), pageId, e);
            }
        }
        return text.toString();
    }

    /**
     * Downloads and extracts one Notion-hosted file/PDF attachment and indexes it as its own
     * document. It never fails the containing page, which was already indexed by the time this
     * runs.
     *
     * <p>
     * <b>{@code ref.url()} -- the signed, time-limited download URL -- must never reach a log, an
     * exception message, or a {@link FailureUrlService} record.</b> The document identity used
     * everywhere below is instead {@code url}, {@code <page url>#<block id>}. A download failure
     * (a non-200 status, or any I/O/transport exception from {@link NotionClient#getFile}) is
     * therefore reported with a fixed message that never touches {@code e.getMessage()}: curl4j's
     * own exceptions embed the request URL in their message, and a warning that logs a wrapped
     * exception prints its full stack trace, so passing one through even as a {@code cause} would
     * still leak it.
     * </p>
     *
     * <p>
     * A download failure is <b>not</b> covered by {@code ignore_error}: that parameter is about
     * failing to extract text from a file this class did receive, not about failing to receive it
     * at all, so a download failure always produces a {@link FailureUrlService} entry with no
     * document indexed. {@code ignore_error} governs only the extraction step, matching {@code
     * SlackDataStore#getFileContent}/{@code BoxDataStore#getFileContents}: with {@code
     * ignore_error=true} (the default) an extraction failure indexes the attachment with empty
     * content instead of dropping it; with {@code false} it is recorded as a failure like a
     * download failure is.
     * </p>
     */
    private void processAttachment(final DataConfig dataConfig, final IndexUpdateCallback callback, final DataStoreParams paramMap,
            final Map<String, String> scriptMap, final Map<String, Object> defaultDataMap, final NotionClient client, final String pageUrl,
            final String pageTitle, final AttachmentRef ref, final UrlFilter urlFilter, final long maxFileSize, final boolean ignoreError,
            final String scriptType) {
        final CrawlerStatsHelper crawlerStatsHelper = ComponentUtil.getCrawlerStatsHelper();
        final String url = pageUrl + "#" + normalize(ref.blockId());
        if (urlFilter != null && !urlFilter.match(url)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Not matched: {}", url);
            }
            return;
        }
        final StatsKeyObject statsKey = new StatsKeyObject(url);
        paramMap.put(Constants.CRAWLER_STATS_KEY, statsKey);
        try {
            crawlerStatsHelper.begin(statsKey);

            final String mimeType = ComponentUtil.getComponent(MimeTypeHelper.class).getContentType(null, ref.name());

            final NotionClient.FileResponse response;
            try {
                response = client.getFile(ref.url(), maxFileSize);
            } catch (final NotionClient.AttachmentTooLargeException e) {
                logger.info("Skipping the Notion attachment \"{}\" because its size ({} bytes) is over max_file_size ({} bytes).",
                        ref.name(), e.size(), maxFileSize);
                crawlerStatsHelper.discard(statsKey);
                return;
            } catch (final Exception e) {
                throw new DataStoreCrawlingException(url, "Failed to download attachment: " + ref.name(), null, false);
            }
            if (response.statusCode() != 200) {
                throw new DataStoreCrawlingException(url,
                        "HTTP Status " + response.statusCode() + ": failed to download attachment: " + ref.name(), null, false);
            }

            String content;
            try (final InputStream in = new ByteArrayInputStream(response.content())) {
                content = ComponentUtil.getExtractorFactory()
                        .builder(in, null)
                        .filename(ref.name())
                        .mimeType(mimeType)
                        .extractorName(extractorName)
                        .maxContentLength(maxFileSize)
                        .extract()
                        .getContent();
            } catch (final Exception e) {
                if (!ignoreError) {
                    throw new DataStoreCrawlingException(url, "Failed to get contents: " + ref.name(), e);
                }
                logger.warn("Failed to get contents: {}. {}", ref.name(), e.getMessage());
                content = StringUtil.EMPTY;
            }

            crawlerStatsHelper.record(statsKey, StatsAction.PREPARED);
            indexAttachment(callback, paramMap, scriptMap, defaultDataMap, pageUrl, pageTitle, ref, url, mimeType,
                    (long) response.content().length, content, scriptType);
            crawlerStatsHelper.record(statsKey, StatsAction.FINISHED);
        } catch (final InterruptedRuntimeException e) {
            throw e;
        } catch (final CrawlingAccessException e) {
            recordAccessFailure(dataConfig, statsKey, url, e, crawlerStatsHelper);
        } catch (final Exception e) {
            recordUnexpectedFailure(dataConfig, statsKey, url, e, crawlerStatsHelper);
        } finally {
            crawlerStatsHelper.done(statsKey);
        }
    }

    private void indexAttachment(final IndexUpdateCallback callback, final DataStoreParams paramMap, final Map<String, String> scriptMap,
            final Map<String, Object> defaultDataMap, final String pageUrl, final String pageTitle, final AttachmentRef ref,
            final String url, final String mimeType, final Long size, final String content, final String scriptType) {
        final Map<String, Object> dataMap = new HashMap<>(defaultDataMap);
        final Map<String, Object> resultMap = newResultMap(paramMap);
        final Map<String, Object> notionMap = new HashMap<>();
        notionMap.put(NOTION_TYPE, TYPE_FILE);
        notionMap.put(NOTION_ID, ref.blockId());
        notionMap.put(NOTION_TITLE, ref.name());
        notionMap.put(NOTION_CONTENT, content);
        notionMap.put(NOTION_MIMETYPE, mimeType);
        notionMap.put(NOTION_URL, url);
        notionMap.put(NOTION_PAGE_URL, pageUrl);
        notionMap.put(NOTION_PAGE_TITLE, pageTitle);
        notionMap.put(NOTION_LAST_EDITED_TIME, ref.lastEditedTime());
        if (size != null) {
            notionMap.put(NOTION_SIZE, size);
        }
        resultMap.put(NOTION, notionMap);

        for (final Map.Entry<String, String> entry : scriptMap.entrySet()) {
            final Object convertValue = convertValue(scriptType, entry.getValue(), resultMap);
            if (convertValue != null) {
                dataMap.put(entry.getKey(), convertValue);
            }
        }

        callback.store(paramMap, dataMap);
    }

    private String getParentId(final JsonNode parent) {
        if (parent == null || parent.isMissingNode()) {
            return null;
        }
        final String type = parent.path("type").asText("");
        return switch (type) {
        case "page_id" -> textOrNull(parent, "page_id");
        case "database_id" -> textOrNull(parent, "database_id");
        case "data_source_id" -> textOrNull(parent, "data_source_id");
        case "block_id" -> textOrNull(parent, "block_id");
        default -> null;
        };
    }

    private String buildContent(final Map<String, String> propertiesText, final String blockText, final String commentsText) {
        final StringBuilder content = new StringBuilder();
        for (final Map.Entry<String, String> entry : propertiesText.entrySet()) {
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        appendSection(content, blockText);
        appendSection(content, commentsText);
        return content.toString();
    }

    private void appendSection(final StringBuilder content, final String section) {
        if (StringUtil.isBlank(section)) {
            return;
        }
        if (content.length() > 0) {
            content.append("\n\n");
        }
        content.append(section);
    }

    private String plainText(final JsonNode richTextArray) {
        if (!richTextArray.isArray()) {
            return StringUtil.EMPTY;
        }
        final StringBuilder line = new StringBuilder();
        for (final JsonNode richText : richTextArray) {
            line.append(richText.path("plain_text").asText(""));
        }
        return line.toString();
    }

    private String textOrNull(final JsonNode node, final String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    /**
     * Builds the map exposed to crawl scripts, withholding {@link #SECRET_PARAMS}. See
     * {@code SlackDataStore#newResultMap} in fess-ds-slack for why: {@link
     * AbstractDataStore#convertValue} returns a value verbatim when a script template matches a
     * parameter key exactly, so a raw copy would let a script such as {@code content=token} index
     * the integration secret.
     *
     * @param paramMap the data store parameters
     * @return a new map safe to expose to scripts
     */
    protected Map<String, Object> newResultMap(final DataStoreParams paramMap) {
        final Map<String, Object> resultMap = new LinkedHashMap<>();
        paramMap.asMap().forEach((k, v) -> {
            if (!SECRET_PARAMS.contains(k)) {
                resultMap.put(k, v);
            }
        });
        return resultMap;
    }

    /**
     * Records a {@code CrawlingAccessException} (or an unwrapped {@code
     * DataStoreCrawlingException} inside a {@code MultipleCrawlingAccessException}) via {@link
     * FailureUrlService} and reports whether it asked to abort the crawl. Shared by the top-level
     * work-item loop and {@link #processAttachment}, matching {@code ExampleDataStore}'s per-record
     * catch idiom.
     *
     * @return true if this failure asked to abort the crawl ({@code
     *         DataStoreCrawlingException#aborted()}); a caller that can never construct an
     *         aborting exception on this path (e.g. {@link #processAttachment}) may ignore the
     *         result
     */
    private boolean recordAccessFailure(final DataConfig dataConfig, final StatsKeyObject statsKey, final String fallbackUrl,
            final CrawlingAccessException e, final CrawlerStatsHelper crawlerStatsHelper) {
        logger.warn("Crawling Access Exception at : {}", fallbackUrl, e);
        Throwable target = e;
        if (target instanceof final MultipleCrawlingAccessException ex) {
            final Throwable[] causes = ex.getCauses();
            if (causes.length > 0) {
                target = causes[causes.length - 1];
            }
        }
        final Throwable cause = target.getCause();
        final String errorName = (cause != null ? cause : target).getClass().getCanonicalName();
        String url = fallbackUrl;
        boolean abort = false;
        if (target instanceof final DataStoreCrawlingException dce) {
            url = dce.getUrl();
            abort = dce.aborted();
        }
        ComponentUtil.getComponent(FailureUrlService.class).store(dataConfig, errorName, url, target);
        crawlerStatsHelper.record(statsKey, StatsAction.ACCESS_EXCEPTION);
        return abort;
    }

    private void recordUnexpectedFailure(final DataConfig dataConfig, final StatsKeyObject statsKey, final String fallbackUrl,
            final Throwable t, final CrawlerStatsHelper crawlerStatsHelper) {
        logger.warn("Unexpected exception at : {}", fallbackUrl, t);
        ComponentUtil.getComponent(FailureUrlService.class).store(dataConfig, t.getClass().getCanonicalName(), fallbackUrl, t);
        crawlerStatsHelper.record(statsKey, StatsAction.EXCEPTION);
    }

    /**
     * Records a database resolve/query failure -- a configured root {@code database_ids} entry,
     * or a {@code child_database} block found while walking a page -- via {@link
     * FailureUrlService}, in addition to the WARN {@link #seedRoots}/{@link #processPage} already
     * log. Unlike a page or data-source work item, a database's own resolve failure has no
     * separate top-level catch to record it (it is always caught locally, close to {@link
     * #resolveDatabase}, so the containing page or the rest of the configured roots is not lost),
     * so this is the one place that does.
     *
     * @param dataConfig the data configuration this crawl is running under
     * @param databaseId the database that failed to resolve or query
     * @param contextPageUrl the URL of the page whose {@code child_database} block named this
     *            database, or {@code null} for a root {@code database_ids} entry
     * @param e the non-fatal failure
     */
    private void recordDatabaseFailure(final DataConfig dataConfig, final String databaseId, final String contextPageUrl,
            final NotionApiException e) {
        final String message = contextPageUrl == null ? "Failed to resolve Notion database " + databaseId + ": " + e.getMessage()
                : "Failed to resolve Notion database " + databaseId + " referenced from " + contextPageUrl + ": " + e.getMessage();
        logger.warn(message);
        ComponentUtil.getComponent(FailureUrlService.class)
                .store(dataConfig, e.getClass().getCanonicalName(), databaseId, new NotionDataStoreException(message, e));
    }

    private List<String> splitIds(final String value) {
        if (StringUtil.isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(StringUtil::isNotBlank).collect(Collectors.toList());
    }

    /**
     * Creates and configures a URL filter based on {@link #INCLUDE_PATTERN_PARAM}/{@link
     * #EXCLUDE_PATTERN_PARAM}, applied to {@code notion.url}.
     *
     * @param paramMap the configuration parameters
     * @return the configured URL filter, or null if the {@link UrlFilter} component is not
     *         registered
     */
    protected UrlFilter getUrlFilter(final DataStoreParams paramMap) {
        final UrlFilter urlFilter;
        try {
            urlFilter = ComponentUtil.getComponent(UrlFilter.class);
        } catch (final ComponentNotFoundException e) {
            logger.warn("UrlFilter component is not registered; {} and {} will not be applied.", INCLUDE_PATTERN_PARAM,
                    EXCLUDE_PATTERN_PARAM, e);
            return null;
        }
        final String include = paramMap.getAsString(INCLUDE_PATTERN_PARAM);
        if (StringUtil.isNotBlank(include)) {
            urlFilter.addInclude(include);
        }
        final String exclude = paramMap.getAsString(EXCLUDE_PATTERN_PARAM);
        if (StringUtil.isNotBlank(exclude)) {
            urlFilter.addExclude(exclude);
        }
        urlFilter.init(paramMap.getAsString(Constants.CRAWLING_INFO_ID));
        if (logger.isDebugEnabled()) {
            logger.debug("urlFilter: {}", urlFilter);
        }
        return urlFilter;
    }

    /**
     * Determines whether comment crawling is requested. See {@link #fetchComments} for when it is
     * disabled mid-crawl regardless of this setting.
     *
     * @param paramMap the configuration parameters
     * @return true if comments should be crawled
     */
    protected boolean isIncludeComments(final DataStoreParams paramMap) {
        return getBooleanParam(paramMap, INCLUDE_COMMENTS_PARAM, true);
    }

    /**
     * Determines whether file/PDF block attachments should be downloaded and indexed.
     *
     * @param paramMap the configuration parameters
     * @return true if attachments should be crawled
     */
    protected boolean isIncludeFiles(final DataStoreParams paramMap) {
        return getBooleanParam(paramMap, INCLUDE_FILES_PARAM, true);
    }

    /**
     * Determines whether an attachment extraction failure is ignored (indexed with empty content)
     * rather than failing that document.
     *
     * @param paramMap the configuration parameters
     * @return true if extraction failures should be ignored
     */
    protected boolean isIgnoreError(final DataStoreParams paramMap) {
        return getBooleanParam(paramMap, IGNORE_ERROR_PARAM, true);
    }

    /**
     * Parses a boolean configuration parameter, falling back to a default with a warning when the
     * value is blank or is not, case-insensitively, {@code "true"} or {@code "false"}. Unlike
     * {@code Constants.TRUE.equalsIgnoreCase(value)}, a garbage value is not silently treated as
     * false.
     *
     * @param paramMap the configuration parameters
     * @param paramName the parameter name to read
     * @param defaultValue the value to fall back to
     * @return the parsed value, or {@code defaultValue} if unset or not a recognized boolean
     */
    private boolean getBooleanParam(final DataStoreParams paramMap, final String paramName, final boolean defaultValue) {
        final String value = paramMap.getAsString(paramName);
        if (StringUtil.isBlank(value)) {
            return defaultValue;
        }
        final String trimmed = value.trim();
        if (Constants.TRUE.equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (Constants.FALSE.equalsIgnoreCase(trimmed)) {
            return false;
        }
        logger.warn("Parameter '{}' is not 'true' or 'false': {}. Falling back to {}.", paramName, value, defaultValue);
        return defaultValue;
    }

    /**
     * Extracts the maximum attachment size, in bytes, this data store will download and extract.
     * A blank, non-numeric, or non-positive value falls back to {@link #DEFAULT_MAX_FILE_SIZE}
     * with a warning.
     *
     * @param paramMap the configuration parameters
     * @return the maximum attachment size in bytes
     */
    protected long getMaxFileSize(final DataStoreParams paramMap) {
        final String value = paramMap.getAsString(MAX_FILE_SIZE_PARAM);
        if (StringUtil.isBlank(value)) {
            return DEFAULT_MAX_FILE_SIZE;
        }
        final long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (final NumberFormatException e) {
            logger.warn("Parameter '{}' is not a number: {}. Falling back to {}.", MAX_FILE_SIZE_PARAM, value, DEFAULT_MAX_FILE_SIZE);
            return DEFAULT_MAX_FILE_SIZE;
        }
        if (parsed <= 0) {
            logger.warn("Parameter '{}' must be positive: {}. Falling back to {}.", MAX_FILE_SIZE_PARAM, parsed, DEFAULT_MAX_FILE_SIZE);
            return DEFAULT_MAX_FILE_SIZE;
        }
        return parsed;
    }

}
