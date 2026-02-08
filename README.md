# JobScrapper

Enterprise-grade job scraping application that collects job listings from multiple job boards, supports MCP (Model Context Protocol) for AI assistant integration, and syncs results to Notion with deduplication.

## Overview

JobScrapper consists of:

- **Java scrapers** – Playwright-based browser automation to scrape LinkedIn, Indeed, Glassdoor, and ZipRecruiter
- **OpenAPI contract** – `models/schemas/job.yaml` defines the API; Java models are generated from it
- **MCP server** (Node.js/TypeScript) – Exposes scraping, job listing, resume validation, and Notion sync as MCP tools for Cursor, Claude Desktop, and other MCP clients
- **Notion integration** – Query existing job URLs for deduplication and sync new jobs to a Notion database

Scraping can run via **direct Java** (Gradle task `runMcpScraper`) or, when running, via the **REST API**. The MCP server prefers direct Java by default so it works without a separate API server.

---

## Features

- **Multi-source scraping**: LinkedIn, Indeed, Glassdoor, ZipRecruiter (extensible)
- **Parallel scraping**: Multiple platforms in separate threads/browser contexts
- **Deduplication**: Skips jobs already in Notion or in local `scraped_jobs/` cache
- **Notion sync**: Sync scraped jobs to a Notion database; only new jobs are added
- **Resume validation**: Compare a resume (text or `.docx`) to a job description; get match score, found/missing keywords, and recommendations
- **MCP tools**: Start/stop scraping, list jobs, get job by ID, check status, validate resume, sync to Notion — all from Cursor or Claude
- **Graceful shutdown**: On Ctrl+C, saves in-progress jobs to `*_interrupted.json`
- **Infinite mode**: Optional repeated scrape cycles with configurable wait between runs

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Build | Gradle 7.x |
| Language | Java 21 |
| Browser automation | Playwright (Java) |
| HTTP (Notion, etc.) | Java 11+ HttpClient, Apache HTTP Client |
| API spec | OpenAPI 3.0.3 (YAML) |
| Codegen | OpenAPI Generator (Java from YAML) |
| MCP server | Node.js 18+, TypeScript, `@modelcontextprotocol/sdk` |
| Notion | Notion API (query + create pages) |
| Resume (DOCX) | mammoth (MCP server) |

---

## Project Structure

```
JobScrapper/
├── config.json                    # Local config (NOTION_*, LINKEDIN_*); do not commit
├── build.gradle                   # Java build, OpenAPI generate, runMcpScraper task
├── models/
│   └── schemas/
│       └── job.yaml               # OpenAPI spec and Job/ScrapingJob schemas
├── src/
│   ├── main/java/com/jobScrapper/
│   │   ├── repository/            # In-memory job and scraping job repositories
│   │   ├── scraper/
│   │   │   ├── BaseJobScraper.java
│   │   │   ├── JobScraper.java
│   │   │   ├── ScraperManager.java
│   │   │   └── impl/
│   │   │       ├── LinkedInScraper.java
│   │   │       ├── IndeedScraper.java
│   │   │       ├── GlassDoorScraper.java
│   │   │       └── ZipRecruiterScraper.java
│   │   ├── service/               # Scraping service (API layer)
│   │   ├── util/
│   │   │   ├── JobStorage.java    # Save to scraped_jobs/, dedup cache
│   │   │   └── NotionClient.java   # Query Notion for existing URLs
│   │   └── test/
│   │       ├── ScraperMain.java   # Main entry: CLI + MCP input file
│   │       └── LinkedInScraperMain.java
│   └── test/java/                 # JUnit tests
├── scraped_jobs/                  # Output: jobs_YYYY-MM-dd_HH-mm-ss[_.*].json (gitignored)
├── mcp_jobs_storage/              # MCP temp: input_*.json, status_*.json (gitignored)
├── mcp-server/                    # MCP server (Node/TS)
│   ├── src/
│   │   ├── index.ts               # MCP server + all tool handlers
│   │   ├── syncJobs.ts            # Notion sync + dedup logic
│   │   ├── syncNewJobs.ts         # Standalone script: sync new jobs only
│   │   └── filterNewJobs.ts
│   ├── package.json
│   ├── cursor-mcp-config.json.example
│   └── README.md                  # MCP-specific setup
├── linkedin_profile/              # Optional Playwright user data (e.g. login state)
├── validate_resume*.js            # Optional standalone resume validation scripts
└── README.md                      # This file
```

