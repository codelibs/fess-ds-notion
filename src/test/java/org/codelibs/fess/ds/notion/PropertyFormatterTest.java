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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PropertyFormatterTest extends UnitDsTestCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private JsonNode tree(final String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    public void test_getTitle_normalPage() throws Exception {
        final JsonNode properties = tree("""
                {"title":{"id":"title","type":"title","title":[{"plain_text":"My Page"}]}}
                """);
        assertEquals("My Page", PropertyFormatter.getTitle(properties));
    }

    @Test
    public void test_getTitle_databaseRow_customPropertyName() throws Exception {
        final JsonNode properties = tree("""
                {"Task Name":{"id":"abc","type":"title","title":[{"plain_text":"Write report"}]},
                 "Status":{"id":"st","type":"status","status":{"name":"In Progress"}}}
                """);
        assertEquals("Write report", PropertyFormatter.getTitle(properties));
    }

    @Test
    public void test_format_excludesTitle_emptyForNormalPage() throws Exception {
        final JsonNode properties = tree("""
                {"title":{"id":"title","type":"title","title":[{"plain_text":"My Page"}]}}
                """);
        final Map<String, String> formatted = PropertyFormatter.format(client, "PAGE1", properties);
        assertTrue("a normal page's properties map must be empty", formatted.isEmpty());
    }

    @Test
    public void test_format_scalarTypes() throws Exception {
        final JsonNode properties = tree("""
                {
                  "Name":{"id":"t","type":"title","title":[{"plain_text":"Row"}]},
                  "Notes":{"id":"n","type":"rich_text","rich_text":[{"plain_text":"Some notes"}]},
                  "Count":{"id":"c","type":"number","number":42},
                  "Category":{"id":"cat","type":"select","select":{"name":"Alpha"}},
                  "Tags":{"id":"tg","type":"multi_select","multi_select":[{"name":"a"},{"name":"b"}]},
                  "State":{"id":"s","type":"status","status":{"name":"Done"}},
                  "Due":{"id":"d","type":"date","date":{"start":"2026-01-01","end":"2026-01-05"}},
                  "Done":{"id":"chk","type":"checkbox","checkbox":true},
                  "Link":{"id":"u","type":"url","url":"https://example.com"},
                  "Contact":{"id":"e","type":"email","email":"user@example.com"},
                  "Phone":{"id":"p","type":"phone_number","phone_number":"+1-555-0100"},
                  "Attachments":{"id":"f","type":"files","files":[{"name":"a.pdf"},{"name":"b.pdf"}]},
                  "Created":{"id":"ct","type":"created_time","created_time":"2026-01-01T00:00:00.000Z"},
                  "Edited":{"id":"lt","type":"last_edited_time","last_edited_time":"2026-01-02T00:00:00.000Z"},
                  "Author":{"id":"cb","type":"created_by","created_by":{"object":"user","id":"U1","name":"Alice"}},
                  "Editor":{"id":"eb","type":"last_edited_by","last_edited_by":{"object":"user","id":"U2"}},
                  "Code":{"id":"uid","type":"unique_id","unique_id":{"prefix":"TASK","number":7}},
                  "Verified":{"id":"v","type":"verification","verification":{"state":"verified"}}
                }
                """);

        final Map<String, String> formatted = PropertyFormatter.format(client, "PAGE1", properties);

        assertFalse("title must not appear in the properties map", formatted.containsKey("Name"));
        assertEquals("Some notes", formatted.get("Notes"));
        assertEquals("42", formatted.get("Count"));
        assertEquals("Alpha", formatted.get("Category"));
        assertEquals("a, b", formatted.get("Tags"));
        assertEquals("Done", formatted.get("State"));
        assertEquals("2026-01-01 - 2026-01-05", formatted.get("Due"));
        assertEquals("true", formatted.get("Done"));
        assertEquals("https://example.com", formatted.get("Link"));
        assertEquals("user@example.com", formatted.get("Contact"));
        assertEquals("+1-555-0100", formatted.get("Phone"));
        assertEquals("a.pdf, b.pdf", formatted.get("Attachments"));
        assertEquals("2026-01-01T00:00:00.000Z", formatted.get("Created"));
        assertEquals("2026-01-02T00:00:00.000Z", formatted.get("Edited"));
        assertEquals("Alice", formatted.get("Author"));
        assertEquals("U2", formatted.get("Editor"));
        assertEquals("TASK-7", formatted.get("Code"));
        assertEquals("verified", formatted.get("Verified"));
    }

    @Test
    public void test_format_formulaAndRollup() throws Exception {
        final JsonNode properties = tree("""
                {
                  "title":{"id":"t","type":"title","title":[{"plain_text":"Row"}]},
                  "Calc":{"id":"f","type":"formula","formula":{"type":"number","number":3.5}},
                  "Flag":{"id":"f2","type":"formula","formula":{"type":"boolean","boolean":true}},
                  "Summary":{"id":"r","type":"rollup","rollup":{"type":"array","array":[
                      {"type":"title","title":[{"plain_text":"Sub A"}]},
                      {"type":"number","number":9}
                  ]}}
                }
                """);

        final Map<String, String> formatted = PropertyFormatter.format(client, "PAGE1", properties);

        assertEquals("3.5", formatted.get("Calc"));
        assertEquals("true", formatted.get("Flag"));
        assertEquals("Sub A, 9", formatted.get("Summary"));
    }

    @Test
    public void test_format_people_embedded_25OrFewer() throws Exception {
        final JsonNode properties = tree("""
                {
                  "title":{"id":"t","type":"title","title":[{"plain_text":"Row"}]},
                  "Owners":{"id":"pp","type":"people","has_more":false,"people":[
                      {"object":"user","id":"U1","name":"Alice"},
                      {"object":"user","id":"U2","name":"Bob"}
                  ]}
                }
                """);

        final Map<String, String> formatted = PropertyFormatter.format(client, "PAGE1", properties);

        assertEquals("Alice, Bob", formatted.get("Owners"));
        assertEquals(0, server.getRequestCount("/v1/pages/PAGE1/properties/pp"));
    }

    @Test
    public void test_format_people_moreThan25_fetchesPropertyEndpoint() throws Exception {
        server.enqueue("/v1/pages/PAGE1/properties/pp", NotionApiMockServer.json("""
                {"object":"list","type":"property_item","has_more":false,"next_cursor":null,"results":[
                  {"object":"user","id":"U1","name":"Alice"},
                  {"object":"user","id":"U2","name":"Bob"},
                  {"object":"user","id":"U3"}
                ]}
                """));
        final JsonNode properties = tree("""
                {
                  "title":{"id":"t","type":"title","title":[{"plain_text":"Row"}]},
                  "Owners":{"id":"pp","type":"people","has_more":true,"people":[
                      {"object":"user","id":"U1","name":"Alice"}
                  ]}
                }
                """);

        final Map<String, String> formatted = PropertyFormatter.format(client, "PAGE1", properties);

        assertEquals("Alice, Bob, U3", formatted.get("Owners"));
        assertEquals(1, server.getRequestCount("/v1/pages/PAGE1/properties/pp"));
    }

    @Test
    public void test_format_relation_moreThan25_fetchesPropertyEndpoint() throws Exception {
        server.enqueue("/v1/pages/PAGE1/properties/rel", NotionApiMockServer.json("""
                {"object":"list","type":"property_item","has_more":false,"next_cursor":null,"results":[
                  {"object":"property_item","id":"rel","type":"relation","relation":{"id":"PG1"}},
                  {"object":"property_item","id":"rel","type":"relation","relation":{"id":"PG2"}}
                ]}
                """));
        final JsonNode properties = tree("""
                {
                  "title":{"id":"t","type":"title","title":[{"plain_text":"Row"}]},
                  "Related":{"id":"rel","type":"relation","has_more":true,"relation":[{"id":"PG1"}]}
                }
                """);

        final Map<String, String> formatted = PropertyFormatter.format(client, "PAGE1", properties);

        assertEquals("PG1, PG2", formatted.get("Related"));
    }

    /**
     * Regression test: Notion is not documented to guarantee {@code has_more} is set on the exact
     * boundary of 25 embedded references, so an array of exactly 25 must also trigger the
     * property-endpoint fetch, not just an explicit {@code has_more: true}.
     */
    @Test
    public void test_format_people_exactly25_triggersFetchEvenWithoutHasMore() throws Exception {
        final StringBuilder embedded = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            if (embedded.length() > 0) {
                embedded.append(',');
            }
            embedded.append("{\"object\":\"user\",\"id\":\"U").append(i).append("\",\"name\":\"Person ").append(i).append("\"}");
        }
        final JsonNode properties = tree("""
                {
                  "title":{"id":"t","type":"title","title":[{"plain_text":"Row"}]},
                  "Owners":{"id":"pp","type":"people","people":[%s]}
                }
                """.formatted(embedded));

        server.enqueue("/v1/pages/PAGE1/properties/pp", NotionApiMockServer.json("""
                {"object":"list","type":"property_item","has_more":false,"next_cursor":null,"results":[
                  {"object":"user","id":"U1","name":"Person 1"},
                  {"object":"user","id":"U26","name":"Person 26"}
                ]}
                """));

        final Map<String, String> formatted = PropertyFormatter.format(client, "PAGE1", properties);

        assertEquals(1, server.getRequestCount("/v1/pages/PAGE1/properties/pp"));
        assertEquals("Person 1, Person 26", formatted.get("Owners"));
    }

    @Test
    public void test_format_people_24Entries_doesNotTriggerFetch() throws Exception {
        final StringBuilder embedded = new StringBuilder();
        for (int i = 1; i <= 24; i++) {
            if (embedded.length() > 0) {
                embedded.append(',');
            }
            embedded.append("{\"object\":\"user\",\"id\":\"U").append(i).append("\",\"name\":\"Person ").append(i).append("\"}");
        }
        final JsonNode properties = tree("""
                {
                  "title":{"id":"t","type":"title","title":[{"plain_text":"Row"}]},
                  "Owners":{"id":"pp","type":"people","people":[%s]}
                }
                """.formatted(embedded));

        PropertyFormatter.format(client, "PAGE1", properties);

        assertEquals(0, server.getRequestCount("/v1/pages/PAGE1/properties/pp"));
    }

    /**
     * Regression test: a non-fatal failure fetching the full {@code people}/{@code relation}
     * value must not fail the whole page -- it falls back to the embedded (possibly truncated)
     * value, with a WARN naming the property and the page.
     */
    @Test
    public void test_format_people_fetchFailure_fallsBackToEmbeddedValue() throws Exception {
        server.enqueue("/v1/pages/PAGE1/properties/pp", NotionApiMockServer.error(404, "object_not_found"));
        final JsonNode properties = tree("""
                {
                  "title":{"id":"t","type":"title","title":[{"plain_text":"Row"}]},
                  "Owners":{"id":"pp","type":"people","has_more":true,"people":[
                      {"object":"user","id":"U1","name":"Alice"}
                  ]}
                }
                """);

        final TestLogAppender appender = TestLogAppender.attachTo(PropertyFormatter.class);
        try {
            final Map<String, String> formatted = PropertyFormatter.format(client, "PAGE1", properties);

            assertEquals("a failed fetch falls back to the truncated embedded value instead of failing the page", "Alice",
                    formatted.get("Owners"));
            final boolean warnedWithContext = appender.messagesAt(org.apache.logging.log4j.Level.WARN)
                    .stream()
                    .anyMatch(m -> m.contains("people") && m.contains("PAGE1"));
            assertTrue("the WARN must name the property type and the page id", warnedWithContext);
        } finally {
            appender.detach();
        }
    }

    @Test
    public void test_format_relation_fetchFailure_fatal_propagates() throws Exception {
        server.enqueue("/v1/pages/PAGE1/properties/rel", NotionApiMockServer.error(401, "unauthorized"));
        final JsonNode properties = tree("""
                {
                  "title":{"id":"t","type":"title","title":[{"plain_text":"Row"}]},
                  "Related":{"id":"rel","type":"relation","has_more":true,"relation":[{"id":"PG1"}]}
                }
                """);

        try {
            PropertyFormatter.format(client, "PAGE1", properties);
            fail("a fatal error fetching a property's full value must propagate");
        } catch (final NotionApiException e) {
            assertTrue(e.isFatal());
        }
    }

    @Test
    public void test_formatUserRef_nameAndIdFallback() throws Exception {
        assertEquals("Alice", PropertyFormatter.formatUserRef(tree("{\"object\":\"user\",\"id\":\"U1\",\"name\":\"Alice\"}")));
        assertEquals("U2", PropertyFormatter.formatUserRef(tree("{\"object\":\"user\",\"id\":\"U2\"}")));
    }

}
