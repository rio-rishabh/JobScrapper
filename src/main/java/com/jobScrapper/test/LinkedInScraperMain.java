package com.jobScrapper.test;

import com.jobScrapper.scraper.impl.LinkedInScraper;
import com.jobscrapper.model.ScrapingJobRequest;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple main method to test LinkedIn scraper.
 * 
 * Run this with: ./gradlew run --main com.jobScrapper.test.LinkedInScraperMain
 * Or: java -cp "build/classes/java/main:build/libs/*" com.jobScrapper.test.LinkedInScraperMain
 */
public class LinkedInScraperMain {

    public static void main(String[] args) {
        System.out.println("🚀 Starting LinkedIn Job Scraper Test...\n");
        
        // Create the scraper instance
        LinkedInScraper scraper = new LinkedInScraper();
        
        // Create a scraping request
        // You can modify these values to test different searches
        ScrapingJobRequest request = new ScrapingJobRequest()
            .addSourcesItem(ScrapingJobRequest.SourcesEnum.LINKED_IN)
            .addKeywordsItem("software engineer")  // Change this to test different keywords
            .addKeywordsItem("java")               // Add more keywords if needed
            .location("Boston, MA")          // Change location or set to null
            .maxResults(5);                        // Limit to 5 jobs for testing
        
        // Track scraped jobs
        AtomicInteger jobCount = new AtomicInteger(0);
        
        System.out.println("📋 Scraping Configuration:");
        System.out.println("   Keywords: " + request.getKeywords());
        System.out.println("   Location: " + (request.getLocation() != null ? request.getLocation() : "Anywhere"));
        System.out.println("   Max Results: " + request.getMaxResults());
        System.out.println("\n🌐 Opening browser and navigating to LinkedIn...\n");
        
        try {
            // Execute the scraping
            scraper.scrape(request, job -> {
                int count = jobCount.incrementAndGet();
                
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println("📌 Job #" + count);
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
            });
            
            // Final summary
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("✅ Scraping completed successfully!");
            System.out.println("📊 Total jobs scraped: " + jobCount.get());
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
        } catch (Exception e) {
            System.err.println("\n❌ Error during scraping:");
            System.err.println("   " + e.getMessage());
            e.printStackTrace();
            System.err.println("\n💡 Tips:");
            System.err.println("   - Make sure you have internet connection");
            System.err.println("   - LinkedIn might require login - check if browser opened to login page");
            System.err.println("   - CSS selectors might need updating if LinkedIn changed their HTML");
            System.err.println("   - Check the browser window that opened to see what's happening");
        }
    }
}

