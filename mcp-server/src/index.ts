#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { spawn } from "child_process";
import { join } from "path";
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, statSync } from "fs";
import mammoth from "mammoth";
import { syncJobsToNotion } from "./syncJobs.js";

// Configuration
const JAVA_PROJECT_PATH = process.env.JOBSCRAPPER_JAVA_PATH || "/Users/rishabhsharma/Downloads/JobScrapper";
const USE_DIRECT_JAVA = process.env.JOBSCRAPPER_USE_DIRECT_JAVA !== "false"; // Default to true
const API_BASE_URL = process.env.JOBSCRAPPER_API_URL || "http://localhost:8080/api";
const SCRAPED_JOBS_DIR = join(JAVA_PROJECT_PATH, "scraped_jobs"); // Same directory as ScraperMain uses
const JOBS_STORAGE_DIR = join(JAVA_PROJECT_PATH, "mcp_jobs_storage"); // For temp files only
const DEFAULT_RESUME_PATH = process.env.JOBSCRAPPER_DEFAULT_RESUME || "/Users/rishabhsharma/Downloads/Resume/Resume(WebScrapping)-Dec2025.docx";

interface ScrapingJobRequest {
  sources: string[];
  keywords: string[];
  location?: string;
  maxResults?: number;
  notionUrls?: string[]; // Array of URLs from Notion to skip (loaded in memory)
}

interface ScrapingJob {
  scrapingId: string;
  status: string;
  startedAt?: string;
}

interface Job {
  id: string;
  title: string;
  company: string;
  url: string;
  postedDate: string;
  source: string;
  location: string;
  description: string;
}

interface JobListResponse {
  jobs: Job[];
  total: number;
  page: number;
  limit: number;
  totalPages?: number;
}

interface ScrapingStatus {
  scrapingId: string;
  status: string;
  jobsFound: number;
  jobsProcessed?: number;
  currentSource?: string;
  startedAt?: string;
  completedAt?: string;
  error?: string;
}

class JobScrapperMCPServer {
  private server: Server;

  constructor() {
    this.server = new Server(
      {
        name: "jobscrapper-mcp-server",
        version: "1.0.0",
      },
      {
        capabilities: {
          tools: {},
        },
      }
    );

    this.setupToolHandlers();
    this.setupErrorHandling();
  }

  private setupErrorHandling(): void {
    this.server.onerror = (error) => {
      console.error("[MCP Error]", error);
    };

    process.on("SIGINT", async () => {
      await this.server.close();
      process.exit(0);
    });
  }

