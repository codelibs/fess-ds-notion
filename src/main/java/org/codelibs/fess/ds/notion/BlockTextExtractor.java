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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codelibs.core.lang.StringUtil;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Walks a Notion block tree (a page's or a block's children, recursively) and turns it into
 * plain text, while collecting the file/PDF attachments and the child page/database references
 * found along the way.
 *
 * <p>
 * Notion's block model has dozens of block types, each with a differently-shaped payload. Rather
 * than a typed class per block type, this reads each block's JSON defensively via
 * {@link JsonNode#path(String)}, which never throws on a missing or unexpectedly-shaped field --
 * an unrecognized or empty block simply contributes no text.
 * </p>
 */
public class BlockTextExtractor {

    private static final Logger logger = LogManager.getLogger(BlockTextExtractor.class);

    /** Block types whose text lives at {@code block[type].rich_text}. */
    private static final Set<String> RICH_TEXT_BLOCK_TYPES = Set.of("paragraph", "heading_1", "heading_2", "heading_3",
            "bulleted_list_item", "numbered_list_item", "to_do", "toggle", "quote", "callout", "code", "template");

    /** Block types whose {@code block[type].caption} contributes text, in addition to any other handling. */
    private static final Set<String> CAPTIONED_BLOCK_TYPES =
            Set.of("image", "video", "audio", "file", "pdf", "bookmark", "embed", "link_preview");

    /**
     * The result of walking one block tree: the assembled text, every {@code file}/{@code pdf}
     * (Notion-hosted only) block found, and every {@code child_page}/{@code child_database}
     * block id found. A {@code child_page}/{@code child_database} block's own subtree is never
     * recursed into: it names a separate page or database, crawled as its own work item.
     */
    public static class Result {

        /**
         * Creates a new, empty result.
         */
        public Result() {
            // populated only by BlockTextExtractor as it walks a block tree
        }

        private final StringBuilder text = new StringBuilder();

        private final List<AttachmentRef> attachments = new ArrayList<>();

        private final List<String> childPageIds = new ArrayList<>();

        private final List<String> childDatabaseIds = new ArrayList<>();

        /**
         * Returns the assembled text of the block tree, blocks separated by newlines.
         *
         * @return the block tree's text, never {@code null}
         */
        public String getText() {
            return text.toString().trim();
        }

        /**
         * Returns the Notion-hosted {@code file}/{@code pdf} blocks found while walking the tree.
         *
         * @return the attachment references, in document order
         */
        public List<AttachmentRef> getAttachments() {
            return attachments;
        }

        /**
         * Returns the {@code child_page} block ids found while walking the tree.
         *
         * @return the child page ids, in document order
         */
        public List<String> getChildPageIds() {
            return childPageIds;
        }

        /**
         * Returns the {@code child_database} block ids found while walking the tree.
         *
         * @return the child database ids, in document order
         */
        public List<String> getChildDatabaseIds() {
            return childDatabaseIds;
        }
    }

    /**
     * A Notion-hosted {@code file}/{@code pdf} block eligible for download and extraction. Only
     * {@code type: "file"} objects (a Notion-hosted, time-limited signed URL) are collected;
     * {@code external} and {@code file_upload} are not.
     *
     * @param blockId the block's id
     * @param url the signed download URL
     * @param name the file name, from the block when present, otherwise derived from the URL
     * @param lastEditedTime the block's {@code last_edited_time}, or {@code null}
     */
    public record AttachmentRef(String blockId, String url, String name, String lastEditedTime) {
    }

    private final NotionClient client;

    private final Result result = new Result();

    private BlockTextExtractor(final NotionClient client) {
        this.client = client;
    }

    /**
     * Walks the children of {@code blockId} (a page or a block), recursively, and returns the
     * assembled result.
     *
     * @param client the Notion client, for fetching each level of children
     * @param blockId the root block (typically a page) whose children are walked
     * @return the assembled text and collected references
     * @throws NotionApiException if fetching one block's children fails other than with a {@code
     *             404 object_not_found} or {@code 403 restricted_resource} -- including a fatal
     *             error ({@link NotionApiException#isFatal()}), and including a retryable status
     *             or transport error whose retries were exhausted. Only those two specific,
     *             expected-to-happen cases are logged and skipped, leaving the rest of the tree
     *             intact; anything else means this page's own text cannot be trusted to be
     *             complete, so the whole page is left to fail rather than be indexed silently
     *             missing content.
     */
    public static Result extract(final NotionClient client, final String blockId) {
        final BlockTextExtractor extractor = new BlockTextExtractor(client);
        extractor.walkChildren(blockId);
        return extractor.result;
    }

    private void walkChildren(final String parentBlockId) {
        client.listBlockChildren(parentBlockId, this::visit);
    }

    private void visit(final JsonNode block) {
        final String type = block.path("type").asText("");
        final String blockId = block.path("id").asText("");

        if ("child_page".equals(type)) {
            if (StringUtil.isNotBlank(blockId)) {
                result.childPageIds.add(blockId);
            }
            return;
        }
        if ("child_database".equals(type)) {
            if (StringUtil.isNotBlank(blockId)) {
                result.childDatabaseIds.add(blockId);
            }
            return;
        }

        appendBlockText(block, type);
        collectAttachment(block, type, blockId);

        if (block.path("has_children").asBoolean(false) && StringUtil.isNotBlank(blockId)) {
            try {
                walkChildren(blockId);
            } catch (final NotionApiException e) {
                if (!isSkippableBlockFetchError(e)) {
                    throw e;
                }
                logger.warn("Skipping the children of Notion block {} ({}): {}", blockId, type, e.getMessage());
            }
        }
    }

    /**
     * Returns whether a failure fetching one block's children is safe to skip -- warning and
     * moving on to the block's siblings -- rather than propagate and fail the whole page. Only a
     * {@code 404 object_not_found} (the block was deleted) or a {@code 403 restricted_resource}
     * (the integration lost access to it) qualifies: both are expected, benign reasons one
     * specific block might be unreachable while the rest of the page is fine. Anything else --
     * including a fatal error, and including a retryable status or transport error whose retries
     * were exhausted -- means this page's content cannot be trusted to be complete, so it must
     * propagate instead of being silently indexed with a gap.
     */
    private static boolean isSkippableBlockFetchError(final NotionApiException e) {
        if (e.isFatal()) {
            return false;
        }
        return (e.getStatus() == 404 && "object_not_found".equals(e.getCode()))
                || (e.getStatus() == 403 && "restricted_resource".equals(e.getCode()));
    }

    private void appendBlockText(final JsonNode block, final String type) {
        final JsonNode typeNode = block.path(type);
        if (RICH_TEXT_BLOCK_TYPES.contains(type)) {
            appendRichText(typeNode.path("rich_text"));
        } else if ("table_row".equals(type)) {
            final StringBuilder row = new StringBuilder();
            for (final JsonNode cell : typeNode.path("cells")) {
                final String cellText = plainText(cell);
                if (StringUtil.isNotBlank(cellText)) {
                    if (row.length() > 0) {
                        row.append(" | ");
                    }
                    row.append(cellText);
                }
            }
            if (row.length() > 0) {
                text().append(row).append('\n');
            }
        } else if ("equation".equals(type)) {
            final String expression = typeNode.path("expression").asText("");
            if (StringUtil.isNotBlank(expression)) {
                text().append(expression).append('\n');
            }
        }
        if (CAPTIONED_BLOCK_TYPES.contains(type)) {
            appendRichText(typeNode.path("caption"));
        }
    }

    private void appendRichText(final JsonNode richTextArray) {
        final String plain = plainText(richTextArray);
        if (StringUtil.isNotBlank(plain)) {
            text().append(plain).append('\n');
        }
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

    private StringBuilder text() {
        return result.text;
    }

    private void collectAttachment(final JsonNode block, final String type, final String blockId) {
        if (!"file".equals(type) && !"pdf".equals(type)) {
            return;
        }
        final JsonNode fileNode = block.path(type);
        if (!"file".equals(fileNode.path("type").asText(""))) {
            // "external" (a link Notion does not host) and "file_upload" (would need an extra
            // lookup this design does not add) are not downloaded.
            return;
        }
        final String url = fileNode.path("file").path("url").asText(null);
        if (StringUtil.isBlank(url)) {
            return;
        }
        final String name = fileNode.hasNonNull("name") ? fileNode.path("name").asText() : deriveNameFromUrl(url);
        final String lastEditedTime = block.hasNonNull("last_edited_time") ? block.path("last_edited_time").asText() : null;
        result.attachments.add(new AttachmentRef(blockId, url, name, lastEditedTime));
    }

    private String deriveNameFromUrl(final String url) {
        try {
            final String path = URI.create(url).getPath();
            if (StringUtil.isBlank(path)) {
                return "attachment";
            }
            final String last = path.substring(path.lastIndexOf('/') + 1);
            final String decoded = URLDecoder.decode(last, StandardCharsets.UTF_8);
            return StringUtil.isBlank(decoded) ? "attachment" : decoded;
        } catch (final Exception e) {
            return "attachment";
        }
    }

}
