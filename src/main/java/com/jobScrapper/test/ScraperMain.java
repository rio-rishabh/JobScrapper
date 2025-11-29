package com.jobScrapper.test;

import com.jobScrapper.scraper.ScraperManager;
import com.jobScrapper.scraper.impl.LinkedInScraper;
import com.jobScrapper.scraper.impl.IndeedScraper;
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
        // Add more scrapers here as you create them:
        // scrapers.add(new GlassdoorScraper());
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
            // Add more sources as needed:
            // .addSourcesItem(ScrapingJobRequest.SourcesEnum.GLASSDOOR)
            // .addSourcesItem(ScrapingJobRequest.SourcesEnum.MONSTER)
            .addKeywordsItem("software engineer")  // Change this to test different keywords
            .addKeywordsItem("java")               // Add more keywords if needed
            .location("San Francisco, CA")          // Change location or set to null
            .maxResults(5);                        // Limit to 5 jobs per platform for testing
        
        // Step 5: Track scraped jobs per platform
        Map<String, AtomicInteger> jobCountsByPlatform = new HashMap<>();
        
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
            
            // Initialize counter for this platform
            jobCountsByPlatform.putIfAbsent(sourceName, new AtomicInteger(0));
            AtomicInteger platformJobCount = jobCountsByPlatform.get(sourceName);
            
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
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
}

