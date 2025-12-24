package com.jobScrapper.scraper;

import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import com.microsoft.playwright.*;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.Random;
import java.time.OffsetDateTime;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;

/**
 * Base class for all job scrapers that handles common Playwright infrastructure.
 * 
 * Site-specific scrapers only need to implement:
 * - buildSearchURL() - How to build the search URL
 * - getJobListSelector() - CSS selector for job listings
 * - extractJobData() - How to extract data from each job element
 * - handleLoginIfNeeded() - Site-specific login handling (optional)
 * 
 * ANTI-CLOUDFLARE FEATURES:
 * - Persistent browser profile to maintain cookies/sessions
 * - Realistic browser fingerprinting
 * - Human-like delays and scrolling
 * - Automatic session management
 */
public abstract class BaseJobScraper implements JobScraper {

    // Random for human-like delays
    private static final Random random = new Random();
    
    // Session persistence directory (fallback if Chrome profile doesn't work)
    private static final String BROWSER_DATA_DIR = System.getProperty("user.home") + "/.jobscrapper/browser_data";
    
    // Chrome remote debugging port - connect to user's already-running Chrome
    private static final int CHROME_DEBUG_PORT = 9222;
    
    // Use the REAL Chrome browser profile - this is key to bypass Cloudflare!
    // Chrome stores profiles here on Mac
    private static final String CHROME_USER_DATA_DIR = System.getProperty("user.home") + "/Library/Application Support/Google/Chrome";
    private static final String CHROME_PROFILE = "Default";  // Or "Profile 1", "Profile 2", etc.
    
    /**
     * Adds a human-like random delay between actions
     * @param minMs minimum delay in milliseconds
     * @param maxMs maximum delay in milliseconds
     */
    protected void humanDelay(Page page, int minMs, int maxMs) {
        int delay = minMs + random.nextInt(maxMs - minMs);
        page.waitForTimeout(delay);
    }
    
    /**
     * Scrolls page down to load more content (for infinite scroll pages)
     * @param page The page to scroll
     * @param scrollCount Number of times to scroll
     * @param waitBetweenScrolls Wait time between scrolls in ms
     */
    protected void scrollToLoadMore(Page page, int scrollCount, int waitBetweenScrolls) {
        System.out.println("[" + getSource() + "] 📜 Scrolling to load more jobs (" + scrollCount + " scrolls)...");
        for (int i = 0; i < scrollCount; i++) {
            page.evaluate("window.scrollBy(0, window.innerHeight * 0.8)");
            humanDelay(page, waitBetweenScrolls, waitBetweenScrolls + 1000);
            
            // Check for "Load more" buttons and click them
            try {
                Locator loadMoreBtn = page.locator("button:has-text('Load more'), button:has-text('Show more jobs'), a:has-text('Load more'), button[class*='load-more']").first();
                if (loadMoreBtn.count() > 0 && loadMoreBtn.isVisible()) {
                    System.out.println("[" + getSource() + "]    Found 'Load more' button, clicking...");
                    loadMoreBtn.click();
                    humanDelay(page, 2000, 4000);
                }
            } catch (Exception e) {
                // No load more button, continue scrolling
            }
        }
        
        // Scroll back to top
        page.evaluate("window.scrollTo(0, 0)");
        humanDelay(page, 1000, 2000);
    }
    
    /**
     * Gets the persistent browser data directory for a specific source
     */
    protected Path getBrowserDataPath() {
        return Paths.get(BROWSER_DATA_DIR, getSource().toLowerCase());
    }