---

## Prerequisites

- **Java 21+**
- **Gradle** (or use `./gradlew`)
- **Node.js 18+** (for MCP server and OpenAPI Generator CLI)
- **Playwright browsers** (installed by Java Playwright on first run)

---

## Setup

### 1. Clone and build Java

```bash
cd JobScrapper
./gradlew clean build
```

This compiles the project and generates Java models from `models/schemas/job.yaml` (if OpenAPI Generator is configured).

### 2. Configuration (`config.json`)

Create `config.json` in the project root. **Do not commit this file** (add to `.gitignore` if needed).

Example (replace with your values):

```json
{
  "NOTION_DATABASE_ID": "your-notion-database-id",
  "NOTION_API_TOKEN": "your-notion-api-token",
  "LINKEDIN_EMAIL": "optional-for-login",
  "LINKEDIN_PASSWORD": "optional-for-login",
  "LINKEDIN_LI_AT_COOKIE": "optional-linkedin-session-cookie"
}
```

- **Notion**: Required for Notion sync and for pre-scrape deduplication (MCP and Java both query Notion for existing job URLs).
- **LinkedIn**: Optional; cookie or login can improve LinkedIn scraping (see `QUICK_START_COOKIE_AUTH.md` / `COOKIE_AUTHENTICATION_GUIDE.md` in the repo).

### 3. MCP server (for Cursor / Claude)

```bash
cd mcp-server
npm install
npm run build
```

Configure Cursor (or Claude Desktop) to use the MCP server. Example for Cursor (`~/.cursor/mcp.json` or project MCP config):

```json
{
  "mcpServers": {
    "jobscrapper": {
      "command": "node",
      "args": ["/absolute/path/to/JobScrapper/mcp-server/dist/index.js"],
      "env": {
        "JOBSCRAPPER_JAVA_PATH": "/absolute/path/to/JobScrapper",
        "JOBSCRAPPER_API_URL": "http://localhost:8080/api",
        "JOBSCRAPPER_USE_DIRECT_JAVA": "true",
        "JOBSCRAPPER_DEFAULT_RESUME": "/path/to/your/resume.docx"
      }
    }
  }
}
```

- **JOBSCRAPPER_JAVA_PATH**: Project root (for `scraped_jobs/`, `config.json`, and Gradle).
- **JOBSCRAPPER_USE_DIRECT_JAVA**: `true` (default) runs scraping via `./gradlew runMcpScraper`; `false` uses REST API only.
- **JOBSCRAPPER_API_URL**: Used when not using direct Java (e.g. when a separate API server is running).
- **JOBSCRAPPER_DEFAULT_RESUME**: Default resume path for the `validate_resume` tool.

See `mcp-server/README.md` and `mcp-server/cursor-mcp-config.json.example` for more detail.

---

## Running the scraper

### From command line (no MCP)

```bash
./gradlew run
```

Uses default request in `ScraperMain` (sources, keywords, location, `maxResults`). Override `maxResults`:

```bash
./gradlew run --args '100'
```

### Via MCP (recommended)

Trigger scraping from Cursor/Claude using the **start_scraping_job** tool with:

- `sources`: e.g. `["LinkedIn", "Indeed", "Ziprecruiter"]`
- `keywords`: e.g. `["software engineer", "java"]`
- `location`: e.g. `"Boston, MA"`
- `maxResults`: e.g. `25` (per source)

The MCP server will:

1. Optionally query your Notion database for existing job URLs.
2. Pass those URLs (and the rest of the request) to the Java scraper via a temp JSON file.
3. Run `./gradlew runMcpScraper -PmcpInput=<path>` so the scraper skips jobs already in Notion (and local cache).

### MCP mode in Java

When `-PmcpInput` is set, `ScraperMain` reads the request from the JSON file (including `notionUrls` if provided). It still runs the same parallel scraping and infinite loop; after each cycle it prints a JSON block for the MCP server to parse.

---

## Output and storage