  private setupToolHandlers(): void {
    this.server.setRequestHandler(ListToolsRequestSchema, async () => ({
      tools: [
        {
          name: "start_scraping_job",
          description:
            "Start a new job scraping job. Scrapes job listings from specified sources (LinkedIn, Indeed, Glassdoor, etc.) based on keywords and location.",
          inputSchema: {
            type: "object",
            properties: {
              sources: {
                type: "array",
                items: {
                  type: "string",
                  enum: [
                    "LinkedIn",
                    "Indeed",
                    "Glassdoor",
                    "Jobright",
                    "Ziprecruiter",
                    "monster",
                    "Dice",
                    "careerBuilder",
                  ],
                },
                description:
                  "List of job board sources to scrape from. Options: LinkedIn, Indeed, Glassdoor, Jobright, Ziprecruiter, monster, Dice, careerBuilder",
              },
              keywords: {
                type: "array",
                items: {
                  type: "string",
                },
                description:
                  "Keywords to search for (e.g., ['software engineer', 'java developer'])",
              },
              location: {
                type: "string",
                description:
                  "Location for job search (e.g., 'Boston, MA' or 'Remote')",
              },
              maxResults: {
                type: "number",
                description:
                  "Maximum number of jobs to scrape per source (default: 25)",
                default: 25,
              },
            },
            required: ["sources", "keywords"],
          },
        },
        {
          name: "get_jobs",
          description:
            "Get a paginated list of scraped jobs. Can filter by scraping job ID.",
          inputSchema: {
            type: "object",
            properties: {
              scrapingId: {
                type: "string",
                description:
                  "Optional scraping job ID to filter jobs by. If not provided, returns all jobs.",
              },
              page: {
                type: "number",
                description: "Page number (default: 1)",
                default: 1,
              },
              limit: {
                type: "number",
                description: "Number of jobs per page (default: 25, max: 100)",
                default: 25,
              },
            },
          },
        },
        {
          name: "get_job_by_id",
          description: "Get a specific job by its unique ID.",
          inputSchema: {
            type: "object",
            properties: {
              id: {
                type: "string",
                description: "The unique identifier of the job",
              },
            },
            required: ["id"],
          },
        },
        {
          name: "get_scraping_status",
          description:
            "Get the current status of a scraping job, including progress information.",
          inputSchema: {
            type: "object",
            properties: {
              scrapingId: {
                type: "string",
                description: "The scraping job ID to check status for",
              },
            },
            required: ["scrapingId"],
          },
        },
        {
          name: "stop_scraping_job",
          description: "Stop or cancel a running scraping job.",
          inputSchema: {
            type: "object",
            properties: {
              scrapingId: {
                type: "string",
                description: "The scraping job ID to stop",
              },
            },
            required: ["scrapingId"],
          },
        },
        {
          name: "validate_resume",
          description: "Validate a resume against a job description. Compares skills, keywords, and requirements, then provides missing keywords and recommendations to improve resume match.",
          inputSchema: {
            type: "object",
            properties: {
              jobId: {
                type: "string",
                description: "The job ID from scraped jobs (use get_job_by_id to find job IDs). Either jobId or jobDescription is required.",
              },
              jobDescription: {
                type: "string",
                description: "Raw job description text. Either jobId or jobDescription is required.",
              },
              resume: {
                type: "string",
                description: "Resume text content to validate against the job description. If not provided, uses default resume from JOBSCRAPPER_DEFAULT_RESUME.",
              },
              resumeFile: {
                type: "string",
                description: "Path to resume file (alternative to resume text). Supports .txt, .md, and .docx files. If not provided, uses default resume.",
              },
            },
            required: [],
          },
        },
        {
          name: "sync_jobs_to_notion",
          description: "Sync scraped jobs to a Notion database. Requires NOTION_API_TOKEN and NOTION_DATABASE_ID environment variables to be set.",
          inputSchema: {
            type: "object",
            properties: {
              scrapingId: {
                type: "string",
                description: "Optional scraping job ID to sync specific jobs. If not provided, syncs all jobs from the most recent scraping files.",
              },
              jobIds: {
                type: "array",
                items: {
                  type: "string",
                },
                description: "Optional array of specific job IDs to sync. If provided, only these jobs will be synced.",
              },
              notionDatabaseId: {
                type: "string",
                description: "Optional Notion database ID. If not provided, uses NOTION_DATABASE_ID environment variable.",
              },
              notionApiToken: {
                type: "string",
                description: "Optional Notion API token. If not provided, uses NOTION_API_TOKEN environment variable.",
              },
            },
            required: [],
          },
        },
      ],
    }));

    this.server.setRequestHandler(CallToolRequestSchema, async (request) => {
      const { name, arguments: args } = request.params;

      try {
        switch (name) {
          case "start_scraping_job":
            return await this.handleStartScrapingJob(
              args as unknown as ScrapingJobRequest
            );

          case "get_jobs":
            return await this.handleGetJobs(
              args as { scrapingId?: string; page?: number; limit?: number }
            );

          case "get_job_by_id":
            return await this.handleGetJobById(args as { id: string });

          case "get_scraping_status":
            return await this.handleGetScrapingStatus(
              args as { scrapingId: string }
            );

          case "stop_scraping_job":
            return await this.handleStopScrapingJob(
              args as { scrapingId: string }
            );

          case "validate_resume":
            return await this.handleValidateResume(
              args as {
                jobId?: string;
                jobDescription?: string;
                resume?: string;
                resumeFile?: string;
              }
            );

          case "sync_jobs_to_notion":
            return await this.handleSyncJobsToNotion(
              args as {
                scrapingId?: string;
                jobIds?: string[];
                notionDatabaseId?: string;
                notionApiToken?: string;
              }
            );

          default:
            throw new Error(`Unknown tool: ${name}`);
        }
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : String(error);
        return {
          content: [
            {
              type: "text",
              text: `Error: ${errorMessage}`,
            },
          ],
          isError: true,
        };
      }
    });
  }

