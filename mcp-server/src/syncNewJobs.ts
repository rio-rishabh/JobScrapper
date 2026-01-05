#!/usr/bin/env node

/**
 * Standalone script to sync only new jobs to Notion
 * This script filters out jobs that already exist in Notion before syncing
 */

import { syncJobsToNotion } from "./syncJobs.js";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

// Get the directory of the current module
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Configuration
const JAVA_PROJECT_PATH = process.env.JOBSCRAPPER_JAVA_PATH || join(__dirname, "..", "..");
const SCRAPED_JOBS_DIR = join(JAVA_PROJECT_PATH, "scraped_jobs");

// Helper to find job by ID (for when specific jobIds are provided)
async function findJobById(jobId: string): Promise<any | null> {
  const { readdirSync, readFileSync, existsSync, statSync } = await import("fs");
  
  if (!existsSync(SCRAPED_JOBS_DIR)) {
    return null;
  }
  
  const jobsFiles = readdirSync(SCRAPED_JOBS_DIR).filter((f: string) =>
    f.startsWith("jobs_") && f.endsWith(".json") && !f.includes("_interrupted")
  );

  for (const file of jobsFiles) {
    try {
      const jobs = JSON.parse(
        readFileSync(join(SCRAPED_JOBS_DIR, file), "utf-8")
      );
      if (Array.isArray(jobs)) {
        const job = jobs.find((j: any) => j.id === jobId);
        if (job) return job;
      }
    } catch (e) {
      continue;
    }
  }
  return null;
}

// Helper to get jobs by scraping ID
async function getJobsByScrapingId(scrapingId: string): Promise<any[]> {
  // For simplicity, we'll just return all jobs from recent files
  // The filtering by scrapingId would need to be implemented based on how scraping IDs are stored
  const { readdirSync, readFileSync, existsSync, statSync } = await import("fs");
  const allJobs: any[] = [];
  
  if (!existsSync(SCRAPED_JOBS_DIR)) {
    return allJobs;
  }
  
  const files = readdirSync(SCRAPED_JOBS_DIR).filter((f: string) =>
    f.startsWith("jobs_") && f.endsWith(".json") && !f.includes("_interrupted")
  );
  
  const sortedFiles = files
    .map((f) => ({
      name: f,
      path: join(SCRAPED_JOBS_DIR, f),
      mtime: statSync(join(SCRAPED_JOBS_DIR, f)).mtime.getTime(),
    }))
    .sort((a, b) => b.mtime - a.mtime)
    .slice(0, 10); // Check last 10 files
  
  for (const file of sortedFiles) {
    try {
      const jobs = JSON.parse(readFileSync(file.path, "utf-8"));
      if (Array.isArray(jobs)) {
        allJobs.push(...jobs);
      }
    } catch (e) {
      continue;
    }
  }
  
  return allJobs;
}

async function main() {
  // Parse command line arguments
  const args = process.argv.slice(2);
  let scrapingId: string | undefined;
  let jobIds: string[] | undefined;
  
  // Simple argument parsing
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--scraping-id" && i + 1 < args.length) {
      scrapingId = args[i + 1];
      i++;
    } else if (args[i] === "--job-ids" && i + 1 < args.length) {
      jobIds = args[i + 1].split(",").map((id) => id.trim());
      i++;
    }
  }

  console.error("🚀 Starting sync of new jobs to Notion...");
  console.error(`📁 Scraped jobs directory: ${SCRAPED_JOBS_DIR}`);

  try {
    const result = await syncJobsToNotion({
      scrapedJobsDir: SCRAPED_JOBS_DIR,
      scrapingId,
      jobIds,
      findJobById,
      getJobsByScrapingId,
    });

    // Parse and display the result
    const resultText = result.content[0]?.text || "{}";
    const resultData = JSON.parse(resultText);

    if (resultData.success) {
      console.log("\n✅ Sync completed successfully!");
      console.log(`📊 Results:`);
      console.log(`   - New jobs synced: ${resultData.synced || 0}`);
      console.log(`   - Jobs skipped (already exist): ${resultData.skipped || 0}`);
      console.log(`   - Total jobs checked: ${resultData.total || 0}`);
      
      if (resultData.message) {
        console.log(`\n💬 ${resultData.message}`);
      }
      
      if (resultData.errors && resultData.errors.length > 0) {
        console.error(`\n⚠️  Errors encountered:`);
        resultData.errors.forEach((error: any) => {
          console.error(`   - Job ${error.jobId}: ${error.error}`);
        });
      }
    } else {
      console.error("\n❌ Sync failed!");
      console.error(`Error: ${resultData.error || resultData.message || "Unknown error"}`);
      process.exit(1);
    }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error("\n❌ Fatal error during sync:");
    console.error(errorMessage);
    process.exit(1);
  }
}

// Run the script
main().catch((error) => {
  console.error("Unhandled error:", error);
  process.exit(1);
});



