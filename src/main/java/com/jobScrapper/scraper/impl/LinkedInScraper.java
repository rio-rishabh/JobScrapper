package com.jobScrapper.scraper.impl;

import com.jobScrapper.scraper.BaseJobScraper;
import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * LinkedIn scraper implementation.
 * 
 * NOTE: LinkedIn requires a persistent browser profile (not incognito) to:
 * - Maintain login sessions
 * - Load resources properly
 * - Avoid detection as a bot
 * - Access more than ~25 job listings
 * 
 * This scraper uses a persistent Chromium profile stored locally.
 * 
 * Only implements site-specific parts:
 * - URL building (LinkedIn's URL format)
 * - CSS selectors (LinkedIn's HTML structure)
 * - Data extraction (LinkedIn's job card format)
 * - Login handling (LinkedIn requires login)
 * - Browser context override (uses persistent profile instead of incognito)
 */
public class LinkedInScraper extends BaseJobScraper {

    private static final String LINKEDIN_JOBS_URL = "https://www.linkedin.com/jobs/search";
    private static final String LINKEDIN_PROFILE_PATH = "linkedin_profile";

    @Override
    public String getSource() {
        return "LinkedIn";
    }
    
    /**
     * Override to use persistent browser context for LinkedIn (not incognito)
     * This allows cookies to be saved and LinkedIn to load properly
     */
    @Override
    protected Path getBrowserDataPath() {
        // Use a dedicated profile folder for LinkedIn in the current directory
        return java.nio.file.Paths.get(LINKEDIN_PROFILE_PATH);
    }

    @Override
    protected String buildSearchURL(ScrapingJobRequest request) {
        StringBuilder url = new StringBuilder(LINKEDIN_JOBS_URL);
        url.append("?");

        // Add keywords (LinkedIn uses %20 for spaces)
        List<String> keywords = request.getKeywords();
        if (keywords != null && !keywords.isEmpty()) {
            String keywordsParam = String.join(" ", keywords);
            url.append("keywords=").append(keywordsParam.replace(" ", "%20"));
        }

        // Add location if provided
        String location = request.getLocation();
        if (location != null && !location.isEmpty()) {
            url.append("&location=").append(location.replace(" ", "%20"));
        }

        // CRITICAL: Add date filter to get only RECENT jobs (past week for better results)
        // f_TPR values: r86400 (past day), r604800 (past week), r2592000 (past month)
        url.append("&f_TPR=r604800");  // Past week (more results than 24 hours)
        
        // Add sortBy=DD to sort by date (most recent first)
        url.append("&sortBy=DD");
        
        System.out.println("[" + getSource() + "] 🔍 Search URL with week filter + date sort: " + url.toString());

        return url.toString();
    }

    @Override
    protected String getJobListSelector() {
        // LinkedIn's job listing selectors - supports both search results AND collections/recommended pages
        // Collections/recommended page uses: li[data-occludable-job-id] and li.scaffold-layout__list-item
        // Search results page uses: li.base-card, li.job-card-container
        // Priority order: collections page selectors first, then search results selectors
        return "li[data-occludable-job-id], li.scaffold-layout__list-item, ul.scaffold-layout__list-container > li, li.base-card, li.job-card-container, .jobs-search-results__list-item, div[data-entity-urn*='jobPosting'], article[data-entity-urn*='jobPosting']";
    }

    @Override
    protected void handleLoginIfNeeded(Page page) {
        System.out.println("\n[" + getSource() + "] ========================================");
        System.out.println("[" + getSource() + "] 🔍 CHECKING LOGIN STATUS");
        System.out.println("[" + getSource() + "] ========================================");
        
        String currentUrl = page.url();
        String pageTitle = page.title().toLowerCase();
        System.out.println("[" + getSource() + "] Current URL: " + currentUrl);
        System.out.println("[" + getSource() + "] Page title: " + pageTitle);

        // Store the original search URL to return to after login
        String originalSearchUrl = currentUrl;
        
        // FIRST: Check if we're using cookie authentication
        boolean usingCookieAuth = checkIfUsingCookieAuth();
        if (usingCookieAuth) {
            System.out.println("[" + getSource() + "] 🍪 Using cookie-based authentication");
            System.out.println("[" + getSource() + "]    Cookie was injected before page creation");
            System.out.println("[" + getSource() + "]    Waiting for page to load and verify authentication...");
            
            // Wait a bit for cookie to take effect
            page.waitForTimeout(2000);
            
            // Navigate to LinkedIn to verify cookie works
            if (!currentUrl.contains("linkedin.com")) {
                page.navigate("https://www.linkedin.com");
                page.waitForTimeout(3000);
            }
            
            // Check if we're logged in (no sign-in button)
            boolean isLoggedIn = checkIfLoggedIn(page);
            if (isLoggedIn) {
                System.out.println("[" + getSource() + "] ✅ Cookie authentication successful! Already logged in.");
                // Navigate to jobs page if needed
                if (!currentUrl.contains("/jobs/search")) {
                    page.navigate(originalSearchUrl);
                    page.waitForTimeout(3000);
                }
                return; // Cookie auth worked, no need for username/password
            } else {
                System.out.println("[" + getSource() + "] ⚠️  Cookie authentication failed - cookie may be expired");
                System.out.println("[" + getSource() + "]    Falling back to username/password login...");
                // Continue to username/password login below
            }
        }
        
        // Check if we're on the jobs page and see if "Sign in" button is present
        // This indicates we're not logged in
        try {
            String[] signInSelectors = {
                "a.nav__button-secondary:has-text('Sign in')",
                "a:has-text('Sign in')",
                "button:has-text('Sign in')",
                ".sign-in-link",
                "a[href*='/login']"
            };
            
            boolean signInButtonFound = false;
            for (String selector : signInSelectors) {
                try {
                    if (page.locator(selector).count() > 0) {
                        signInButtonFound = true;
                        System.out.println("[" + getSource() + "] 🔐 Found 'Sign in' button - not logged in yet");
                        break;
                    }
                } catch (Exception e) {
                    // Continue
                }
            }
            
            // If we found a sign-in button or are on login page, attempt login
            if (signInButtonFound || currentUrl.contains("/login") || currentUrl.contains("/checkpoint")) {
                System.out.println("[" + getSource() + "] 🔐 LinkedIn requires login - attempting automatic login from jobs page...");
                
                // Try automatic login
                if (attemptAutoLoginFromJobsPage(page)) {
                    System.out.println("[" + getSource() + "] ✅ Successfully logged in to LinkedIn!");
                    
                    // CRITICAL: Navigate back to the original search URL to continue scraping
                    System.out.println("[" + getSource() + "] 🔄 Navigating back to jobs search page after login...");
                    System.out.println("[" + getSource() + "]    Target URL: " + originalSearchUrl);
                    page.navigate(originalSearchUrl);
                    
                    // Wait for ALL resources to load (like linkedin_scraper does)
                    try {
                        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, 
                            new Page.WaitForLoadStateOptions().setTimeout(30000));
                        System.out.println("[" + getSource() + "]    ✅ Network idle - all resources loaded on jobs page");
                    } catch (Exception e) {
                        System.out.println("[" + getSource() + "]    ⚠️  Network idle timeout, using fallback");
                        page.waitForTimeout(5000);
                    }
                    
                    // Additional wait for page to be interactive
                    page.waitForTimeout(2000);
                    
                    // Verify we're back on the search page
                    String afterNavUrl = page.url();
                    System.out.println("[" + getSource() + "]    ✅ Back on search page: " + afterNavUrl);
                    
                    return;
                } else {
                    System.out.println("[" + getSource() + "] ⚠️  Automatic login failed - please log in manually");
                    System.out.println("[" + getSource() + "]    Waiting 60 seconds for manual login...");
                    page.waitForTimeout(60000);
                    
                    // After manual login, navigate back to search page
                    System.out.println("[" + getSource() + "] 🔄 Navigating back to jobs search page...");
                    page.navigate(originalSearchUrl);
                    page.waitForTimeout(3000);
                    System.out.println("[" + getSource() + "]    Continuing scraping...");
                }
            } else {
                System.out.println("[" + getSource() + "] ✅ Already logged in to LinkedIn (no sign-in button found)");
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] ⚠️  Error checking login status: " + e.getMessage());
        }
    }
    
    /**
     * Attempt automatic login to LinkedIn from the jobs page using credentials from config.json
     */
    private boolean attemptAutoLoginFromJobsPage(Page page) {
        try {
            // Load credentials from config.json
            String email = null;
            String password = null;
            
            try {
                java.io.File configFile = new java.io.File("config.json");
                if (configFile.exists()) {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    java.io.FileReader reader = new java.io.FileReader(configFile);
                    java.util.Map<String, String> config = gson.fromJson(reader, 
                        new com.google.gson.reflect.TypeToken<java.util.Map<String, String>>(){}.getType());
                    reader.close();
                    
                    email = config.get("LINKEDIN_EMAIL");
                    password = config.get("LINKEDIN_PASSWORD");
                }
            } catch (Exception e) {
                System.err.println("[" + getSource() + "] ⚠️  Could not read LinkedIn credentials from config.json: " + e.getMessage());
            }
            
            // Fallback to environment variables
            if (email == null || email.isEmpty()) {
                email = System.getenv("LINKEDIN_EMAIL");
            }
            if (password == null || password.isEmpty()) {
                password = System.getenv("LINKEDIN_PASSWORD");
            }
            
            if (email == null || password == null || email.isEmpty() || password.isEmpty()) {
                System.out.println("[" + getSource() + "] ⚠️  LinkedIn credentials not found in config.json or environment variables");
                return false;
            }
            
            System.out.println("[" + getSource() + "]    📧 Using email: " + email.substring(0, Math.min(4, email.length())) + "***");
            
            // Step 1: We're already on the jobs page - look for "Sign in" button
            System.out.println("[" + getSource() + "]    Step 1: Looking for 'Sign in' button on jobs page...");
            String currentUrl = page.url();
            System.out.println("[" + getSource() + "]    Current page: " + currentUrl);
            
            // First, try to close any modals that might be blocking clicks
            try {
                String[] modalCloseSelectors = {
                    "button[data-tracking-control-name='public_jobs_contextual-sign-in-modal_modal_dismiss']",
                    ".modal__dismiss",
                    "button[aria-label='Dismiss']",
                    ".artdeco-modal__dismiss"
                };
                for (String closeSelector : modalCloseSelectors) {
                    if (page.locator(closeSelector).count() > 0) {
                        System.out.println("[" + getSource() + "]    Closing modal that might block click...");
                        page.locator(closeSelector).first().click();
                        page.waitForTimeout(1000);
                        break;
                    }
                }
            } catch (Exception e) {
                // Continue even if modal closing fails
            }
            
            String[] signInSelectors = {
                "a.nav__button-secondary:has-text('Sign in')",
                "a:has-text('Sign in')",
                "button:has-text('Sign in')",
                ".sign-in-link",
                "a[href*='/login']",
                ".cta-modal__primary-btn"
            };
            
            boolean clickedSignIn = false;
            for (String selector : signInSelectors) {
                try {
                    Locator signInLink = page.locator(selector);
                    if (signInLink.count() > 0) {
                        System.out.println("[" + getSource() + "]    Found 'Sign in' button with selector: " + selector);
                        
                        // Try to get the href attribute to navigate directly
                        String href = signInLink.first().getAttribute("href");
                        if (href != null && href.contains("/login")) {
                            System.out.println("[" + getSource() + "]    Navigating directly to login page via href: " + href);
                            // Make sure it's a full URL
                            if (!href.startsWith("http")) {
                                href = "https://www.linkedin.com" + href;
                            }
                            page.navigate(href);
                            clickedSignIn = true;
                            page.waitForTimeout(3000);
                            System.out.println("[" + getSource() + "]    ✅ Navigation successful!");
                            break;
                        } else {
                            // Fallback to force click if no href
                            System.out.println("[" + getSource() + "]    Attempting force click...");
                            signInLink.first().click(new Locator.ClickOptions().setForce(true));
                            System.out.println("[" + getSource() + "]    ✅ Click successful!");
                            clickedSignIn = true;
                            page.waitForTimeout(3000);
                            System.out.println("[" + getSource() + "]    Waited 3 seconds after click");
                            break;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "]    ⚠️  Error with selector " + selector + ": " + e.getMessage());
                    // Try next selector
                }
            }
            
            // If we couldn't find sign in button, navigate directly to login page
            if (!clickedSignIn) {
                System.out.println("[" + getSource() + "]    'Sign in' button not found on jobs page");
                System.out.println("[" + getSource() + "]    Navigating directly to login page...");
                page.navigate("https://www.linkedin.com/login");
                page.waitForTimeout(3000);
            }
            
            // Step 2: Wait for login form to load
            System.out.println("[" + getSource() + "]    Step 2: Waiting for login form...");
            page.waitForSelector("#username", new Page.WaitForSelectorOptions().setTimeout(10000));
            
            // Step 3: Fill in email
            System.out.println("[" + getSource() + "]    Step 3: Entering email...");
            page.fill("#username", email);
            page.waitForTimeout(1000 + (int)(Math.random() * 1000)); // Human-like delay
            
            // Step 4: Fill in password
            System.out.println("[" + getSource() + "]    Step 4: Entering password...");
            page.fill("#password", password);
            page.waitForTimeout(1000 + (int)(Math.random() * 1000));
            
            // Step 5: Click sign in button
            System.out.println("[" + getSource() + "]    Step 5: Clicking 'Sign in' button...");
            Locator signInButton = page.locator("button[type='submit']");
            if (signInButton.count() > 0) {
                signInButton.click();
            } else {
                // Try alternative selectors
                String[] submitSelectors = {
                    "button:has-text('Sign in')",
                    "button.sign-in-form__submit-button",
                    "button[data-litms-control-urn*='login']"
                };
                boolean clicked = false;
                for (String selector : submitSelectors) {
                    try {
                        if (page.locator(selector).count() > 0) {
                            page.click(selector);
                            clicked = true;
                            break;
                        }
                    } catch (Exception e) {
                        // Try next
                    }
                }
                if (!clicked) {
                    System.err.println("[" + getSource() + "] ⚠️  Could not find submit button!");
                }
            }
            
            // Step 6: Wait for navigation after login (like linkedin_scraper does)
            System.out.println("[" + getSource() + "]    Step 6: Waiting for login to complete and resources to load...");
            
            // Wait for network to be idle (all resources loaded) - CRITICAL for LinkedIn
            try {
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, 
                    new Page.WaitForLoadStateOptions().setTimeout(15000));
                System.out.println("[" + getSource() + "]    ✅ Network idle after login");
            } catch (Exception e) {
                System.out.println("[" + getSource() + "]    ⚠️  Network idle timeout, using fallback wait");
                page.waitForTimeout(5000);
            }
            
            // Additional wait for page to be fully interactive
            page.waitForTimeout(2000);
            
            // Check if login was successful
            String afterLoginUrl = page.url();
            String afterLoginTitle = page.title().toLowerCase();
            
            System.out.println("[" + getSource() + "]    After login - URL: " + afterLoginUrl);
            System.out.println("[" + getSource() + "]    After login - Title: " + afterLoginTitle);
            
            // Check for 2FA or verification
            if (afterLoginUrl.contains("/checkpoint") || afterLoginUrl.contains("/challenge") ||
                afterLoginTitle.contains("verify") || afterLoginTitle.contains("security") ||
                afterLoginTitle.contains("let's do a quick security check")) {
                System.out.println("[" + getSource() + "] 🔐 LinkedIn requires additional verification (2FA/security check)");
                System.out.println("[" + getSource() + "]    Please complete the verification in the browser window");
                System.out.println("[" + getSource() + "]    Waiting up to 90 seconds...");
                
                // Wait for user to complete verification
                for (int i = 0; i < 18; i++) {
                    page.waitForTimeout(5000);
                    String checkUrl = page.url();
                    String checkTitle = page.title().toLowerCase();
                    if (!checkUrl.contains("/checkpoint") && !checkUrl.contains("/challenge") &&
                        !checkTitle.contains("verify") && !checkTitle.contains("security")) {
                        System.out.println("[" + getSource() + "] ✅ Verification completed!");
                        afterLoginUrl = checkUrl;
                        break;
                    }
                    if (i % 3 == 0) {
                        System.out.println("[" + getSource() + "]    Still waiting... (" + (90 - (i * 5)) + "s remaining)");
                    }
                }
            }
            
            // Check if we're now logged in
            afterLoginUrl = page.url();
            if (afterLoginUrl.contains("/feed") || afterLoginUrl.contains("/mynetwork") || 
                afterLoginUrl.contains("/in/") || afterLoginUrl.contains("/jobs") ||
                (!afterLoginUrl.contains("/login") && !afterLoginUrl.contains("/checkpoint"))) {
                System.out.println("[" + getSource() + "] ✅ Login successful!");
                return true;
            } else {
                System.err.println("[" + getSource() + "] ❌ Login appears to have failed");
                System.err.println("[" + getSource() + "]    Current URL: " + afterLoginUrl);
                return false;
            }
            
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] ❌ Error during automatic login: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    

    @Override
    protected Job extractJobData(Locator jobElement, Page page) {
        // Extract job data from card - supports both public view AND logged-in view
        String title = null;
        String company = null;
        String location = null;
        String url = null;
        String description = "";
        
        try {
            // DEBUG: Log element info
            String elementTag = "";
            String elementClass = "";
            try {
                elementTag = jobElement.evaluate("el => el.tagName").toString();
                elementClass = jobElement.getAttribute("class");
            } catch (Exception e) {
                // Ignore
            }
            System.out.println("[" + getSource() + "] 🔍 DEBUG extractJobData - Element: " + elementTag + ", class: " + 
                (elementClass != null ? elementClass.substring(0, Math.min(50, elementClass.length())) : "null"));
            
            // First, check if this is a valid job card element
            // We support two structures:
            // 1. PUBLIC view: h3.base-search-card__title, li.base-card
            // 2. LOGGED-IN view: li.scaffold-layout__list-item, .job-card-container
            boolean hasJobCardStructure = false;
            
            // Check for PUBLIC view job card structure
            Locator publicTitleCheck = jobElement.locator("h3.base-search-card__title, h3.job-search-card__title");
            if (publicTitleCheck.count() > 0) {
                hasJobCardStructure = true;
            }
            
            // Check for LOGGED-IN view job card structure (scaffold-layout list items)
            // Collections/recommended page uses these structures
            if (!hasJobCardStructure) {
                Locator loggedInTitleCheck = jobElement.locator(".job-card-list__title, .job-card-container__link, .artdeco-entity-lockup__title, .job-card-list__title--link");
                if (loggedInTitleCheck.count() > 0) {
                    hasJobCardStructure = true;
                }
            }
            
            // Check for collections/recommended page structure (data-occludable-job-id)
            if (!hasJobCardStructure) {
                try {
                    String dataAttr = jobElement.getAttribute("data-occludable-job-id");
                    if (dataAttr != null && !dataAttr.isEmpty()) {
                        hasJobCardStructure = true;
                    }
                } catch (Exception e) {
                    // Continue
                }
            }
            
            // Check if element itself is a scaffold-layout list item (collections page)
            if (!hasJobCardStructure) {
                try {
                    String className = jobElement.getAttribute("class");
                    if (className != null && className.contains("scaffold-layout__list-item")) {
                        // Check if it has job-related content
                        Locator jobLink = jobElement.locator("a[href*='/jobs/view/']");
                        if (jobLink.count() > 0) {
                            hasJobCardStructure = true;
                        }
                    }
                } catch (Exception e) {
                    // Continue
                }
            }
            
            // Check for job links (works for both views)
            if (!hasJobCardStructure) {
                Locator jobLinks = jobElement.locator("a[href*='/jobs/view/'], a.job-card-container__link, a.job-card-list__title");
                if (jobLinks.count() > 0) {
                    try {
                        String linkHref = jobLinks.first().getAttribute("href");
                        if (linkHref != null && linkHref.contains("/jobs/view/")) {
                            hasJobCardStructure = true;
                        }
                    } catch (Exception e) {
                        // Continue
                    }
                }
            }
            
            
            // If still no structure found, try to find ANY link to a job
            if (!hasJobCardStructure) {
                try {
                    Locator anyJobLink = jobElement.locator("a");
                    if (anyJobLink.count() > 0) {
                        String href = anyJobLink.first().getAttribute("href");
                        if (href != null && href.contains("/jobs/view/")) {
                            hasJobCardStructure = true;
                        }
                    }
                } catch (Exception e) {
                    // Continue
                }
            }
            
            // If no job card structure found, skip this element
            if (!hasJobCardStructure) {
                System.out.println("[" + getSource() + "] ⚠️  DEBUG - No job card structure found, skipping element");
                return null;
            }
            
            System.out.println("[" + getSource() + "] ✅ DEBUG - Job card structure found, proceeding with extraction");
            // Extract title from job card - supports both PUBLIC and LOGGED-IN views
            // IMPORTANT: These work even when GraphQL fails - extract from static HTML
            String[] titleSelectors = {
                // LOGGED-IN view selectors (prioritize these - collections/recommended page)
                ".job-card-list__title",           // Logged-in: job card title
                ".job-card-container__link",       // Logged-in: clickable title
                "a.job-card-container__link",      // Logged-in: title link
                ".job-card-list__title--link",     // Logged-in: title link variant
                ".artdeco-entity-lockup__title",   // Logged-in: entity lockup title
                "span.job-card-list__title",        // Sometimes title is in span
                // PUBLIC view selectors
                "h3.base-search-card__title",      // Public: base search card
                "h3.job-search-card__title",       // Public: job search card
                "a[data-tracking-control-name='public_jobs_jserp-result_job-search-card-title']",
                ".base-card__full-link",           // Public: full card link
                // Fallback selectors - extract from link text if title element not found
                "a[href*='/jobs/view/']",          // Any job link - get text content
                "strong",                          // Sometimes title is in strong tag
                "h3",                              // Generic h3
                "h2"                               // Sometimes h2
            };
            
            for (String selector : titleSelectors) {
                try {
                    Locator titleElement = jobElement.locator(selector).first();
                    if (titleElement.count() > 0) {
                        title = titleElement.textContent();
                        if (title != null && !title.trim().isEmpty()) {
                            title = title.trim();
                            // If we got title from a link, make sure it's not just the URL
                            if (selector.contains("a[href") && title.length() < 5) {
                                continue; // Too short, probably not a title
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // If title still not found and we have a job link, try to get title from link's aria-label or text
            if ((title == null || title.isEmpty()) && url != null) {
                try {
                    Locator jobLink = jobElement.locator("a[href*='/jobs/view/']").first();
                    if (jobLink.count() > 0) {
                        // Try aria-label first
                        String ariaLabel = jobLink.getAttribute("aria-label");
                        if (ariaLabel != null && !ariaLabel.trim().isEmpty() && ariaLabel.length() > 5) {
                            title = ariaLabel.trim();
                        } else {
                            // Try text content
                            String linkText = jobLink.textContent();
                            if (linkText != null && !linkText.trim().isEmpty() && linkText.length() > 5) {
                                title = linkText.trim();
                            }
                        }
                    }
                } catch (Exception e) {
                    // Continue
                }
            }
            
            // Extract company from job card - supports both PUBLIC and LOGGED-IN views
            // IMPORTANT: These work even when GraphQL fails
            String[] companySelectors = {
                // LOGGED-IN view selectors (prioritize these - collections/recommended page)
                ".job-card-container__primary-description",  // Logged-in: primary description (company name)
                ".job-card-container__company-name",         // Logged-in: company name
                ".job-card-list__company-name",              // Logged-in: company name in list
                ".artdeco-entity-lockup__subtitle",          // Logged-in: entity subtitle
                "span.job-card-container__company-name",     // Logged-in: company name span
                // PUBLIC view selectors
                "h4.base-search-card__subtitle",             // Public: base search card subtitle
                ".job-search-card__subtitle",                // Public: job search card subtitle
                "a[data-tracking-control-name='public_jobs_jserp-result_job-search-card-subtitle']",
                ".base-search-card__subtitle",               // Public: base subtitle
                // Fallback selectors
                ".job-search-card__subtitle-link",
                "h4",                                        // Generic h4
                "span[class*='company']"                     // Any span with company in class
            };
            
            for (String selector : companySelectors) {
                try {
                    Locator companyElement = jobElement.locator(selector).first();
                    if (companyElement.count() > 0) {
                        company = companyElement.textContent();
                        if (company != null && !company.trim().isEmpty()) {
                            company = company.trim();
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // Extract location from job card - supports both PUBLIC and LOGGED-IN views
            // IMPORTANT: These work even when GraphQL fails
            String[] locationSelectors = {
                // LOGGED-IN view selectors (prioritize these - collections/recommended page)
                ".job-card-container__metadata-item",        // Logged-in: metadata item (location)
                ".job-card-list__location",                  // Logged-in: location in list
                ".job-card-container__metadata-wrapper li",  // Logged-in: metadata wrapper
                "li.job-card-container__metadata-item",      // Logged-in: list item metadata
                ".artdeco-entity-lockup__caption",           // Logged-in: entity caption
                // PUBLIC view selectors
                "span.job-search-card__location",            // Public: location span
                ".base-search-card__metadata",               // Public: metadata
                ".job-search-card__metadata",                // Public: job search metadata
                // Fallback selectors
                "span[class*='location']",                   // Any span with location in class
                ".base-search-card__metadata-item",
                ".job-search-card__metadata-item",
                "span[class*='metadata']"                    // Any metadata span
            };
            
            for (String locationSelector : locationSelectors) {
                try {
                    Locator locationElement = jobElement.locator(locationSelector).first();
                    if (locationElement.count() > 0) {
                        location = locationElement.textContent();
                        if (location != null && !location.trim().isEmpty()) {
                            location = location.trim();
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // Extract URL from job card link - supports both PUBLIC and LOGGED-IN views
            // MUST be a job URL, not messaging/notification links
            String[] urlSelectors = {
                // LOGGED-IN view selectors (prioritize these)
                "a.job-card-container__link",              // Logged-in: container link
                "a.job-card-list__title",                  // Logged-in: title link
                "a.job-card-list__title--link",            // Logged-in: title link variant
                // PUBLIC view selectors
                "a.base-card__full-link",                  // Public: full card link
                "a[data-tracking-control-name='public_jobs_jserp-result_job-search-card-title']",
                // Fallback selectors - any job link
                "a[href*='/jobs/view/']",                  // Any link to job view
                "a[href*='jobPosting']"                    // Any jobPosting link
            };
            
            for (String urlSelector : urlSelectors) {
                try {
                    Locator urlElement = jobElement.locator(urlSelector).first();
                    if (urlElement.count() > 0) {
                        String foundUrl = urlElement.getAttribute("href");
                        if (foundUrl != null && !foundUrl.trim().isEmpty()) {
                            // Make sure URL is absolute
                            if (foundUrl.startsWith("/")) {
                                foundUrl = "https://www.linkedin.com" + foundUrl;
                            }
                            // CRITICAL: Only accept URLs that are actual job listings
                            // Exclude messaging, notifications, profile links, etc.
                            if (foundUrl.contains("/jobs/view/") || foundUrl.contains("jobPosting")) {
                                url = foundUrl;
                        break;
                            }
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // Description is usually not available in the card, set to empty or try to get snippet
            try {
                Locator descElement = jobElement.locator(".job-search-card__snippet, .base-search-card__snippet").first();
                if (descElement.count() > 0) {
                    description = descElement.textContent();
                }
            } catch (Exception e) {
                // Description not available in card
            }
            
            // DEBUG: Log what we extracted
            System.out.println("[" + getSource() + "] 🔍 DEBUG - Extracted: title='" + title + "', company='" + company + 
                "', location='" + location + "', url='" + url + "'");
            
            // Create job object - STRICT: must have both title AND valid job URL
            // This filters out navigation items, messaging links, notifications, etc.
            if (title != null && !title.trim().isEmpty() && 
                url != null && !url.trim().isEmpty() && 
                (url.contains("/jobs/view/") || url.contains("jobPosting"))) {
                
                // Additional validation: exclude common non-job URLs
                if (url.contains("/messaging/") || 
                    url.contains("/notifications/") || 
                    url.contains("/in/") || 
                    url.contains("/feed/") ||
                    url.contains("/mynetwork/") ||
                    url.contains("/profile/")) {
                    // This is not a job URL, skip it
                    System.out.println("[" + getSource() + "] ⚠️  DEBUG - URL excluded (non-job URL): " + url);
                    return null;
                }
                
                System.out.println("[" + getSource() + "] ✅ DEBUG - Creating job object with title: " + title);
                
                return new Job()
                    .id(UUID.randomUUID().toString())
                    .source(getSource())
                    .title(title.trim())
                    .company(company != null && !company.trim().isEmpty() ? company.trim() : "Not Specified")
                    .location(location != null && !location.trim().isEmpty() ? location.trim() : "Not Specified")
                    .url(url.trim())
                    .description(description != null ? description : "")
                    .postedDate(OffsetDateTime.now());
            }
            
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] ❌ Error extracting job data from card: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("[" + getSource() + "] ⚠️  DEBUG - Returning null from extractJobData (no valid job found)");
        return null;
    }
    
    /**
     * Extract job data by clicking the job card and extracting from the right panel
     * This is the new approach based on user's workflow:
     * 1. Click job card in left panel
     * 2. Wait for right panel to load
     * 3. Extract title, company, location from right panel
     * 4. Click "More" in "About the job" section
     * 5. Extract full description
     * 6. Extract URL from "Apply" button
     */
    private Job extractJobDataByClicking(Locator jobCardElement, Page page) {
        String title = null;
        String company = null;
        String location = null;
        String url = null;
        String description = null;
        
        try {
            System.out.println("[" + getSource() + "]    🔍 Clicking job card to load details in right panel...");
            
            // Step 1: Click the job card to load details in right panel
            jobCardElement.click(new Locator.ClickOptions().setTimeout(5000.0));
            System.out.println("[" + getSource() + "]    ✅ Clicked job card");
            
            // Step 2: Wait for right panel to load
            page.waitForTimeout(2000);
            
            // Step 3: Extract title from right panel
            String[] titleSelectors = {
                "h1.job-details-jobs-unified-top-card__job-title",
                "h1.jobs-details-top-card__job-title",
                "h1[class*='job-title']",
                "h1",
                ".jobs-details-top-card__job-title-text"
            };
            
            for (String selector : titleSelectors) {
                try {
                    Locator titleElement = page.locator(selector).first();
                    if (titleElement.isVisible(new Locator.IsVisibleOptions().setTimeout(3000.0))) {
                        title = titleElement.textContent();
                        if (title != null && !title.trim().isEmpty()) {
                            title = title.trim();
                            System.out.println("[" + getSource() + "]    ✅ Found title: " + title);
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // Step 4: Extract company from right panel
            String[] companySelectors = {
                "a.jobs-details-top-card__company-name",
                "a[data-tracking-control-name='public_jobs_topcard_org_name']",
                ".jobs-details-top-card__company-info a",
                "span.jobs-details-top-card__company-name",
                ".jobs-details-top-card__company-name",
                "a[href*='/company/']",
                ".jobs-details-top-card__primary-description-without-tagline a"
            };
            
            for (String selector : companySelectors) {
                try {
                    Locator companyElement = page.locator(selector).first();
                    if (companyElement.isVisible(new Locator.IsVisibleOptions().setTimeout(2000.0))) {
                        company = companyElement.textContent();
                        if (company != null && !company.trim().isEmpty()) {
                            company = company.trim();
                            System.out.println("[" + getSource() + "]    ✅ Found company: " + company);
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // Step 5: Extract location from right panel
            String[] locationSelectors = {
                ".jobs-details-top-card__primary-description-without-tagline",
                ".jobs-details-top-card__job-info span",
                ".jobs-details-top-card__primary-description",
                "span[class*='location']",
                ".jobs-details-top-card__bullet",
                ".jobs-details-top-card__job-info"
            };
            
            for (String selector : locationSelectors) {
                try {
                    Locator locationElement = page.locator(selector).first();
                    if (locationElement.isVisible(new Locator.IsVisibleOptions().setTimeout(2000.0))) {
                        location = locationElement.textContent();
                        if (location != null && !location.trim().isEmpty()) {
                            location = location.trim();
                            System.out.println("[" + getSource() + "]    ✅ Found location: " + location);
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // Step 6: Click "More" button in "About the job" section to expand description
            System.out.println("[" + getSource() + "]    🔄 Looking for 'More' button in 'About the job' section...");
            String[] moreButtonSelectors = {
                "button:has-text('Show more')",
                "button:has-text('more')",
                "button.show-more-less-html__button--more",
                "button.show-more-less-html__button",
                ".show-more-less-html__button",
                "button[aria-label*='more']",
                ".jobs-description__text button",
                "button[data-tracking-control-name='public_jobs_show_more']"
            };
            
            boolean clickedMore = false;
            for (String selector : moreButtonSelectors) {
                try {
                    Locator moreButton = page.locator(selector).first();
                    if (moreButton.isVisible(new Locator.IsVisibleOptions().setTimeout(3000.0))) {
                        System.out.println("[" + getSource() + "]    Found 'More' button with selector: " + selector);
                        moreButton.click(new Locator.ClickOptions().setTimeout(5000.0));
                        System.out.println("[" + getSource() + "]    ✅ Clicked 'More' button!");
                        clickedMore = true;
                        page.waitForTimeout(1000); // Wait for description to expand
                        break;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            if (!clickedMore) {
                System.out.println("[" + getSource() + "]    ⚠️  'More' button not found - description may already be expanded");
            }
            
            // Step 7: Extract full description from "About the job" section
            String[] descSelectors = {
                ".jobs-description__text",
                ".show-more-less-html__markup",
                ".jobs-box__html-content",
                "section.jobs-description__content",
                ".jobs-details__main-content .jobs-description",
                ".jobs-description-content__text",
                ".jobs-description__content",
                "div[data-test-id='job-details-description']"
            };
            
            for (String selector : descSelectors) {
                try {
                    Locator descElement = page.locator(selector).first();
                    if (descElement.isVisible(new Locator.IsVisibleOptions().setTimeout(2000.0))) {
                        description = descElement.textContent();
                        if (description != null && !description.trim().isEmpty() && description.length() > 50) {
                            description = description.trim();
                            System.out.println("[" + getSource() + "]    ✅ Found description (" + description.length() + " chars)");
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // Step 8: Extract URL from "Apply" button
            System.out.println("[" + getSource() + "]    🔄 Looking for 'Apply' button to get URL...");
            String[] applyButtonSelectors = {
                "a.jobs-apply-button",
                "a[data-tracking-control-name='public_jobs_topcard_apply']",
                "a.jobs-s-apply-button",
                "a:has-text('Apply')",
                "button:has-text('Apply')",
                ".jobs-s-apply button",
                ".jobs-s-apply a",
                "a[href*='/jobs/view/']",
                ".jobs-details-top-card__apply-button"
            };
            
            for (String selector : applyButtonSelectors) {
                try {
                    Locator applyButton = page.locator(selector).first();
                    if (applyButton.isVisible(new Locator.IsVisibleOptions().setTimeout(2000.0))) {
                        url = applyButton.getAttribute("href");
                        if (url == null || url.isEmpty()) {
                            // If it's a button, try to find the link inside or get from data attribute
                            url = applyButton.getAttribute("data-job-id");
                            if (url != null && !url.isEmpty()) {
                                url = "https://www.linkedin.com/jobs/view/" + url;
                            }
                        }
                        if (url != null && !url.isEmpty()) {
                            if (!url.startsWith("http")) {
                                url = "https://www.linkedin.com" + url;
                            }
                            System.out.println("[" + getSource() + "]    ✅ Found URL: " + url);
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            // If URL not found from Apply button, try to get from current URL
            if (url == null || url.isEmpty()) {
                String currentUrl = page.url();
                if (currentUrl.contains("/jobs/view/")) {
                    url = currentUrl.split("\\?")[0]; // Remove query params
                    System.out.println("[" + getSource() + "]    ✅ Using URL from page: " + url);
                }
            }
            
        } catch (Exception e) {
            System.err.println("[" + getSource() + "]    ⚠️  Error extracting job data: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Validate we have at least title
        if (title == null || title.isEmpty()) {
            System.err.println("[" + getSource() + "]    ❌ Could not extract title - returning null");
            return null;
        }
        
        // Set defaults for missing fields
        if (company == null || company.isEmpty()) {
            company = "Unknown Company";
        }
        if (location == null || location.isEmpty()) {
            location = "Not Specified";
        }
        if (description == null || description.isEmpty()) {
            description = "Description not available";
        }
        if (url == null || url.isEmpty()) {
            url = "";
        }
        
        System.out.println("[" + getSource() + "]    ✅ Extracted: title='" + title + "', company='" + company + "', location='" + location + "'");
        
        // Create and return the job object
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setTitle(title);
        job.setCompany(company);
        job.setUrl(url);
        job.setPostedDate(OffsetDateTime.now());
        job.setSource(getSource());
        job.setLocation(location);
        job.setDescription(description);
        
        return job;
    }
    
    /**
     * Override scrolling for LinkedIn - LinkedIn loads jobs dynamically as you scroll
     * We need to scroll more aggressively and wait for jobs to appear
     */
    @Override
    protected void scrollToLoadMore(Page page, int scrollCount, int waitBetweenScrolls) {
        System.out.println("[" + getSource() + "] 📜 LinkedIn: Scrolling to load more jobs (" + scrollCount + " scrolls)...");
        
        String jobSelector = getJobListSelector();
        String[] selectors = jobSelector.contains(",") ? jobSelector.split(",\\s*") : new String[]{jobSelector};
        String workingSelector = selectors[0].trim(); // Use first selector
        
        int initialJobCount = 0;
        try {
            initialJobCount = page.locator(workingSelector).count();
            System.out.println("[" + getSource() + "]    Initial job count: " + initialJobCount);
        } catch (Exception e) {
            System.out.println("[" + getSource() + "]    Could not get initial count, continuing...");
        }
        
        for (int i = 0; i < scrollCount; i++) {
            // Scroll down
            page.evaluate("window.scrollBy(0, window.innerHeight * 0.9)");
            
            // Wait for LinkedIn to load new jobs (LinkedIn needs more time)
            humanDelay(page, waitBetweenScrolls + 1000, waitBetweenScrolls + 2000);
            
            // Check if new jobs appeared
            try {
                int currentCount = page.locator(workingSelector).count();
                if (currentCount > initialJobCount) {
                    System.out.println("[" + getSource() + "]    ✅ New jobs loaded! Count: " + initialJobCount + " → " + currentCount);
                    initialJobCount = currentCount;
                } else if (i % 3 == 0 && i > 0) {
                    System.out.println("[" + getSource() + "]    📊 Current job count: " + currentCount + " (scroll " + (i + 1) + "/" + scrollCount + ")");
                }
            } catch (Exception e) {
                // Continue scrolling
            }
            
            // Check for "See more jobs" or "Show more" buttons on LinkedIn
            try {
                String[] buttonSelectors = {
                    "button:has-text('See more jobs')",
                    "button:has-text('Show more')",
                    "button[aria-label*='more jobs']",
                    ".jobs-search-results__pagination button",
                    "button[data-tracking-control-name='pagination_next']"
                };
                
                for (String btnSelector : buttonSelectors) {
                    Locator loadMoreBtn = page.locator(btnSelector).first();
                    if (loadMoreBtn.count() > 0 && loadMoreBtn.isVisible()) {
                        System.out.println("[" + getSource() + "]    🔘 Found 'Load more' button, clicking...");
                        loadMoreBtn.click();
                        humanDelay(page, 3000, 5000); // Wait longer after clicking
                        break;
                }
            }
        } catch (Exception e) {
                // No load more button, continue scrolling
            }
        }
        
        // Final wait for all jobs to load
        System.out.println("[" + getSource() + "]    ⏳ Final wait for LinkedIn to finish loading jobs...");
        humanDelay(page, 3000, 5000);
        
        // Scroll back to top
        page.evaluate("window.scrollTo(0, 0)");
        humanDelay(page, 2000, 3000);
        
        // Final count
        try {
            int finalCount = page.locator(workingSelector).count();
            System.out.println("[" + getSource() + "]    ✅ Final job count after scrolling: " + finalCount);
        } catch (Exception e) {
            // Continue
        }
    }
    
    /**
     * Handle pagination for LinkedIn - navigate to pages 2, 3, etc. and continue scraping
     */
    public void handlePagination(Page page, ScrapingJobRequest request, java.util.function.Consumer<Job> sink, String workingSelector) {
        if (!getSource().equalsIgnoreCase("LinkedIn")) {
            return; // Only for LinkedIn
        }
        
        int maxRequested = request.getMaxResults() != null ? request.getMaxResults() : 50;
        int currentPage = 1;
        int totalJobsScraped = 0;
        int maxPages = 10; // Limit to 10 pages to avoid infinite loops
        
        System.out.println("[" + getSource() + "] 🔄 Starting pagination - will scrape up to " + maxPages + " pages");
        
        while (currentPage < maxPages) {
            // Check if we've scraped enough jobs
            try {
                int currentJobCount = page.locator(workingSelector).count();
                if (totalJobsScraped >= maxRequested) {
                    System.out.println("[" + getSource() + "] ✅ Reached max results (" + maxRequested + "), stopping pagination");
                    break;
                }
            } catch (Exception e) {
                // Continue
            }
            
            // Look for pagination buttons/links
            System.out.println("[" + getSource() + "] 🔍 Looking for page " + (currentPage + 1) + " navigation...");
            
            boolean foundNextPage = false;
            String[] paginationSelectors = {
                // Next button
                "button[aria-label*='Next']",
                "button[aria-label*='next']",
                "button:has-text('Next')",
                "a[aria-label*='Next']",
                "a[aria-label*='next']",
                // Page number buttons
                "button[aria-label*='Page " + (currentPage + 1) + "']",
                "a[aria-label*='Page " + (currentPage + 1) + "']",
                // Pagination links
                ".artdeco-pagination__button--next",
                ".jobs-search-results__pagination button[aria-label*='Next']",
                "button[data-tracking-control-name='pagination_next']",
                // Generic pagination
                ".pagination button:has-text('" + (currentPage + 1) + "')",
                "li[class*='pagination'] button:has-text('" + (currentPage + 1) + "')"
            };
            
            for (String selector : paginationSelectors) {
                try {
                    Locator paginationButton = page.locator(selector).first();
                    if (paginationButton.count() > 0 && paginationButton.isVisible(new Locator.IsVisibleOptions().setTimeout(3000.0))) {
                        System.out.println("[" + getSource() + "]    ✅ Found pagination button with selector: " + selector);
                        
                        // Check if button is disabled (no more pages)
                        try {
                            String ariaDisabled = paginationButton.getAttribute("aria-disabled");
                            String disabled = paginationButton.getAttribute("disabled");
                            if ("true".equals(ariaDisabled) || disabled != null) {
                                System.out.println("[" + getSource() + "]    ⚠️  Pagination button is disabled - no more pages");
                                foundNextPage = false;
                                break;
                            }
                        } catch (Exception e) {
                            // Continue
                        }
                        
                        // Click the pagination button
                        paginationButton.click(new Locator.ClickOptions().setTimeout(5000.0));
                        System.out.println("[" + getSource() + "]    ✅ Clicked pagination button - navigating to page " + (currentPage + 1));
                        foundNextPage = true;
                        
                        // Wait for navigation
                        page.waitForTimeout(3000);
                        
                        // Wait for URL to change or page to load
                        try {
                            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                                new Page.WaitForLoadStateOptions().setTimeout(15000));
                        } catch (Exception e) {
                            System.out.println("[" + getSource() + "]    ⚠️  Network idle timeout, but continuing...");
                        }
                        page.waitForTimeout(3000);
                        
                        break;
                    }
                } catch (Exception e) {
                    // Try next selector
                    continue;
                }
            }
            
            if (!foundNextPage) {
                System.out.println("[" + getSource() + "]    ⚠️  No pagination button found - reached last page");
                break;
            }
            
            currentPage++;
            System.out.println("[" + getSource() + "] 📄 Now on page " + currentPage);
            
            // Wait for job cards to load on new page
            System.out.println("[" + getSource() + "]    ⏳ Waiting for job cards to load on page " + currentPage + "...");
            page.waitForTimeout(5000);
            
            // Wait for job cards to appear
            int maxWaitAttempts = 10;
            boolean jobCardsFound = false;
            for (int attempt = 0; attempt < maxWaitAttempts; attempt++) {
                try {
                    int jobCardCount = page.locator(workingSelector).count();
                    if (jobCardCount > 0) {
                        System.out.println("[" + getSource() + "]    ✅ Found " + jobCardCount + " job cards on page " + currentPage);
                        jobCardsFound = true;
                        break;
                    }
                } catch (Exception e) {
                    // Continue waiting
                }
                page.waitForTimeout(2000);
            }
            
            if (!jobCardsFound) {
                System.out.println("[" + getSource() + "]    ⚠️  No job cards found on page " + currentPage + " - stopping pagination");
                break;
            }
            
            // Scroll to load more jobs on this page
            scrollToLoadMore(page, 5, 2000);
            page.waitForTimeout(3000);
            
            // Get all job elements on this page
            Locator jobElements = page.locator(workingSelector);
            int jobCount = jobElements.count();
            System.out.println("[" + getSource() + "]    📊 Found " + jobCount + " jobs on page " + currentPage);
            
            // Extract jobs from this page
            int jobsScrapedThisPage = 0;
            for (int i = 0; i < jobCount; i++) {
                try {
                    Locator jobElement = jobElements.nth(i);
                    Job job = extractJobData(jobElement, page);
                    
                    if (job != null) {
                        sink.accept(job);
                        jobsScrapedThisPage++;
                        totalJobsScraped++;
                        System.out.println("[" + getSource() + "] ✅ Scraped job #" + totalJobsScraped + " from page " + currentPage + ": " + 
                            (job.getTitle() != null ? job.getTitle().substring(0, Math.min(50, job.getTitle().length())) : "Untitled"));
                        
                        // Check if we've reached max results
                        if (totalJobsScraped >= maxRequested) {
                            System.out.println("[" + getSource() + "] ✅ Reached max results (" + maxRequested + "), stopping pagination");
                            return;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "] ❌ Error scraping job from page " + currentPage + ": " + e.getMessage());
                    // Continue with next job
                }
            }
            
            System.out.println("[" + getSource() + "]    ✅ Scraped " + jobsScrapedThisPage + " jobs from page " + currentPage + " (total: " + totalJobsScraped + ")");
        }
        
        System.out.println("[" + getSource() + "] ✅ Pagination complete - scraped " + totalJobsScraped + " jobs across " + currentPage + " pages");
    }
    
    /**
     * Override to get more scrolls for LinkedIn (LinkedIn needs more scrolling)
     * But don't over-scroll - if there are few results, scrolling more won't help
     */
    @Override
    protected int getScrollCount(ScrapingJobRequest request) {
        Integer maxResults = request.getMaxResults();
        int requested = maxResults != null ? maxResults : 50;
        // LinkedIn loads ~5-7 jobs initially, then more with scrolling
        // Use fewer scrolls to avoid wasting time when there are few jobs
        int scrolls = Math.min(10, Math.max(3, requested / 5));  // Between 3 and 10 scrolls
        System.out.println("[" + getSource() + "] 📜 LinkedIn will perform " + scrolls + " scrolls to load jobs");
        return scrolls;
    }
    
    /**
     * Check if we're using cookie-based authentication (li_at cookie in config.json)
     */
    private boolean checkIfUsingCookieAuth() {
        try {
            java.io.File configFile = new java.io.File("config.json");
            if (!configFile.exists()) {
                return false;
            }
            
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.io.FileReader reader = new java.io.FileReader(configFile);
            java.util.Map<String, String> config = gson.fromJson(reader, 
                new com.google.gson.reflect.TypeToken<java.util.Map<String, String>>(){}.getType());
            reader.close();
            
            String liAtCookie = config.get("LINKEDIN_LI_AT_COOKIE");
            return liAtCookie != null && !liAtCookie.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if we're already logged in to LinkedIn (no sign-in button visible)
     */
    private boolean checkIfLoggedIn(Page page) {
        try {
            // Wait a bit for page to load
            page.waitForTimeout(2000);
            
            // Check for sign-in button (if present, we're NOT logged in)
            String[] signInSelectors = {
                "a.nav__button-secondary:has-text('Sign in')",
                "a:has-text('Sign in')",
                "button:has-text('Sign in')"
            };
            
            for (String selector : signInSelectors) {
                try {
                    if (page.locator(selector).count() > 0 && page.locator(selector).first().isVisible()) {
                        return false; // Sign-in button found = not logged in
                    }
                } catch (Exception e) {
                    // Continue
                }
            }
            
            // Check for logged-in indicators (profile menu, etc.)
            String[] loggedInSelectors = {
                "button[aria-label*='Me']",
                ".global-nav__me",
                "img[alt*='profile']",
                ".nav__button-secondary--member"
            };
            
            for (String selector : loggedInSelectors) {
                try {
                    if (page.locator(selector).count() > 0) {
                        return true; // Logged-in indicator found
                    }
                } catch (Exception e) {
                    // Continue
                }
            }
            
            // If URL doesn't contain /login, assume we're logged in
            return !page.url().contains("/login") && !page.url().contains("/checkpoint");
        } catch (Exception e) {
            return false; // On error, assume not logged in
        }
    }
}
