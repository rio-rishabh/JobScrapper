import { Client } from "@notionhq/client";
import { join, dirname } from "path";
import { existsSync, readdirSync, readFileSync, statSync } from "fs";
import { fileURLToPath } from "url";

// Get the directory of the current module
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

/**
 * Loads config from a local config.json file as fallback
 */
function loadConfigFromFile(): { apiToken?: string; databaseId?: string } {
  // Try multiple possible locations for config.json
  const possiblePaths = [
    join(__dirname, "..", "..", "config.json"), // From dist: go up to project root
    join(__dirname, "..", "config.json"), // From src: go up to project root
    join(process.cwd(), "config.json"), // From project root
  ];
  
  for (const configPath of possiblePaths) {
    if (existsSync(configPath)) {
      try {
        const config = JSON.parse(readFileSync(configPath, "utf-8"));
        console.error(`[MCP Notion Sync] Loaded config from: ${configPath}`);
        return {
          apiToken: config.NOTION_API_TOKEN || config.notionApiToken,
          databaseId: config.NOTION_DATABASE_ID || config.notionDatabaseId,
        };
      } catch (e) {
        console.error(`[MCP Notion Sync] Error reading config file ${configPath}: ${e}`);
      }
    }
  }
  return {};
}

/**
 * Loads jobs from scraped_jobs directory
 */
function loadJobsFromFiles(scrapedJobsDir: string, maxFiles: number = 5): any[] {
  const jobs: any[] = [];
  if (!existsSync(scrapedJobsDir)) {
    return jobs;
  }
  const jobsFiles = readdirSync(scrapedJobsDir).filter(
    (f) => f.startsWith("jobs_") && f.endsWith(".json") && !f.includes("_interrupted")
  );
  const sortedFiles = jobsFiles
    .map((f) => ({
      name: f,
      path: join(scrapedJobsDir, f),
      mtime: statSync(join(scrapedJobsDir, f)).mtime.getTime(),
    }))
    .sort((a, b) => b.mtime - a.mtime)
    .slice(0, maxFiles);

  for (const file of sortedFiles) {
    try {
      const fileJobs = JSON.parse(readFileSync(file.path, "utf-8"));
      if (Array.isArray(fileJobs)) {
        jobs.push(...fileJobs);
      }
    } catch (e) {
      console.error(`[MCP Notion Sync] Error reading ${file.path}:`, e);
      continue;
    }
  }
  return jobs;
}

/**
 * Queries Notion database for existing job URLs
 * Returns a Set of URLs that already exist in Notion
 */
async function getExistingJobUrls(
  notion: Client,
  databaseId: string,
  apiToken: string
): Promise<Set<string>> {
  const existingUrls = new Set<string>();
  let hasMore = true;
  let startCursor: string | undefined = undefined;

  console.error(`[MCP Notion Sync] Querying Notion for existing jobs...`);

  try {
    while (hasMore) {
      // Use direct HTTP request to query database since SDK doesn't expose query method
      const response = await fetch(
        `https://api.notion.com/v1/databases/${databaseId}/query`,
        {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${apiToken}`,
            "Notion-Version": "2022-06-28",
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            start_cursor: startCursor,
            page_size: 100,
          }),
        }
      );

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(
          `Notion API error: ${response.status} ${response.statusText} - ${errorText}`
        );
      }

      const data: any = await response.json();

      // Log only summary, not every page

      // Extract URLs from each page
      for (const page of data.results || []) {
        if ("properties" in page) {
          // Try different possible property names for URL
          const urlProperty =
            page.properties.url ||
            page.properties.URL ||
            page.properties.link ||
            page.properties.Link ||
            page.properties.jobUrl ||
            page.properties.job_url;

          if (urlProperty) {
            const urlValue =
              urlProperty.url ||
              urlProperty.rich_text?.[0]?.plain_text ||
              urlProperty.title?.[0]?.plain_text;

            if (urlValue) {
              // Normalize URL (remove query params and fragments for comparison)
              try {
                const url = new URL(urlValue);
                // Remove query params and fragments, but keep pathname
                const normalizedUrl = `${url.protocol}//${url.host}${url.pathname}`;
                existingUrls.add(normalizedUrl);
                // Also add the full URL in case it's stored differently
                existingUrls.add(urlValue);
                // Add without trailing slash variations
                if (normalizedUrl.endsWith('/')) {
                  existingUrls.add(normalizedUrl.slice(0, -1));
                } else {
                  existingUrls.add(normalizedUrl + '/');
                }
              } catch (e) {
                // If URL parsing fails, just use the raw URL
                existingUrls.add(urlValue);
              }
            }
          }
        }
      }

      hasMore = data.has_more;
      startCursor = data.next_cursor || undefined;
    }

    console.error(
      `[MCP Notion Sync] Found ${existingUrls.size} existing jobs in Notion`
    );
  } catch (error) {
    const errorMessage =
      error instanceof Error ? error.message : String(error);
    console.error(
      `[MCP Notion Sync] ERROR querying Notion for existing jobs:`,
      errorMessage
    );
    console.error(
      `[MCP Notion Sync] Full error:`,
      error
    );
    // Throw error instead of silently continuing - deduplication is critical
    throw new Error(
      `Failed to query Notion for existing jobs. Deduplication cannot work without this. Error: ${errorMessage}`
    );
  }

  return existingUrls;
}

