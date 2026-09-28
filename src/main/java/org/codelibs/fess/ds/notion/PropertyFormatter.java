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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Converts a Notion page's {@code properties} object into text.
 *
 * <p>
 * A normal (non-database-row) page has a single property, named {@code "title"} and of type
 * {@code "title"}. A database row has that same title property (under whatever name the database
 * schema gives it) plus one property per database column. {@link #getTitle(JsonNode)} finds the
 * title regardless of its property name by its {@code type}; {@link #format(NotionClient,
 * String, JsonNode)} formats every other property, so it is naturally empty for a normal page.
 * </p>
 */
public final class PropertyFormatter {

    private static final Logger logger = LogManager.getLogger(PropertyFormatter.class);

    private PropertyFormatter() {
        // utility class
    }

    /**
     * Finds and formats the page's title: the property whose {@code type} is {@code "title"},
     * whatever its own name is.
     *
     * @param properties the page's {@code properties} object
     * @return the title text, or an empty string if no title property was found
     */
    public static String getTitle(final JsonNode properties) {
        for (final Map.Entry<String, JsonNode> entry : properties.properties()) {
            final JsonNode value = entry.getValue();
            if ("title".equals(value.path("type").asText(""))) {
                return plainText(value.path("title"));
            }
        }
        return StringUtil.EMPTY;
    }

