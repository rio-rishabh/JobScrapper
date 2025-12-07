package com.jobScrapper.test;

import com.jobScrapper.scraper.ScraperManager;
import com.jobScrapper.scraper.impl.LinkedInScraper;
import com.jobScrapper.scraper.impl.IndeedScraper;
import com.jobScrapper.util.JobStorage;
import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
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
        
        // Step 1: Create all available scrapers
        List<com.jobScrapper.scraper.JobScraper> scrapers = new ArrayList<>();
        scrapers.add(new LinkedInScraper());
        scrapers.add(new IndeedScraper());
        scrapers.add(new com.jobScrapper.scraper.impl.GlassDoorScraper());
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
        // You can specify multiple platforms in the sources list
        ScrapingJobRequest request = new ScrapingJobRequest()
            .addSourcesItem(ScrapingJobRequest.SourcesEnum.LINKED_IN)  // Scrape from LinkedIn
            .addSourcesItem(ScrapingJobRequest.SourcesEnum.INDEED)     // Scrape from Indeed
            .addSourcesItem(ScrapingJobRequest.SourcesEnum.GLASSDOOR)  // Scrape from GlassDoor
            // Add more sources as needed:
            // .addSourcesItem(ScrapingJobRequest.SourcesEnum.GLASSDOOR)
            // .addSourcesItem(ScrapingJobRequest.SourcesEnum.MONSTER)
            .addKeywordsItem("software engineer")  // Change this to test different keywords
            .addKeywordsItem("java")               // Add more keywords if needed
            .location("Boston, MA")          // Change location or set to null
            .maxResults(maxResults);                // Number of jobs to scrape per platform
        
        // Step 5: Track scraped jobs per platform and store all jobs
        Map<String, AtomicInteger> jobCountsByPlatform = new HashMap<>();
        Map<String, List<Job>> jobsByPlatform = new ConcurrentHashMap<>(); // Thread-safe storage
        JobStorage jobStorage = new JobStorage();
        
        System.out.println("📋 Scraping Configuration:");
        System.out.println("   Platforms: " + request.getSources());
        System.out.println("   Keywords: " + request.getKeywords());
        System.out.println("   Location: " + (request.getLocation() != null ? request.getLocation() : "Anywhere"));
        System.out.println("   Max Results per Platform: " + request.getMaxResults());
        System.out.println("\n🌐 Starting scraping from all specified platforms...\n");
        
        // Step 6: Scrape from ALL platforms in PARALLEL (multiple browser tabs simultaneously)
        System.out.println("📝 Total platforms to scrape: " + request.getSources().size());
        System.out.println("🚀 Starting PARALLEL scraping - multiple browser tabs will open simultaneously!\n");
        
        // Create a thread pool - one thread per platform
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
                        int count = platformJobCount.incrementAndGet();
                        
                        // Validate job before storing
                        if (job == null) {
                            System.err.println("[" + finalSourceName + "] ⚠️  Received null job, skipping...");
                            return;
                        }
                        
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
        
        // Shutdown the thread pool
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        
        System.out.println("✅ All parallel scraping tasks completed!\n");
        
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
        System.out.println("📊 Total jobs scraped across all platforms: " + totalJobs);
        
        // Count actual jobs in storage
        int actualJobsInStorage = 0;
        for (List<Job> platformJobList : jobsByPlatform.values()) {
            actualJobsInStorage += platformJobList.size();
        }
        System.out.println("📦 Total jobs in storage: " + actualJobsInStorage);
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
                    // Save to both JSON and CSV
                    String timestamp = java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                    );
                    String baseFilename = "jobs_" + timestamp;
                    
                    String[] savedFiles = jobStorage.saveAll(allJobs, baseFilename);
                    
                    if (savedFiles[0] != null && savedFiles[1] != null) {
                        System.out.println("\n✅ Jobs saved successfully!");
                        System.out.println("   📄 JSON file: " + savedFiles[0]);
                        System.out.println("   📊 CSV file:  " + savedFiles[1]);
                        System.out.println("   📁 Location:  scraped_jobs/ directory\n");
                    } else {
                        System.out.println("\n⚠️  Warning: Files were not saved (check errors above).\n");
                    }
                    
                    // Also save per-platform files
                    System.out.println("💾 Saving per-platform files...");
                    int platformFilesSaved = 0;
                    for (Map.Entry<String, List<Job>> entry : jobsByPlatform.entrySet()) {
                        if (!entry.getValue().isEmpty()) {
                            String platformFilename = baseFilename + "_" + entry.getKey().toLowerCase();
                            String[] platformFiles = jobStorage.saveAll(entry.getValue(), platformFilename);
                            if (platformFiles[0] != null && platformFiles[1] != null) {
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
        
        // Add shutdown hook to save jobs if program is interrupted
        // Note: jobStorage and jobsByPlatform are effectively final, so they can be used in lambda
        final JobStorage finalJobStorage = jobStorage; // Make effectively final for lambda
        final Map<String, List<Job>> finalJobsByPlatform = jobsByPlatform; // Make effectively final
        
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
                    
                    String[] savedFiles = finalJobStorage.saveAll(allJobs, baseFilename);
                    
                    if (savedFiles != null && savedFiles[0] != null && savedFiles[1] != null) {
                        System.out.println("✅ Saved " + jobsToSave + " jobs before shutdown!");
                        System.out.println("   📄 JSON: " + savedFiles[0]);
                        System.out.println("   📊 CSV:  " + savedFiles[1]);
                    }
                } else {
                    System.out.println("⚠️  No jobs to save.");
                }
            } catch (Exception e) {
                System.err.println("❌ Error saving jobs on shutdown: " + e.getMessage());
            }
        }));
        
        // Step 9: Keep browsers open indefinitely (after saving is complete)
        System.out.println("🔓 All browsers will stay open indefinitely...");
        System.out.println("   Close browser windows manually when you're done.");
        System.out.println("   Press Ctrl+C in the terminal to stop the program.");
        System.out.println("   Jobs have been saved to: scraped_jobs/ directory");
        System.out.println("   💡 Tip: If you interrupt, jobs will be auto-saved!\n");
        
        // Wait indefinitely so browsers stay open
        // This allows you to view results in browsers while files are already saved
        try {
            while (true) {
                Thread.sleep(60000); // Wait 1 minute at a time, but loop forever
            }
        } catch (InterruptedException e) {
            System.out.println("\n⏹️  Program interrupted, shutting down...");
        }
    }
}

