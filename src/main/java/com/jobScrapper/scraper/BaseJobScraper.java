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
    
    /**
     * Injects cookies into the browser context if available in config.json
     * This allows cookie-based authentication (much less detectable than username/password)
     * Called BEFORE creating pages so cookies are available immediately
     */
    protected void injectCookiesIfAvailable(BrowserContext context) {
        try {
            // Try to load cookie from config.json
            java.io.File configFile = new java.io.File("config.json");
            if (!configFile.exists()) {
                return; // No config file, skip cookie injection
            }
            
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.io.FileReader reader = new java.io.FileReader(configFile);
            java.util.Map<String, String> config = gson.fromJson(reader, 
                new com.google.gson.reflect.TypeToken<java.util.Map<String, String>>(){}.getType());
            reader.close();
            
            // Check for LinkedIn li_at cookie (most common)
            String liAtCookie = config.get("LINKEDIN_LI_AT_COOKIE");
            if (liAtCookie != null && !liAtCookie.isEmpty() && getSource().equals("LinkedIn")) {
                System.out.println("[" + getSource() + "] 🍪 Found LINKEDIN_LI_AT_COOKIE in config.json");
                System.out.println("[" + getSource() + "]    Injecting cookie for authentication (no login needed!)");
                
                // Create cookie object for LinkedIn using builder pattern
                // Playwright Java API: Cookie(name, value) constructor
                com.microsoft.playwright.options.Cookie cookie = new com.microsoft.playwright.options.Cookie(
                    "li_at",    // name
                    liAtCookie  // value
                );
                
                // Set cookie properties using setters
                cookie.setDomain(".linkedin.com");
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                cookie.setSecure(true);
                cookie.setSameSite(com.microsoft.playwright.options.SameSiteAttribute.NONE);
                
                // Add cookie to context
                context.addCookies(java.util.Arrays.asList(cookie));
                System.out.println("[" + getSource() + "]    ✅ Cookie injected successfully!");
                System.out.println("[" + getSource() + "]    🎯 Using cookie authentication (80%+ less detection)");
                return;
            }
            
            // Could add other cookie types here for other sites
            // For example: INDEED_COOKIE, GLASSDOOR_COOKIE, etc.
            
        } catch (Exception e) {
            // Silently fail - cookie injection is optional
            // If it fails, we'll fall back to username/password login
            System.err.println("[" + getSource() + "] ⚠️  Could not inject cookies: " + e.getMessage());
        }
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
        boolean usePersistentContext = false;
        
        // Check if this scraper needs persistent context (like LinkedIn)
        String sourceLower = getSource().toLowerCase();
        if (sourceLower.equals("linkedin")) {
            usePersistentContext = true;
            System.out.println("[" + getSource() + "] 🌐 Using CHROMIUM browser with PERSISTENT PROFILE");
            System.out.println("[" + getSource() + "]    💾 Profile saved at: " + browserDataPath);
            System.out.println("[" + getSource() + "]    🔑 Cookies and login will be remembered\n");
        } else {
            System.out.println("[" + getSource() + "] 🌐 Using CHROMIUM browser in INCOGNITO MODE");
            System.out.println("[" + getSource() + "]    ✨ Fresh session - no cookies, no cache!");
            System.out.println("[" + getSource() + "]    🔒 Private browsing enabled\n");
        }
        
        // Step 2: Clear any existing browser cache/data for Indeed and Glassdoor
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
        // Try to use real Chrome if available (less detectable than Chromium)
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
            .setHeadless(false)
            .setSlowMo(100);  // Human-like delays
        
        // Try to use real Chrome executable (more realistic, less detectable)
        String chromePath = System.getenv("CHROME_PATH");
        if (chromePath == null || chromePath.isEmpty()) {
            // Default Chrome paths on macOS
            String[] possiblePaths = {
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium"
            };
            for (String path : possiblePaths) {
                java.io.File chromeFile = new java.io.File(path);
                if (chromeFile.exists()) {
                    chromePath = path;
                    System.out.println("[" + getSource() + "] 🔍 Found Chrome at: " + chromePath);
                    break;
                }
            }
        }
        
        if (chromePath != null && !chromePath.isEmpty()) {
            launchOptions.setExecutablePath(java.nio.file.Paths.get(chromePath));
            System.out.println("[" + getSource() + "] ✅ Using real Chrome browser (less detectable)");
        } else {
            System.out.println("[" + getSource() + "] ⚠️  Using Chromium (Chrome not found, may be more detectable)");
        }
        
        // For persistent context (LinkedIn), we need to set executable path in LaunchPersistentContextOptions
        // For regular context, we can use the browser launch
        if (usePersistentContext) {
            // Use launchPersistentContext for LinkedIn to save cookies
            System.out.println("[" + getSource() + "] 🔄 Creating persistent browser context with REAL Chrome...");
            
            // Create LaunchPersistentContextOptions with Chrome executable
            BrowserType.LaunchPersistentContextOptions persistentOptions = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(false)
                    .setSlowMo(100)
                    .setViewportSize(1440, 900)
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .setLocale("en-US")
            .setTimezoneId("America/New_York")
                    .setColorScheme(com.microsoft.playwright.options.ColorScheme.LIGHT)
                    .setDeviceScaleFactor(2.0)
                    .setHasTouch(false)
                    .setJavaScriptEnabled(true)
                    .setIgnoreHTTPSErrors(true)
                    .setAcceptDownloads(false);
            
            // CRITICAL: Set Chrome executable path for persistent context too!
            if (chromePath != null && !chromePath.isEmpty()) {
                persistentOptions.setExecutablePath(java.nio.file.Paths.get(chromePath));
                System.out.println("[" + getSource() + "] ✅ Using REAL Chrome browser for persistent context (much less detectable!)");
            } else {
                System.out.println("[" + getSource() + "] ⚠️  Using Chromium for persistent context (Chrome not found)");
            }
            
            // Add args to make browser behave like a REAL user browser
            // Without these, LinkedIn detects automation and shows reCAPTCHA
            persistentOptions.setArgs(java.util.Arrays.asList(
                        "--disable-blink-features=AutomationControlled",  // Hide automation (MOST IMPORTANT)
                        "--exclude-switches=enable-automation",            // Remove automation flag
                        "--disable-dev-shm-usage",                         // Prevent crashes
                        "--no-sandbox",                                    // Already in Playwright default
                        "--disable-setuid-sandbox",                        // Better resource loading
                        "--disable-infobars",                              // Remove automation info banner
                        "--window-position=0,0",                           // Consistent window
                        "--ignore-certificate-errors",                     // Handle SSL
                        "--ignore-certificate-errors-spki-list",           // Handle SSL
                        "--disable-gpu",                                   // Headless GPU issues
                        "--no-first-run",                                  // Skip first run
                        "--no-default-browser-check",                      // Skip browser check
                        "--disable-background-timer-throttling",           // Better performance
                        "--disable-backgrounding-occluded-windows",         // Better performance
                        "--disable-renderer-backgrounding"                 // Better performance
                    ));
            
            // Grant permissions to look like a real user
            persistentOptions.setPermissions(java.util.Arrays.asList("notifications", "geolocation"));
            
            // Create persistent context (this returns a BrowserContext with saved data)
            context = playwright.chromium().launchPersistentContext(browserDataPath, persistentOptions);
            System.out.println("[" + getSource() + "] ✅ Persistent browser context created with REAL Chrome!");
            browser = null; // No separate browser object for persistent context
        } else {
            // Create a FRESH context (like incognito mode) - no persistent storage
            // Each run starts completely clean with no cookies or cached data
            context = browser.newContext(
                new Browser.NewContextOptions()
                    .setViewportSize(1440, 900)  // Common MacBook screen size
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
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
        }

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
        
        // Step 2.5: Inject cookies if available (for cookie-based authentication)
        // This is called BEFORE creating the page so cookies are available immediately
        injectCookiesIfAvailable(context);
        
        // Step 3: Create a new page in the context
        Page page = context.newPage();
        System.out.println("[" + getSource() + "] 📄 Created new browser tab");
        
        // Block invalid extension requests to prevent console errors
        try {
            page.route("**/chrome-extension/**", route -> route.abort());
        } catch (Exception e) {
            // Ignore route errors
        }
        try {
            page.route("**/chrome-error/**", route -> route.abort());
        } catch (Exception e) {
            // Ignore route errors
        }
        
        // ENHANCED JavaScript injection for Cloudflare bypass
        // SKIP this if connected to existing Chrome (it's already a real browser!)
        if (!connectedToExisting) {
        page.addInitScript("""
            // Override webdriver property (MOST IMPORTANT for Cloudflare/LinkedIn)
            // This is the #1 way sites detect automation
            Object.defineProperty(navigator, 'webdriver', {
                get: () => false,  // Return false instead of undefined
                configurable: true
            });
            
            // Delete webdriver property from prototype
            try {
                delete navigator.__proto__.webdriver;
            } catch(e) {}
            
            // Also delete from window
            try {
                delete window.navigator.webdriver;
            } catch(e) {}
            
            // Hide the automation banner by removing the flag
            Object.defineProperty(navigator, 'webdriver', {
                get: () => false,
                configurable: true,
                enumerable: false
            });
            
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
            
            // Prevent detection via iframe (but don't break functionality)
            // Commented out - this was breaking LinkedIn's GraphQL requests
            // Object.defineProperty(HTMLIFrameElement.prototype, 'contentWindow', {
            //     get: function() {
            //         return null;
            //     }
            // });
            
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
            
            // Hide the "Chrome is being controlled by automated test software" banner
            // This banner appears when automation is detected
            // Use safer DOM manipulation to avoid interfering with LinkedIn's DOM
            try {
                if (document.head) {
                    const style = document.createElement('style');
                    style.textContent = '[id*="automation"], [class*="automation"], [data-automation], [aria-label*="automated"], [aria-label*="controlled"], [class*="controlled"], [id*="controlled"] { display: none !important; visibility: hidden !important; opacity: 0 !important; height: 0 !important; }';
                    document.head.appendChild(style);
                }
            } catch (e) {
                // Silently fail - don't interfere with page
            }
            
            // Also try to remove the banner via DOM manipulation (less aggressive, safer)
            // Only run if document is ready and avoid interfering with LinkedIn's DOM
            let bannerRemovalInterval = null;
            function startBannerRemoval() {
                if (bannerRemovalInterval) return; // Already running
                
                bannerRemovalInterval = setInterval(() => {
                    try {
                        // Only remove automation banners, don't touch other elements
                        const banners = document.querySelectorAll('[id*="automation"], [class*="automation"], [data-automation], [aria-label*="automated"], [aria-label*="controlled"], [class*="controlled"], [id*="controlled"], [class*="infobar"], [id*="infobar"]');
                        banners.forEach(banner => {
                            if (banner && banner.parentNode) {
                                try {
                                    banner.style.display = 'none';
                                    banner.style.visibility = 'hidden';
                                } catch (e) {
                                    // Ignore errors
                                }
                            }
                        });
                    } catch (e) {
                        // Silently fail - don't break the page
                    }
                }, 2000); // Less frequent to avoid interference
            }
            
            // Start banner removal when DOM is ready
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', startBannerRemoval);
            } else {
                startBannerRemoval();
            }
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
            // CRITICAL: Wait for page to load, but don't waste too much time on LinkedIn
            System.out.println("[" + getSource() + "] Waiting for page to fully load...");
            try {
                // First wait for DOM content loaded (faster)
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED, 
                    new Page.WaitForLoadStateOptions().setTimeout(15000));
                System.out.println("[" + getSource() + "] ✅ DOM content loaded");
                
                // For LinkedIn, skip the network idle wait - it takes too long and isn't needed
                if (!getSource().equalsIgnoreCase("LinkedIn")) {
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, 
                        new Page.WaitForLoadStateOptions().setTimeout(30000));
                    System.out.println("[" + getSource() + "] ✅ Network idle - all resources loaded");
                } else {
                    // For LinkedIn, just wait a fixed 3 seconds
                    page.waitForTimeout(3000);
                    System.out.println("[" + getSource() + "] ✅ LinkedIn quick load complete");
                }
            } catch (Exception e) {
                System.err.println("[" + getSource() + "] ⚠️  Load state wait timed out (this is often OK): " + e.getMessage());
                // Fallback: just wait a fixed time and continue
                page.waitForTimeout(3000);
                System.out.println("[" + getSource() + "] ✅ Continuing after fallback wait");
            }
            
            // Human-like delay after page loads
            humanDelay(page, 2000, 4000);
            
            // Step 6.5: For LinkedIn - check if we already have job cards on search results page
            // Skip complex navigation if we already see jobs
            if (getSource().equalsIgnoreCase("LinkedIn")) {
                System.out.println("[" + getSource() + "] 🔄 For LinkedIn: Checking for job cards on current page...");
                System.out.println("[" + getSource() + "]    Current URL: " + page.url());
                
                // First check if we already have job cards visible on the search page
                page.waitForTimeout(3000);
                int existingJobCards = page.locator("li[data-occludable-job-id], div.job-card-container, ul.scaffold-layout__list-container > li, li.jobs-search-results__list-item").count();
                
                if (existingJobCards > 0) {
                    System.out.println("[" + getSource() + "]    ✅ Found " + existingJobCards + " job cards on search page - skipping navigation flow!");
                    // Job cards already visible, no need for complex navigation
                } else {
                    System.out.println("[" + getSource() + "]    No job cards found yet, trying Jobs icon → Show all flow...");
                try {
                    // Wait a bit for page to be interactive
                    page.waitForTimeout(3000);
                    
                    // Step 1: ALWAYS click the Jobs icon (third icon in navigation - bag/briefcase icon)
                    // This is required to get to the jobs page, then Show all takes us to collections/recommended
                    String[] jobsIconSelectors = {
                        "nav li:nth-child(3) a",  // Third item in nav (Jobs icon) - PRIORITIZE THIS
                        ".global-nav__primary-items li:nth-child(3) a",  // Third item in global nav
                        "header nav li:nth-child(3) a",  // Third item in header nav
                        "nav a[href*='/jobs']",  // Navigation link to jobs
                        "header a[href*='/jobs']",  // Header link
                        ".global-nav__primary-link[href*='/jobs']",  // Global nav primary link
                        "a.global-nav__primary-link[href*='/jobs']",  // Global nav with href
                        "a[aria-label*='Jobs']",  // Aria label with Jobs
                        "a[data-tracking-control-name='nav_jobs']",  // Tracking control name
                        "a[href='/jobs/']",  // Direct jobs link
                        "a[href*='linkedin.com/jobs']"  // Any jobs link
                    };
                    
                    boolean clickedJobsIcon = false;
                    for (String selector : jobsIconSelectors) {
                        try {
                            Locator jobsIcon = page.locator(selector).first();
                            if (jobsIcon.isVisible(new Locator.IsVisibleOptions().setTimeout(3000.0))) {
                                System.out.println("[" + getSource() + "]    Found Jobs icon with selector: " + selector);
                                jobsIcon.click(new Locator.ClickOptions().setTimeout(5000.0));
                                System.out.println("[" + getSource() + "]    ✅ Clicked Jobs icon!");
                                clickedJobsIcon = true;
                                
                                // Wait for navigation
                                page.waitForTimeout(3000);
                                break;
                            }
                        } catch (Exception e) {
                            // Try next selector
                            continue;
                        }
                    }
                    
                    if (clickedJobsIcon) {
                        // Wait a bit after clicking Jobs icon for page to navigate
                        page.waitForTimeout(3000);
                        
                        // Step 2: Click "Show all" button/link to reveal full job list
                        System.out.println("[" + getSource() + "]    🔄 Looking for 'Show all' button to list all jobs...");
                        page.waitForTimeout(2000);
                        
                        String[] showAllSelectors = {
                            "button:has-text('Show all')",
                            "a:has-text('Show all')",
                            "span:has-text('Show all')",
                            ".artdeco-button:has-text('Show all')",
                            "button[aria-label*='Show all']",
                            "a[aria-label*='Show all']",
                            ".jobs-search-results__show-all",
                            "button.show-all-jobs"
                        };
                        
                        boolean clickedShowAll = false;
                        String showAllSelectorUsed = null;
                        for (String selector : showAllSelectors) {
                            try {
                                Locator showAllButton = page.locator(selector).first();
                                if (showAllButton.isVisible(new Locator.IsVisibleOptions().setTimeout(3000.0))) {
                                    System.out.println("[" + getSource() + "]    Found 'Show all' with selector: " + selector);
                                    
                                    // Try to get the href and navigate directly if it's a link
                                    try {
                                        String href = showAllButton.getAttribute("href");
                                        if (href != null && !href.isEmpty()) {
                                            // Make it absolute URL if needed
                                            if (href.startsWith("/")) {
                                                href = "https://www.linkedin.com" + href;
                                            } else if (!href.startsWith("http")) {
                                                href = "https://www.linkedin.com/" + href;
                                            }
                                            System.out.println("[" + getSource() + "]    Found href: " + href);
                                            System.out.println("[" + getSource() + "]    🔄 Navigating directly to collections/recommended page...");
                                            page.navigate(href);
                                            page.waitForTimeout(3000);
                                            System.out.println("[" + getSource() + "]    ✅ Navigated to: " + page.url());
                                            clickedShowAll = true;
                                            showAllSelectorUsed = selector;
                                            break;
                                        }
                                    } catch (Exception e) {
                                        // If getting href fails, fall back to clicking
                                        System.out.println("[" + getSource() + "]    No href found, clicking instead...");
                                    }
                                    
                                    // Fallback: Click the button/link
                                    showAllButton.click(new Locator.ClickOptions().setTimeout(5000.0));
                                    System.out.println("[" + getSource() + "]    ✅ Clicked 'Show all'!");
                                    clickedShowAll = true;
                                    showAllSelectorUsed = selector;
                                    
                                    // Wait for navigation to collections/recommended page
                                    System.out.println("[" + getSource() + "]    ⏳ Waiting for navigation to collections/recommended page...");
                                    try {
                                        // Wait for URL to contain 'collections' or 'recommended'
                                        page.waitForURL("**/jobs/collections/**", new Page.WaitForURLOptions().setTimeout(10000));
                                        System.out.println("[" + getSource() + "]    ✅ Navigated to collections/recommended page: " + page.url());
                                    } catch (Exception e) {
                                        // If URL doesn't change, wait a bit and check current URL
                                        page.waitForTimeout(5000);
                                        String currentUrl = page.url();
                                        System.out.println("[" + getSource() + "]    Current URL after 'Show all': " + currentUrl);
                                        if (!currentUrl.contains("collections") && !currentUrl.contains("recommended")) {
                                            System.out.println("[" + getSource() + "]    ⚠️  URL didn't change to collections/recommended, but continuing...");
                                        }
                                    }
                                    break;
                                }
                            } catch (Exception e) {
                                // Try next selector
                                continue;
                            }
                        }
                        
                        if (clickedShowAll) {
                            // Check if we're now on the collections/recommended page
                            String currentUrl = page.url();
                            boolean isOnCollectionsPage = currentUrl.contains("collections") || currentUrl.contains("recommended");
                            
                            if (isOnCollectionsPage) {
                                System.out.println("[" + getSource() + "]    ✅ Successfully navigated to collections/recommended page!");
                                System.out.println("[" + getSource() + "]    Current URL: " + currentUrl);
                                
                                // CRITICAL: Wait longer for GraphQL API calls to complete and job cards to render
                                // LinkedIn uses GraphQL to load job data, so we need to wait for that
                                System.out.println("[" + getSource() + "]    ⏳ Waiting for job cards to load via GraphQL API...");
                                page.waitForTimeout(5000); // Initial wait
                                
                                // Wait for job cards to appear - check for job card elements
                                int maxWaitAttempts = 10;
                                boolean jobCardsFound = false;
                                for (int attempt = 0; attempt < maxWaitAttempts; attempt++) {
                                    try {
                                        // Check for job card elements
                                        int jobCardCount = page.locator("li[data-occludable-job-id], li.scaffold-layout__list-item, a[href*='/jobs/view/']").count();
                                        if (jobCardCount > 0) {
                                            System.out.println("[" + getSource() + "]    ✅ Found " + jobCardCount + " job cards after " + (attempt + 1) + " attempts!");
                                            jobCardsFound = true;
                                            break;
                                        }
                                    } catch (Exception e) {
                                        // Continue waiting
                                    }
                                    page.waitForTimeout(2000); // Wait 2 seconds between checks
                                }
                                
                                if (!jobCardsFound) {
                                    System.out.println("[" + getSource() + "]    ⚠️  Job cards not found after waiting - GraphQL may have failed");
                                    System.out.println("[" + getSource() + "]    ⚠️  This might be due to automation detection - continuing anyway...");
                                }
                                
                                // CRITICAL: Check if left panel is visible/has job cards
                                // Sometimes LinkedIn collapses the left panel after navigation
                                System.out.println("[" + getSource() + "]    🔍 Checking if left panel with job cards is visible...");
                                page.waitForTimeout(2000);
                                
                                int visibleJobCards = page.locator("li[data-occludable-job-id], li.scaffold-layout__list-item, a[href*='/jobs/view/']").count();
                                if (visibleJobCards == 0) {
                                    System.out.println("[" + getSource() + "]    ⚠️  Left panel appears to be collapsed or hidden!");
                                    System.out.println("[" + getSource() + "]    🔄 Attempting to restore left panel...");
                                    
                                    // Try to find and click buttons to show/expand the left panel
                                    String[] expandSelectors = {
                                        "button[aria-label*='Show']",
                                        "button[aria-label*='Expand']",
                                        "button[aria-label*='View']",
                                        "a:has-text('Show all jobs')",
                                        "a:has-text('View all jobs')",
                                        "button:has-text('Show')",
                                        ".jobs-search-results__show-all",
                                        "button.jobs-search-results__show-all"
                                    };
                                    
                                    boolean expanded = false;
                                    for (String selector : expandSelectors) {
                                        try {
                                            Locator expandButton = page.locator(selector).first();
                                            if (expandButton.isVisible(new Locator.IsVisibleOptions().setTimeout(2000.0))) {
                                                System.out.println("[" + getSource() + "]    Found expand button: " + selector);
                                                expandButton.click(new Locator.ClickOptions().setTimeout(3000.0));
                                                System.out.println("[" + getSource() + "]    ✅ Clicked expand button!");
                                                page.waitForTimeout(3000);
                                                expanded = true;
                                                break;
                                            }
                                        } catch (Exception e) {
                                            continue;
                                        }
                                    }
                                    
                                    // If no expand button found, try scrolling to trigger panel to show
                                    if (!expanded) {
                                        System.out.println("[" + getSource() + "]    🔄 No expand button found, trying scroll to trigger panel...");
                                        page.evaluate("window.scrollTo(0, 0);"); // Scroll to top
                                        page.waitForTimeout(1000);
                                        page.evaluate("window.scrollTo(0, 500);"); // Scroll down
                                        page.waitForTimeout(2000);
                                    }
                                    
                                    // Check again after trying to expand
                                    visibleJobCards = page.locator("li[data-occludable-job-id], li.scaffold-layout__list-item, a[href*='/jobs/view/']").count();
                                    if (visibleJobCards > 0) {
                                        System.out.println("[" + getSource() + "]    ✅ Left panel restored! Found " + visibleJobCards + " job cards");
                                    } else {
                                        System.out.println("[" + getSource() + "]    ⚠️  Left panel still not visible, trying page reload...");
                                        // Try reloading the page to restore the panel
                                        page.reload(new Page.ReloadOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
                                        page.waitForTimeout(5000);
                                        
                                        // Check one more time after reload
                                        visibleJobCards = page.locator("li[data-occludable-job-id], li.scaffold-layout__list-item, a[href*='/jobs/view/']").count();
                                        if (visibleJobCards > 0) {
                                            System.out.println("[" + getSource() + "]    ✅ Left panel restored after reload! Found " + visibleJobCards + " job cards");
                                        } else {
                                            System.out.println("[" + getSource() + "]    ⚠️  Left panel still not visible after reload");
                                            System.out.println("[" + getSource() + "]    ⚠️  This is likely due to automation detection blocking the job list");
                                            System.out.println("[" + getSource() + "]    ⚠️  The scraper will continue but may find 0 jobs");
                                        }
                                    }
                                } else {
                                    System.out.println("[" + getSource() + "]    ✅ Left panel is visible with " + visibleJobCards + " job cards");
                                }
                                
                                // Additional wait for network
                                try {
                                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                                        new Page.WaitForLoadStateOptions().setTimeout(10000));
                                    System.out.println("[" + getSource() + "]    ✅ Network idle");
                                } catch (Exception e) {
                                    System.out.println("[" + getSource() + "]    ⚠️  Network idle timeout, but continuing...");
                                }
                                page.waitForTimeout(3000); // Final wait
                                System.out.println("[" + getSource() + "]    ✅ Ready to start scraping - full job list should be visible");
                            } else {
                                // If not on collections page yet, do hard reload and try Show all again
                                System.out.println("[" + getSource() + "]    ⚠️  Not on collections page yet, performing hard reload...");
                                page.reload(new Page.ReloadOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
                                page.waitForTimeout(3000);
                                
                                try {
                                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                                        new Page.WaitForLoadStateOptions().setTimeout(15000));
                                } catch (Exception e) {
                                    System.out.println("[" + getSource() + "]    ⚠️  Network idle timeout after reload, continuing...");
                                }
                                page.waitForTimeout(2000);
                                
                                // Click 'Show all' again after reload
                                System.out.println("[" + getSource() + "]    🔄 Clicking 'Show all' again after reload...");
                                for (String selector : showAllSelectors) {
                                    try {
                                        Locator showAllButton = page.locator(selector).first();
                                        if (showAllButton.isVisible(new Locator.IsVisibleOptions().setTimeout(3000.0))) {
                                            showAllButton.click(new Locator.ClickOptions().setTimeout(5000.0));
                                            System.out.println("[" + getSource() + "]    ✅ Clicked 'Show all' again after reload!");
                                            
                                            // Wait for navigation again
                                            page.waitForTimeout(3000);
                                            try {
                                                page.waitForURL("**/jobs/collections/**", new Page.WaitForURLOptions().setTimeout(10000));
                                                System.out.println("[" + getSource() + "]    ✅ Navigated to collections/recommended page: " + page.url());
                                            } catch (Exception e) {
                                                System.out.println("[" + getSource() + "]    Current URL: " + page.url());
                                            }
                                            break;
                                        }
                                    } catch (Exception e) {
                                        continue;
                                    }
                                }
                                
                                // Wait for jobs to load
                                System.out.println("[" + getSource() + "]    ⏳ Waiting for full job list to load...");
                                page.waitForTimeout(5000);
                                try {
                                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                                        new Page.WaitForLoadStateOptions().setTimeout(20000));
                                    System.out.println("[" + getSource() + "]    ✅ Jobs list loaded!");
                                } catch (Exception e) {
                                    System.out.println("[" + getSource() + "]    ⚠️  Network idle timeout, but continuing...");
                                    page.waitForTimeout(5000);
                                }
                                System.out.println("[" + getSource() + "]    ✅ Ready to start scraping - full job list should be visible");
                            }
                        }
                        
                        if (!clickedShowAll) {
                            System.out.println("[" + getSource() + "]    ⚠️  Could not find 'Show all' button - jobs may already be listed");
                            // Even if Show all not found, wait a bit for jobs to be visible
                            page.waitForTimeout(3000);
                        }
                    } else {
                        // If Jobs icon not found, try clicking Jobs link in navigation
                        System.out.println("[" + getSource() + "]    🔄 Jobs icon not found, trying Jobs link in navigation...");
                        String[] jobsLinkSelectors = {
                            "a[href*='/jobs']:has-text('Jobs')",
                            "a:has-text('Jobs')",
                            "nav a:has-text('Jobs')"
                        };
                        
                        for (String selector : jobsLinkSelectors) {
                            try {
                                Locator jobsLink = page.locator(selector).first();
                                if (jobsLink.isVisible(new Locator.IsVisibleOptions().setTimeout(3000.0))) {
                                    System.out.println("[" + getSource() + "]    Found Jobs link with selector: " + selector);
                                    jobsLink.click(new Locator.ClickOptions().setTimeout(5000.0));
                                    System.out.println("[" + getSource() + "]    ✅ Clicked Jobs link!");
                                    page.waitForTimeout(3000);
                                    break;
                                }
                            } catch (Exception e) {
                                continue;
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[" + getSource() + "]    ⚠️  Error in Jobs icon/Show all flow: " + e.getMessage() + " - continuing anyway");
                }
                } // end of else block (no existing job cards)
            }
            
            // Step 7: Handle login if needed (site-specific, optional)
            System.out.println("[" + getSource() + "] ========================================");
            System.out.println("[" + getSource() + "] STEP 7: ABOUT TO CALL handleLoginIfNeeded()");
            System.out.println("[" + getSource() + "] ========================================");
            try {
                handleLoginIfNeeded(page);
                
                // For LinkedIn, if we're on any jobs page, skip verification wait
                // These are legitimate LinkedIn pages, not verification/CAPTCHA pages
                boolean skipVerificationCheck = false;
                if (getSource().equalsIgnoreCase("LinkedIn")) {
                    String currentUrl = page.url().toLowerCase();
                    String currentTitle = page.title().toLowerCase();
                    
                    // Check if we're on any legitimate LinkedIn jobs page
                    boolean isOnJobsPage = currentUrl.contains("/jobs/") || 
                                           currentUrl.contains("/jobs?") ||
                                           currentUrl.endsWith("/jobs") ||
                                           (currentTitle.contains("jobs") && currentTitle.contains("linkedin"));
                    
                    if (isOnJobsPage) {
                        System.out.println("[" + getSource() + "] ✅ On LinkedIn jobs page - skipping verification check");
                        System.out.println("[" + getSource() + "]    URL: " + page.url());
                        skipVerificationCheck = true;
                    }
                }
                
                // After handling login/verification, wait longer and verify we're past Cloudflare
                // But skip if we're already on LinkedIn jobs page (no CAPTCHA expected)
                if (!skipVerificationCheck) {
                page.waitForTimeout(5000);
                } else {
                    page.waitForTimeout(2000); // Shorter wait for LinkedIn jobs page
                }
                
                // Check multiple times if we're still on verification page (skip for LinkedIn jobs page)
                boolean stillOnVerification = false;
                int maxChecks = skipVerificationCheck ? 1 : 15; // Only check once for LinkedIn jobs page
                for (int check = 0; check < maxChecks; check++) {
                    String currentTitle = page.title().toLowerCase();
                    String currentUrl = page.url().toLowerCase();
                    String pageContent = "";
                    try {
                        pageContent = page.content().toLowerCase();
                    } catch (Exception e) {
                        // Continue
                    }
                    
                    // Comprehensive Cloudflare and reCAPTCHA detection
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
                        pageContent.contains("recaptcha") ||
                        pageContent.contains("could not connect to the recaptcha service") ||
                        pageContent.contains("check your internet connection") ||
                        page.locator("text=/Additional Verification Required/i").count() > 0 ||
                        page.locator("text=/Help Us Protect/i").count() > 0 ||
                        page.locator("text=/Please unblock challenges.cloudflare.com/i").count() > 0 ||
                        page.locator("text=/reCAPTCHA/i").count() > 0 ||
                        page.locator("iframe[src*='recaptcha']").count() > 0 ||
                        page.locator(".g-recaptcha").count() > 0;
                    
                    if (stillOnVerification && !skipVerificationCheck) {
                        if (check == 0) {
                        System.err.println("\n[" + getSource() + "] ⚠️  ⚠️  VERIFICATION DETECTED (Cloudflare/reCAPTCHA) ⚠️  ⚠️");
                            System.err.println("[" + getSource() + "]    URL: " + page.url());
                            System.err.println("[" + getSource() + "]    Title: " + page.title());
                        System.err.println("[" + getSource() + "]    Please complete the verification challenge manually in the browser window!");
                            System.err.println("[" + getSource() + "]    Waiting up to 90 seconds for you to complete verification...\n");
                        }
                        
                        if (check % 5 == 0 && check > 0) {
                            System.out.println("[" + getSource() + "]    Still waiting... (" + (90 - check * 3) + " seconds remaining)");
                        }
                        
                        // Skip wait if we're on LinkedIn jobs page (no CAPTCHA expected)
                        if (!skipVerificationCheck) {
                        page.waitForTimeout(3000); // Wait 3 seconds between checks
                        } else {
                            break; // Skip wait for LinkedIn jobs page
                        }
                    } else {
                        System.out.println("[" + getSource() + "] ✅ Passed Cloudflare verification page!");
                        break;
                    }
                }
                
                if (stillOnVerification && !skipVerificationCheck) {
                    System.err.println("\n[" + getSource() + "] ⚠️  WARNING: Still on verification page (Cloudflare/reCAPTCHA) after 90 seconds!");
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
            
            // Final check: Make sure we're not still on Cloudflare/reCAPTCHA page
            // But skip for LinkedIn if we're already on jobs list page (search, collections, or any jobs page)
            boolean skipFinalVerificationCheck = false;
            if (getSource().equalsIgnoreCase("LinkedIn")) {
                String currentUrl = page.url().toLowerCase();
                // Skip verification check if we're on ANY LinkedIn jobs page
                if (currentUrl.contains("/jobs/") || currentUrl.contains("/jobs?")) {
                    skipFinalVerificationCheck = true;
                    System.out.println("[" + getSource() + "] ✅ Skipping final verification check - already on LinkedIn jobs page");
                    System.out.println("[" + getSource() + "]    URL: " + page.url());
                }
            }
            
            if (!skipFinalVerificationCheck) {
            String finalCheckTitle = page.title().toLowerCase();
            String finalCheckUrl = page.url().toLowerCase();
                String finalCheckContent = page.content().toLowerCase();
            if (finalCheckTitle.contains("just a moment") || 
                finalCheckTitle.contains("verification required") ||
                finalCheckUrl.contains("challenge") ||
                    finalCheckContent.contains("recaptcha") ||
                    finalCheckContent.contains("could not connect to the recaptcha service") ||
                    page.locator("text=/Additional Verification Required/i").count() > 0 ||
                    page.locator("text=/reCAPTCHA/i").count() > 0 ||
                    page.locator("iframe[src*='recaptcha']").count() > 0) {
                    System.err.println("[" + getSource() + "] ⚠️  Still on verification page (Cloudflare/reCAPTCHA)! Waiting 30 more seconds...");
                System.err.println("[" + getSource() + "]    Please complete verification in the browser window!");
                page.waitForTimeout(30000);
                }
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
                        // For LinkedIn, wait longer and check if elements exist (even if not immediately visible)
                        if (getSource().equalsIgnoreCase("LinkedIn")) {
                            // Wait a bit for dynamic content to load
                            page.waitForTimeout(3000);
                            // Check if selector finds any elements (don't wait for visibility, just existence)
                            int count = page.locator(selector).count();
                            if (count > 0) {
                                System.out.println("[" + getSource() + "] ✅ Found " + count + " job listings with selector: " + selector);
                                found = true;
                                workingSelector = selector; // Use the selector that worked
                                break;
                            } else {
                                System.out.println("[" + getSource() + "] ⚠️  Selector found 0 elements: " + selector);
                            }
                        } else {
                            // For other sites, use the original approach
                        page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(60000));
                        System.out.println("[" + getSource() + "] ✅ Found job listings with selector: " + selector);
                        found = true;
                        workingSelector = selector; // Use the selector that worked
                        break;
                        }
                    } catch (Exception e) {
                        lastException = e;
                        System.out.println("[" + getSource() + "] ⚠️  Selector failed: " + selector);
                        // Try next selector
                    }
                }
            }
            
            if (!found) {
                // For LinkedIn, try a more aggressive fallback: look for any clickable elements that might be job cards
                if (getSource().equalsIgnoreCase("LinkedIn")) {
                    System.out.println("[" + getSource() + "] 🔄 All standard selectors failed, trying fallback approach...");
                    page.waitForTimeout(5000); // Wait longer for content
                    
                    // CRITICAL FIX: For LinkedIn, FIRST search for job card content elements directly
                    // Then get their parent containers. This is more reliable than searching for containers.
                    if (getSource().equalsIgnoreCase("LinkedIn")) {
                        System.out.println("[" + getSource() + "] 🔍 LinkedIn: Searching for job card content elements first...");
                    String[] contentSelectors = {
                        "a[href*='/jobs/view/']",  // Any job view link - PRIORITIZE THIS (works on collections page!)
                        "h3.base-search-card__title",  // PROVEN: Job card title
                        "a.base-card__full-link[href*='/jobs/view/']"  // PROVEN: Job card link
                    };
                        
                        for (String contentSelector : contentSelectors) {
                            try {
                                Locator contentElements = page.locator(contentSelector);
                                int contentCount = contentElements.count();
                                if (contentCount > 0) {
                                    System.out.println("[" + getSource() + "] ✅ Found " + contentCount + " job card content elements with: " + contentSelector);
                                    
                                    // Get parent containers for each content element
                                    // Use XPath to get closest li ancestor
                                    java.util.List<String> parentXPaths = new java.util.ArrayList<>();
                                    for (int i = 0; i < Math.min(contentCount, 50); i++) {
                                        try {
                                            Locator contentEl = contentElements.nth(i);
                                            // Get the closest li ancestor using XPath
                                            String xpath = "xpath=//" + contentSelector.replace("h3.", "").replace("a.", "").replace("[href*='/jobs/view/']", "") + "/ancestor::li[1]";
                                            // Actually, let's use a simpler approach: get parent li directly
                                            Locator parentLi = contentEl.locator("xpath=ancestor::li[1]");
                                            if (parentLi.count() > 0) {
                                                // Check if this parent has job card structure
                                                Locator titleCheck = parentLi.locator("h3.base-search-card__title, a.base-card__full-link");
                                                if (titleCheck.count() > 0) {
                                                    parentXPaths.add("xpath=(//" + contentSelector + ")[" + (i+1) + "]/ancestor::li[1]");
                                                }
                                            }
                                        } catch (Exception e) {
                                            continue;
                                        }
                                    }
                                    
                                    if (parentXPaths.size() > 0) {
                                        System.out.println("[" + getSource() + "]    ✅ Found " + parentXPaths.size() + " parent containers with job card structure");
                                        // Use the first parent XPath as the selector (we'll handle multiple in extraction)
                                        workingSelector = parentXPaths.get(0);
                                        found = true;
                                        break;
                                    } else {
                                        // If we can't get parents, use content elements directly
                                        System.out.println("[" + getSource() + "]    ⚠️  Could not get parent containers, using content elements directly");
                                        workingSelector = contentSelector;
                                        found = true;
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                continue;
                            }
                        }
                    }
                    
                    // If LinkedIn content search didn't work, try container selectors
                    if (!found) {
                        String[] fallbackSelectors = {
                            "li.base-card",  // PROVEN: Base card class used by successful scrapers
                            "li.job-card-container",  // Job card container
                            ".jobs-search-results__list-item",  // Jobs search results item
                            "ul[class*='list'] > li",  // Any ul with 'list' in class containing li
                            "ul[class*='scaffold'] li",  // Generic scaffold list items
                            "ul > li",  // Very generic: any li in a ul (will filter during extraction)
                            "div[class*='scaffold'] > div",  // Scaffold divs
                            "ul li[class*='job']",  // List items with 'job' in class
                            "div[class*='job']"  // Divs with 'job' in class
                        };
                    
                    for (String fallbackSelector : fallbackSelectors) {
                        try {
                            int count = page.locator(fallbackSelector).count();
                            // For link-based selectors, we need multiple links (left panel), not just one (right panel)
                            if (count > 0 && (count > 1 || !fallbackSelector.contains("href"))) {
                                System.out.println("[" + getSource() + "] ✅ Fallback selector found " + count + " elements: " + fallbackSelector);
                                found = true;
                                workingSelector = fallbackSelector;
                                break;
                            } else if (count == 1 && fallbackSelector.contains("href")) {
                                System.out.println("[" + getSource() + "] ⚠️  Fallback selector found only 1 link (likely right panel), skipping: " + fallbackSelector);
                            }
                        } catch (Exception e) {
                            continue;
                        }
                        }
                    }
                    
                    // If still not found, try to find clickable job cards by looking for elements with job-related attributes
                    if (!found) {
                        System.out.println("[" + getSource() + "] 🔄 Trying attribute-based selectors...");
                        String[] attrSelectors = {
                            "[data-job-id]",
                            "[data-entity-urn*='job']",
                            "[data-occludable-job-id]",
                            "li[data-test-id*='job']",
                            "div[data-test-id*='job']"
                        };
                        
                        for (String attrSelector : attrSelectors) {
                            try {
                                int count = page.locator(attrSelector).count();
                                if (count > 0) {
                                    System.out.println("[" + getSource() + "] ✅ Attribute selector found " + count + " elements: " + attrSelector);
                                    found = true;
                                    workingSelector = attrSelector;
                                    break;
                                }
                            } catch (Exception e) {
                                continue;
                            }
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
                    } else {
                        // Dump page HTML for debugging
                        System.err.println("[" + getSource() + "] 🔍 DEBUG: Checking for specific elements:");
                        System.err.println("[" + getSource() + "]    - 'ul' elements: " + page.locator("ul").count());
                        System.err.println("[" + getSource() + "]    - 'li' elements: " + page.locator("li").count());
                        System.err.println("[" + getSource() + "]    - 'article' elements: " + page.locator("article").count());
                        System.err.println("[" + getSource() + "]    - elements with 'job' in class: " + page.locator("[class*='job']").count());
                        System.err.println("[" + getSource() + "]    - elements with 'scaffold' in class: " + page.locator("[class*='scaffold']").count());
                        System.err.println("[" + getSource() + "]    - links with '/jobs/view/': " + page.locator("a[href*='/jobs/view/']").count());
                }
                
                throw new Exception("Failed to find job listings. Cloudflare verification may be required.", lastException);
                }
            }
            
            // Step 8.5: SCROLL TO LOAD MORE JOBS
            // This is critical for Indeed and Glassdoor which use infinite scroll
            int scrollsNeeded = getScrollCount(request);
            if (scrollsNeeded > 0) {
                scrollToLoadMore(page, scrollsNeeded, 1500);
            }
            
            // Step 8.6: Wait for ALL resources to fully load (like linkedin_scraper does)
            System.out.println("[" + getSource() + "] ⏳ Waiting for all jobs and resources to fully load...");
            try {
                // Wait for network to be idle - ensures all dynamic content is loaded
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, 
                    new Page.WaitForLoadStateOptions().setTimeout(20000));
                System.out.println("[" + getSource() + "] ✅ Network idle - all resources loaded");
            } catch (Exception e) {
                System.out.println("[" + getSource() + "] ⚠️  Network idle timeout, using fallback wait");
                humanDelay(page, 5000, 7000);
            }
            
            // Additional wait for dynamic content (especially LinkedIn's React components)
            humanDelay(page, 2000, 4000);
            
            // Step 9: Find all job elements (use the selector that worked)
            // Wait a moment and then count jobs (LinkedIn loads dynamically)
            page.waitForTimeout(2000);
            Locator jobElements = page.locator(workingSelector);
            int jobCount = jobElements.count();
            System.out.println("[" + getSource() + "] ✅ Found " + jobCount + " job listings using selector: " + workingSelector);
            
            // SPECIAL HANDLING FOR LINKEDIN: If we found job card content elements (h3.base-search-card__title, etc.)
            // instead of containers, we need to get their parent containers
            if (getSource().equalsIgnoreCase("LinkedIn") && 
                (workingSelector.contains("base-search-card__title") || 
                 workingSelector.contains("base-card__full-link") ||
                 workingSelector.contains("/jobs/view/"))) {
                System.out.println("[" + getSource() + "] 🔄 Found job card content elements, getting parent containers...");
                try {
                    // Get all job card content elements
                    Locator contentElements = page.locator(workingSelector);
                    int contentCount = contentElements.count();
                    System.out.println("[" + getSource() + "]    Found " + contentCount + " job card content elements");
                    
                    // Try to get parent containers - use XPath to get closest li ancestor
                    // For each content element, get its closest li parent
                    java.util.List<Locator> parentContainers = new java.util.ArrayList<>();
                    for (int i = 0; i < Math.min(contentCount, 100); i++) { // Limit to 100 for performance
                        try {
                            Locator contentElement = contentElements.nth(i);
                            // Get the closest li ancestor
                            Locator parentLi = contentElement.locator("xpath=ancestor::li[1]");
                            if (parentLi.count() > 0) {
                                parentContainers.add(parentLi.first());
                            }
                        } catch (Exception e) {
                            continue;
                        }
                    }
                    
                    if (parentContainers.size() > 0) {
                        System.out.println("[" + getSource() + "]    ✅ Found " + parentContainers.size() + " parent containers");
                        // Create a combined locator from all parent containers
                        // We'll need to process them individually in the extraction loop
                        jobCount = parentContainers.size();
                        workingSelector = "LINKEDIN_PARENT_CONTAINERS"; // Special marker
                        System.out.println("[" + getSource() + "]    Using parent containers for extraction");
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "]    ⚠️  Error getting parent containers: " + e.getMessage());
                }
            }
            
            // DEBUG: If jobs found but count is 0, try alternative selectors
            if (jobCount == 0) {
                System.err.println("[" + getSource() + "] ⚠️  WARNING: Selector found elements but count is 0!");
                System.err.println("[" + getSource() + "]    Trying to debug...");
                
                    // Try to see what's actually on the page
                    try {
                        int articleCount = page.locator("article").count();
                        int liCount = page.locator("li").count();
                        int divWithJobId = page.locator("div[data-job-id]").count();
                        int divWithEntityUrn = page.locator("div[data-entity-urn*='jobPosting']").count();
                        
                        // Check for job card content elements (proven selectors)
                        // IMPORTANT: Check a[href*='/jobs/view/'] FIRST - this is what worked during wait phase!
                        int anyJobLinks = page.locator("a[href*='/jobs/view/']").count();
                        int jobTitles = page.locator("h3.base-search-card__title").count();
                        int jobLinks = page.locator("a.base-card__full-link[href*='/jobs/view/']").count();
                        
                        System.err.println("[" + getSource() + "]    DEBUG: article elements: " + articleCount);
                        System.err.println("[" + getSource() + "]    DEBUG: li elements: " + liCount);
                        System.err.println("[" + getSource() + "]    DEBUG: div[data-job-id]: " + divWithJobId);
                        System.err.println("[" + getSource() + "]    DEBUG: div[data-entity-urn*='jobPosting']: " + divWithEntityUrn);
                        System.err.println("[" + getSource() + "]    DEBUG: a[href*='/jobs/view/']: " + anyJobLinks + " (THIS IS WHAT WORKED!)");
                        System.err.println("[" + getSource() + "]    DEBUG: h3.base-search-card__title: " + jobTitles);
                        System.err.println("[" + getSource() + "]    DEBUG: a.base-card__full-link[href*='/jobs/view/']: " + jobLinks);
                        
                        // If we found job card content elements, use them!
                        // PRIORITIZE a[href*='/jobs/view/'] since that's what worked during wait phase
                        if (anyJobLinks > 0) {
                            String contentSelector = "a[href*='/jobs/view/']";
                            System.err.println("[" + getSource() + "]    ✅ Found job card links! Using: " + contentSelector);
                            workingSelector = contentSelector;
                            jobElements = page.locator(workingSelector);
                            jobCount = jobElements.count();
                            System.out.println("[" + getSource() + "] ✅ Using job card link selector: " + workingSelector + " (found " + jobCount + " elements)");
                        } else if (jobTitles > 0 || jobLinks > 0) {
                            String contentSelector = jobTitles > 0 ? "h3.base-search-card__title" : "a.base-card__full-link[href*='/jobs/view/']";
                            System.err.println("[" + getSource() + "]    ✅ Found job card content! Using: " + contentSelector);
                            workingSelector = contentSelector;
                            jobElements = page.locator(workingSelector);
                            jobCount = jobElements.count();
                            System.out.println("[" + getSource() + "] ✅ Using job card content selector: " + workingSelector + " (found " + jobCount + " elements)");
                        }
                    
                    // Try alternative selectors
                    String[] altSelectors = {
                        "article",
                        "li[data-occludable-job-id]",
                        "div[data-entity-urn*='jobPosting']",
                        ".scaffold-layout__list-item"
                    };
                    
                    for (String altSelector : altSelectors) {
                        try {
                            int altCount = page.locator(altSelector).count();
                            if (altCount > 0) {
                                System.err.println("[" + getSource() + "]    ✅ Alternative selector '" + altSelector + "' found " + altCount + " elements!");
                                workingSelector = altSelector;
                                jobElements = page.locator(workingSelector);
                                jobCount = altCount;
                                System.out.println("[" + getSource() + "] ✅ Using alternative selector: " + workingSelector);
                                break;
                            }
                        } catch (Exception e) {
                            // Continue
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "]    Error in debug: " + e.getMessage());
                }
            }
            
            // If we found very few jobs (less than 5), try ONE quick scroll
            // Skip extra scrolling if we have a reasonable number of jobs already
            int maxRequested = request.getMaxResults() != null ? request.getMaxResults() : 50;
            
            if (jobCount < 5) {
                System.out.println("[" + getSource() + "] ⚠️  Only found " + jobCount + " jobs, trying one quick scroll...");
                int initialJobCount = jobCount;
                scrollToLoadMore(page, 2, 1000);  // Very quick 2-scroll attempt
                page.waitForTimeout(1500);
                jobElements = page.locator(workingSelector);
                jobCount = jobElements.count();
                if (jobCount > initialJobCount) {
                    System.out.println("[" + getSource() + "] ⏩ Found " + jobCount + " jobs after scroll");
                } else {
                    System.out.println("[" + getSource() + "] ℹ️  No more jobs to load, continuing with " + jobCount + " jobs");
                }
            } else {
                System.out.println("[" + getSource() + "] ✅ Found " + jobCount + " jobs (enough to proceed)");
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
            
            // CRITICAL: Re-get the job elements right before scraping to get fresh count
            jobElements = page.locator(workingSelector);
            
            // For LinkedIn with generic 'ul > li' selector, try to find job cards in the jobs list container
            // LinkedIn loads job cards dynamically via API, so we need to look in the right container
            if (getSource().equalsIgnoreCase("LinkedIn") && (workingSelector.equals("ul > li") || workingSelector.contains("ul > li"))) {
                System.out.println("[" + getSource() + "] 🔄 Looking for job cards in jobs list container...");
                // Wait a bit longer for dynamic content to load (LinkedIn uses API calls)
                page.waitForTimeout(3000);
                
                // Try to find the jobs list container first, then get li elements from there
                String[] containerSelectors = {
                    "ul.scaffold-layout__list-container",
                    "ul[class*='jobs-search-results']",
                    "ul[class*='list-container']",
                    "div[class*='jobs-search-results'] ul",
                    "main ul",
                    "div[class*='scaffold-layout__list'] ul"
                };
                
                boolean foundContainer = false;
                for (String containerSelector : containerSelectors) {
                    try {
                        Locator container = page.locator(containerSelector).first();
                        if (container.count() > 0) {
                            // Found container, get li elements from it
                            Locator containerLis = container.locator("li");
                            int count = containerLis.count();
                            if (count > 0) {
                                jobElements = containerLis;
                                System.out.println("[" + getSource() + "]    ✅ Found " + count + " job cards in container: " + containerSelector);
                                foundContainer = true;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
                
                if (!foundContainer) {
                    // Fallback: filter ul > li to only those with job links
                    int totalCount = jobElements.count();
                    System.out.println("[" + getSource() + "]    ⚠️  No container found, filtering " + totalCount + " list items to job cards...");
                    try {
                        Locator filtered = jobElements.filter(new Locator.FilterOptions().setHas(
                            page.locator("a[href*='/jobs/view/'], a[href*='jobPosting']")
                        ));
                        int filteredCount = filtered.count();
                        if (filteredCount > 0) {
                            jobElements = filtered;
                            System.out.println("[" + getSource() + "]    ✅ Filtered to " + filteredCount + " job cards with job links");
                        } else {
                            System.out.println("[" + getSource() + "]    ⚠️  No elements with job links found, will extract from all and filter during extraction");
                        }
                    } catch (Exception e) {
                        System.out.println("[" + getSource() + "]    ⚠️  Filter failed, will extract from all: " + e.getMessage());
                    }
                }
            }
            
            jobCount = jobElements.count();
            System.out.println("[" + getSource() + "] Final job count before scraping: " + jobCount);
            
            int jobsToScrape = Math.min(jobCount, maxResults);
            
            // Step 11: Loop through each job listing and extract data
            System.out.println("[" + getSource() + "] Starting to extract " + jobsToScrape + " jobs...");
            for (int i = 0; i < jobsToScrape; i++) {
                try {
                    System.out.println("[" + getSource() + "] Extracting job " + (i + 1) + " of " + jobsToScrape + "...");
                    
                    // Re-fetch the locator to ensure fresh query
                    Locator jobElement = page.locator(workingSelector).nth(i);
                    
                    // Check if this element actually exists before trying to scroll
                    int currentCount = page.locator(workingSelector).count();
                    if (i >= currentCount) {
                        System.err.println("[" + getSource() + "] ⚠️  Job #" + (i + 1) + " no longer exists (count is now " + currentCount + "), skipping...");
                        continue;
                    }
                    
                    // Scroll the job element into view to ensure it's loaded (with timeout)
                    try {
                        jobElement.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(5000));
                    } catch (Exception scrollError) {
                        System.err.println("[" + getSource() + "] ⚠️  Could not scroll job #" + (i + 1) + " into view, trying anyway: " + scrollError.getMessage());
                    }
                    
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
                        
                        // Skip description enrichment for LinkedIn to speed up scraping
                        // The description can be fetched later if needed
                        // Description enrichment navigates to each job's detail page which is very slow
                        if (!getSource().equalsIgnoreCase("LinkedIn")) {
                            // Enrich job with description from detail page if needed (for non-LinkedIn scrapers)
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
                        }
                        
                        sink.accept(job);
                        System.out.println("[" + getSource() + "] ✅ Scraped job #" + (i + 1) + ": " + 
                            (!title.equals("NO TITLE") ? title : "Untitled") + 
                            " at " + (!company.equals("NO COMPANY") ? company : "Unknown"));
                    } else {
                        System.err.println("[" + getSource() + "] ⚠️  Skipped job #" + (i + 1) + " - extractJobData returned null");
                        // Debug: Try to see what's in the element
                        try {
                            String elementText = jobElement.textContent();
                            String elementHTML = jobElement.innerHTML();
                            System.err.println("[" + getSource() + "]    DEBUG - Element text (first 200 chars): " + 
                                (elementText != null ? elementText.substring(0, Math.min(200, elementText.length())) : "null"));
                            System.err.println("[" + getSource() + "]    DEBUG - Element HTML (first 300 chars): " + 
                                (elementHTML != null ? elementHTML.substring(0, Math.min(300, elementHTML.length())) : "null"));
                        } catch (Exception debugE) {
                            System.err.println("[" + getSource() + "]    DEBUG - Could not inspect element: " + debugE.getMessage());
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "] ❌ Error scraping job " + (i + 1) + ": " + e.getMessage());
                    e.printStackTrace(); // Print full stack trace for debugging
                    // Continue with next job even if this one fails
                }
            }
            
            // Step 10: Handle pagination (for LinkedIn and other sites that support it)
            if (getSource().equalsIgnoreCase("LinkedIn")) {
                try {
                    System.out.println("[" + getSource() + "] 🔄 Starting pagination to scrape additional pages...");
                    // Call LinkedIn-specific pagination handler
                    if (this instanceof com.jobScrapper.scraper.impl.LinkedInScraper) {
                        ((com.jobScrapper.scraper.impl.LinkedInScraper) this).handlePagination(page, request, sink, workingSelector);
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "] ⚠️  Error during pagination: " + e.getMessage());
                    // Continue - pagination is optional
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
    public String extractDescriptionFromDetailPage(Page page, String jobURL) {
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