  private async makeApiRequest<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = `${API_BASE_URL}${endpoint}`;
    const response = await fetch(url, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...options.headers,
      },
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(
        `API request failed: ${response.status} ${response.statusText} - ${errorText}`
      );
    }

    return response.json() as Promise<T>;
  }

  private async handleStartScrapingJob(
    args: ScrapingJobRequest
  ): Promise<{ content: Array<{ type: string; text: string }> }> {
    // Generate scraping ID immediately
    const scrapingId = `scrape_${Date.now()}_${Math.random().toString(36).substring(7)}`;
    
    // STEP 1: Query Notion to get existing job URLs BEFORE scraping
    try {
      console.error(`[MCP] Querying Notion database for existing job URLs...`);
      const notionUrls = await this.getNotionExistingUrls();
      console.error(`[MCP] Found ${notionUrls.size} existing jobs in Notion. These will be skipped during scraping.`);
      
      // Pass URLs directly in the request (no file needed)
      if (notionUrls.size > 0) {
        args.notionUrls = Array.from(notionUrls);
        console.error(`[MCP] URLs will be checked in-memory during scraping - no file needed`);
      } else {
        console.error(`[MCP] No existing jobs found in Notion - all jobs will be considered new`);
      }
    } catch (notionError) {
      const errorMsg = notionError instanceof Error ? notionError.message : String(notionError);
      console.error(`[MCP] ⚠️  WARNING: Failed to query Notion: ${errorMsg}`);
      console.error(`[MCP] Scraper will continue WITHOUT Notion deduplication - duplicates may be scraped!`);
    }
    
    // Use direct Java execution if enabled (default)
    if (USE_DIRECT_JAVA) {
      // Start scraping asynchronously - don't wait for it to complete
      this.runJavaScraperDirectlyAsync(args, scrapingId).catch((error) => {
        console.error(`[MCP] Scraping job ${scrapingId} failed:`, error);
      });
      
      // Return immediately with scraping ID
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                scrapingId: scrapingId,
                status: "STARTED",
                message: `Scraping job started successfully. Scraping ID: ${scrapingId}. The job is running in the background. Use get_scraping_status to check progress.`,
              },
              null,
              2
            ),
          },
        ],
      };
    }
    
    // Fallback to REST API if available
    try {
      const response = await this.makeApiRequest<ScrapingJob>(
        "/scraping/start",
        {
          method: "POST",
          body: JSON.stringify(args),
        }
      );

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                scrapingId: response.scrapingId,
                status: response.status,
                startedAt: response.startedAt,
                message: `Scraping job started successfully. Scraping ID: ${response.scrapingId}`,
              },
              null,
              2
            ),
          },
        ],
      };
    } catch (error) {
      // If REST API fails, fallback to direct Java async
      const scrapingId = `scrape_${Date.now()}_${Math.random().toString(36).substring(7)}`;
      
      // Query Notion and get URLs before starting scraper (if not already done)
      if (!args.notionUrls || args.notionUrls.length === 0) {
        try {
          console.error(`[MCP] Querying Notion for existing jobs before scraping...`);
          const notionUrls = await this.getNotionExistingUrls();
          console.error(`[MCP] Found ${notionUrls.size} existing jobs in Notion. Scraper will skip these.`);
          args.notionUrls = Array.from(notionUrls);
        } catch (notionError) {
          console.error(`[MCP] Warning: Failed to query Notion. Scraper will continue without deduplication:`, notionError);
        }
      }
      
      this.runJavaScraperDirectlyAsync(args, scrapingId).catch((error) => {
        console.error(`[MCP] Scraping job ${scrapingId} failed:`, error);
      });
      
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                scrapingId: scrapingId,
                status: "STARTED",
                message: `Scraping job started successfully. Scraping ID: ${scrapingId}. The job is running in the background.`,
              },
              null,
              2
            ),
          },
        ],
      };
    }
  }

  private async runJavaScraperDirectlyAsync(
    args: ScrapingJobRequest,
    scrapingId: string
  ): Promise<void> {
    // This runs asynchronously in the background
    return new Promise((resolve, reject) => {
      try {
        // Prepare input JSON
        const inputJson: any = {
          sources: args.sources,
          keywords: args.keywords,
          location: args.location,
          maxResults: args.maxResults || 25,
        };
        
        // Add Notion URLs array if provided (for in-memory checking)
        if (args.notionUrls && args.notionUrls.length > 0) {
          inputJson.notionUrls = args.notionUrls;
        }

        // Create temp file for input
        if (!existsSync(JOBS_STORAGE_DIR)) {
          mkdirSync(JOBS_STORAGE_DIR, { recursive: true });
        }
        const inputFile = join(JOBS_STORAGE_DIR, `input_${Date.now()}.json`);
        writeFileSync(inputFile, JSON.stringify(inputJson, null, 2));

        // Use gradle to run with custom task runMcpScraper
        const gradlePath = join(JAVA_PROJECT_PATH, "gradlew");
        const gradleProcess = spawn(
          gradlePath,
          [
            "runMcpScraper",
            `-PmcpInput=${inputFile}`
          ],
          {
            cwd: JAVA_PROJECT_PATH,
            stdio: ["ignore", "pipe", "pipe"],
            env: { 
              ...process.env, 
              JAVA_OPTS: "-Dfile.encoding=UTF-8"
            },
          }
        );

        let stdout = "";
        let stderr = "";

        gradleProcess.stdout.on("data", (data) => {
          stdout += data.toString();
        });

        gradleProcess.stderr.on("data", (data) => {
          stderr += data.toString();
        });

        gradleProcess.on("close", (code) => {
          try {
            // Extract JSON from stdout (look for JSON object)
            const jsonMatch = stdout.match(/\{[\s\S]*\}/);
            if (jsonMatch) {
              const result = JSON.parse(jsonMatch[0]);
              
              // Jobs are already saved to scraped_jobs/ by Java (same as ScraperMain)
              // We don't need to save them again here
              // The Java code saves them as: jobs_YYYY-MM-dd_HH-mm-ss[_source].json
              
              // Store status file
              const statusFile = join(JOBS_STORAGE_DIR, `status_${scrapingId}.json`);
              writeFileSync(statusFile, JSON.stringify({
                scrapingId: scrapingId,
                status: result.status || "COMPLETED",
                jobsFound: result.jobsFound || 0,
                completedAt: new Date().toISOString()
              }, null, 2));
              
              console.log(`[MCP] Scraping job ${scrapingId} completed successfully with ${result.jobsFound || 0} jobs`);
              resolve();
            } else {
              // Check for error in output
              if (stderr.includes("error") || code !== 0) {
                const statusFile = join(JOBS_STORAGE_DIR, `status_${scrapingId}.json`);
                writeFileSync(statusFile, JSON.stringify({
                  scrapingId: scrapingId,
                  status: "FAILED",
                  error: stderr.substring(0, 500),
                  completedAt: new Date().toISOString()
                }, null, 2));
                console.error(`[MCP] Scraping job ${scrapingId} failed: ${stderr.substring(0, 500)}`);
                reject(new Error(`Scraping failed (code ${code}): ${stderr.substring(0, 500)}`));
              } else {
                // Still mark as completed even if no JSON found
                const statusFile = join(JOBS_STORAGE_DIR, `status_${scrapingId}.json`);
                writeFileSync(statusFile, JSON.stringify({
                  scrapingId: scrapingId,
                  status: "COMPLETED",
                  jobsFound: 0,
                  note: "No JSON output found in stdout",
                  completedAt: new Date().toISOString()
                }, null, 2));
                console.warn(`[MCP] Scraping job ${scrapingId} completed but no JSON output found`);
                resolve();
              }
            }
          } catch (parseError) {
            const statusFile = join(JOBS_STORAGE_DIR, `status_${scrapingId}.json`);
            writeFileSync(statusFile, JSON.stringify({
              scrapingId: scrapingId,
              status: "FAILED",
              error: `Parse error: ${parseError}`,
              completedAt: new Date().toISOString()
            }, null, 2));
            console.error(`[MCP] Scraping job ${scrapingId} parse error:`, parseError);
            reject(new Error(`Failed to parse output: ${parseError}. stdout: ${stdout.substring(0, 500)}`));
          }
        });

        gradleProcess.on("error", (error) => {
          reject(new Error(`Failed to start Gradle process: ${error.message}`));
        });

      } catch (error) {
        reject(error);
      }
    });
  }

  private async handleGetJobs(args: {
    scrapingId?: string;
    page?: number;
    limit?: number;
  }): Promise<{ content: Array<{ type: string; text: string }> }> {
    // If using direct Java, read from scraped_jobs directory (same as ScraperMain)
    if (USE_DIRECT_JAVA) {
      try {
        // Read all job files from scraped_jobs directory
        // Files are named: jobs_YYYY-MM-dd_HH-mm-ss[_source].json
        const allJobs: any[] = [];
        
        if (existsSync(SCRAPED_JOBS_DIR)) {
          const files = readdirSync(SCRAPED_JOBS_DIR).filter((f: string) => 
            f.startsWith("jobs_") && f.endsWith(".json") && !f.includes("_interrupted")
          );
          
          // Sort by modification time (newest first)
          const sortedFiles = files
            .map(f => ({
              name: f,
              path: join(SCRAPED_JOBS_DIR, f),
              mtime: statSync(join(SCRAPED_JOBS_DIR, f)).mtime.getTime()
            }))
            .sort((a, b) => b.mtime - a.mtime);
          
          // If scrapingId provided, try to find matching jobs (by reading all recent files)
          // Otherwise, get jobs from most recent files
          for (const file of sortedFiles.slice(0, 10)) { // Check last 10 files
            try {
              const jobs = JSON.parse(readFileSync(file.path, "utf-8"));
              if (Array.isArray(jobs)) {
                if (args.scrapingId) {
                  // Filter by job ID if scrapingId matches any job's ID
                  const matchingJobs = jobs.filter((j: any) => j.id === args.scrapingId);
                  if (matchingJobs.length > 0) {
                    allJobs.push(...matchingJobs);
                    break; // Found matching jobs, stop searching
                  }
                } else {
                  // No filtering, add all jobs
                  allJobs.push(...jobs);
                }
              }
            } catch (e) {
              // Skip invalid JSON files
              continue;
            }
          }
        }
        
        if (allJobs.length > 0) {
          const page = args.page || 1;
          const limit = args.limit || 25;
          const start = (page - 1) * limit;
          const end = start + limit;
          const jobs = allJobs.slice(start, end);
          const totalPages = Math.ceil(allJobs.length / limit);

          return {
            content: [
              {
                type: "text",
                text: JSON.stringify(
                  {
                    jobs,
                    total: allJobs.length,
                    page,
                    limit,
                    totalPages,
                    summary: `Found ${allJobs.length} jobs in scraped_jobs/ directory (page ${page} of ${totalPages})`,
                  },
                  null,
                  2
                ),
              },
            ],
          };
        }
      } catch (error) {
        // Fall through to API call
        console.error("[MCP] Error reading from scraped_jobs:", error);
      }
    }

    // Fallback to REST API
    try {
      const params = new URLSearchParams();
      if (args.scrapingId) params.append("scrapingId", args.scrapingId);
      if (args.page) params.append("page", args.page.toString());
      if (args.limit) params.append("limit", args.limit.toString());

      const response = await this.makeApiRequest<JobListResponse>(
        `/jobs?${params.toString()}`
      );

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                jobs: response.jobs,
                total: response.total,
                page: response.page,
                limit: response.limit,
                totalPages: response.totalPages,
                summary: `Found ${response.total} jobs (page ${response.page} of ${response.totalPages || Math.ceil(response.total / response.limit)})`,
              },
              null,
              2
            ),
          },
        ],
      };
    } catch (error) {
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                error: "No jobs found. Make sure you've started a scraping job first.",
                jobs: [],
                total: 0,
                page: args.page || 1,
                limit: args.limit || 25,
              },
              null,
              2
            ),
          },
        ],
      };
    }
  }

  private async handleGetJobById(args: {
    id: string;
  }): Promise<{ content: Array<{ type: string; text: string }> }> {
    // Try to find job in scraped_jobs directory first
    if (USE_DIRECT_JAVA && existsSync(SCRAPED_JOBS_DIR)) {
      try {
        const files = readdirSync(SCRAPED_JOBS_DIR).filter((f: string) =>
          f.startsWith("jobs_") && f.endsWith(".json") && !f.includes("_interrupted")
        );
        
        // Sort by modification time (newest first)
        const sortedFiles = files
          .map(f => ({
            name: f,
            path: join(SCRAPED_JOBS_DIR, f),
            mtime: require("fs").statSync(join(SCRAPED_JOBS_DIR, f)).mtime.getTime()
          }))
          .sort((a, b) => b.mtime - a.mtime);
        
        for (const file of sortedFiles.slice(0, 20)) {
          try {
            const jobs = JSON.parse(readFileSync(file.path, "utf-8"));
            if (Array.isArray(jobs)) {
              const job = jobs.find((j: any) => j.id === args.id);
              if (job) {
                return {
                  content: [
                    {
                      type: "text",
                      text: JSON.stringify(job, null, 2),
                    },
                  ],
                };
              }
            }
          } catch (e) {
            continue;
          }
        }
      } catch (error) {
        // Fall through to API call
      }
    }
    
    // Fallback to REST API
    try {
      const response = await this.makeApiRequest<Job>(`/jobs/${args.id}`);
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(response, null, 2),
          },
        ],
      };
    } catch (error) {
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                error: `Job with ID ${args.id} not found in scraped_jobs/ directory or via API.`,
                id: args.id,
              },
              null,
              2
            ),
          },
        ],
      };
    }
  }

  private async handleGetScrapingStatus(args: {
    scrapingId: string;
  }): Promise<{ content: Array<{ type: string; text: string }> }> {
    // Check status file first (for direct Java execution)
    if (USE_DIRECT_JAVA) {
      try {
        const statusFile = join(JOBS_STORAGE_DIR, `status_${args.scrapingId}.json`);
        if (existsSync(statusFile)) {
          const status = JSON.parse(readFileSync(statusFile, "utf-8"));
          // Jobs are stored in scraped_jobs/ directory, not JOBS_STORAGE_DIR
          // Count jobs from scraped_jobs directory if needed
          let jobsCount = status.jobsFound || 0;
          
          return {
            content: [
              {
                type: "text",
                text: JSON.stringify(
                  {
                    scrapingId: args.scrapingId,
                    status: status.status || "UNKNOWN",
                    jobsFound: status.jobsFound || jobsCount,
                    completedAt: status.completedAt,
                    error: status.error,
                    message: status.status === "COMPLETED" 
                      ? `Scraping completed! Found ${status.jobsFound || jobsCount} jobs.`
                      : status.status === "FAILED"
                      ? `Scraping failed: ${status.error || "Unknown error"}`
                      : "Scraping is still in progress or status unknown.",
                  },
                  null,
                  2
                ),
              },
            ],
          };
        } else {
          // Status file doesn't exist - job might still be running
          // Check if process is running by looking for input file
          const inputFiles = readdirSync(JOBS_STORAGE_DIR).filter((f: string) => f.startsWith("input_"));
          return {
            content: [
              {
                type: "text",
                text: JSON.stringify(
                  {
                    scrapingId: args.scrapingId,
                    status: "RUNNING",
                    message: "Scraping job is still running. Check back in a few minutes.",
                  },
                  null,
                  2
                ),
              },
            ],
          };
        }
      } catch (error) {
        // Fall through to API check
      }
    }
    
    // Fallback to REST API if available
    try {
      const jobsResponse = await this.makeApiRequest<JobListResponse>(
        `/jobs?scrapingId=${args.scrapingId}&limit=1`
      );
      
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                scrapingId: args.scrapingId,
                jobsFound: jobsResponse.total,
                jobs: jobsResponse.jobs.length > 0 ? jobsResponse.jobs.length : 0,
                message: `Found ${jobsResponse.total} jobs for scraping job ${args.scrapingId}`,
              },
              null,
              2
            ),
          },
        ],
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      throw new Error(`Failed to get scraping status: ${errorMessage}`);
    }
  }

  private async handleStopScrapingJob(args: {
    scrapingId: string;
  }): Promise<{ content: Array<{ type: string; text: string }> }> {
    const response = await this.makeApiRequest<ScrapingStatus>(
      `/scraping/${args.scrapingId}`,
      {
        method: "DELETE",
      }
    );

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              scrapingId: response.scrapingId,
              status: response.status,
              message: `Scraping job ${args.scrapingId} stopped successfully`,
            },
            null,
            2
          ),
        },
      ],
    };
  }

  private async handleValidateResume(args: {
    jobId?: string;
    jobDescription?: string;
    resume?: string;
    resumeFile?: string;
  }): Promise<{ content: Array<{ type: string; text: string }> }> {
    try {
      // Get resume content
      let resumeText = args.resume || "";
      let resumeFilePath = args.resumeFile;
      
      // If no resume provided, use default resume
      if (!resumeText && !resumeFilePath) {
        resumeFilePath = DEFAULT_RESUME_PATH;
        console.log(`[MCP] Using default resume: ${DEFAULT_RESUME_PATH}`);
      }
      
      // Read resume from file if needed
      if (!resumeText && resumeFilePath) {
        if (existsSync(resumeFilePath)) {
          // Handle .docx files - try to extract text
          if (resumeFilePath.endsWith('.docx')) {
            resumeText = await this.extractTextFromDocx(resumeFilePath);
          } else {
            // Regular text file
            resumeText = readFileSync(resumeFilePath, "utf-8");
          }
        } else {
          throw new Error(`Resume file not found: ${resumeFilePath}`);
        }
      }
      
      if (!resumeText) {
        throw new Error(`Resume text is required. Tried to read from: ${resumeFilePath || 'not specified'}`);
      }

      // Get job description
      let jobDescription = args.jobDescription || "";
      if (!jobDescription && args.jobId) {
        // Try to get job from scraped_jobs directory (same location as ScraperMain saves)
        if (USE_DIRECT_JAVA && existsSync(SCRAPED_JOBS_DIR)) {
          const jobsFiles = readdirSync(SCRAPED_JOBS_DIR).filter((f: string) =>
            f.startsWith("jobs_") && f.endsWith(".json") && !f.includes("_interrupted")
          );
          
          // Sort by modification time (newest first)
          const sortedFiles = jobsFiles
            .map(f => ({
              name: f,
              path: join(SCRAPED_JOBS_DIR, f),
              mtime: statSync(join(SCRAPED_JOBS_DIR, f)).mtime.getTime()
            }))
            .sort((a, b) => b.mtime - a.mtime);
          
          for (const file of sortedFiles.slice(0, 20)) { // Check last 20 files
            try {
              const jobs = JSON.parse(readFileSync(file.path, "utf-8"));
              const job = Array.isArray(jobs)
                ? jobs.find((j: any) => j.id === args.jobId)
                : null;
              if (job && job.description) {
                jobDescription = job.description;
                break;
              }
            } catch (e) {
              // Continue searching
              continue;
            }
          }
        }

        // If not found in stored files, try REST API
        if (!jobDescription) {
          try {
            const job = await this.makeApiRequest<Job>(`/jobs/${args.jobId}`);
            jobDescription = job.description || "";
          } catch (e) {
            // Ignore API errors
          }
        }

        if (!jobDescription) {
          throw new Error(
            `Job not found with ID: ${args.jobId}. Make sure the job was scraped and the ID is correct.`
          );
        }
      }

      if (!jobDescription) {
        throw new Error(
          "Job description is required. Provide either jobId or jobDescription."
        );
      }

      // Perform validation
      const validationResult = await this.validateResumeAgainstJob(
        resumeText,
        jobDescription
      );

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(validationResult, null, 2),
          },
        ],
      };
    } catch (error) {
      const errorMessage =
        error instanceof Error ? error.message : String(error);
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                error: errorMessage,
                matchScore: 0,
                recommendations: [],
                analysis: `Error: ${errorMessage}`,
              },
              null,
              2
            ),
          },
        ],
      };
    }
  }

  private async extractTextFromDocx(filePath: string): Promise<string> {
    try {
      // Use mammoth to extract text from .docx file
      const result = await mammoth.extractRawText({ path: filePath });
      return result.value;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      throw new Error(`Failed to read DOCX file: ${errorMessage}`);
    }
  }

  private async validateResumeAgainstJob(
    resume: string,
    jobDescription: string
  ): Promise<{
    matchScore: number;
    foundKeywords: string[];
    missingKeywords: string[];
    recommendations: string[];
    analysis: string;
  }> {
    // Normalize text to lowercase for comparison
    const resumeLower = resume.toLowerCase();
    const jobDescLower = jobDescription.toLowerCase();

    // Common technical skills/keywords to extract
    const commonKeywords = [
      // Programming Languages
      "java",
      "python",
      "javascript",
      "typescript",
      "c++",
      "c#",
      "go",
      "rust",
      "kotlin",
      "swift",
      "php",
      "ruby",
      "scala",
      "r",
      "sql",
      // Frameworks & Libraries
      "react",
      "angular",
      "vue",
      "node.js",
      "express",
      "spring",
      "django",
      "flask",
      "rails",
      "asp.net",
      "laravel",
      // Tools & Technologies
      "docker",
      "kubernetes",
      "aws",
      "azure",
      "gcp",
      "terraform",
      "jenkins",
      "git",
      "ci/cd",
      "microservices",
      "rest api",
      "graphql",
      "mongodb",
      "postgresql",
      "mysql",
      "redis",
      "elasticsearch",
      // Methodologies
      "agile",
      "scrum",
      "devops",
      "tdd",
      "test driven development",
      "api development",
      "full stack",
      "backend",
      "frontend",
      // Soft skills (context-dependent)
      "collaboration",
      "leadership",
      "problem solving",
    ];

    // Extract keywords from job description
    const jobKeywords: string[] = [];
    for (const keyword of commonKeywords) {
      if (jobDescLower.includes(keyword.toLowerCase())) {
        jobKeywords.push(keyword);
      }
    }

    // Also extract capitalized/technical terms (e.g., "React", "Node.js")
    const technicalTerms = jobDescription.match(
      /\b[A-Z][a-z]+(?:\.[a-z]+)?\b/g
    );
    if (technicalTerms) {
      for (const term of technicalTerms) {
        const termLower = term.toLowerCase();
        if (
          !jobKeywords.includes(termLower) &&
          commonKeywords.some((k) => k.toLowerCase() === termLower)
        ) {
          jobKeywords.push(termLower);
        }
      }
    }

    // Find which keywords are in resume
    const foundKeywords: string[] = [];
    const missingKeywords: string[] = [];

    for (const keyword of jobKeywords) {
      if (resumeLower.includes(keyword.toLowerCase())) {
        foundKeywords.push(keyword);
      } else {
        missingKeywords.push(keyword);
      }
    }

    // Calculate match score (percentage)
    const matchScore =
      jobKeywords.length > 0
        ? Math.round((foundKeywords.length / jobKeywords.length) * 100)
        : 0;

    // Generate recommendations
    const recommendations: string[] = [];

    if (missingKeywords.length > 0) {
      recommendations.push(
        `Add these ${missingKeywords.length} missing keywords to your resume: ${missingKeywords
          .slice(0, 10)
          .join(", ")}${missingKeywords.length > 10 ? "..." : ""}`
      );
    }

    if (matchScore < 50) {
      recommendations.push(
        "Your resume has a low match score. Consider highlighting more relevant skills and experiences that align with the job requirements."
      );
    } else if (matchScore < 70) {
      recommendations.push(
        "Your resume has a moderate match. Focus on adding the missing keywords naturally in your experience descriptions."
      );
    } else {
      recommendations.push(
        "Good match! Your resume aligns well with the job description. Consider emphasizing the matching skills more prominently."
      );
    }

    // Generate analysis
    let analysis = `Resume Match Analysis:\n`;
    analysis += `- Match Score: ${matchScore}%\n`;
    analysis += `- Found ${foundKeywords.length} out of ${jobKeywords.length} required keywords\n`;
    analysis += `- Missing ${missingKeywords.length} keywords\n\n`;

    if (foundKeywords.length > 0) {
      analysis += `✅ Keywords found in your resume:\n${foundKeywords
        .slice(0, 15)
        .map((k) => `  • ${k}`)
        .join("\n")}\n\n`;
    }

    if (missingKeywords.length > 0) {
      analysis += `❌ Keywords missing from your resume:\n${missingKeywords
        .slice(0, 15)
        .map((k) => `  • ${k}`)
        .join("\n")}\n`;
    }

    return {
      matchScore,
      foundKeywords,
      missingKeywords,
      recommendations,
      analysis,
    };
  }

  private async handleSyncJobsToNotion(args: {
    scrapingId?: string;
    jobIds?: string[];
    notionDatabaseId?: string;
    notionApiToken?: string;
  }): Promise<{ content: Array<{ type: string; text: string }> }> {
    // Helper to get jobs by scraping ID
    const getJobsByScrapingId = async (scrapingId: string): Promise<any[]> => {
      const jobsResponse = await this.handleGetJobs({ scrapingId });
      const jobsText = jobsResponse.content[0]?.text || "{}";
      const jobsData = JSON.parse(jobsText);
      return jobsData.jobs && Array.isArray(jobsData.jobs) ? jobsData.jobs : [];
    };

    return await syncJobsToNotion({
      ...args,
      scrapedJobsDir: SCRAPED_JOBS_DIR,
      findJobById: (jobId: string) => this.findJobById(jobId),
      getJobsByScrapingId,
    });
  }

  private async findJobById(jobId: string): Promise<any | null> {
    if (existsSync(SCRAPED_JOBS_DIR)) {
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
    }
    return null;
  }

  /**
   * Query Notion database to get all existing job URLs
   */
  private async getNotionExistingUrls(): Promise<Set<string>> {
    const existingUrls = new Set<string>();
    
    // Load config from file or environment
    const configPath = join(JAVA_PROJECT_PATH, "config.json");
    let databaseId = process.env.NOTION_DATABASE_ID || "";
    let apiToken = process.env.NOTION_API_TOKEN || "";
    
    if (existsSync(configPath)) {
      try {
        const config = JSON.parse(readFileSync(configPath, "utf-8"));
        databaseId = databaseId || config.NOTION_DATABASE_ID || "";
        apiToken = apiToken || config.NOTION_API_TOKEN || "";
      } catch (e) {
        console.error(`[MCP] Error reading config file:`, e);
      }
    }
    
    if (!databaseId || !apiToken) {
      throw new Error("Notion database ID and API token are required. Set NOTION_DATABASE_ID and NOTION_API_TOKEN or configure config.json");
    }
    
    let hasMore = true;
    let startCursor: string | undefined = undefined;
    
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
          throw new Error(`Notion API error: ${response.status} ${response.statusText} - ${errorText}`);
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
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      throw new Error(`Failed to query Notion for existing jobs: ${errorMessage}`);
    }
    
    return existingUrls;
  }
  
  /**
   * Save Notion URLs to a file that Java scraper can read
   */
  private async saveNotionUrlsFile(urls: Set<string>): Promise<string> {
    if (!existsSync(JOBS_STORAGE_DIR)) {
      mkdirSync(JOBS_STORAGE_DIR, { recursive: true });
    }
    
    const urlsFile = join(JOBS_STORAGE_DIR, `notion_urls_${Date.now()}.json`);
    const urlsArray = Array.from(urls);
    writeFileSync(urlsFile, JSON.stringify(urlsArray, null, 2));
    
    return urlsFile;
  }

  async run(): Promise<void> {
    const transport = new StdioServerTransport();
    await this.server.connect(transport);
    console.error("JobScrapper MCP server running on stdio");
  }
}

// Start the server
const server = new JobScrapperMCPServer();
server.run().catch(console.error);
