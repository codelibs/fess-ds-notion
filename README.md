Notion Data Store for Fess
[![Java CI with Maven](https://github.com/codelibs/fess-ds-notion/actions/workflows/maven.yml/badge.svg)](https://github.com/codelibs/fess-ds-notion/actions/workflows/maven.yml)
==========================

> [!NOTE]
> **Experimental.** This data store debuts in Fess 15.9 as an experimental release. It has not yet
> been tested across a wide range of environments, so parameters and behavior may change in
> upcoming releases. Feedback from real-world use will drive its improvements - please share your
> experience in [GitHub Issues](https://github.com/codelibs/fess-ds-notion/issues).

## Overview

Notion Data Store is an extension for Fess Data Store Crawling. It authenticates as a Notion
internal integration and indexes everything that integration has been shared with: pages,
database rows (with their property values), Notion-hosted file/PDF block attachments, and
unresolved page comments.

## Download

See [Maven Repository](https://maven.codelibs.org/release/org/codelibs/fess/fess-ds-notion/).

## Requirements

- Fess 15.9 or later
- Java 21 or later

## Installation

1. Download fess-ds-notion-X.X.X.jar
2. Copy fess-ds-notion-X.X.X.jar to $FESS\_HOME/app/WEB-INF/plugin or /usr/share/fess/app/WEB-INF/plugin, then restart Fess (or install it from the admin System > Plugin page)

## Getting Started

### Parameters
Example:
```
token=ntn_************************************
```

| Key | Default | Value |
| --- | --- | --- |
| `token` | *(required)* | The internal integration secret ("Internal Integration Secret" on the integration's Configuration page). Never copied into the script evaluation context or logged. |
| `root_page_ids` | *(empty)* | Comma-separated page IDs (not URLs) to crawl from. Leave both this and `database_ids` empty to discover every page and database shared with the integration via search instead. |
| `database_ids` | *(empty)* | Comma-separated database IDs (not URLs) to crawl from. Each database is resolved to its data source(s), and every row of each is indexed. |
| `include_comments` | `true` | `true` or `false`. Fetches each page's unresolved comments and folds their text into `notion.content`/`notion.comments`. Requires the integration's "Read comments" capability; see below. |
| `include_files` | `true` | `true` or `false`. Downloads and extracts Notion-hosted `file`/`pdf` block attachments, indexing each as its own document. |
| `max_file_size` | `10485760` (10MB) | An attachment larger than this many bytes is skipped entirely (not indexed, not even with empty content). |
| `include_pattern` | *(empty)* | Regular expression a document's `notion.url` must match to be indexed. Applies per document, not per page subtree -- see below. |
| `exclude_pattern` | *(empty)* | Regular expression a document's `notion.url` must not match. Applies per document, not per page subtree -- see below. |
| `request_interval` | `350` | Milliseconds to sleep before each Notion API request, to stay under Notion's rate limit (about 3 requests/second on non-Business plans). |
| `max_retry_count` | `5` | Maximum number of retries for a request that receives a `429` (rate limited), `5xx`, or `529` response, or a transport-level failure (a connection timeout, a reset, or any other I/O error before a response was received at all). Exhausting the retries fails that one page/database/attachment -- it does not abort the crawl; see **Error handling** below. |
| `connection_timeout` | `10000` | Connection timeout, in milliseconds, for each Notion API request. Must be positive; a zero or negative value falls back to the default. |
| `read_timeout` | `30000` | Read timeout, in milliseconds, for each Notion API request. Must be positive; a zero or negative value falls back to the default. |
| `ignore_error` | `true` | `true` or `false`. When `true`, an *extraction* failure on an attachment this data store did successfully download indexes that attachment with empty content instead of failing the document. Does not cover a download failure (a non-200 response, or any I/O error) -- that always fails the document, regardless of this setting; see **Error handling**. |

A blank or non-numeric value for any numeric parameter above falls back to its default, with a
warning logged; so does a negative `max_retry_count`/`request_interval` (`0` is valid for both:
"never retry" and "no pacing", respectively) and a `true`/`false` parameter set to anything other
than those two values (case-insensitive).

The crawl is single-threaded and every run is a full crawl: `number_of_threads`, `readInterval` and
`script_type` are not used, and there is no incremental crawl. Requests go straight to
`api.notion.com`; HTTP proxies are not supported.

### Crawl scope and discovery

- With neither `root_page_ids` nor `database_ids` set, this data store calls Notion's search
  endpoint to discover every page and database shared with the integration. Notion documents this
  endpoint as not guaranteed to be exhaustive; if some content is missing from the index, set
  `root_page_ids`/`database_ids` explicitly instead.
- From each root, every `child_page` and `child_database` block reachable from it is crawled too,
  so a single root page is usually enough to reach an entire workspace section.
- **`include_pattern`/`exclude_pattern` decide only whether one page's or attachment's own
  document is indexed -- never whether its descendants are discovered.** A page's blocks are
  always walked (to find `child_page`/`child_database` references) before the pattern is checked
  against that page's own URL, so an excluded page's children are still crawled and indexed under
  their own URLs. The same holds when a page fails outright for some other reason: its
  already-discovered children are not lost along with it. If you want an entire subtree excluded,
  the pattern must match every URL under it, not just its root.
- A page or database row that is in Notion's trash is skipped.
- A database row's properties beyond 25 `relation`/`people` references are completed via an extra
  API call per property; this only happens for a property that actually needs it. A property whose
  embedded value is exactly 25 references also triggers that call, since Notion is not documented
  to guarantee `has_more` is set on that exact boundary.

### Error handling

- **A crawl aborts only on HTTP `401` (the token cannot authenticate) or a `429` whose body names
  `public_api_request_blocked`** (Notion's rate limiter blocking this integration outright). Both
  mean every further request this crawl makes would fail the same way, so continuing cannot help.
- **Aborting is not free.** Fess's `DataIndexHelper` deletes every document of this crawl's data
  config that was not re-indexed in the current session whenever the crawl throws -- not merely
  "an incomplete index," but the removal of previously-indexed content that simply was not reached
  again before the failure. A revoked token partway through a large crawl therefore does not just
  stop indexing; it deletes everything not already re-crawled in that same run. Rotate a token
  between crawls with care, and treat a crawl abort in the log as significant.
- **Everything else is a per-document failure, not an abort.** A `404`/`object_not_found` or
  `403`/`restricted_resource` on one page, database, comment thread, or nested block is logged and
  skipped, the crawl continuing with the rest of the queue. So is exhausting `max_retry_count` on a
  `429`/`5xx`/`529` status or a transport-level failure -- this data store cannot tell a transient
  outage from a permanent one, so it does not guess by aborting the whole crawl over it. Any other
  error fetching a page's own blocks (for example a `400`) still fails that whole page, rather than
  indexing it with silently missing content.
- Every per-document failure is recorded under **Crawler > Failure URL**, identified by the page or
  data source id, or by `<page url>#<block id>` for an attachment. A Notion-hosted attachment's signed download URL is never used
  for this, and never appears in a log line either -- only the document identity above does.
- One case beyond `401`/blocked-`429` also aborts: `POST /v1/search` failing while neither
  `root_page_ids` nor `database_ids` is configured. With no configured roots, a broken search
  leaves nothing to crawl from at all, unlike one configured `database_ids` entry failing (which
  is instead recorded and skipped, like any other database resolution failure).

### Scripts
Example:
```
title=notion.title
content=notion.content
url=notion.url
mimetype=notion.mimetype
last_modified=notion.last_edited_time
created=notion.created_time
```

Every document -- a page, a database row, or a file/PDF attachment -- is exposed to crawl scripts
under a single `notion` root object.

| Key | Value |
| --- | --- |
| `notion.type` | `page` for a normal page or database row, `file` for an attachment. |
| `notion.id` | The page id, or the block id for an attachment. |
| `notion.title` | The page's title (the property whose type is `title`, whatever its name), or the attachment's file name. |
| `notion.content` | For a page: its non-title property values (database rows only), its block text, and its comment text, joined together. For an attachment: its extracted text. |
| `notion.url` | The page's Notion URL, or, for an attachment, the containing page's URL with `#<block id>` appended. |
| `notion.mimetype` | `text/html` for a page; the attachment's detected MIME type for a file. |
| `notion.created_time` | The page's creation timestamp. Not present on an attachment document. |
| `notion.last_edited_time` | The page's (or the attachment block's) last-edited timestamp. |
| `notion.created_by` | The page's creator: their name if Notion returned one, otherwise their user id (for these two fields Notion normally returns only the id). Not present on an attachment document. |
| `notion.last_edited_by` | The last editor, formatted the same way. Not present on an attachment document. |
| `notion.parent_id` | The id of the page's parent page, database, data source, or block; empty for a page parented directly on the workspace. Not present on an attachment document. |
| `notion.properties` | A map of database column name to formatted text, for a database row. Empty for a normal page. Not present on an attachment document. |
| `notion.comments` | The page's comment text, joined together. Empty when `include_comments=false` or the page has none. Not present on an attachment document. |
| `notion.page_url` | The containing page's URL. Only present on an attachment document. |
| `notion.page_title` | The containing page's title. Only present on an attachment document. |
| `notion.size` | The attachment's size in bytes, when Notion's response reported one. Only present on an attachment document. |

### Permissions

Notion's API exposes no way to read a page's sharing/permissions, so this data store cannot
synchronize per-page access control. Every document is indexed with only the permissions
configured on the crawl's **Permissions** field in the admin UI (the data config's own
`role`/`user`/`group` settings), applied uniformly.

**This means everything shared with the integration becomes searchable by whatever roles that
field grants**, regardless of who can actually see it in Notion. Scope the integration's own
sharing carefully -- give it access only to the pages and databases that should be searchable by
those roles -- rather than relying on this data store to narrow it down afterward.

### Required integration capabilities

Configure these on the integration's Configuration page before crawling:

| Capability | Required | Used for |
| --- | --- | --- |
| Read content | Always | Reading pages, blocks, databases, and data source rows. Without it, Notion answers `403 restricted_resource`; every page is then skipped individually and nothing is indexed. |
| Read comments | Only if `include_comments=true` (the default) | Fetching page comments. Missing this capability answers `403`; this data store logs one warning and disables comment crawling for the rest of that crawl rather than failing it. |
| Read user information | Optional | Resolving a user id in `people` properties to a display name. Without it, Notion still returns the user id, and this data store falls back to indexing the id instead of a name. |

The integration must also be explicitly shared with (added to) every page or database it should
crawl -- adding a capability alone does not grant access to any content.