/**
 * Normalizes a job URL for comparison
 */
function normalizeJobUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const urlObj = new URL(url);
    // Remove query parameters and fragments for comparison
    return `${urlObj.protocol}//${urlObj.host}${urlObj.pathname}`;
  } catch (e) {
    return url; // Return original if parsing fails
  }
}

/**
 * Filters jobs to only include those not already in Notion
 */
function filterNewJobs(jobs: any[], existingUrls: Set<string>): {
  newJobs: any[];
  skippedJobs: any[];
} {
  const newJobs: any[] = [];
  const skippedJobs: any[] = [];

  for (const job of jobs) {
    if (!job.url) {
      // Jobs without URLs are always considered new (can't check for duplicates)
      newJobs.push(job);
      continue;
    }

    const normalizedUrl = normalizeJobUrl(job.url);
    
    // Check normalized URL, full URL, and variations
    const isDuplicate =
      (normalizedUrl && existingUrls.has(normalizedUrl)) ||
      existingUrls.has(job.url) ||
      (normalizedUrl && normalizedUrl.endsWith('/') && existingUrls.has(normalizedUrl.slice(0, -1))) ||
      (normalizedUrl && !normalizedUrl.endsWith('/') && existingUrls.has(normalizedUrl + '/'));

    if (isDuplicate) {
      skippedJobs.push(job);
      continue;
    }

    newJobs.push(job);
  }

  if (skippedJobs.length > 0) {
    console.error(
      `[MCP Notion Sync] Skipping ${skippedJobs.length} jobs that already exist in Notion`
    );
  }

  return { newJobs, skippedJobs };
}

/**
 * Syncs jobs to Notion database
 */
