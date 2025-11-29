package com.jobScrapper.scraper.impl;

import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for LinkedInScraper.
 * 
 * Note: This test requires:
 * 1. Playwright browsers to be installed (run: mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install chromium")
 *    OR use Gradle: ./gradlew -Pexec.mainClass=com.microsoft.playwright.CLI -Pexec.args="install chromium" run
 * 2. Internet connection
 * 3. LinkedIn website to be accessible
 * 
 * This is an integration test that actually scrapes LinkedIn.
 * Remove @Disabled annotation to run the test.
 */
@Disabled("Enable this to run actual scraping test - requires Playwright browsers installed")
public class LinkedInScraperTest {

    private LinkedInScraper scraper;
    private List<Job> scrapedJobs;

    @BeforeEach
    void setUp() {
        scraper = new LinkedInScraper();
        scrapedJobs = new ArrayList<>();
    }

    @Test
    void testGetSource() {
        // Test that getSource returns "LinkedIn"
        String source = scraper.getSource();
        assertEquals("LinkedIn", source, "Source should be 'LinkedIn'");
    }

    @Test
    void testScrapeLinkedInJobs() throws Exception {
        // Create a scraping request
        ScrapingJobRequest request = new ScrapingJobRequest()
            .addSourcesItem(ScrapingJobRequest.SourcesEnum.LINKED_IN)
            .addKeywordsItem("software engineer")
            .addKeywordsItem("java developer")
            .location("San Francisco, CA")
            .maxResults(5); // Limit to 5 jobs for testing

        // Track how many jobs were scraped
        AtomicInteger jobCount = new AtomicInteger(0);

        // Execute scraping
        scraper.scrape(request, job -> {
            scrapedJobs.add(job);
            jobCount.incrementAndGet();
            System.out.println("Scraped Job #" + jobCount.get() + ":");
            System.out.println("  Title: " + job.getTitle());
            System.out.println("  Company: " + job.getCompany());
            System.out.println("  Location: " + job.getLocation());
            System.out.println("  URL: " + job.getUrl());
            System.out.println("  Description: " + job.getDescription().substring(0, Math.min(100, job.getDescription().length())) + "...");
            System.out.println("---");
        });

        // Assertions
        assertTrue(jobCount.get() > 0, "Should have scraped at least one job");
        assertTrue(jobCount.get() <= 5, "Should not exceed maxResults");
        
        // Verify job properties
        for (Job job : scrapedJobs) {
            assertNotNull(job.getId(), "Job ID should not be null");
            assertNotNull(job.getTitle(), "Job title should not be null");
            assertNotNull(job.getCompany(), "Job company should not be null");
            assertNotNull(job.getUrl(), "Job URL should not be null");
            assertEquals("LinkedIn", job.getSource(), "Job source should be LinkedIn");
            assertTrue(job.getUrl().contains("linkedin.com"), "Job URL should contain linkedin.com");
        }

        System.out.println("\n✅ Successfully scraped " + jobCount.get() + " jobs from LinkedIn!");
    }

    @Test
    void testScrapeWithMultipleKeywords() throws Exception {
        // Test scraping with multiple keywords
        ScrapingJobRequest request = new ScrapingJobRequest()
            .addSourcesItem(ScrapingJobRequest.SourcesEnum.LINKED_IN)
            .addKeywordsItem("python")
            .addKeywordsItem("developer")
            .maxResults(3);

        AtomicInteger jobCount = new AtomicInteger(0);

        scraper.scrape(request, job -> {
            jobCount.incrementAndGet();
            assertNotNull(job.getTitle());
        });

        assertTrue(jobCount.get() > 0, "Should scrape jobs with multiple keywords");
    }

    @Test
    void testScrapeWithLocation() throws Exception {
        // Test scraping with location filter
        ScrapingJobRequest request = new ScrapingJobRequest()
            .addSourcesItem(ScrapingJobRequest.SourcesEnum.LINKED_IN)
            .addKeywordsItem("data scientist")
            .location("New York, NY")
            .maxResults(3);

        AtomicInteger jobCount = new AtomicInteger(0);

        scraper.scrape(request, job -> {
            jobCount.incrementAndGet();
            // Note: Location in job listing might not match exactly due to LinkedIn's format
            assertNotNull(job.getLocation());
        });

        assertTrue(jobCount.get() > 0, "Should scrape jobs with location filter");
    }
}

