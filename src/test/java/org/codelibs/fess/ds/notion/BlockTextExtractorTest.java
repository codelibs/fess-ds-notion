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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class BlockTextExtractorTest extends UnitDsTestCase {

    private NotionApiMockServer server;

    private NotionClient client;

    @Override
    public void setUp(final TestInfo testInfo) throws Exception {
        super.setUp(testInfo);
        server = new NotionApiMockServer();
        server.start();
        client = server.newClient("secret_test_token");
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
    public void test_richTextBlocks_and_heading() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"B1","type":"heading_1","has_children":false,
                    "heading_1":{"rich_text":[{"plain_text":"Title Block"}]}},
                  {"id":"B2","type":"paragraph","has_children":false,
                    "paragraph":{"rich_text":[{"plain_text":"Hello "},{"plain_text":"World"}]}}
                ]}
                """));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue("text should contain heading", result.getText().contains("Title Block"));
        assertTrue("text should contain concatenated paragraph runs", result.getText().contains("Hello World"));
    }

    @Test
    public void test_tableRowCells() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"TBL","type":"table","has_children":true,"table":{"table_width":2}}
                ]}
                """));
        server.enqueue("/v1/blocks/TBL/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"ROW1","type":"table_row","has_children":false,
                    "table_row":{"cells":[[{"plain_text":"Name"}],[{"plain_text":"Value"}]]}}
                ]}
                """));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue("table row text should join cells", result.getText().contains("Name | Value"));
    }

    @Test
    public void test_captionsOnImageAndFileBlocks() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"IMG","type":"image","has_children":false,
                    "image":{"type":"external","external":{"url":"https://example.com/pic.png"},
                             "caption":[{"plain_text":"A picture"}]}}
                ]}
                """));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue("caption text should be included", result.getText().contains("A picture"));
        assertTrue("an external image must not be collected as an attachment", result.getAttachments().isEmpty());
    }

    @Test
    public void test_fileBlock_collectedAsAttachment_withCaption() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"FILE1","type":"file","last_edited_time":"2026-01-02T03:04:05.000Z","has_children":false,
                    "file":{"type":"file","file":{"url":"https://s3.example.com/signed/report.pdf?x=1"},
                            "name":"report.pdf","caption":[{"plain_text":"Quarterly report"}]}}
                ]}
                """));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue("caption text should still be included", result.getText().contains("Quarterly report"));
        assertEquals("one Notion-hosted file should be collected", 1, result.getAttachments().size());
        final BlockTextExtractor.AttachmentRef ref = result.getAttachments().get(0);
        assertEquals("FILE1", ref.blockId());
        assertEquals("report.pdf", ref.name());
        assertEquals("https://s3.example.com/signed/report.pdf?x=1", ref.url());
        assertEquals("2026-01-02T03:04:05.000Z", ref.lastEditedTime());
    }

    @Test
    public void test_fileBlock_withoutName_derivesNameFromUrl() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"FILE2","type":"pdf","has_children":false,
                    "pdf":{"type":"file","file":{"url":"https://s3.example.com/signed/Design%20Doc.pdf?x=1"}}}
                ]}
                """));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertEquals(1, result.getAttachments().size());
        assertEquals("Design Doc.pdf", result.getAttachments().get(0).name());
    }

    @Test
    public void test_externalAndFileUploadBlocks_notCollected() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"EXT","type":"file","has_children":false,
                    "file":{"type":"external","external":{"url":"https://example.com/doc.pdf"}}},
                  {"id":"UPLOAD","type":"file","has_children":false,
                    "file":{"type":"file_upload","file_upload":{"id":"upload-id"}}}
                ]}
                """));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue("neither external nor file_upload should be collected", result.getAttachments().isEmpty());
    }

    @Test
    public void test_childPageAndChildDatabase_collectedNotRecursed() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"CHILDPAGE1","type":"child_page","has_children":true,
                    "child_page":{"title":"Sub Page"}},
                  {"id":"CHILDDB1","type":"child_database","has_children":true,
                    "child_database":{"title":"Sub Database"}}
                ]}
                """));
        // No response queued for CHILDPAGE1/CHILDDB1 children: if BlockTextExtractor ever tried to
        // recurse into either, the unscripted-default empty list would mask the bug, so assert the
        // paths were never even requested.

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertEquals(List.of("CHILDPAGE1"), result.getChildPageIds());
        assertEquals(List.of("CHILDDB1"), result.getChildDatabaseIds());
        assertEquals(0, server.getRequestCount("/v1/blocks/CHILDPAGE1/children"));
        assertEquals(0, server.getRequestCount("/v1/blocks/CHILDDB1/children"));
    }

    @Test
    public void test_syncedBlock_originalAndCopy_bothRecursed() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"ORIG","type":"synced_block","has_children":true,
                    "synced_block":{"synced_from":null}},
                  {"id":"COPY","type":"synced_block","has_children":true,
                    "synced_block":{"synced_from":{"block_id":"ORIG"}}}
                ]}
                """));
        server.enqueue("/v1/blocks/ORIG/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"OP","type":"paragraph","has_children":false,"paragraph":{"rich_text":[{"plain_text":"Original text"}]}}
                ]}
                """));
        server.enqueue("/v1/blocks/COPY/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"CP","type":"paragraph","has_children":false,"paragraph":{"rich_text":[{"plain_text":"Copy text"}]}}
                ]}
                """));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue(result.getText().contains("Original text"));
        assertTrue(result.getText().contains("Copy text"));
    }

    @Test
    public void test_columnListAndColumn_recursed() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"COLLIST","type":"column_list","has_children":true,"column_list":{}}
                ]}
                """));
        server.enqueue("/v1/blocks/COLLIST/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"COL1","type":"column","has_children":true,"column":{}}
                ]}
                """));
        server.enqueue("/v1/blocks/COL1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"COLP","type":"paragraph","has_children":false,"paragraph":{"rich_text":[{"plain_text":"Column text"}]}}
                ]}
                """));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue(result.getText().contains("Column text"));
    }

    @Test
    public void test_nonFatalErrorOnSubtree_isSkippedNotPropagated() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"GOOD","type":"paragraph","has_children":false,"paragraph":{"rich_text":[{"plain_text":"Kept"}]}},
                  {"id":"BAD","type":"toggle","has_children":true,"toggle":{"rich_text":[{"plain_text":"Broken toggle"}]}}
                ]}
                """));
        server.enqueue("/v1/blocks/BAD/children", NotionApiMockServer.error(404, "object_not_found"));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue("sibling content survives a skipped subtree", result.getText().contains("Kept"));
        assertTrue("the toggle's own text is still collected", result.getText().contains("Broken toggle"));
    }

    @Test
    public void test_fatalErrorOnSubtree_propagates() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"FATAL","type":"toggle","has_children":true,"toggle":{"rich_text":[]}}
                ]}
                """));
        server.enqueue("/v1/blocks/FATAL/children", NotionApiMockServer.error(401, "unauthorized"));

        try {
            BlockTextExtractor.extract(client, "PAGE1");
            fail("a fatal Notion API error must propagate out of extract()");
        } catch (final NotionApiException e) {
            assertTrue(e.isFatal());
        }
    }

    @Test
    public void test_forbiddenErrorOnSubtree_isSkippedNotPropagated() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"GOOD","type":"paragraph","has_children":false,"paragraph":{"rich_text":[{"plain_text":"Kept"}]}},
                  {"id":"FORBIDDEN","type":"toggle","has_children":true,"toggle":{"rich_text":[]}}
                ]}
                """));
        server.enqueue("/v1/blocks/FORBIDDEN/children", NotionApiMockServer.error(403, "restricted_resource"));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue("403 restricted_resource must be skipped like 404, not propagated", result.getText().contains("Kept"));
    }

    /**
     * The regression test for the block-fetch error narrowing: only {@code 404 object_not_found}
     * and {@code 403 restricted_resource} are skip-and-continue. Anything else -- here a {@code
     * 400 validation_error} -- must propagate and fail the whole page rather than silently index
     * it with a missing subtree, since this class cannot tell whether that subtree's content
     * would have been safe to omit.
     */
    @Test
    public void test_otherNonFatalErrorOnSubtree_propagatesInsteadOfSkipping() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"BAD","type":"toggle","has_children":true,"toggle":{"rich_text":[]}}
                ]}
                """));
        server.enqueue("/v1/blocks/BAD/children", NotionApiMockServer.error(400, "validation_error"));

        try {
            BlockTextExtractor.extract(client, "PAGE1");
            fail("a 400 validation_error must propagate, not be silently skipped");
        } catch (final NotionApiException e) {
            assertFalse("a 400 is not fatal either -- it is a per-page failure, not a crawl-aborting one", e.isFatal());
            assertEquals(400, e.getStatus());
        }
    }

    @Test
    public void test_recursion_acrossMultipleLevels() {
        server.enqueue("/v1/blocks/PAGE1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"L1","type":"toggle","has_children":true,"toggle":{"rich_text":[{"plain_text":"Level 1"}]}}
                ]}
                """));
        server.enqueue("/v1/blocks/L1/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"L2","type":"toggle","has_children":true,"toggle":{"rich_text":[{"plain_text":"Level 2"}]}}
                ]}
                """));
        server.enqueue("/v1/blocks/L2/children", NotionApiMockServer.json("""
                {"object":"list","has_more":false,"next_cursor":null,"results":[
                  {"id":"L3","type":"paragraph","has_children":false,"paragraph":{"rich_text":[{"plain_text":"Level 3"}]}}
                ]}
                """));

        final BlockTextExtractor.Result result = BlockTextExtractor.extract(client, "PAGE1");

        assertTrue(result.getText().contains("Level 1"));
        assertTrue(result.getText().contains("Level 2"));
        assertTrue(result.getText().contains("Level 3"));
    }

}
