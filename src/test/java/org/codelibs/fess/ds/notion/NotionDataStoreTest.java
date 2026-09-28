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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.codelibs.core.exception.InterruptedRuntimeException;
import org.codelibs.fess.app.service.FailureUrlService;
import org.codelibs.fess.crawler.container.CrawlerContainer;
import org.codelibs.fess.crawler.extractor.ExtractorFactory;
import org.codelibs.fess.crawler.extractor.impl.TikaExtractor;
import org.codelibs.fess.crawler.filter.UrlFilter;
import org.codelibs.fess.crawler.helper.MimeTypeHelper;
import org.codelibs.fess.crawler.helper.impl.MimeTypeHelperImpl;
import org.codelibs.fess.entity.DataStoreParams;
import org.codelibs.fess.exception.DataStoreException;
import org.codelibs.fess.helper.CrawlerStatsHelper;
import org.codelibs.fess.helper.SystemHelper;
import org.codelibs.fess.opensearch.config.exentity.CrawlingConfig;
import org.codelibs.fess.opensearch.config.exentity.DataConfig;
import org.codelibs.fess.opensearch.config.exentity.FailureUrl;
import org.codelibs.fess.script.ScriptEngineFactory;
import org.codelibs.fess.util.ComponentUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Exercises {@link NotionDataStore#storeData} end to end against {@link NotionApiMockServer}.
 *
 * <p>
 * {@code convertValue} evaluates a scriptMap template that is not a literal top-level key of the
 * resultMap (e.g. {@code "notion.title"}) through {@code ComponentUtil.getScriptEngineFactory()},
 * which is not registered by convention in this module's narrow test container. A trivial
 * dot-path-walking {@link org.codelibs.fess.script.ScriptEngine} stands in for a real script
 * engine here, matching the precedent in fess-ds-slack's {@code SlackDataStoreStoreDataTest}.
 * </p>
 */
public class NotionDataStoreTest extends UnitDsTestCase {

    private NotionApiMockServer server;

    private NotionDataStore dataStore;

    /** One {@link FailureUrlService#store} call: the error name, the recorded url, and the throwable, for leak/content assertions. */
    private record FailureRecord(String errorName, String url, Throwable throwable) {
    }

    private final List<FailureRecord> recordedFailures = new ArrayList<>();

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new NotionApiMockServer();
        server.start();
        dataStore = new NotionDataStore();

        ComponentUtil.register(new SystemHelper(), "systemHelper");
        final CrawlerStatsHelper crawlerStatsHelper = new CrawlerStatsHelper();
        crawlerStatsHelper.init();
        ComponentUtil.register(crawlerStatsHelper, "crawlerStatsHelper");

        recordedFailures.clear();
        ComponentUtil.register(new FailureUrlService() {
            @Override
            public FailureUrl store(final CrawlingConfig crawlingConfig, final String errorName, final String url, final Throwable e) {
                recordedFailures.add(new FailureRecord(errorName, url, e));
                return null;
            }
        }, FailureUrlService.class.getCanonicalName());

        // A minimal ScriptEngine that walks a dot-separated path through nested Maps, standing in
        // for the real Groovy/JavaScript engine that evaluates templates like "notion.title"
        // against the resultMap in production.
        final ScriptEngineFactory scriptEngineFactory = new ScriptEngineFactory();
        scriptEngineFactory.add("groovy", (template, resultMap) -> {
            Object value = resultMap;
            for (final String part : template.split("\\.")) {
                if (!(value instanceof Map)) {
                    return null;
                }
                value = ((Map<?, ?>) value).get(part);
            }
            return value;
        });
        ComponentUtil.register(scriptEngineFactory, "scriptEngineFactory");

        // Registered unconditionally (a fresh instance per test, since JUnit5 creates a new test
        // instance per method) rather than only inside the one test that sets include_pattern:
        // ComponentUtil's componentMap is not cleared by UnitDsTestCase#tearDown, so a filter
        // configured by one test would otherwise still be sitting there, with its patterns from
        // that test, when a later test's storeData() looked it up. With no include/exclude ever
        // added, FakeUrlFilter#match is a pass-through, so this is a no-op for every other test.
        ComponentUtil.register(new FakeUrlFilter(), UrlFilter.class.getCanonicalName());
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

    private Map<String, String> defaultScriptMap() {
        final Map<String, String> scriptMap = new HashMap<>();
        scriptMap.put("title", "notion.title");
        scriptMap.put("content", "notion.content");
        scriptMap.put("url", "notion.url");
        scriptMap.put("mimetype", "notion.mimetype");
        scriptMap.put("type", "notion.type");
        return scriptMap;
    }

    private DataStoreParams newParamMap(final String token) {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("token", token);
        paramMap.put("request_interval", "0");
        return paramMap;
    }

    @Test
    public void test_searchRoots_discoversPagesAndDataSources() {
        server.enqueue("/v1/search", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"request_status":{"type":"complete"},"results":[
                  {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                   "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                   "parent":{"type":"workspace","workspace":true},
                   "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Root Page"}]}}}
                ]}
                """));
        server.enqueue("/v1/search", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"request_status":{"type":"complete"},"results":[
                  {"object":"data_source","id":"DS1"}
                ]}
                """));
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));
        server.enqueue("/v1/data_sources/DS1/query", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"object":"page","id":"ROW1","url":"https://notion.so/ROW1","in_trash":false,
                   "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                   "parent":{"type":"data_source_id","data_source_id":"DS1"},
                   "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Row One"}]}}}
                ]}
                """));
        server.enqueue("/v1/blocks/ROW1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, newParamMap("secret_token"), defaultScriptMap(), new HashMap<>());

        assertEquals(2, callback.size());
        final List<String> titles = callback.getDataMaps().stream().map(m -> (String) m.get("title")).toList();
        assertTrue(titles.contains("Root Page"));
        assertTrue(titles.contains("Row One"));
    }

    @Test
    public void test_rootPageIds_skipsSearch() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Root Page"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(1, callback.size());
        assertEquals(0, server.getRequestCount("/v1/search"));
    }

    @Test
    public void test_childPage_traversal_andDedupe() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Parent"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"PAGE2","type":"child_page","has_children":false,"child_page":{"title":"Child"}}
                ]}
                """));
        server.enqueue("/v1/pages/PAGE2", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE2","url":"https://notion.so/PAGE2","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"page_id","page_id":"PAGE1"},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Child"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE2/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        // Same page listed twice, once with dashes: normalization must dedupe this to one fetch.
        paramMap.put("root_page_ids", "PAGE1,PAGE1");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(2, callback.size());
        assertEquals(1, server.getRequestCount("/v1/pages/PAGE1"));
    }

    @Test
    public void test_databaseIds_resolvesDataSourcesAndRows() {
        server.enqueue("/v1/databases/DB1",
                NotionApiMockServer.json("{\"object\":\"database\",\"id\":\"DB1\",\"data_sources\":[{\"id\":\"DS1\",\"name\":\"Main\"}]}"));
        server.enqueue("/v1/data_sources/DS1/query", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"object":"page","id":"ROW1","url":"https://notion.so/ROW1","in_trash":false,
                   "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                   "parent":{"type":"data_source_id","data_source_id":"DS1"},
                   "properties":{
                     "Name":{"id":"title","type":"title","title":[{"plain_text":"Task A"}]},
                     "Status":{"id":"st","type":"status","status":{"name":"Open"}}
                   }}
                ]}
                """));
        server.enqueue("/v1/blocks/ROW1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("database_ids", "DB1");
        final Map<String, String> scriptMap = defaultScriptMap();

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, scriptMap, new HashMap<>());

        assertEquals(1, callback.size());
        final Map<String, Object> dataMap = callback.getDataMaps().get(0);
        assertEquals("Task A", dataMap.get("title"));
        assertTrue(((String) dataMap.get("content")).contains("Status: Open"));
    }

    @Test
    public void test_comments_included() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));
        server.enqueue("/v1/comments", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"C1","rich_text":[{"plain_text":"Looks good to me"}]}
                ]}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(1, callback.size());
        assertTrue(((String) callback.getDataMaps().get(0).get("content")).contains("Looks good to me"));
    }

    @Test
    public void test_includeComments_false_skipsCommentsCall() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");
        paramMap.put("include_comments", "false");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(1, callback.size());
        assertEquals(0, server.getRequestCount("/v1/comments"));
    }

    @Test
    public void test_trashedPage_isSkipped() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":true,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Trashed"}]}}}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(0, callback.size());
        assertEquals(0, server.getRequestCount("/v1/blocks/PAGE1/children"));
    }

    @Test
    public void test_includeExcludePattern_filtersDocumentOnly() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/keep-me","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Keep"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));
        server.enqueue("/v1/pages/PAGE2", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE2","url":"https://notion.so/drop-me","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Drop"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE2/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1,PAGE2");
        paramMap.put("include_pattern", ".*keep-me.*");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(1, callback.size());
        assertEquals("Keep", callback.getDataMaps().get(0).get("title"));
        // include/exclude decides only whether a page's own document is indexed, not whether its
        // blocks are walked: PAGE2's blocks are still fetched (see
        // test_excludedPage_descendantsStillDiscovered for why that matters).
        assertEquals(1, server.getRequestCount("/v1/blocks/PAGE2/children"));
    }

    /**
     * The regression test for the subtree-loss fix: a page excluded by {@code exclude_pattern}
     * (or one that fails outright) must not drop the child pages/databases discovered from its
     * own blocks. {@code processPage} walks blocks -- and enqueues what they reference -- before
     * checking the URL filter, so PAGE3 here is discovered and indexed even though its parent,
     * PAGE2, is filtered out and never indexed itself.
     */
    @Test
    public void test_excludedPage_descendantsStillDiscovered() {
        server.enqueue("/v1/pages/PAGE2", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE2","url":"https://notion.so/drop-me","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Drop"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE2/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"PAGE3","type":"child_page","has_children":false,"child_page":{"title":"Keep Me Too"}}
                ]}
                """));
        server.enqueue("/v1/pages/PAGE3", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE3","url":"https://notion.so/keep-me-too","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"page_id","page_id":"PAGE2"},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Keep Me Too"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE3/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE2");
        paramMap.put("exclude_pattern", ".*drop-me$");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals("only PAGE2 is excluded; PAGE3 must still be indexed", 1, callback.size());
        assertEquals("Keep Me Too", callback.getDataMaps().get(0).get("title"));
    }

    @Test
    public void test_tokenNotExposedToScripts() {
        final DataStoreParams paramMap = newParamMap("secret_SHOULD_NOT_LEAK");
        paramMap.put("root_page_ids", "PAGE1");

        final Map<String, Object> resultMap = dataStore.newResultMap(paramMap);

        assertNull("token must not be exposed to scripts", resultMap.get("token"));
        assertEquals("PAGE1", resultMap.get("root_page_ids"));
    }

    @Test
    public void test_fatalError_abortsCrawl() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.error(401, "unauthorized"));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        try {
            dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());
            fail("a 401 must abort the whole crawl");
        } catch (final DataStoreException e) {
            // expected: NotionApiException extends NotionDataStoreException extends DataStoreException
        }
    }

    @Test
    public void test_nonFatalError_skipsOneItem_continuesCrawl() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.error(404, "object_not_found"));
        server.enqueue("/v1/pages/PAGE2", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE2","url":"https://notion.so/PAGE2","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page 2"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE2/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1,PAGE2");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(1, callback.size());
        assertEquals("Page 2", callback.getDataMaps().get(0).get("title"));
        assertEquals(1, recordedFailures.size());
    }

    @Test
    public void test_includeFiles_false_skipsAttachmentDownload() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page"}]}}}
                """));
        final String fileUrl = server.putFile("report.txt", "irrelevant", "text/plain");
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"FILE1","type":"file","has_children":false,
                    "file":{"type":"file","file":{"url":"%s"},"name":"report.txt"}}
                ]}
                """.formatted(fileUrl)));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");
        paramMap.put("include_files", "false");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(1, callback.size());
        assertEquals("page", callback.getDataMaps().get(0).get("type"));
    }

    @Test
    public void test_oversizedAttachment_isSkippedEntirely() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page"}]}}}
                """));
        final String bigContent = "x".repeat(2048);
        final String fileUrl = server.putFile("big.txt", bigContent, "text/plain");
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"FILE1","type":"file","has_children":false,
                    "file":{"type":"file","file":{"url":"%s"},"name":"big.txt"}}
                ]}
                """.formatted(fileUrl)));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");
        paramMap.put("max_file_size", "100");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        // Only the page document is indexed; the oversized attachment is skipped, not indexed
        // with empty content (that fallback is reserved for an extraction failure).
        assertEquals(1, callback.size());
        assertEquals("page", callback.getDataMaps().get(0).get("type"));
    }

    /**
     * Exercises the full attachment pipeline -- download, MIME detection, and extraction -- by
     * wiring a real {@link ExtractorFactory}/{@link TikaExtractor} the way {@code
     * BoxDataStoreTest#test_storeData} does, plus the {@code CrawlerContainer} seam
     * {@code ExtractorBuilder#extract()} needs and that class does not set up (its equivalent
     * test only runs against live credentials). A plain functional {@link CrawlerContainer} is
     * enough: {@code ExtractorFactory}'s {@code crawlerContainer} field is package-private only
     * by convention ({@code protected}), not sealed, so reflection sets it once here.
     */
    @Test
    public void test_attachment_downloadedAndExtracted() throws Exception {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page"}]}}}
                """));
        final String fileUrl = server.putFile("notes.txt", "Attachment file content for extraction test.", "text/plain");
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"FILE1","type":"file","last_edited_time":"2026-01-03T00:00:00.000Z","has_children":false,
                    "file":{"type":"file","file":{"url":"%s"},"name":"notes.txt"}}
                ]}
                """.formatted(fileUrl)));

        registerRealExtraction();

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(2, callback.size());
        final Map<String, Object> fileDoc =
                callback.getDataMaps().stream().filter(m -> "file".equals(m.get("type"))).findFirst().orElse(null);
        assertNotNull("the attachment must be indexed as its own document", fileDoc);
        assertTrue(((String) fileDoc.get("content")).contains("Attachment file content for extraction test."));
        assertEquals("text/plain", fileDoc.get("mimetype"));
    }

    /**
     * The regression test for the "never leak the signed download URL" fix: a download failure
     * (here, a 404 from the mock file server -- a URL {@code putFile} was never called for) must
     * be recorded under the document identity ({@code <page url>#<block id>}), never the signed
     * URL, in the {@link FailureUrlService} record's url, its throwable's message chain, or any
     * WARN/INFO log line from {@link NotionDataStore} or {@link NotionClient}.
     */
    @Test
    public void test_attachmentDownloadFailure_doesNotLeakSignedUrl() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page"}]}}}
                """));
        final String secretMarker = "SIGNED-URL-SECRET-MARKER";
        final String fileUrl = server.getEndpoint().replace("/v1/", "/files/") + secretMarker + ".pdf";
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"FILE1","type":"file","has_children":false,
                    "file":{"type":"file","file":{"url":"%s"},"name":"report.pdf"}}
                ]}
                """.formatted(fileUrl)));

        final TestLogAppender dataStoreLog = TestLogAppender.attachTo(NotionDataStore.class);
        final TestLogAppender clientLog = TestLogAppender.attachTo(NotionClient.class);
        try {
            final DataStoreParams paramMap = newParamMap("secret_token");
            paramMap.put("root_page_ids", "PAGE1");

            final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
            dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

            // The page itself is still indexed; only the attachment download failed.
            assertEquals(1, callback.size());
            assertEquals(1, recordedFailures.size());
            final FailureRecord failure = recordedFailures.get(0);
            assertEquals("https://notion.so/PAGE1#FILE1", failure.url());
            assertFalse("the FailureUrlService url must be the document url, not the signed download url",
                    failure.url().contains(secretMarker));
            assertFalse("the recorded throwable's message chain must not contain the signed url",
                    containsInChain(failure.throwable(), secretMarker));
            assertFalse("no log line from NotionDataStore may contain the signed url", dataStoreLog.containsAnywhere(secretMarker));
            assertFalse("no log line from NotionClient may contain the signed url", clientLog.containsAnywhere(secretMarker));
        } finally {
            dataStoreLog.detach();
            clientLog.detach();
        }
    }

    private boolean containsInChain(final Throwable throwable, final String text) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void test_rootDatabaseId_failureRecordsFailureUrlServiceWithDatabaseId() {
        server.enqueue("/v1/databases/DB1", NotionApiMockServer.error(404, "object_not_found"));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("database_ids", "DB1");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals(0, callback.size());
        assertEquals(1, recordedFailures.size());
        final FailureRecord failure = recordedFailures.get(0);
        assertEquals("DB1", failure.url());
        assertTrue("the failure message must name the database id", failure.throwable().getMessage().contains("DB1"));
    }

    @Test
    public void test_childDatabase_failureRecordsFailureUrlServiceWithParentPageUrl() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"CHILDDB1","type":"child_database","has_children":false,"child_database":{"title":"Broken DB"}}
                ]}
                """));
        server.enqueue("/v1/databases/CHILDDB1", NotionApiMockServer.error(403, "restricted_resource"));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback();
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        // The containing page is still indexed; only the child database resolution failed.
        assertEquals(1, callback.size());
        assertEquals(1, recordedFailures.size());
        final FailureRecord failure = recordedFailures.get(0);
        assertEquals("CHILDDB1", failure.url());
        assertTrue("the failure message must name the database id", failure.throwable().getMessage().contains("CHILDDB1"));
        assertTrue("the failure message must name the parent page's url",
                failure.throwable().getMessage().contains("https://notion.so/PAGE1"));
    }

    /**
     * The regression test for honouring {@code stop()}: {@code AbstractDataStore#alive} must be
     * checked in the work-queue loop, not only at the very start, so an operator's admin-UI stop
     * button actually cuts a crawl short instead of running the whole queue regardless.
     */
    @Test
    public void test_stop_haltsQueueProcessingMidCrawl() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page1"}]}}}
                """));
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[]}
                """));
        // PAGE2's response is deliberately never enqueued: if stop() failed to take effect and it
        // were fetched anyway, the mock server's unscripted-default body would produce a
        // title-less document instead of failing loudly, so this is checked via the request
        // count below instead of relying on that fetch to fail.

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1,PAGE2");

        final TestIndexUpdateCallback callback = new TestIndexUpdateCallback() {
            @Override
            public void store(final DataStoreParams p, final Map<String, Object> dataMap) {
                super.store(p, dataMap);
                // Simulates an operator clicking "stop" in the admin UI right after this document.
                dataStore.stop();
            }
        };
        dataStore.storeData(new DataConfig(), callback, paramMap, defaultScriptMap(), new HashMap<>());

        assertEquals("only the page processed before stop() must be indexed", 1, callback.size());
        assertEquals(0, server.getRequestCount("/v1/pages/PAGE2"));
    }

    /**
     * The regression test for treating an interrupt as an interrupt: a genuine thread interrupt
     * (surfacing as {@link InterruptedRuntimeException} from {@code NotionClient}'s pacing sleep)
     * must propagate out of {@code storeData} and must not be recorded via {@link
     * FailureUrlService} as if it were an ordinary per-item failure.
     */
    @Test
    public void test_interrupt_propagatesRatherThanRecordedAsFailure() {
        server.enqueue("/v1/pages/PAGE1", NotionApiMockServer.json("""
                {"object":"page","id":"PAGE1","url":"https://notion.so/PAGE1","in_trash":false,
                 "created_time":"2026-01-01T00:00:00.000Z","last_edited_time":"2026-01-02T00:00:00.000Z",
                 "parent":{"type":"workspace","workspace":true},
                 "properties":{"title":{"id":"title","type":"title","title":[{"plain_text":"Page"}]}}}
                """));

        final DataStoreParams paramMap = newParamMap("secret_token");
        paramMap.put("root_page_ids", "PAGE1");
        paramMap.put("request_interval", "50"); // positive, so pace() actually calls sleep()

        Thread.currentThread().interrupt();
        try {
            try {
                dataStore.storeData(new DataConfig(), new TestIndexUpdateCallback(), paramMap, defaultScriptMap(), new HashMap<>());
                fail("an interrupt during pacing must propagate out of storeData, not be swallowed");
            } catch (final InterruptedRuntimeException e) {
                // expected
            }
            assertEquals("an interrupt must not be recorded as an ordinary per-item failure", 0, recordedFailures.size());
        } finally {
            // Clear the flag NotionClient.sleep() restored, so it does not leak into whatever
            // later test in this JVM next calls Thread.sleep() (most of this suite does).
            Thread.interrupted();
        }
    }

    @Test
    public void test_isIncludeComments_garbageValue_fallsBackToDefaultTrue() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("include_comments", "maybe");
        assertTrue("a garbage boolean value must fall back to the default (true), not be treated as false",
                dataStore.isIncludeComments(paramMap));
    }

    @Test
    public void test_isIncludeFiles_explicitFalseCaseInsensitive_isHonoured() {
        final DataStoreParams paramMap = new DataStoreParams();
        paramMap.put("include_files", "FALSE");
        assertFalse(dataStore.isIncludeFiles(paramMap));
    }

    @Test
    public void test_getMaxFileSize_nonPositive_fallsBackToDefault() {
        final DataStoreParams zero = new DataStoreParams();
        zero.put("max_file_size", "0");
        assertEquals(NotionDataStore.DEFAULT_MAX_FILE_SIZE, dataStore.getMaxFileSize(zero));

        final DataStoreParams negative = new DataStoreParams();
        negative.put("max_file_size", "-10");
        assertEquals(NotionDataStore.DEFAULT_MAX_FILE_SIZE, dataStore.getMaxFileSize(negative));
    }

    private void registerRealExtraction() throws Exception {
        final MimeTypeHelperImpl mimeTypeHelper = new MimeTypeHelperImpl();
        ComponentUtil.register(mimeTypeHelper, MimeTypeHelper.class.getCanonicalName());

        final TikaExtractor tikaExtractor = new TikaExtractor();
        tikaExtractor.init();

        final ExtractorFactory extractorFactory = new ExtractorFactory();
        final Map<String, Object> components = new HashMap<>();
        components.put("extractorFactory", extractorFactory);
        components.put("tikaExtractor", tikaExtractor);
        components.put("mimeTypeHelper", mimeTypeHelper);
        final CrawlerContainer crawlerContainer = new CrawlerContainer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T getComponent(final String name) {
                return (T) components.get(name);
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void destroy() {
                // nothing
            }
        };
        // ExtractorFactory#crawlerContainer is @Resource-injected by Lasta Di in production;
        // this test constructs the factory directly, so the field is set here instead.
        final Field field = ExtractorFactory.class.getDeclaredField("crawlerContainer");
        field.setAccessible(true);
        field.set(extractorFactory, crawlerContainer);

        ComponentUtil.register(extractorFactory, "extractorFactory");
    }

    /** A minimal {@link UrlFilter} standing in for fess-crawler's {@code UrlFilterImpl}, whose {@code match} needs a live {@code CrawlerContainer}-backed service not worth wiring up here. */
    private static final class FakeUrlFilter implements UrlFilter {
        private final List<Pattern> includes = new ArrayList<>();
        private final List<Pattern> excludes = new ArrayList<>();

        @Override
        public void init(final String sessionId) {
            // nothing to do
        }

        @Override
        public boolean match(final String url) {
            if (!includes.isEmpty() && includes.stream().noneMatch(p -> p.matcher(url).matches())) {
                return false;
            }
            return excludes.stream().noneMatch(p -> p.matcher(url).matches());
        }

        @Override
        public void addInclude(final String urlPattern) {
            includes.add(Pattern.compile(urlPattern));
        }

        @Override
        public void addExclude(final String urlPattern) {
            excludes.add(Pattern.compile(urlPattern));
        }

        @Override
        public void processUrl(final String url) {
            // not used by NotionDataStore
        }

        @Override
        public void clear() {
            includes.clear();
            excludes.clear();
        }
    }

}
