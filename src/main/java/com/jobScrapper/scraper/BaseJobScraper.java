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
            .setSlowMo(100));   // Reduced from 1000ms to 200ms for faster scraping
        
        // Step 3: Create a new page
        Page page = browser.newPage();
        
        try {
            // Step 4: Build the search URL (site-specific)
            String searchURL = buildSearchURL(request);
            
            System.out.println("[" + getSource() + "] Navigating to: " + searchURL);
            
            // Step 5: Navigate to the search URL
            try {
                page.navigate(searchURL);
            } catch (Exception e) {
                System.err.println("[" + getSource() + "] ⚠️  Navigation error, retrying: " + e.getMessage());
                page.waitForTimeout(2000);
                page.navigate(searchURL); // Retry once
            }
            
            // Step 6: Wait for page to load
            System.out.println("[" + getSource() + "] Waiting for page to load...");
            try {
                page.waitForTimeout(3000);
                // Wait for page to be in a ready state
                page.waitForLoadState();
            } catch (Exception e) {
                System.err.println("[" + getSource() + "] ⚠️  Load state wait error, continuing: " + e.getMessage());
                page.waitForTimeout(3000); // Fallback wait
            }
            
            // Step 7: Handle login if needed (site-specific, optional)
            try {
                handleLoginIfNeeded(page);
            } catch (Exception e) {
                System.err.println("[" + getSource() + "] ⚠️  Error in handleLoginIfNeeded, continuing anyway: " + e.getMessage());
                // Don't throw - continue with scraping
            }
            
            // Step 8: Wait for job listings to appear (site-specific selector)
            System.out.println("[" + getSource() + "] Looking for job listings...");
            String jobListSelector = getJobListSelector();
            System.out.println("[" + getSource() + "] Using selector: " + jobListSelector);
            
            // Try multiple selectors if the main one fails (for sites like Indeed that use comma-separated selectors)
            String[] selectors = jobListSelector.contains(",") ? jobListSelector.split(",\\s*") : new String[]{jobListSelector};
            boolean found = false;
            Exception lastException = null;
            String workingSelector = jobListSelector;
            
            for (String selector : selectors) {
                selector = selector.trim();
                try {
                    System.out.println("[" + getSource() + "] Trying selector: " + selector);
                    page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(20000)); // 20 seconds
                    System.out.println("[" + getSource() + "] ✅ Found job listings with selector: " + selector);
                    found = true;
                    workingSelector = selector; // Use the selector that worked
                    break;
                } catch (Exception e) {
                    lastException = e;
                    System.out.println("[" + getSource() + "] ⚠️  Selector failed: " + selector);
                    // Try next selector
                }
            }
            
            if (!found) {
                System.err.println("[" + getSource() + "] ❌ All selectors failed to find job listings");
                System.err.println("[" + getSource() + "]    Tried selectors: " + String.join(", ", selectors));
                System.err.println("[" + getSource() + "]    Current page URL: " + page.url());
                System.err.println("[" + getSource() + "]    Current page title: " + page.title());
                throw new Exception("Failed to find job listings. The page structure may have changed or verification is required.", lastException);
            }
            
            // Step 9: Find all job elements (use the selector that worked)
            Locator jobElements = page.locator(workingSelector);
            int jobCount = jobElements.count();
            System.out.println("[" + getSource() + "] Found " + jobCount + " job listings using selector: " + workingSelector);
            
            if (jobCount == 0) {
                System.err.println("[" + getSource() + "] ⚠️  WARNING: No job listings found!");
                System.err.println("[" + getSource() + "]    Current page URL: " + page.url());
                System.err.println("[" + getSource() + "]    Current page title: " + page.title());
                System.err.println("[" + getSource() + "]    This might indicate:");
                System.err.println("[" + getSource() + "]      - Login/verification required");
                System.err.println("[" + getSource() + "]      - Page structure changed");
                System.err.println("[" + getSource() + "]      - No jobs match the search criteria");
            }
            
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
                    // Wait longer for dynamic content to load (especially for LinkedIn)
                    page.waitForTimeout(500); // Increased wait for content to load
                    
                    // Wait for the job element to be visible
                    try {
                        jobElement.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                    } catch (Exception e) {
                        // If wait fails, continue anyway
                    }
                    
                    Job job = extractJobData(jobElement, page);
                    // Only add job if it has at least a title or company (to avoid empty jobs)
                    if (job != null && (job.getTitle() != null && !job.getTitle().trim().isEmpty() || 
                        job.getCompany() != null && !job.getCompany().trim().isEmpty())) {
                        sink.accept(job);
                        System.out.println("[" + getSource() + "] ✅ Scraped job #" + (i + 1) + ": " + 
                            (job.getTitle() != null && !job.getTitle().isEmpty() ? job.getTitle() : "Untitled") + 
                            " at " + (job.getCompany() != null && !job.getCompany().isEmpty() ? job.getCompany() : "Unknown"));
                    } else {
                        System.err.println("[" + getSource() + "] ⚠️  Skipped job #" + (i + 1) + " - no valid data extracted");
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "] ❌ Error scraping job " + (i + 1) + ": " + e.getMessage());
                    e.printStackTrace(); // Print full stack trace for debugging
                    // Continue with next job even if this one fails
                }
            }
            
            System.out.println("[" + getSource() + "] ✅ Scraping completed successfully!");
            
            // Note: Browser will stay open - the infinite wait is handled in ScraperMain
            // after all jobs are saved to files
            
        } catch (Exception e) {
            System.err.println("\n[" + getSource() + "] ❌ Error during scraping:");
            System.err.println("   Message: " + e.getMessage());
            System.err.println("   Type: " + e.getClass().getSimpleName());
            e.printStackTrace();
            throw e;
        } finally {
            // Step 12: Keep browsers open - don't close them automatically
            // Browsers will stay open so user can view results
            // They'll be closed when user presses Ctrl+C in ScraperMain
            // This allows jobs to be saved AND browsers to stay open
            System.out.println("[" + getSource() + "] 🔓 Browser will stay open (close manually or press Ctrl+C to stop program)");
            // Note: We don't close browser/playwright here - they stay open for user to view
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

