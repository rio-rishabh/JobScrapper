package com.jobScrapper.scraper;

import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import com.microsoft.playwright.*;
import java.util.function.Consumer;

/**
 * Base class for all job scrapers that handles common Playwright infrastructure.
 * 
 * Site-specific scrapers only need to implement:
 * - buildSearchURL() - How to build the search URL
 * - getJobListSelector() - CSS selector for job listings
 * - extractJobData() - How to extract data from each job element
 * - handleLoginIfNeeded() - Site-specific login handling (optional)
 */
public abstract class BaseJobScraper implements JobScraper {

    @Override
    public final void scrape(ScrapingJobRequest request, Consumer<Job> sink) throws Exception {
        // Step 1: Create Playwright instance
        Playwright playwright = Playwright.create();
        
        // Step 2: Launch browser instance
        Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(false)  // Set to true for production
            .setSlowMo(200));   // Reduced from 1000ms to 200ms for faster scraping
        
        // Step 3: Create a new page
        Page page = browser.newPage();
        
        try {
            // Step 4: Build the search URL (site-specific)
            String searchURL = buildSearchURL(request);
            
            System.out.println("[" + getSource() + "] Navigating to: " + searchURL);
            
            // Step 5: Navigate to the search URL
            page.navigate(searchURL);
            
            // Step 6: Wait for page to load
            System.out.println("[" + getSource() + "] Waiting for page to load...");
            page.waitForTimeout(3000);
            
            // Step 7: Handle login if needed (site-specific, optional)
            handleLoginIfNeeded(page);
            
            // Step 8: Wait for job listings to appear (site-specific selector)
            System.out.println("[" + getSource() + "] Looking for job listings...");
            String jobListSelector = getJobListSelector();
            page.waitForSelector(jobListSelector, new Page.WaitForSelectorOptions().setTimeout(15000));
            System.out.println("[" + getSource() + "] ✅ Found job listings");
            
            // Step 9: Find all job elements
            Locator jobElements = page.locator(jobListSelector);
            int jobCount = jobElements.count();
            System.out.println("[" + getSource() + "] Found " + jobCount + " job listings");
            
            // Step 10: Determine how many jobs to scrape
            Integer maxResultsInt = request.getMaxResults();
            int maxResults = maxResultsInt != null ? maxResultsInt : 50;
            int jobsToScrape = Math.min(jobCount, maxResults);
            
            // Step 11: Loop through each job listing and extract data
            System.out.println("[" + getSource() + "] Starting to extract " + jobsToScrape + " jobs...");
            for (int i = 0; i < jobsToScrape; i++) {
                try {
                    System.out.println("[" + getSource() + "] Extracting job " + (i + 1) + " of " + jobsToScrape + "...");
                    Locator jobElement = jobElements.nth(i);
                    
                    // Scroll the job element into view to ensure it's loaded
                    jobElement.scrollIntoViewIfNeeded();
                    page.waitForTimeout(200); // Small wait for content to load (reduced from 500ms)
                    
                    Job job = extractJobData(jobElement, page);
                    sink.accept(job);
                    System.out.println("[" + getSource() + "] ✅ Scraped job #" + (i + 1) + ": " + job.getTitle() + " at " + job.getCompany());
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "] ❌ Error scraping job " + (i + 1) + ": " + e.getMessage());
                    // Continue with next job even if this one fails
                }
            }
            
            System.out.println("[" + getSource() + "] ✅ Scraping completed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n[" + getSource() + "] ❌ Error during scraping:");
            System.err.println("   Message: " + e.getMessage());
            System.err.println("   Type: " + e.getClass().getSimpleName());
            e.printStackTrace();
            throw e;
        } finally {
            // Step 12: Cleanup - always close browser and playwright
            System.out.println("[" + getSource() + "] Closing browser...");
            browser.close();
            playwright.close();
            System.out.println("[" + getSource() + "] Browser closed successfully");
        }
    }

    /**
     * Builds the search URL for this specific job board.
     * Each scraper implements this differently based on the site's URL format.
     * 
     * @param request The scraping request with keywords, location, etc.
     * @return The complete search URL
     */
    protected abstract String buildSearchURL(ScrapingJobRequest request);

    /**
     * Returns the CSS selector for finding job listing elements.
     * Each site has different HTML structure, so each scraper provides its own selector.
     * 
     * @return CSS selector string (e.g., "ul.jobs-search__results-list > li")
     */
    protected abstract String getJobListSelector();

    /**
     * Extracts job data from a single job listing element.
     * Each site has different HTML structure, so extraction logic is site-specific.
     * 
     * @param jobElement The Playwright Locator pointing to a single job listing
     * @param page The page object (needed for some operations)
     * @return A Job object with all extracted data
     */
    protected abstract Job extractJobData(Locator jobElement, Page page);

    /**
     * Handles login if the site requires it.
     * Override this method if the site needs special login handling.
     * Default implementation does nothing.
     * 
     * @param page The page object
     */
    protected void handleLoginIfNeeded(Page page) {
        // Default: no login handling needed
        // Override in subclasses if login is required
    }
}