- **Scraped jobs**: Saved under `scraped_jobs/`:
  - `jobs_YYYY-MM-dd_HH-mm-ss.json` – all jobs from that run
  - `jobs_YYYY-MM-dd_HH-mm-ss_<source>.json` – per-source files
  - `*_interrupted.json` – saved on Ctrl+C before exit
- **MCP temp files**: `mcp_jobs_storage/` – `input_*.json` (request for Java), `status_<scrapingId>.json` (status for get_scraping_status).

---

## MCP tools

| Tool | Description |
|------|-------------|
| **start_scraping_job** | Start a scraping job (sources, keywords, location, maxResults). Uses Notion URLs for dedup when configured. |
| **get_jobs** | Paginated list of jobs (from `scraped_jobs/` or API). Optional `scrapingId` filter. |
| **get_job_by_id** | Get one job by ID. |
| **get_scraping_status** | Status and counts for a scraping job (from status file or API). |
| **stop_scraping_job** | Stop a running job (API only; direct Java runs until process ends). |
| **validate_resume** | Compare resume (text or file, e.g. `.docx`) to a job (by `jobId` or raw `jobDescription`). Returns match score, keywords, recommendations. |
| **sync_jobs_to_notion** | Sync jobs to Notion DB. Optional `scrapingId` or `jobIds`. Filters out jobs already in Notion. |

---

## Notion integration

- **Deduplication**: Before/during scrape, the app (Java and/or MCP) queries the Notion database for existing job URLs and skips those when saving/scraping.
- **Sync**: `sync_jobs_to_notion` loads jobs from `scraped_jobs/` (or by scraping ID / job IDs), filters by existing Notion URLs, then creates only new pages. Notion property names (e.g. `title`, `company`, `location`, `url`, `postedDate`, `description`, `source`) are set in `syncJobs.ts`.

Ensure your Notion database has compatible properties (e.g. URL, rich text, date). Required env or config: `NOTION_DATABASE_ID`, `NOTION_API_TOKEN`.

---

## API (OpenAPI)

When running a REST API (e.g. future Spring Boot app), the contract is:

- `POST /api/scraping/start` – Start scraping
- `GET /api/jobs` – List jobs (query: `scrapingId`, `page`, `limit`)
- `GET /api/jobs/{id}` – Get job by ID
- `DELETE /api/scraping/{id}` – Stop scraping job

Current `application mainClass` is `ScraperMain`; no HTTP server is started by default. The MCP server can still work by calling the Java scraper directly via `runMcpScraper`.

---

## Generate OpenAPI models

If you add or change `models/schemas/*.yaml`:

```bash
./gradlew generateAllOpenApiModels
```

Generated Java lives under `build/generated/openapi/` and is included in the main source set.

---

## Standalone scripts

- **Sync new jobs to Notion only** (no MCP):
  ```bash
  cd mcp-server && npm run sync-new-jobs
  ```
- **Resume validation** (optional): `validate_resume.js` / `validate_resume_job.js` / `validate_resume_enhanced.js` in the project root can be run with Node if you use them locally.

---

## Security and best practices

- **Never commit** `config.json` (Notion token, LinkedIn cookie, etc.). Use a local or env-only config.
- Prefer **cookie-based LinkedIn auth** over storing password in config (see `QUICK_START_COOKIE_AUTH.md`).
- Keep **Notion API token** and **database ID** in env or a non-committed config file.

---

## Troubleshooting

- **No jobs returned**: Ensure at least one scrape has run and `scraped_jobs/` (or API) has data; check `get_jobs` without `scrapingId` first.
- **Notion sync duplicates**: Ensure `NOTION_DATABASE_ID` and `NOTION_API_TOKEN` are correct and the Notion DB has a URL property; the sync filters by existing URLs.
- **LinkedIn blocks / login**: Use a persistent browser profile (e.g. `linkedin_profile/`) and/or valid `LINKEDIN_LI_AT_COOKIE`; see the cookie auth guides in the repo.
- **MCP “Scraping job started” but no jobs**: Scraping runs in the background; use **get_scraping_status** and **get_jobs** after a few minutes. Check Java/Gradle logs for errors.
- **Resume validation “Job not found”**: Ensure the job ID exists in `scraped_jobs/` or from **get_jobs** / **get_job_by_id**.

---

## License

[Add your license here]
