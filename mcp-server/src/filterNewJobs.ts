import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from "fs";
import { join } from "path";

/**
 * Queries Notion to get all existing job URLs
 */
async function getExistingJobUrls(
  databaseId: string,
  apiToken: string
): Promise<Set<string>> {
  const existingUrls = new Set<string>();
  let hasMore = true;
  let startCursor: string | undefined = undefined;

  console.error(`[Filter] Querying Notion for existing jobs...`);

  try {
    while (hasMore) {
      const response = await fetch(
        `https://api.notion.com/v1/databases/${databaseId}/query`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${apiToken}`,
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

      for (const page of data.results || []) {
        if ("properties" in page) {
          const urlProperty =
            page.properties.url ||
            page.properties.URL ||
            page.properties.link ||
            page.properties.Link;

          if (urlProperty) {
            const urlValue =
              urlProperty.url ||
              urlProperty.rich_text?.[0]?.plain_text ||
              urlProperty.title?.[0]?.plain_text;

            if (urlValue) {
              try {
                const url = new URL(urlValue);
                const normalizedUrl = `${url.protocol}//${url.host}${url.pathname}`;
                existingUrls.add(normalizedUrl);
                existingUrls.add(urlValue);
                if (normalizedUrl.endsWith("/")) {
                  existingUrls.add(normalizedUrl.slice(0, -1));
                } else {
                  existingUrls.add(normalizedUrl + "/");
                }
              } catch (e) {
                existingUrls.add(urlValue);
              }
            }
          }
        }
      }

      hasMore = data.has_more;
      startCursor = data.next_cursor || undefined;
    }

    console.error(`[Filter] Found ${existingUrls.size} existing jobs in Notion`);
  } catch (error) {
    const errorMessage =
      error instanceof Error ? error.message : String(error);
    console.error(`[Filter] ERROR querying Notion:`, errorMessage);
    throw error;
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
    return `${urlObj.protocol}//${urlObj.host}${urlObj.pathname}`;
  } catch (e) {
    return url;
  }
}

/**
 * Filters jobs to only include those not in Notion
 */
function filterNewJobs(jobs: any[], existingUrls: Set<string>): any[] {
  const newJobs: any[] = [];

  for (const job of jobs) {
    if (!job.url) {
      // Jobs without URLs are considered new
      newJobs.push(job);
      continue;
    }

    const normalizedUrl = normalizeJobUrl(job.url);
    const isDuplicate =
      (normalizedUrl && existingUrls.has(normalizedUrl)) ||
      existingUrls.has(job.url) ||
      (normalizedUrl &&
        normalizedUrl.endsWith("/") &&
        existingUrls.has(normalizedUrl.slice(0, -1))) ||
      (normalizedUrl &&
        !normalizedUrl.endsWith("/") &&
        existingUrls.has(normalizedUrl + "/"));

    if (!isDuplicate) {
      newJobs.push(job);
    }
  }

  return newJobs;
}

/**
 * Main function to filter scraped jobs
 */
export async function filterScrapedJobs(
  scrapedJobsDir: string,
  databaseId: string,
  apiToken: string
): Promise<{ newJobs: any[]; totalJobs: number; filteredJobs: number }> {
  // Get existing URLs from Notion
  const existingUrls = await getExistingJobUrls(databaseId, apiToken);

  // Find the most recent jobs file
  if (!existsSync(scrapedJobsDir)) {
    throw new Error(`Scraped jobs directory not found: ${scrapedJobsDir}`);
  }

  const jobsFiles = readdirSync(scrapedJobsDir).filter(
    (f) => f.startsWith("jobs_") && f.endsWith(".json") && !f.includes("_interrupted")
  );

  if (jobsFiles.length === 0) {
    throw new Error("No jobs files found");
  }

  const sortedFiles = jobsFiles
    .map((f) => ({
      name: f,
      path: join(scrapedJobsDir, f),
      mtime: statSync(join(scrapedJobsDir, f)).mtime.getTime(),
    }))
    .sort((a, b) => b.mtime - a.mtime);

  const latestFile = sortedFiles[0];
  console.error(`[Filter] Reading jobs from: ${latestFile.name}`);

  // Load jobs from the latest file
  const allJobs = JSON.parse(readFileSync(latestFile.path, "utf-8"));
  const totalJobs = Array.isArray(allJobs) ? allJobs.length : 0;

  // Filter to only new jobs
  const newJobs = filterNewJobs(allJobs, existingUrls);
  const filteredJobs = totalJobs - newJobs.length;

  console.error(
    `[Filter] Filtered ${totalJobs} jobs: ${newJobs.length} new, ${filteredJobs} already in Notion`
  );

  // Save filtered jobs to a new file
  if (newJobs.length > 0) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, -5);
    const outputFile = join(scrapedJobsDir, `jobs_${timestamp}_new_only.json`);
    writeFileSync(outputFile, JSON.stringify(newJobs, null, 2));
    console.error(`[Filter] Saved ${newJobs.length} new jobs to: ${outputFile}`);
  }

  return {
    newJobs,
    totalJobs,
    filteredJobs,
  };
}