    @Override
    public final void scrape(ScrapingJobRequest request, Consumer<Job> sink) throws Exception {
        // Step 1: Create Playwright instance
        Playwright playwright = Playwright.create();
        
        // ═══════════════════════════════════════════════════════════════════════════
        // USING CHROMIUM in INCOGNITO MODE - Fresh session every time
        // No cookies, no cache, no stored data - completely clean slate
        // ═══════════════════════════════════════════════════════════════════════════
        
        Path browserDataPath = getBrowserDataPath();
        BrowserContext context = null;
        Browser browser = null;
        boolean connectedToExisting = false;
        
        System.out.println("[" + getSource() + "] 🌐 Using CHROMIUM browser in INCOGNITO MODE");
        System.out.println("[" + getSource() + "]    ✨ Fresh session - no cookies, no cache!");
        System.out.println("[" + getSource() + "]    🔒 Private browsing enabled\n");
        
        // Step 2: Clear any existing browser cache/data for Indeed and Glassdoor
        String sourceLower = getSource().toLowerCase();
        if (sourceLower.equals("indeed") || sourceLower.equals("glassdoor")) {
            try {
                if (Files.exists(browserDataPath)) {
                    System.out.println("[" + getSource() + "] 🗑️  Clearing browser cache...");
                    // Delete all files in the browser data directory
                    Files.walk(browserDataPath)
                        .sorted((a, b) -> b.compareTo(a)) // Reverse order to delete files before directories
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception e) {
                                // Ignore deletion errors
                            }
                        });
                    System.out.println("[" + getSource() + "]    ✅ Cache cleared!");
                }
            } catch (Exception e) {
                System.out.println("[" + getSource() + "]    ⚠️ Could not clear cache: " + e.getMessage());
            }
        }
        
        // Launch Chromium with human-like settings
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions()
                .setHeadless(false)
                .setSlowMo(100)  // Human-like delays
        );
        
        // Create a FRESH context (like incognito mode) - no persistent storage
        // Each run starts completely clean with no cookies or cached data
        context = browser.newContext(
            new Browser.NewContextOptions()
                .setViewportSize(1440, 900)  // Common MacBook screen size
                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .setLocale("en-US")
                .setTimezoneId("America/New_York")
                .setColorScheme(com.microsoft.playwright.options.ColorScheme.LIGHT)
                .setDeviceScaleFactor(2.0)  // Retina display
                .setHasTouch(false)
                .setJavaScriptEnabled(true)
                .setIgnoreHTTPSErrors(true)
                // These options ensure truly private/incognito behavior:
                .setAcceptDownloads(false)
                .setBypassCSP(false)
        );
        
        System.out.println("[" + getSource() + "] ✅ Chromium INCOGNITO browser launched!");

        // Adding Extra headers to replicate real browser behavior
        context.setExtraHTTPHeaders(java.util.Map.of(
            "Accept-Language", "en-US,en;q=0.9",
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Encoding", "gzip, deflate, br",
            "Connection", "keep-alive",
            "Upgrade-Insecure-Requests", "1",
            "Sec-Fetch-Dest", "document",
            "Sec-Fetch-Mode", "navigate",
            "Sec-Fetch-Site", "none",
            "Sec-Fetch-User", "?1"
        ));
        
        // Step 3: Create a new page in the context
        Page page = context.newPage();
        System.out.println("[" + getSource() + "] 📄 Created new browser tab");
        
        // ENHANCED JavaScript injection for Cloudflare bypass
        // SKIP this if connected to existing Chrome (it's already a real browser!)
        if (!connectedToExisting) {
            page.addInitScript("""
            // Override webdriver property (MOST IMPORTANT for Cloudflare)
            Object.defineProperty(navigator, 'webdriver', {
                get: () => undefined,
                configurable: true
            });
            
            // Delete webdriver property
            delete navigator.__proto__.webdriver;
            
            // Override plugins to look like a real browser
            Object.defineProperty(navigator, 'plugins', {
                get: () => {
                    const plugins = [
                        { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                        { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
                        { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
                    ];
                    plugins.item = (i) => plugins[i];
                    plugins.namedItem = (name) => plugins.find(p => p.name === name);
                    plugins.refresh = () => {};
                    return plugins;
                },
                configurable: true
            });
            
            // Override languages
            Object.defineProperty(navigator, 'languages', {
                get: () => ['en-US', 'en'],
                configurable: true
            });
            
            // Make chrome object look real
            window.chrome = {
                runtime: {
                    connect: () => {},
                    sendMessage: () => {},
                    onMessage: { addListener: () => {} }
                },
                loadTimes: () => {},
                csi: () => {},
                app: {}
            };
            
            // Override permissions query
            const originalQuery = window.navigator.permissions.query;
            window.navigator.permissions.query = (parameters) => (
                parameters.name === 'notifications' ?
                    Promise.resolve({ state: Notification.permission }) :
                    originalQuery(parameters)
            );
            
            // Hide automation indicators in the navigator
            Object.defineProperty(navigator, 'maxTouchPoints', {
                get: () => 0,
                configurable: true
            });
            
            // Override hardware concurrency (number of CPU cores)
            Object.defineProperty(navigator, 'hardwareConcurrency', {
                get: () => 8,
                configurable: true
            });
            
            // Override device memory
            Object.defineProperty(navigator, 'deviceMemory', {
                get: () => 8,
                configurable: true
            });
            
            // Override connection (for more realistic fingerprint)
            Object.defineProperty(navigator, 'connection', {
                get: () => ({
                    effectiveType: '4g',
                    rtt: 50,
                    downlink: 10,
                    saveData: false
                }),
                configurable: true
            });
            
            // Prevent detection via iframe
            Object.defineProperty(HTMLIFrameElement.prototype, 'contentWindow', {
                get: function() {
                    return null;
                }
            });
            
            // Override the toString methods to hide modifications
            const originalToString = Function.prototype.toString;
            Function.prototype.toString = function() {
                if (this === navigator.permissions.query) {
                    return 'function query() { [native code] }';
                }
                return originalToString.call(this);
            };
            
            // Remove Playwright-specific properties
            delete window.__playwright;
            delete window.__pw_manual;
        """);
        }  // End of if (!connectedToExisting)
        
        try {
            // Step 4: Build the search URL (site-specific)
            String searchURL = buildSearchURL(request);
            
            System.out.println("[" + getSource() + "] Navigating to: " + searchURL);
            
            // Step 5: Navigate to the search URL with human-like behavior
            try {
                // Add a small random delay before navigation (like a human would)
                humanDelay(page, 500, 1500);
                page.navigate(searchURL);
            } catch (Exception e) {
                System.err.println("[" + getSource() + "] ⚠️  Navigation error, retrying: " + e.getMessage());
                humanDelay(page, 2000, 4000);  // Human-like retry delay
                page.navigate(searchURL); // Retry once
            }
            
            // Step 6: Wait for page to load with human-like behavior
            System.out.println("[" + getSource() + "] Waiting for page to load...");
            try {
                humanDelay(page, 3000, 5000);  // Variable wait like a human
                // Wait for page to be in a ready state
                page.waitForLoadState();
            } catch (Exception e) {
                System.err.println("[" + getSource() + "] ⚠️  Load state wait error, continuing: " + e.getMessage());
                humanDelay(page, 3000, 5000); // Fallback wait
            }
            
            // Step 7: Handle login if needed (site-specific, optional)
            try {
                handleLoginIfNeeded(page);
                
                // After handling login/verification, wait longer and verify we're past Cloudflare
                page.waitForTimeout(5000);
                
                // Check multiple times if we're still on verification page
                boolean stillOnVerification = false;
                for (int check = 0; check < 15; check++) {
                    String currentTitle = page.title().toLowerCase();
                    String currentUrl = page.url().toLowerCase();
                    String pageContent = "";
                    try {
                        pageContent = page.content().toLowerCase();
                    } catch (Exception e) {
                        // Continue
                    }
                    
                    // Comprehensive Cloudflare detection
                    stillOnVerification = 
                        currentTitle.contains("just a moment") || 
                        currentTitle.contains("checking your browser") ||
                        currentTitle.contains("please wait") ||
                        currentTitle.contains("verification required") ||
                        currentUrl.contains("challenge") ||
                        currentUrl.contains("verify") ||
                        currentUrl.contains("cf-") ||
                        pageContent.contains("additional verification required") ||
                        pageContent.contains("help us protect") ||
                        pageContent.contains("verify you are human") ||
                        pageContent.contains("cloudflare") ||
                        pageContent.contains("ray id") ||
                        page.locator("text=/Additional Verification Required/i").count() > 0 ||
                        page.locator("text=/Help Us Protect/i").count() > 0 ||
                        page.locator("text=/Please unblock challenges.cloudflare.com/i").count() > 0;
                    
                    if (stillOnVerification) {
                        if (check == 0) {
                            System.err.println("\n[" + getSource() + "] ⚠️  ⚠️  CLOUDFLARE VERIFICATION DETECTED ⚠️  ⚠️");
                            System.err.println("[" + getSource() + "]    URL: " + page.url());
                            System.err.println("[" + getSource() + "]    Title: " + page.title());
                            System.err.println("[" + getSource() + "]    Please complete the Cloudflare challenge manually in the browser window!");
                            System.err.println("[" + getSource() + "]    Waiting up to 90 seconds for you to complete verification...\n");
                        }
                        
                        if (check % 5 == 0 && check > 0) {
                            System.out.println("[" + getSource() + "]    Still waiting... (" + (90 - check * 3) + " seconds remaining)");
                        }
                        
                        page.waitForTimeout(3000); // Wait 3 seconds between checks
                    } else {
                        System.out.println("[" + getSource() + "] ✅ Passed Cloudflare verification page!");
                        break;
                    }
                }
                
                if (stillOnVerification) {
                    System.err.println("\n[" + getSource() + "] ⚠️  WARNING: Still on Cloudflare verification page after 90 seconds!");
                    System.err.println("[" + getSource() + "]    The scraper will attempt to continue, but may fail to find job listings.");
                    System.err.println("[" + getSource() + "]    Please complete verification in the browser and restart the scraper.\n");
                }
            } catch (Exception e) {
                System.err.println("[" + getSource() + "] ⚠️  Error in handleLoginIfNeeded, continuing anyway: " + e.getMessage());
                // Don't throw - continue with scraping
            }
            
            // Step 8: Wait for job listings to appear (site-specific selector)
            System.out.println("[" + getSource() + "] Looking for job listings...");
            String jobListSelector = getJobListSelector();
            System.out.println("[" + getSource() + "] Using selector: " + jobListSelector);
            
            // Final check: Make sure we're not still on Cloudflare page
            String finalCheckTitle = page.title().toLowerCase();
            String finalCheckUrl = page.url().toLowerCase();
            if (finalCheckTitle.contains("just a moment") || 
                finalCheckTitle.contains("verification required") ||
                finalCheckUrl.contains("challenge") ||
                page.locator("text=/Additional Verification Required/i").count() > 0) {
                System.err.println("[" + getSource() + "] ⚠️  Still on Cloudflare page! Waiting 30 more seconds...");
                System.err.println("[" + getSource() + "]    Please complete verification in the browser window!");
                page.waitForTimeout(30000);
            }
            
            // Try multiple selectors if the main one fails (for sites like Indeed that use comma-separated selectors)
            String[] selectors = jobListSelector.contains(",") ? jobListSelector.split(",\\s*") : new String[]{jobListSelector};
            boolean found = false;
            Exception lastException = null;
            String workingSelector = jobListSelector;
            
            // Increase timeout and add retry logic
            int maxRetries = 3;
            for (int retry = 0; retry < maxRetries && !found; retry++) {
                if (retry > 0) {
                    System.out.println("[" + getSource() + "] Retry attempt " + (retry + 1) + "/" + maxRetries);
                    page.waitForTimeout(5000);
                    
                    // Check if we're still on verification page
                    String retryTitle = page.title().toLowerCase();
                    String retryUrl = page.url().toLowerCase();
                    if (retryTitle.contains("just a moment") || retryUrl.contains("challenge")) {
                        System.out.println("[" + getSource() + "] ⚠️  Still on verification page, waiting 15 more seconds...");
                        page.waitForTimeout(15000);
                    }
                }
                
                for (String selector : selectors) {
                    selector = selector.trim();
                    try {
                        System.out.println("[" + getSource() + "] Trying selector: " + selector);
                        // Increase timeout to 60 seconds
                        page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(60000));
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
            }
            
            if (!found) {
                System.err.println("\n[" + getSource() + "] ❌ All selectors failed to find job listings after " + maxRetries + " retries");
                System.err.println("[" + getSource() + "]    Tried selectors: " + String.join(", ", selectors));
                System.err.println("[" + getSource() + "]    Current page URL: " + page.url());
                System.err.println("[" + getSource() + "]    Current page title: " + page.title());
                
                // Check if we're on Cloudflare page
                String errorTitle = page.title().toLowerCase();
                String errorUrl = page.url().toLowerCase();
                if (errorTitle.contains("just a moment") || errorTitle.contains("verification") || errorUrl.contains("challenge")) {
                    System.err.println("[" + getSource() + "]    ⚠️  CLOUDFLARE IS BLOCKING ACCESS!");
                    System.err.println("[" + getSource() + "]    Please complete the Cloudflare challenge in the browser window");
                    System.err.println("[" + getSource() + "]    Then restart the scraper\n");
                }
                
                throw new Exception("Failed to find job listings. Cloudflare verification may be required.", lastException);
            }
            
            // Step 8.5: SCROLL TO LOAD MORE JOBS
            // This is critical for Indeed and Glassdoor which use infinite scroll
            int scrollsNeeded = getScrollCount(request);
            if (scrollsNeeded > 0) {
                scrollToLoadMore(page, scrollsNeeded, 1500);
            }
            
            // Step 9: Find all job elements (use the selector that worked)
            Locator jobElements = page.locator(workingSelector);
            int jobCount = jobElements.count();
            System.out.println("[" + getSource() + "] Found " + jobCount + " job listings using selector: " + workingSelector);
            
            // If we only found 1-2 jobs, try scrolling more
            if (jobCount < 5) {
                System.out.println("[" + getSource() + "] ⚠️  Only found " + jobCount + " jobs, trying additional scrolling...");
                scrollToLoadMore(page, 5, 2000);  // More aggressive scrolling
                jobElements = page.locator(workingSelector);
                jobCount = jobElements.count();
                System.out.println("[" + getSource() + "] After additional scrolling: Found " + jobCount + " job listings");
            }
            
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
     * Returns how many times to scroll to load more jobs.
     * Override in subclasses for sites with infinite scroll (Indeed, Glassdoor).
     * 
     * @param request The scraping request (can use maxResults to determine scrolls needed)
     * @return Number of scroll operations to perform
     */
    protected int getScrollCount(ScrapingJobRequest request) {
        // Default: calculate based on maxResults (roughly 10 jobs per scroll)
        Integer maxResults = request.getMaxResults();
        int requested = maxResults != null ? maxResults : 25;
        return Math.max(3, requested / 10);  // Minimum 3 scrolls
    }
    
    /**
     * Returns a random realistic user agent string.
     * Rotating user agents helps avoid fingerprinting.
     */
    protected String getRandomUserAgent() {
        String[] userAgents = {
            // Chrome on Mac
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            // Chrome on Windows
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            // Firefox on Mac
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:121.0) Gecko/20100101 Firefox/121.0",
            // Safari on Mac
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15",
            // Edge on Windows
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"
        };
        return userAgents[random.nextInt(userAgents.length)];
    }
    
    /**
     * Should the scraper skip navigating to detail pages?
     * Override to return true when Cloudflare is active.
     */
    protected boolean shouldSkipDetailPages() {
        return false;  // Default: don't skip
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

