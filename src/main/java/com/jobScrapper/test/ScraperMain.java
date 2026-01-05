package com.jobScrapper.test;

import com.jobScrapper.scraper.ScraperManager;
import com.jobScrapper.scraper.impl.LinkedInScraper;
import com.jobScrapper.scraper.impl.IndeedScraper;
import com.jobScrapper.scraper.impl.ZipRecruiterScraper;
import com.jobScrapper.util.JobStorage;
import com.jobScrapper.util.NotionClient;
import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Generic main class to test job scrapers from multiple platforms.
 * 
 * You can specify which platforms to scrape from in the request.
 * The ScraperManager will automatically use the appropriate scraper for each platform.
 * 
 * Run this with: ./gradlew run
 */
public class ScraperMain {

    public static void main(String[] args) {
        System.out.println("🚀 Starting Multi-Platform Job Scraper...\n");
        
        // Step 1: Create all available scrapers
        List<com.jobScrapper.scraper.JobScraper> scrapers = new ArrayList<>();
        scrapers.add(new LinkedInScraper());
        scrapers.add(new IndeedScraper());
        scrapers.add(new com.jobScrapper.scraper.impl.GlassDoorScraper());
        scrapers.add(new ZipRecruiterScraper());  // ZipRecruiter - less Cloudflare issues!
        // Add more scrapers here as you create them:
        // scrapers.add(new MonsterScraper());
        
        // Step 2: Create ScraperManager with all scrapers
        ScraperManager scraperManager = new ScraperManager(scrapers);
        
        // Step 3: Show available platforms
        System.out.println("📋 Available Platforms: " + scraperManager.getAvailableSources());
        System.out.println("📋 Registered Scrapers:");
        for (com.jobScrapper.scraper.JobScraper scraper : scrapers) {
            System.out.println("   - " + scraper.getSource() + " (registered as: '" + scraper.getSource().toLowerCase() + "')");
        }
        System.out.println();
        
        // Step 4: Create a scraping request
        // Check if MCP input file is provided via system property
        ScrapingJobRequest request;
        String mcpInputFile = System.getProperty("mcpInput");
        
        if (mcpInputFile != null && !mcpInputFile.isEmpty()) {
            // MCP mode: Read request from JSON file
            System.out.println("📥 MCP Mode: Reading scraping request from: " + mcpInputFile);
            try {
                String jsonContent = new String(Files.readAllBytes(Paths.get(mcpInputFile)));
                ObjectMapper mapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> inputData = (Map<String, Object>) mapper.readValue(jsonContent, Map.class);
                
                request = new ScrapingJobRequest();
                
                // Parse sources
                @SuppressWarnings("unchecked")
                List<String> sources = (List<String>) inputData.get("sources");
                if (sources != null) {
                    for (String source : sources) {
                        try {
                            ScrapingJobRequest.SourcesEnum sourceEnum = ScrapingJobRequest.SourcesEnum.fromValue(source);
                            request.addSourcesItem(sourceEnum);
                        } catch (Exception e) {
                            System.err.println("⚠️  Invalid source: " + source);
                        }
                    }
                }
                
                // Parse keywords
                @SuppressWarnings("unchecked")
                List<String> keywords = (List<String>) inputData.get("keywords");
                if (keywords != null) {
                    for (String keyword : keywords) {
                        request.addKeywordsItem(keyword);
                    }
                }
                
                // Parse location
                String location = (String) inputData.get("location");
                if (location != null && !location.isEmpty()) {
                    request.location(location);
                }
                
                // Parse maxResults
                Object maxResultsObj = inputData.get("maxResults");
                if (maxResultsObj != null) {
                    int maxResults = maxResultsObj instanceof Number 
                        ? ((Number) maxResultsObj).intValue() 
                        : Integer.parseInt(maxResultsObj.toString());
                    request.maxResults(maxResults);
                } else {
                    request.maxResults(50); // Default
                }
                
                // Parse notionUrls (optional - array of URLs from Notion to skip)
                @SuppressWarnings("unchecked")
                List<String> notionUrls = (List<String>) inputData.get("notionUrls");
                if (notionUrls != null && !notionUrls.isEmpty()) {
                    System.out.println("📋 Notion URLs provided: " + notionUrls.size() + " URLs to check against");
                    // Store to pass to JobStorage later
                    System.setProperty("notionUrls", String.join(",", notionUrls)); // Temporary storage
                    // Better: store in a way JobStorage can access
                    inputData.put("_notionUrlsList", notionUrls); // Store in inputData for later
                }
                
                System.out.println("✅ Successfully loaded MCP request");
            } catch (IOException e) {
                System.err.println("❌ Error reading MCP input file: " + e.getMessage());
                e.printStackTrace();
                return;
            } catch (Exception e) {
                System.err.println("❌ Error parsing MCP input JSON: " + e.getMessage());
                e.printStackTrace();
                return;
            }
        } else {
            // Default mode: Use hardcoded values
            // Parse command-line arguments for maxResults
            int maxResults = 50; // Default value
            if (args.length > 0) {
                try {
                    maxResults = Integer.parseInt(args[0]);
                    System.out.println("📊 Using maxResults from command line: " + maxResults);
                } catch (NumberFormatException e) {
                    System.err.println("⚠️  Invalid maxResults argument: " + args[0] + ". Using default: " + maxResults);
                }
            }
            System.out.println("💡 Tip: You can specify maxResults as first argument: ./gradlew run --args '100'\n");
            
            // Create default request
            request = new ScrapingJobRequest()
                .addSourcesItem(ScrapingJobRequest.SourcesEnum.LINKED_IN)     // Scrape from LinkedIn ✅
                .addSourcesItem(ScrapingJobRequest.SourcesEnum.ZIPRECRUITER)  // Scrape from ZipRecruiter ✅ (no Cloudflare!)
                // Problematic scrapers (heavy Cloudflare):
                // .addSourcesItem(ScrapingJobRequest.SourcesEnum.INDEED)
            // .addSourcesItem(ScrapingJobRequest.SourcesEnum.GLASSDOOR)
                // Other available sources:
            // .addSourcesItem(ScrapingJobRequest.SourcesEnum.MONSTER)
                // .addSourcesItem(ScrapingJobRequest.SourcesEnum.DICE)
            .addKeywordsItem("software engineer")  // Change this to test different keywords
            .addKeywordsItem("java")               // Add more keywords if needed
                .location("United States")          // Change location or set to null
            .maxResults(maxResults);                // Number of jobs to scrape per platform
        }
        
        // Step 5: Track scraped jobs per platform and store all jobs
        Map<String, AtomicInteger> jobCountsByPlatform = new HashMap<>();
        Map<String, List<Job>> jobsByPlatform = new ConcurrentHashMap<>(); // Thread-safe storage
        JobStorage jobStorage = new JobStorage();
        
        // DEDUPLICATION: Query Notion directly for existing URLs
        NotionClient notionClient = new NotionClient();
        if (notionClient.isConfigured()) {
            notionClient.loadExistingUrls();  // This queries Notion API directly
        } else {
            System.out.println("⚠️  Notion not configured in config.json - deduplication disabled");
            System.out.println("   To enable: add NOTION_DATABASE_ID and NOTION_API_TOKEN to config.json");
        }
        
        // Also check local scraped jobs for deduplication
        System.out.println("🔍 Loading local scraped jobs cache...");
        jobStorage.buildDeduplicationCache();
        AtomicInteger duplicatesSkipped = new AtomicInteger(0);
        System.out.println("✅ Ready to filter out previously scraped jobs!\n");
        
        // IMPORTANT: Register shutdown hook EARLY so it can save jobs even if interrupted
        final JobStorage finalJobStorage = jobStorage; // Make effectively final for lambda
        final Map<String, List<Job>> finalJobsByPlatform = jobsByPlatform; // Make effectively final
        final NotionClient finalNotionClient = notionClient; // Make effectively final for lambda
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n\n🛑 Shutdown detected! Saving any collected jobs...");
            try {
                int jobsToSave = 0;
                for (List<Job> platformJobList : finalJobsByPlatform.values()) {
                    synchronized (platformJobList) {
                        jobsToSave += platformJobList.size();
                    }
                }
                
                if (jobsToSave > 0) {
                    System.out.println("   Found " + jobsToSave + " jobs to save...");
                    List<Job> allJobs = new ArrayList<>();
                    for (List<Job> platformJobList : finalJobsByPlatform.values()) {
                        synchronized (platformJobList) {
                            allJobs.addAll(platformJobList);
                        }
                    }
                    
                    String timestamp = java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                    );
                    String baseFilename = "jobs_" + timestamp + "_interrupted";
                    
                    String savedFile = finalJobStorage.saveToJson(allJobs, baseFilename + ".json");
                    
                    if (savedFile != null) {
                        System.out.println("✅ Saved " + jobsToSave + " jobs before shutdown!");
                        System.out.println("   📄 JSON: " + savedFile);
                    } else {
                        System.err.println("❌ Failed to save file!");
                    }
                } else {
                    System.out.println("⚠️  No jobs to save (jobsToSave = " + jobsToSave + ").");
                }
            } catch (Exception e) {
                System.err.println("❌ Error saving jobs on shutdown: " + e.getMessage());
                e.printStackTrace();
            }
        }));
        
        System.out.println("📋 Scraping Configuration:");
        System.out.println("   Platforms: " + request.getSources());
        System.out.println("   Keywords: " + request.getKeywords());
        System.out.println("   Location: " + (request.getLocation() != null ? request.getLocation() : "Anywhere"));
        System.out.println("   Max Results per Platform: " + request.getMaxResults());
        
        // Check if MCP mode - still runs infinitely but outputs JSON after each cycle
        boolean isMcpMode = mcpInputFile != null && !mcpInputFile.isEmpty();
        
        // ALWAYS run in infinite mode - only stop when manually terminated
        System.out.println("\n🔄 INFINITE MODE: Scraper will run FOREVER until manually stopped (Ctrl+C or kill)");
        System.out.println("   Each cycle will scrape jobs, save them, then wait 5 minutes before next cycle");
        if (isMcpMode) {
            System.out.println("   📥 MCP Mode: Will also output JSON result after each cycle");
        }
        System.out.println("");
        
        // Flag to control infinite loop (can be set to false on shutdown)
        final java.util.concurrent.atomic.AtomicBoolean keepRunning = new java.util.concurrent.atomic.AtomicBoolean(true);
        
        // Register shutdown hook to stop the loop gracefully (ALWAYS - for all modes)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            keepRunning.set(false);
            System.out.println("\n🛑 Stopping infinite scraper loop...");
        }));
        
        int cycleNumber = 0;
        
        // INFINITE LOOP: Keep scraping until manually stopped (or single run in MCP mode)
        do {
            cycleNumber++;
            System.out.println("\n" + "=".repeat(80));
            System.out.println("🔄 CYCLE #" + cycleNumber + " - Starting at " + 
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            System.out.println("=".repeat(80) + "\n");
            
            // Clear job counts and lists for this cycle (but keep deduplication cache)
            jobCountsByPlatform.clear();
            for (String key : jobsByPlatform.keySet()) {
                jobsByPlatform.put(key, new CopyOnWriteArrayList<>());
            }
            duplicatesSkipped.set(0);
        
        // Step 6: Scrape from ALL platforms in PARALLEL (multiple browser tabs simultaneously)
        System.out.println("📝 Total platforms to scrape: " + request.getSources().size());
        System.out.println("🚀 Starting PARALLEL scraping - multiple browser tabs will open simultaneously!\n");
        
            // Create a thread pool - one thread per platform (recreated each cycle)
        ExecutorService executor = Executors.newFixedThreadPool(request.getSources().size());
        List<Future<?>> futures = new ArrayList<>();
        
        int platformIndex = 0;
        for (ScrapingJobRequest.SourcesEnum sourceEnum : request.getSources()) {
            platformIndex++;
            String sourceName = sourceEnum.getValue(); // Gets "LinkedIn", "Indeed", etc.
            
            // Debug: Check if scraper exists
            if (!scraperManager.getScraper(sourceName).isPresent()) {
                System.err.println("❌ ERROR: No scraper found for source: " + sourceName);
                System.err.println("   Available scrapers: " + scraperManager.getAvailableSources());
                System.err.println("   Make sure the scraper's getSource() returns exactly: " + sourceName);
                System.out.println("⏭️  Skipping " + sourceName + "\n");
                continue; // Skip this source and continue with next
            }
            
            // Initialize counter and storage for this platform
            jobCountsByPlatform.putIfAbsent(sourceName, new AtomicInteger(0));
            jobsByPlatform.putIfAbsent(sourceName, new CopyOnWriteArrayList<>());
            AtomicInteger platformJobCount = jobCountsByPlatform.get(sourceName);
            List<Job> platformJobs = jobsByPlatform.get(sourceName);
            
            // Create final copies for use in lambda
            final int currentPlatformIndex = platformIndex;
            final int totalPlatforms = request.getSources().size();
            final String finalSourceName = sourceName;
            
            // Submit scraping task to thread pool (runs in parallel)
            Future<?> future = executor.submit(() -> {
                try {
                    System.out.println("\n" + "=".repeat(80));
                    System.out.println("🔄 [THREAD] Starting platform " + currentPlatformIndex + " of " + totalPlatforms + ": " + finalSourceName);
                    System.out.println("=".repeat(80) + "\n");
                    
                    System.out.println("🔍 [" + finalSourceName + "] Looking for scraper...");
                    System.out.println("✅ [" + finalSourceName + "] Found scraper, opening browser...");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("🔍 [" + finalSourceName + "] Scraping from: " + finalSourceName);
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                    
                    // Use ScraperManager to get and run the appropriate scraper
                    scraperManager.runScraper(finalSourceName, request, job -> {
                        // Validate job before storing
                        if (job == null) {
                            System.err.println("[" + finalSourceName + "] ⚠️  Received null job, skipping...");
                            return;
                        }
                        
                        // DEDUPLICATION CHECK 1: Skip jobs already in Notion
                        if (finalNotionClient.urlExists(job.getUrl())) {
                            duplicatesSkipped.incrementAndGet();
                            synchronized (System.out) {
                                System.out.println("[" + finalSourceName + "] ⏭️  SKIPPING (in Notion): " + job.getTitle() + " at " + job.getCompany());
                            }
                            return;  // Already in Notion, skip
                        }
                        
                        // DEDUPLICATION CHECK 2: Skip jobs we've already scraped locally
                        if (jobStorage.isDuplicate(job)) {
                            duplicatesSkipped.incrementAndGet();
                            synchronized (System.out) {
                                System.out.println("[" + finalSourceName + "] ⏭️  SKIPPING (local): " + job.getTitle() + " at " + job.getCompany());
                            }
                            return;  // Don't store duplicate jobs
                        }
                        
                        int count = platformJobCount.incrementAndGet();
                        
                        // Add to cache so we don't add it again in this session
                        jobStorage.addToCache(job);
                        
                        // Store the job for later use (thread-safe list)
                        synchronized (platformJobs) {
                            platformJobs.add(job);
                        }
                        
                        // Use synchronized output to avoid mixed console messages
                        synchronized (System.out) {
                            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                            System.out.println("📌 [" + finalSourceName + "] Job #" + count);
                            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                            System.out.println("   Title:      " + job.getTitle());
                            System.out.println("   Company:    " + job.getCompany());
                            System.out.println("   Location:   " + job.getLocation());
                            System.out.println("   URL:        " + job.getUrl());
                            System.out.println("   Posted:     " + job.getPostedDate());
                            System.out.println("   Source:     " + job.getSource());
                            System.out.println("   ID:         " + job.getId());
                            
                            // Show description preview (first 150 characters)
                            String description = job.getDescription();
                            if (description != null && description.length() > 150) {
                                System.out.println("   Description: " + description.substring(0, 150) + "...");
                            } else {
                                System.out.println("   Description: " + description);
                            }
                            System.out.println();
                        }
                    });
                    
                    System.out.println("✅ [" + finalSourceName + "] Scraping completed!");
                    System.out.println("📊 [" + finalSourceName + "] Total jobs scraped: " + platformJobCount.get());
                    System.out.println("📦 [" + finalSourceName + "] Jobs stored in memory: " + platformJobs.size());
                    System.out.println();
                    
                } catch (Exception e) {
                    synchronized (System.err) {
                        System.err.println("\n❌ [" + finalSourceName + "] Error during scraping:");
                        System.err.println("   " + e.getMessage());
                        System.err.println("   Error type: " + e.getClass().getSimpleName());
                        System.err.println("\n💡 Tips for " + finalSourceName + ":");
                        System.err.println("   - Make sure you have internet connection");
                        System.err.println("   - Some platforms might require login - check the browser window");
                        System.err.println("   - CSS selectors might need updating if the site changed their HTML");
                        System.err.println("   - Check the browser window that opened to see what's happening");
                        System.err.println();
                    }
                } finally {
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("✅ [" + finalSourceName + "] Finished processing");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                }
            });
            
            futures.add(future);
        }
        
        // Wait for all parallel scraping tasks to complete
        System.out.println("⏳ Waiting for all platforms to finish scraping...\n");
        for (Future<?> future : futures) {
            try {
                future.get(); // Wait for this task to complete
            } catch (Exception e) {
                System.err.println("Error waiting for task: " + e.getMessage());
            }
        }
        
        // Shutdown the thread pool for this cycle
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            keepRunning.set(false); // Stop loop if interrupted
        }
        
        System.out.println("✅ All parallel scraping tasks completed for cycle #" + cycleNumber + "!\n");
        
        System.out.println("✅ Finished processing all platforms!");
        System.out.println();
        
        // Step 7: Final summary across all platforms
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("📊 FINAL SUMMARY");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        int totalJobs = 0;
        for (Map.Entry<String, AtomicInteger> entry : jobCountsByPlatform.entrySet()) {
            int count = entry.getValue().get();
            totalJobs += count;
            System.out.println("   " + entry.getKey() + ": " + count + " jobs");
        }
        
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("✅ All scraping completed!");
        System.out.println("📊 Total NEW jobs scraped: " + totalJobs);
        System.out.println("⏭️  Duplicates skipped: " + duplicatesSkipped.get());
        
        // Count actual jobs in storage
        int actualJobsInStorage = 0;
        for (List<Job> platformJobList : jobsByPlatform.values()) {
            actualJobsInStorage += platformJobList.size();
        }
        System.out.println("📦 Total jobs to save: " + actualJobsInStorage);
        System.out.println("🔍 Existing jobs in database: " + jobStorage.getExistingJobCount());
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        
        // Step 8: Save all scraped jobs to files
        if (actualJobsInStorage > 0) {
            System.out.println("💾 Saving scraped jobs to files...\n");
            
            try {
                // Collect all jobs from all platforms
                List<Job> allJobs = new ArrayList<>();
                for (List<Job> platformJobList : jobsByPlatform.values()) {
                    synchronized (platformJobList) {
                        allJobs.addAll(platformJobList);
                    }
                }
                
                if (allJobs.isEmpty()) {
                    System.out.println("⚠️  No jobs collected to save (all lists were empty).\n");
                } else {
                    // Save to JSON only
                    String timestamp = java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                    );
                    String baseFilename = "jobs_" + timestamp;
                    
                    String savedFile = jobStorage.saveToJson(allJobs, baseFilename + ".json");
                    
                    if (savedFile != null) {
                        System.out.println("\n✅ Jobs saved successfully!");
                        System.out.println("   📄 JSON file: " + savedFile);
                        System.out.println("   📁 Location:  scraped_jobs/ directory\n");
                    } else {
                        System.out.println("\n⚠️  Warning: File was not saved (check errors above).\n");
                    }
                    
                    // Also save per-platform files
                    System.out.println("💾 Saving per-platform files...");
                    int platformFilesSaved = 0;
                    for (Map.Entry<String, List<Job>> entry : jobsByPlatform.entrySet()) {
                        if (!entry.getValue().isEmpty()) {
                            String platformFilename = baseFilename + "_" + entry.getKey().toLowerCase() + ".json";
                            String platformFile = jobStorage.saveToJson(entry.getValue(), platformFilename);
                            if (platformFile != null) {
                                platformFilesSaved++;
                            }
                        }
                    }
                    if (platformFilesSaved > 0) {
                        System.out.println("✅ Per-platform files saved! (" + platformFilesSaved + " platforms)\n");
                    } else {
                        System.out.println("⚠️  No per-platform files were saved.\n");
                    }
                }
                
            } catch (Exception e) {
                System.err.println("❌ Error saving jobs to files: " + e.getMessage());
                e.printStackTrace();
            }
            } else {
                System.out.println("⚠️  No jobs to save (totalJobs = 0, actualJobsInStorage = " + actualJobsInStorage + ").\n");
        }
        
        // Note: Shutdown hook was already registered earlier (before scraping starts)
        // This ensures jobs are saved even if program is interrupted during scraping
        
        // Step 9: Output JSON for MCP mode (optional) and wait before next cycle
        if (isMcpMode) {
            // MCP mode: Output JSON result to stdout after each cycle
            try {
                List<Job> allJobs = new ArrayList<>();
                for (List<Job> platformJobList : jobsByPlatform.values()) {
                    synchronized (platformJobList) {
                        allJobs.addAll(platformJobList);
                    }
                }
                
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> result = new HashMap<>();
                result.put("status", "CYCLE_COMPLETED");
                result.put("cycle", cycleNumber);
                result.put("jobsFound", allJobs.size());
                result.put("totalJobs", totalJobs);
                result.put("duplicatesSkipped", duplicatesSkipped.get());
                result.put("platforms", jobCountsByPlatform.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().get()
                    )));
                
                // Output JSON to stdout (MCP will capture this)
                System.out.println("\n[MCP_RESULT]");
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                System.out.println("[/MCP_RESULT]");
            } catch (Exception e) {
                System.err.println("❌ Error outputting MCP JSON: " + e.getMessage());
                e.printStackTrace();
            }
            // DO NOT set keepRunning to false - continue running infinitely!
        }
        
        // ALWAYS wait before next cycle (infinite mode for ALL modes)
        if (keepRunning.get()) {
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("✅ Cycle #" + cycleNumber + " completed!");
            System.out.println("⏸️  Waiting 5 minutes before next cycle...");
            System.out.println("   Use 'stop_scraping_job' or Ctrl+C to stop the scraper");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            
            // Wait 5 minutes (300 seconds) before next cycle
            try {
                for (int i = 0; i < 60 && keepRunning.get(); i++) {
                    Thread.sleep(5000); // Sleep 5 seconds at a time, check flag every 5 seconds
            }
        } catch (InterruptedException e) {
            System.out.println("\n⏹️  Program interrupted, shutting down...");
                keepRunning.set(false);
            }
        }
        
        } while (keepRunning.get()); // INFINITE LOOP - runs forever until manually stopped
        
        // Final shutdown message (for ALL modes since all are now infinite)
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🛑 INFINITE SCRAPER STOPPED");
        System.out.println("=".repeat(80));
        System.out.println("   Total cycles completed: " + cycleNumber);
        System.out.println("   All jobs have been saved to: scraped_jobs/ directory");
        System.out.println("   Thank you for using the Job Scraper!\n");
    }
}