export async function syncJobsToNotion(args: {
  scrapedJobsDir: string;
  scrapingId?: string;
  jobIds?: string[];
  notionDatabaseId?: string;
  notionApiToken?: string;
  findJobById?: (jobId: string) => Promise<any | null>;
  getJobsByScrapingId?: (scrapingId: string) => Promise<any[]>;
}): Promise<{ content: Array<{ type: string; text: string }> }> {
  try {
    // Try to load from config file as fallback
    const configFromFile = loadConfigFromFile();
    
    const apiToken =
      args.notionApiToken ||
      process.env.NOTION_API_TOKEN ||
      configFromFile.apiToken ||
      "";
    const databaseId =
      args.notionDatabaseId ||
      process.env.NOTION_DATABASE_ID ||
      configFromFile.databaseId ||
      "";

    if (!apiToken) {
      throw new Error(
        "Notion API token is required. Set NOTION_API_TOKEN environment variable or provide notionApiToken parameter."
      );
    }
    if (!databaseId) {
      throw new Error(
        "Notion database ID is required. Set NOTION_DATABASE_ID environment variable or provide notionDatabaseId parameter."
      );
    }

    const notion = new Client({ auth: apiToken });

    // Version marker
    console.error(
      `[MCP Notion Sync] VERSION: 3.0 - With Notion deduplication`
    );

    // Verify database access
    try {
      await notion.databases.retrieve({ database_id: databaseId });
      console.error(`[MCP Notion Sync] Database connection verified`);
    } catch (dbError) {
      const dbErrorMessage =
        dbError instanceof Error ? dbError.message : String(dbError);
      console.error(
        `[MCP Notion Sync] Database retrieval error:`,
        dbErrorMessage
      );
      throw new Error(
        `Failed to access Notion database: ${dbErrorMessage}. Please verify your database ID (${databaseId}) and API token are correct.`
      );
    }

    // STEP 1: Query Notion for existing jobs
    let existingUrls: Set<string>;
    try {
      existingUrls = await getExistingJobUrls(notion, databaseId, apiToken);
      console.error(
        `[MCP Notion Sync] Successfully queried Notion: found ${existingUrls.size} existing job URLs`
      );
    } catch (queryError) {
      const errorMessage =
        queryError instanceof Error ? queryError.message : String(queryError);
      console.error(
        `[MCP Notion Sync] WARNING: Failed to query existing jobs: ${errorMessage}`
      );
      console.error(
        `[MCP Notion Sync] Continuing without deduplication - this may create duplicates!`
      );
      // Use empty set so all jobs are considered new
      existingUrls = new Set<string>();
    }

    // STEP 2: Collect jobs to sync
    let jobsToSync: any[] = [];
    if (args.jobIds && args.jobIds.length > 0) {
      // Sync specific job IDs
      for (const jobId of args.jobIds) {
        const job = args.findJobById
          ? await args.findJobById(jobId)
          : null;
        if (job) {
          jobsToSync.push(job);
        }
      }
    } else if (args.scrapingId) {
      // Sync jobs from specific scraping job
      jobsToSync = args.getJobsByScrapingId
        ? await args.getJobsByScrapingId(args.scrapingId)
        : [];
    } else {
      // Sync all jobs from recent files
      jobsToSync = loadJobsFromFiles(args.scrapedJobsDir);
    }

    if (jobsToSync.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                success: false,
                message: "No jobs found to sync",
                synced: 0,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    // STEP 3: Filter out jobs that already exist in Notion
    console.error(
      `[MCP Notion Sync] Before filtering: ${jobsToSync.length} jobs to check`
    );
    console.error(
      `[MCP Notion Sync] Existing URLs in Notion: ${existingUrls.size}`
    );
    
    const { newJobs, skippedJobs } = filterNewJobs(jobsToSync, existingUrls);
    const skippedCount = skippedJobs.length;

    console.error(
      `[MCP Notion Sync] After filtering: ${newJobs.length} new jobs, ${skippedCount} skipped (already exist)`
    );

    if (newJobs.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                success: true,
                message: `All ${jobsToSync.length} jobs already exist in Notion. No new jobs to sync.`,
                synced: 0,
                total: jobsToSync.length,
                skipped: skippedCount,
              },
              null,
              2
            ),
          },
        ],
      };
    }

    console.error(
      `[MCP Notion Sync] Syncing ${newJobs.length} new jobs (${skippedCount} already exist in Notion)`
    );

    // STEP 4: Sync new jobs to Notion
    const syncedJobs: string[] = [];
    const errors: Array<{ jobId: string; error: string }> = [];
    let additionalSkipped = 0;

    for (const job of newJobs) {
      try {
        // Convert postedDate - handle both Unix timestamp (seconds) and ISO string formats
        let postedDateStr: string | null = null;
        if (job.postedDate) {
          try {
            let date: Date;
            // Check if it's a number (Unix timestamp in seconds)
            if (typeof job.postedDate === 'number') {
              // postedDate is in seconds (e.g., 1766347643.232133)
              const dateMs = job.postedDate * 1000;
              date = new Date(dateMs);
            } else if (typeof job.postedDate === 'string') {
              // It's already a string, try to parse it
              date = new Date(job.postedDate);
            } else {
              date = new Date();
            }
            
            // Validate the date is reasonable (between 2020 and 2030)
            if (!isNaN(date.getTime()) && date.getFullYear() >= 2020 && date.getFullYear() <= 2030) {
              postedDateStr = date.toISOString().split("T")[0];
            } else {
              // If date is invalid or out of range, use today's date
              postedDateStr = new Date().toISOString().split("T")[0];
            }
          } catch (e) {
            // If conversion fails, use today's date
            postedDateStr = new Date().toISOString().split("T")[0];
          }
        }

        // Build properties - matching user's Notion database schema
        const notionProperties = {
          // title (rich_text) - Job title
          title: {
            rich_text: [{ text: { content: String(job.title || "Untitled") } }],
          },
          // company (rich_text)
          company: {
            rich_text: [
              { text: { content: String(job.company || "Unknown") } },
            ],
          },
          // location (rich_text)
          location: {
            rich_text: [
              { text: { content: String(job.location || "Not Specified") } },
            ],
          },
          // source (rich_text)
          source: {
            rich_text: [
              { text: { content: String(job.source || "Unknown") } },
            ],
          },
          // url (url)
          url: {
            url: job.url || null,
          },
          // postedDate (date)
          postedDate: {
            date: postedDateStr ? { start: postedDateStr } : null,
          },
          // description (rich_text) - truncated for Notion's 2000 char limit
          description: {
            rich_text: [
              {
                text: {
                  content: String((job.description || "").substring(0, 2000)),
                },
              },
            ],
          },
        };

        console.error(
          `[MCP Notion Sync] Syncing job: ${job.title} from ${job.company}`
        );

        // Create page in Notion
        await notion.pages.create({
          parent: {
            database_id: databaseId,
          },
          properties: notionProperties,
        });

        syncedJobs.push(job.id);
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : String(error);
        console.error(
          `[MCP Notion Sync] Error syncing job ${job.id}:`,
          errorMessage
        );

        // Check if error is due to duplicate (Notion sometimes returns this)
        if (
          errorMessage.includes("duplicate") ||
          errorMessage.includes("already exists") ||
          errorMessage.includes("conflict")
        ) {
          console.error(
            `[MCP Notion Sync] Job ${job.id} already exists in Notion (caught during sync)`
          );
          additionalSkipped++; // Increment skipped count
        } else {
          errors.push({
            jobId: job.id || "unknown",
            error: errorMessage,
          });
        }
      }
    }

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              success: syncedJobs.length > 0,
              message: `Synced ${syncedJobs.length} new jobs to Notion (${skippedCount + additionalSkipped} already existed)`,
              synced: syncedJobs.length,
              total: jobsToSync.length,
              skipped: skippedCount + additionalSkipped,
              syncedJobIds: syncedJobs,
              errors: errors.length > 0 ? errors : undefined,
            },
            null,
            2
          ),
        },
      ],
    };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              success: false,
              error: errorMessage,
            },
            null,
            2
          ),
        },
      ],
    };
  }
}