    /**
     * Formats every property other than the title into text, keyed by property name.
     *
     * <p>
     * A {@code relation} or {@code people} property truncated to 25 references in the page
     * object (signalled by a sibling {@code has_more: true}) is completed via
     * {@link NotionClient#getPropertyItems(String, String)} before being formatted.
     * </p>
     *
     * @param client the Notion client, for completing a truncated {@code relation}/{@code people}
     *            property
     * @param pageId the page id, for the same
     * @param properties the page's {@code properties} object
     * @return property name to formatted text, omitting the title property and any property that
     *         formats to an empty string
     */
    public static Map<String, String> format(final NotionClient client, final String pageId, final JsonNode properties) {
        final Map<String, String> result = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonNode> entry : properties.properties()) {
            final String name = entry.getKey();
            final JsonNode value = entry.getValue();
            final String type = value.path("type").asText("");
            if ("title".equals(type)) {
                continue;
            }
            final String text = formatValue(client, pageId, value, type);
            if (StringUtil.isNotBlank(text)) {
                result.put(name, text);
            }
        }
        return result;
    }

    /**
     * Formats a {@code created_by}/{@code last_edited_by} user reference: the user's name when
     * Notion returned one, otherwise the user id. Shared by {@link #format} (for the same-named
     * property types) and by {@code NotionDataStore} (for the page object's own
     * {@code created_by}/{@code last_edited_by} fields).
     *
     * @param userRef a Notion user reference object ({@code {"object":"user","id":...,"name":...}})
     * @return the user's name, the user id, or an empty string if {@code userRef} carries neither
     */
    public static String formatUserRef(final JsonNode userRef) {
        if (userRef == null || userRef.isMissingNode() || userRef.isNull()) {
            return StringUtil.EMPTY;
        }
        if (userRef.hasNonNull("name")) {
            return userRef.path("name").asText();
        }
        return userRef.path("id").asText("");
    }

    private static String formatValue(final NotionClient client, final String pageId, final JsonNode value, final String type) {
        switch (type) {
        case "rich_text":
            return plainText(value.path("rich_text"));
        case "number":
            return value.path("number").isNull() ? StringUtil.EMPTY : value.path("number").asText();
        case "select":
            return value.path("select").path("name").asText("");
        case "status":
            return value.path("status").path("name").asText("");
        case "multi_select":
            return joinNames(value.path("multi_select"));
        case "date":
            return formatDate(value.path("date"));
        case "checkbox":
            return String.valueOf(value.path("checkbox").asBoolean(false));
        case "url":
            return value.path("url").asText("");
        case "email":
            return value.path("email").asText("");
        case "phone_number":
            return value.path("phone_number").asText("");
        case "files":
            return joinFileNames(value.path("files"));
        case "people":
            return formatPeople(client, pageId, value);
        case "relation":
            return formatRelation(client, pageId, value);
        case "rollup":
            return formatRollup(value.path("rollup"));
        case "formula":
            return formatFormula(value.path("formula"));
        case "created_time":
            return value.path("created_time").asText("");
        case "last_edited_time":
            return value.path("last_edited_time").asText("");
        case "created_by":
            return formatUserRef(value.path("created_by"));
        case "last_edited_by":
            return formatUserRef(value.path("last_edited_by"));
        case "unique_id":
            return formatUniqueId(value.path("unique_id"));
        case "verification":
            return value.path("verification").path("state").asText("");
        default:
            return StringUtil.EMPTY;
        }
    }

    private static String formatPeople(final NotionClient client, final String pageId, final JsonNode value) {
        return formatPaginatedRefList(client, pageId, value, "people");
    }

    private static String formatRelation(final NotionClient client, final String pageId, final JsonNode value) {
        return formatPaginatedRefList(client, pageId, value, "relation");
    }

    /**
     * Formats a {@code people}/{@code relation} property value, completing it via {@link
     * NotionClient#getPropertyItems} first when it looks truncated.
     *
     * <p>
     * Truncation is signalled by a sibling {@code has_more: true} -- but Notion is not documented
     * to guarantee that flag is set on the boundary case of <em>exactly</em> 25 embedded
     * references, so an array of exactly 25 also triggers the fetch, on the assumption that a
     * property with precisely the embedded cap's worth of references is more likely truncated
     * than coincidentally exactly full.
     * </p>
     *
     * <p>
     * A non-fatal failure fetching the full list is warned (naming the property and the page) and
     * falls back to the embedded (possibly truncated) value, rather than failing the whole page
     * over one property being incomplete. A fatal failure still propagates.
     * </p>
     */
    private static String formatPaginatedRefList(final NotionClient client, final String pageId, final JsonNode value, final String type) {
        final JsonNode embedded = value.path(type);
        if (needsPropertyItemsFetch(value, embedded)) {
            final String propertyId = value.path("id").asText("");
            try {
                final List<JsonNode> items = client.getPropertyItems(pageId, propertyId);
                return join(items, item -> describeRef(unwrap(item, type), type));
            } catch (final NotionApiException e) {
                if (e.isFatal()) {
                    throw e;
                }
                logger.warn("Failed to fetch the full value of Notion '{}' property (id={}) on page {}: {}; "
                        + "falling back to the embedded (possibly truncated) value.", type, propertyId, pageId, e.getMessage());
            }
        }
        return join(embedded, node -> describeRef(node, type));
    }

    private static boolean needsPropertyItemsFetch(final JsonNode value, final JsonNode embedded) {
        if (value.path("has_more").asBoolean(false)) {
            return true;
        }
        return embedded.isArray() && embedded.size() == 25;
    }

    /**
     * Returns the type-specific payload of one paginated property-item result, falling back to
     * the item itself when it carries no such nested field -- the "people" pagination endpoint is
     * documented to return the user object directly rather than nested under a {@code "people"}
     * key.
     */
    private static JsonNode unwrap(final JsonNode item, final String type) {
        final JsonNode nested = item.path(type);
        return nested.isMissingNode() || nested.isNull() ? item : nested;
    }

    private static String describeRef(final JsonNode node, final String type) {
        if ("people".equals(type)) {
            return node.hasNonNull("name") ? node.path("name").asText() : node.path("id").asText("");
        }
        return node.path("id").asText("");
    }

    private static String formatRollup(final JsonNode rollup) {
        final String rollupType = rollup.path("type").asText("");
        switch (rollupType) {
        case "number":
            return rollup.path("number").isNull() ? StringUtil.EMPTY : rollup.path("number").asText();
        case "date":
            return formatDate(rollup.path("date"));
        case "array":
            final StringBuilder joined = new StringBuilder();
            for (final JsonNode element : rollup.path("array")) {
                final String elementType = element.path("type").asText("");
                final String elementText = formatRollupArrayElement(element, elementType);
                if (StringUtil.isNotBlank(elementText)) {
                    if (joined.length() > 0) {
                        joined.append(", ");
                    }
                    joined.append(elementText);
                }
            }
            return joined.toString();
        default:
            return StringUtil.EMPTY;
        }
    }

    private static String formatRollupArrayElement(final JsonNode element, final String elementType) {
        switch (elementType) {
        case "title":
            return plainText(element.path("title"));
        case "rich_text":
            return plainText(element.path("rich_text"));
        case "select":
            return element.path("select").path("name").asText("");
        case "number":
            return element.path("number").isNull() ? StringUtil.EMPTY : element.path("number").asText();
        case "date":
            return formatDate(element.path("date"));
        default:
            return StringUtil.EMPTY;
        }
    }

    private static String formatFormula(final JsonNode formula) {
        final String formulaType = formula.path("type").asText("");
        switch (formulaType) {
        case "string":
            return formula.path("string").asText("");
        case "number":
            return formula.path("number").isNull() ? StringUtil.EMPTY : formula.path("number").asText();
        case "boolean":
            return String.valueOf(formula.path("boolean").asBoolean(false));
        case "date":
            return formatDate(formula.path("date"));
        default:
            return StringUtil.EMPTY;
        }
    }

    private static String formatUniqueId(final JsonNode uniqueId) {
        if (uniqueId.isMissingNode() || uniqueId.isNull()) {
            return StringUtil.EMPTY;
        }
        final String prefix = uniqueId.path("prefix").asText("");
        final String number = uniqueId.path("number").isNull() ? "" : uniqueId.path("number").asText();
        return StringUtil.isBlank(prefix) ? number : prefix + "-" + number;
    }

    private static String formatDate(final JsonNode date) {
        if (date.isMissingNode() || date.isNull()) {
            return StringUtil.EMPTY;
        }
        final String start = date.path("start").asText("");
        final String end = date.path("end").asText("");
        return StringUtil.isBlank(end) ? start : start + " - " + end;
    }

    private static String joinNames(final JsonNode array) {
        return join(array, node -> node.path("name").asText(""));
    }

    private static String joinFileNames(final JsonNode array) {
        return join(array, node -> node.path("name").asText(""));
    }

    private static String join(final Iterable<JsonNode> nodes, final java.util.function.Function<JsonNode, String> toText) {
        final StringBuilder joined = new StringBuilder();
        for (final JsonNode node : nodes) {
            final String text = toText.apply(node);
            if (StringUtil.isNotBlank(text)) {
                if (joined.length() > 0) {
                    joined.append(", ");
                }
                joined.append(text);
            }
        }
        return joined.toString();
    }

    private static String plainText(final JsonNode richTextArray) {
        if (!richTextArray.isArray()) {
            return StringUtil.EMPTY;
        }
        final StringBuilder line = new StringBuilder();
        for (final JsonNode richText : richTextArray) {
            line.append(richText.path("plain_text").asText(""));
        }
        return line.toString();
    }

}
