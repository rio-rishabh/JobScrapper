package com.jobScrapper.scraper;

import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import com.microsoft.playwright.*;
import java.util.function.Consumer;
import java.util.UUID;
import java.time.OffsetDateTime;

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
                // After handling login/verification, wait a bit more and check if we're on the right page
                page.waitForTimeout(2000);
                String currentTitle = page.title().toLowerCase();
                String currentUrl = page.url().toLowerCase();
                
                // If still on verification/challenge page, log warning
                if (currentTitle.contains("just a moment") || 
                    currentTitle.contains("checking your browser") ||
                    currentTitle.contains("please wait") ||
                    currentUrl.contains("challenge") ||
                    currentUrl.contains("verify")) {
                    System.err.println("[" + getSource() + "] ⚠️  WARNING: Still appears to be on verification page");
                    System.err.println("[" + getSource() + "]    URL: " + page.url());
                    System.err.println("[" + getSource() + "]    Title: " + page.title());
                    System.err.println("[" + getSource() + "]    Job search may fail - please verify manually in browser");
                }
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
                    
                    Job job = null;
                    try {
                        job = extractJobData(jobElement, page);
                    } catch (Exception extractError) {
                        System.err.println("[" + getSource() + "] ❌ Error in extractJobData for job #" + (i + 1) + ": " + extractError.getMessage());
                        extractError.printStackTrace();
                        // Try to create a minimal job object anyway
                        try {
                            job = new Job()
                                .id(UUID.randomUUID().toString())
                                .source(getSource())
                                .title("Extraction Failed - Job #" + (i + 1))
                                .company("Unknown")
                                .location("Not Specified")
                                .url(page.url())
                                .description("Error extracting job data: " + extractError.getMessage())
                                .postedDate(OffsetDateTime.now());
                        } catch (Exception e2) {
                            System.err.println("[" + getSource() + "] ❌ Failed to create fallback job object: " + e2.getMessage());
                        }
                    }
                    
                    // Accept job if it exists (even if some fields are empty)
                    if (job != null) {
                        // Log what we got
                        String title = job.getTitle() != null ? job.getTitle() : "NO TITLE";
                        String company = job.getCompany() != null ? job.getCompany() : "NO COMPANY";
                        System.out.println("[" + getSource() + "] 📝 Extracted job #" + (i + 1) + ": title='" + title + "', company='" + company + "'");
                        
                        // Enrich job with description from detail page if needed
                        String currentDescription = job.getDescription();
                        if (currentDescription == null || currentDescription.isEmpty() || 
                            currentDescription.equals("No description available") || currentDescription.length() < 100) {
                            
                            String jobURL = job.getUrl();
                            if (jobURL != null && !jobURL.isEmpty()) {
                                try {
                                    String enrichedDescription = extractDescriptionFromDetailPage(page, jobURL);
                                    if (enrichedDescription != null && !enrichedDescription.isEmpty() && 
                                        !enrichedDescription.equals("No description available") && enrichedDescription.length() > 20) {
                                        job.description(enrichedDescription);
                                        System.out.println("[" + getSource() + "] ✅ Enriched description (" + enrichedDescription.length() + " chars)");
                                    }
                                } catch (Exception e) {
                                    System.err.println("[" + getSource() + "] ⚠️  Error enriching description: " + e.getMessage());
                                }
                            }
                        }
                        
                        sink.accept(job);
                        System.out.println("[" + getSource() + "] ✅ Scraped job #" + (i + 1) + ": " + 
                            (!title.equals("NO TITLE") ? title : "Untitled") + 
                            " at " + (!company.equals("NO COMPANY") ? company : "Unknown"));
                    } else {
                        System.err.println("[" + getSource() + "] ⚠️  Skipped job #" + (i + 1) + " - extractJobData returned null");
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
    
    /**
     * Returns CSS selectors for finding job descriptions on detail pages.
     * Each scraper provides site-specific selectors.
     * 
     * @return Array of CSS selectors to try (in order of preference)
     */
    protected String[] getDescriptionSelectors() {
        // Default: return empty array - scrapers can override
        return new String[0];
    }
    
    /**
     * Returns selectors for "Show more" buttons on detail pages.
     * Each scraper can provide site-specific selectors.
     * 
     * @return Array of CSS selectors to try for "Show more" buttons
     */
    protected String[] getShowMoreButtonSelectors() {
        // Default selectors that work for many sites
        return new String[]{
            "button:has-text('Show more')",
            "button:has-text('see more')",
            "button:has-text('See more')",
            "span:has-text('Show more')",
            "button[aria-label*='more']"
        };
    }
    
    /**
     * Extracts full job description by navigating to the job detail page.
     * This is a common pattern that works for most job sites.
     * 
     * @param page The Playwright Page object
     * @param jobURL The URL of the job detail page
     * @return The extracted description, or "No description available" if not found
     */
    protected String extractDescriptionFromDetailPage(Page page, String jobURL) {
        if (jobURL == null || jobURL.isEmpty()) {
            return "No description available";
        }
        
        // Get selectors for this scraper
        String[] descriptionSelectors = getDescriptionSelectors();
        if (descriptionSelectors.length == 0) {
            // If no selectors provided, this scraper doesn't support detail page extraction
            return "No description available";
        }
        
        String originalUrl = null;
        try {
            System.out.println("[" + getSource() + "] 📄 Navigating to job detail page to extract full description...");
            System.out.println("[" + getSource() + "]    URL: " + jobURL);

            // Save current URL to navigate back later
            originalUrl = page.url();
            
            // Navigate to job detail page in the same page
            page.navigate(jobURL);
            page.waitForLoadState();
            page.waitForTimeout(5000); // Wait for initial load
            
            // Check page state
            String pageTitle = page.title();
            String pageUrl = page.url();
            System.out.println("[" + getSource() + "]    Page loaded - Title: " + pageTitle);
            System.out.println("[" + getSource() + "]    Page loaded - URL: " + pageUrl);
            
            // Check if login is required
            String pageTitleLower = pageTitle.toLowerCase();
            String pageUrlLower = pageUrl.toLowerCase();
            if (pageTitleLower.contains("sign in") || pageTitleLower.contains("login") || 
                pageUrlLower.contains("challenge") || pageUrlLower.contains("authwall") ||
                pageUrlLower.contains("login") || pageUrlLower.contains("checkpoint")) {
                System.err.println("[" + getSource() + "] ⚠️  Login required to view job description");
                System.err.println("[" + getSource() + "]    Page redirected to: " + pageUrl);
                System.err.println("[" + getSource() + "]    Please log in to " + getSource() + " in the main browser window");
                // Navigate back to search results
                page.navigate(originalUrl);
                page.waitForLoadState();
                return "No description available";
            } else if (pageTitleLower.contains("job") && !pageUrlLower.contains("view") && !pageUrlLower.contains("jobs")) {
                // Check if we got redirected away from job page (but allow job listing pages)
                System.err.println("[" + getSource() + "] ⚠️  Unexpected redirect - not on job detail page");
                System.err.println("[" + getSource() + "]    Current URL: " + pageUrl);
                // Navigate back to search results
                page.navigate(originalUrl);
                page.waitForLoadState();
                return "No description available";
            } else {
                // Wait more for dynamic content to load
                page.waitForTimeout(3000);
                
                // Try to find and click "Show more" button if it exists
                try {
                    String[] showMoreSelectors = getShowMoreButtonSelectors();
                    for (String btnSelector : showMoreSelectors) {
                        try {
                            Locator showMoreButton = page.locator(btnSelector).first();
                            if (showMoreButton.count() > 0) {
                                try {
                                    showMoreButton.waitFor(new Locator.WaitForOptions().setTimeout(2000));
                                    if (showMoreButton.isVisible()) {
                                        showMoreButton.click();
                                        System.out.println("[" + getSource() + "]    Clicked 'Show more' button");
                                        page.waitForTimeout(2000);
                                        break;
                                    }
                                } catch (Exception e) {
                                    // Try next selector
                                }
                            }
                        } catch (Exception e) {
                            continue;
                        }
                    }
                } catch (Exception e) {
                    // Ignore if show more button doesn't exist
                }

                // Try all description selectors
                boolean found = false;
                String description = "No description available";
                
                for(String selector : descriptionSelectors){
                    try{
                        System.out.println("[" + getSource() + "]    Trying selector: " + selector);
                        Locator descriptionLocator = page.locator(selector).first();
                        int count = descriptionLocator.count();
                        System.out.println("[" + getSource() + "]    Found " + count + " elements with selector: " + selector);
                        
                        if(count > 0){
                            // Wait for element to be visible
                            try {
                                descriptionLocator.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                            } catch (Exception e) {
                                System.out.println("[" + getSource() + "]    Wait timeout, continuing anyway");
                            }
                            
                            // Try textContent first
                            String descText = descriptionLocator.textContent();
                            if(descText != null && !descText.trim().isEmpty() && descText.trim().length() > 20){
                                description = descText.trim();
                                System.out.println("[" + getSource() + "] ✅ Found description (" + description.length() + " chars) using selector: " + selector);
                                found = true;
                                break;
                            } else {
                                // Try innerHTML as fallback
                                try {
                                    String innerHtml = descriptionLocator.innerHTML();
                                    if (innerHtml != null && innerHtml.length() > 50) {
                                        // Extract text from HTML
                                        descText = innerHtml.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                                        if (descText.length() > 20) {
                                            description = descText;
                                            System.out.println("[" + getSource() + "] ✅ Found description from innerHTML (" + description.length() + " chars) using selector: " + selector);
                                            found = true;
                                            break;
                                        }
                                    }
                                } catch (Exception e) {
                                    // Continue to next selector
                                }
                            }
                        }
                    } catch(Exception e){
                        System.out.println("[" + getSource() + "]    Error with selector " + selector + ": " + e.getMessage());
                        continue;
                    }
                }
                
                if (!found) {
                    System.err.println("[" + getSource() + "] ⚠️  Could not find description with any selector");
                    System.err.println("[" + getSource() + "]    Page title: " + pageTitle);
                    System.err.println("[" + getSource() + "]    Page URL: " + pageUrl);
                    
                    // Last resort: Try to get any text content from the page
                    try {
                        String pageText = page.locator("body").textContent();
                        if (pageText != null && pageText.length() > 100) {
                            // Extract a reasonable portion (look for job description keywords)
                            int startIdx = pageText.toLowerCase().indexOf("about the job");
                            if (startIdx == -1) startIdx = pageText.toLowerCase().indexOf("job description");
                            if (startIdx == -1) startIdx = pageText.toLowerCase().indexOf("responsibilities");
                            if (startIdx == -1) startIdx = 0;
                            
                            int endIdx = Math.min(startIdx + 3000, pageText.length());
                            description = pageText.substring(startIdx, endIdx).trim();
                            System.out.println("[" + getSource() + "] ⚠️  Using page text as fallback (" + description.length() + " chars)");
                        }
                    } catch (Exception e) {
                        System.err.println("[" + getSource() + "]    Error extracting page text: " + e.getMessage());
                    }
                }
                
                // Navigate back to search results page
                System.out.println("[" + getSource() + "]    Navigating back to search results...");
                page.navigate(originalUrl);
                page.waitForLoadState();
                page.waitForTimeout(2000); // Wait for search results to reload
                
                return description;
            }

        } catch (Exception e){
            System.err.println("[" + getSource() + "] ❌ Error extracting description from URL: " + jobURL);
            System.err.println("[" + getSource() + "]    Error: " + e.getMessage());
            // Try to navigate back if we're still on the detail page
            try {
                if (originalUrl != null) {
                    String currentUrl = page.url();
                    // Only navigate back if we're not already on the search results page
                    if (currentUrl != null && !currentUrl.contains("search") && !currentUrl.equals(originalUrl)) {
                        page.navigate(originalUrl);
                        page.waitForLoadState();
                    }
                }
            } catch (Exception e2) {
                // Ignore navigation errors
            }
            return "No description available";
        }
    }
}

