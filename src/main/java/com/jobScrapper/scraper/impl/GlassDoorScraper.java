package com.jobScrapper.scraper.impl;

import com.jobScrapper.scraper.BaseJobScraper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;
/**
 * Glassdoor scraper with ANTI-CLOUDFLARE features:
 * - AUTO-LOGIN to bypass Cloudflare (logged-in users are trusted)
 * - Extended scrolling to load all jobs
 * - Human-like delays between actions
 * - Smart fallback when Cloudflare blocks detail pages
 */
public class GlassDoorScraper extends BaseJobScraper{
    
    private static final String GLASSDOOR_JOBS_URL ="https://www.glassdoor.com/Jobs";
    
    // LOGIN CREDENTIALS - Change these to your own
    private static final String GLASSDOOR_EMAIL = "sharma.rishabh@northeastern.edu";
    private static final String GLASSDOOR_PASSWORD = "Parthrishabh@448";
    
    // Track if we've encountered Cloudflare to avoid hammering detail pages
    private boolean cloudflareEncountered = false;
    
    @Override
    public String getSource(){
        return "Glassdoor";  // Must match SourcesEnum.GLASSDOOR value
    }

    @Override
    protected String buildSearchURL(ScrapingJobRequest request){
        // Glassdoor search URL - use the search page and let it handle location filtering
        // We'll navigate to the search page and then interact with the location filter if needed
        // Format: https://www.glassdoor.com/Job/jobs.htm?sc.keyword=...&locT=C&locId=...

        StringBuilder url = new StringBuilder("https://www.glassdoor.com/Job/jobs.htm");
        boolean hasParams = false;

        // Add keywords
        List<String> keywords = request.getKeywords();
        if(keywords != null && !keywords.isEmpty()){
            String keywordsParam = String.join(" ", keywords);
            url.append("?sc.keyword=").append(keywordsParam.replace(" ", "%20"));
            hasParams = true;
        }
        
        // Add location - Glassdoor needs location in a specific format
        String location = request.getLocation();
        if(location != null && !location.isEmpty()){
            if(hasParams){
                url.append("&");
            } else {
                url.append("?");
            }
            // Parse location: "San Francisco, CA" -> city="San Francisco", state="CA"
            String[] locationParts = location.split(",");
            if(locationParts.length >= 2){
                String city = locationParts[0].trim();
                String state = locationParts[1].trim();
                // Use locT=C for city and locId with city, state format
                url.append("locT=C&locId=").append(city.replace(" ", "%20")).append("%2C%20").append(state);
            } else {
                // Fallback: use the whole location string
                url.append("locT=C&locId=").append(location.replace(" ", "%20").replace(",", "%2C"));
            }
        }
        
        return url.toString();
    }

    @Override
    protected String getJobListSelector(){
        // Glassdoor uses various selectors - UPDATED for 2024 structure
        // Primary: job cards with data-test attributes (most reliable)
        // Updated with modern selectors based on current Glassdoor structure
        return "li[data-test='jobListing'], ul[data-test='jobsList'] > li, div[data-test='jobListing'], li.react-job-listing, div.JobCard, li.JobCard, div[class*='JobCard'], li[class*='JobCard'], article[class*='JobCard'], ul.jobsList > li, div.jobContainer, article.jobContainer, li.jobContainer, div[class*='jobContainer'], ul[class*='JobsList'] > li, div[data-jobid], li[data-jobid]";
    }
    
    /**
     * Glassdoor needs more scrolling because it uses infinite scroll
     */
    @Override
    protected int getScrollCount(ScrapingJobRequest request) {
        Integer maxResults = request.getMaxResults();
        int requested = maxResults != null ? maxResults : 25;
        // Glassdoor shows about 10-15 jobs per scroll
        return Math.max(5, (requested / 12) + 2);  // Extra scrolls for safety
    }
    
    /**
     * Skip detail pages if Cloudflare is actively blocking
     */
    @Override
    protected boolean shouldSkipDetailPages() {
        return cloudflareEncountered;
    }


    @Override
    protected void handleLoginIfNeeded(Page page){
        // STRATEGY: Go directly to Glassdoor (skip Google - it flags automated browsers)
        // Then login to get full access
        
        try {
            System.out.println("[" + getSource() + "] 🌐 Navigating directly to Glassdoor...");
            System.out.println("[" + getSource() + "]    (Skipping Google to avoid detection)\n");
            
            // ═══════════════════════════════════════════════════════════════════
            // STEP 1: Go directly to Glassdoor homepage
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("[" + getSource() + "] 📍 Step 1: Going to Glassdoor...");
            page.navigate("https://www.glassdoor.com");
            humanDelay(page, 4000, 6000);
            
            // Handle Cloudflare if present
            if (waitForCloudflare(page, 60)) {
                System.out.println("[" + getSource() + "] ✅ Passed Cloudflare check");
            }
            
            humanDelay(page, 2000, 3000);

            // ═══════════════════════════════════════════════════════════════════
            // STEP 2: Check if already logged in
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("[" + getSource() + "] 👤 Step 2: Checking login status...");
            boolean isLoggedIn = checkIfLoggedIn(page);
            
            if (isLoggedIn) {
                System.out.println("[" + getSource() + "] ✅ Already logged in! Proceeding to scrape...");
                return;
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // STEP 3: Click Sign In button on homepage
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("[" + getSource() + "] 🔑 Step 3: Clicking Sign In...");
            System.out.println("[" + getSource() + "]    Email: " + GLASSDOOR_EMAIL);
            
            try {
                // Look for Sign In link/button on homepage
                Locator signInLink = page.locator("a:has-text('Sign In'), button:has-text('Sign In'), a[href*='login'], a[data-test='sign-in-link']").first();
                if (signInLink.count() > 0 && signInLink.isVisible()) {
                    signInLink.click();
                    humanDelay(page, 3000, 5000);
                    System.out.println("[" + getSource() + "]    ✅ Clicked Sign In link");
                } else {
                    // Navigate directly to login page
                    page.navigate("https://www.glassdoor.com/profile/login_input.htm");
                    humanDelay(page, 3000, 5000);
                }
            } catch (Exception e) {
                page.navigate("https://www.glassdoor.com/profile/login_input.htm");
                humanDelay(page, 3000, 5000);
            }
            
            // Handle Cloudflare on login page
            if (waitForCloudflare(page, 45)) {
                System.out.println("[" + getSource() + "] ✅ Passed Cloudflare on login page");
            }
            
            humanDelay(page, 2000, 3000);
            
            // ═══════════════════════════════════════════════════════════════════
            // STEP 4: Enter email (type slowly like a human)
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("[" + getSource() + "] ✉️  Step 4: Entering email...");
            try {
                Locator emailField = page.locator("input[type='email'], input[name='username'], input#inlineUserEmail, input[data-test='emailInput'], input[name='email']").first();
                if (emailField.count() > 0) {
                    emailField.click();
                    humanDelay(page, 500, 1000);
                    // Type slowly like a human
                    emailField.type(GLASSDOOR_EMAIL, new Locator.TypeOptions().setDelay(50));
                    humanDelay(page, 1000, 2000);
                    System.out.println("[" + getSource() + "]    ✅ Email entered");
                } else {
                    System.err.println("[" + getSource() + "]    ❌ Could not find email field");
                    }
                } catch (Exception e) {
                System.err.println("[" + getSource() + "]    Error entering email: " + e.getMessage());
            }
            
            // Click continue/next button if there's a two-step login
                try {
                Locator continueBtn = page.locator("button[type='submit'], button:has-text('Continue'), button:has-text('Next'), button[data-test='continueButton']").first();
                if (continueBtn.count() > 0 && continueBtn.isVisible()) {
                    System.out.println("[" + getSource() + "]    Clicking continue...");
                    continueBtn.click();
                    humanDelay(page, 2000, 3000);
                    }
                } catch (Exception e) {
                // May not have a continue button, that's okay
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // STEP 5: Enter password (type slowly like a human)
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("[" + getSource() + "] 🔒 Step 5: Entering password...");
                try {
                Locator passwordField = page.locator("input[type='password'], input[name='password'], input#inlineUserPassword, input[data-test='passwordInput']").first();
                if (passwordField.count() > 0) {
                    passwordField.click();
                    humanDelay(page, 500, 1000);
                    // Type slowly like a human
                    passwordField.type(GLASSDOOR_PASSWORD, new Locator.TypeOptions().setDelay(50));
                    humanDelay(page, 1000, 2000);
                    System.out.println("[" + getSource() + "]    ✅ Password entered");
                } else {
                    System.err.println("[" + getSource() + "]    ❌ Could not find password field");
                    }
                } catch (Exception e) {
                System.err.println("[" + getSource() + "]    Error entering password: " + e.getMessage());
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // STEP 6: Click Sign In button
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("[" + getSource() + "] 🚀 Step 6: Clicking Sign In button...");
            try {
                Locator signInBtn = page.locator("button[type='submit'], button:has-text('Sign In'), button:has-text('Log In'), button[data-test='signInButton'], button[name='submit']").first();
                if (signInBtn.count() > 0) {
                    signInBtn.click();
                    humanDelay(page, 5000, 8000);  // Wait for login to complete
                    System.out.println("[" + getSource() + "]    ✅ Sign In clicked");
                } else {
                    // Try pressing Enter instead
                    page.keyboard().press("Enter");
                    humanDelay(page, 5000, 8000);
                }
            } catch (Exception e) {
                System.err.println("[" + getSource() + "]    Error clicking sign in: " + e.getMessage());
            }
            
            // Handle any CAPTCHA or verification
            humanDelay(page, 3000, 5000);
            String currentUrl = page.url().toLowerCase();
            if (currentUrl.contains("captcha") || currentUrl.contains("verify") || currentUrl.contains("challenge")) {
                System.out.println("[" + getSource() + "] ⚠️  CAPTCHA or verification required!");
                System.out.println("[" + getSource() + "]    Please complete it manually in the browser...");
                System.out.println("[" + getSource() + "]    Waiting 45 seconds...");
                humanDelay(page, 45000, 50000);
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // STEP 7: Verify login was successful
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("[" + getSource() + "] ✅ Step 7: Verifying login...");
            humanDelay(page, 2000, 3000);
            isLoggedIn = checkIfLoggedIn(page);
            
            if (isLoggedIn) {
                System.out.println("[" + getSource() + "] 🎉 LOGIN SUCCESSFUL! Ready to scrape jobs...\n");
            } else {
                System.out.println("[" + getSource() + "] ⚠️  Login may not have completed. Check browser window.");
                System.out.println("[" + getSource() + "]    Waiting 30 more seconds for manual completion if needed...");
                humanDelay(page, 30000, 35000);
            }
            
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error in handleLoginIfNeeded: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Check if user is logged in to Glassdoor
     */
    private boolean checkIfLoggedIn(Page page) {
        try {
            // Look for logged-in indicators
            Locator profileMenu = page.locator("button[data-test='desktop-profile-header'], div[class*='ProfileAvatar'], a[href*='/member/profile'], a[href*='/member/home']").first();
            Locator signOutLink = page.locator("a[href*='signout'], a:has-text('Sign Out')").first();
            Locator accountLink = page.locator("a[href*='/member/'], div[class*='account']").first();
            
            if (profileMenu.count() > 0 || signOutLink.count() > 0 || accountLink.count() > 0) {
                return true;
            }
            
            // Also check if we're on a member page
            String currentUrl = page.url();
            if (currentUrl.contains("/member/") || currentUrl.contains("/profile/")) {
                return true;
                        }
                    } catch (Exception e) {
            // Couldn't check
        }
        return false;
    }
    
    /**
     * Wait for Cloudflare challenge to pass
     * @return true if Cloudflare was detected and passed, false if no Cloudflare
     */
    private boolean waitForCloudflare(Page page, int maxWaitSeconds) {
        try {
            String pageTitle = page.title().toLowerCase();
            String pageUrl = page.url().toLowerCase();
            
            boolean isCloudflare = pageTitle.contains("just a moment") || 
                                   pageUrl.contains("challenge") || 
                                   pageUrl.contains("verify");
            
            if (!isCloudflare) {
                return false;  // No Cloudflare detected
            }
            
            System.out.println("[" + getSource() + "] ⚠️  Cloudflare detected! Waiting for it to pass...");
            System.out.println("[" + getSource() + "]    If stuck, complete the challenge manually in the browser");
            
            for (int i = 0; i < maxWaitSeconds; i += 3) {
                humanDelay(page, 3000, 3500);
                pageTitle = page.title().toLowerCase();
                pageUrl = page.url().toLowerCase();
                
                if (!pageTitle.contains("just a moment") && !pageUrl.contains("challenge") && !pageUrl.contains("verify")) {
                    return true;  // Cloudflare passed
                }
            }
            
            System.out.println("[" + getSource() + "] ⚠️  Cloudflare still present after " + maxWaitSeconds + " seconds");
            return true;  // Cloudflare was detected (even if not fully passed)
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected Job extractJobData(Locator jobElement, Page page){
        // Glassdoor job extraction - try multiple selectors for each field
        
        //Step 1 : Get the job Title
        String title = "";
        String[] titleSelectors = {
            "a[data-test='job-title']",
            "a.jobLink",
            "h2 a",
            "h3 a",
            "a.jobTitle",
            "span[data-test='job-title']"
        };
        for (String selector : titleSelectors) {
            try {
                Locator titleLocator = jobElement.locator(selector).first();
                if (titleLocator.count() > 0) {
                    title = titleLocator.textContent().trim();
                    if (title != null && !title.isEmpty()) break;
                }
            } catch (Exception e) {
                continue;
            }
        }
        if (title == null || title.isEmpty()) {
            System.err.println("[" + getSource() + "] Error extracting title: No matching selector found");
            title = "";
        }

        //Step 2: Get the company name
        String company = "";
        String[] companySelectors = {
            "a[data-test='employer-name']",
            "div[data-test='employer-name']",
            "span[data-test='employer-name']",
            "a.employerName",
            "div.employerName"
        };
        for (String selector : companySelectors) {
            try {
                Locator companyLocator = jobElement.locator(selector).first();
                if (companyLocator.count() > 0) {
                    company = companyLocator.textContent().trim();
                    if (company != null && !company.isEmpty()) break;
                }
            } catch (Exception e) {
                continue;
            }
        }
        if (company == null || company.isEmpty()) {
            System.err.println("[" + getSource() + "] Error extracting company: No matching selector found");
            company = "";
        }

        //Step 3: Get the Job URL
        String jobURL = "";
        try {
            // Try to get URL from title link
            for (String selector : titleSelectors) {
                try {
                    Locator linkLocator = jobElement.locator(selector).first();
                    if (linkLocator.count() > 0) {
                        String relativeUrl = linkLocator.getAttribute("href");
                        if (relativeUrl != null && !relativeUrl.isEmpty()) {
                            jobURL = relativeUrl.startsWith("http") 
                                ? relativeUrl 
                                : "https://www.glassdoor.com" + relativeUrl;
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting URL: " + e.getMessage());
        }

        //Step 4: Get the Job Location - Glassdoor shows location in various formats
        String location = "Not specified";
        String[] locationSelectors = {
            "div[data-test='job-location']",
            "span[data-test='job-location']",
            "div[data-test='emp-location']",
            "span[data-test='emp-location']",
            "div.location",
            "span.location",
            "div.jobLocation",
            "span.jobLocation",
            "div[class*='location']",
            "span[class*='location']",
            "div[class*='Location']",
            "span[class*='Location']"
        };
        for (String selector : locationSelectors) {
            try {
                Locator locationLocator = jobElement.locator(selector).first();
                if (locationLocator.count() > 0) {
                    String locationText = locationLocator.textContent();
                    if (locationText != null && !locationText.trim().isEmpty()) {
                        String trimmed = locationText.trim();
                        // Accept location if it contains comma (City, State format) or is a valid location
                        if (trimmed.contains(",") || trimmed.matches(".*[A-Z]{2}.*")) {
                            location = trimmed;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                continue;
            }
        }
        
        // If still not found, try getting all text and finding location pattern
        if (location.equals("Not specified")) {
            try {
                String allText = jobElement.textContent();
                if (allText != null && allText.contains(",")) {
                    // Split by newlines and look for location patterns
                    String[] lines = allText.split("[\n\r]+");
                    for (String line : lines) {
                        line = line.trim();
                        // Look for location pattern: "City, State" or "City, State Country"
                        // Match patterns like "Nashville, TN" or "San Francisco, CA"
                        if (line.contains(",") && 
                            (line.matches(".*, [A-Z]{2}(\\s|$).*") || // State abbreviation
                             line.matches(".*, [A-Z][a-z]+(\\s|$).*"))) { // Full state name
                            // Clean up the location (remove extra text)
                            String[] parts = line.split(",");
                            if (parts.length >= 2) {
                                location = (parts[0].trim() + ", " + parts[1].trim().split("\\s")[0]).trim();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        //Step 5: Get the job Description - first try from search results
        String description = "No description available";
        String[] descSelectors = {
            "div[data-test='job-snippet']",
            "p.job-snippet",
            "div.job-snippet",
            "span[data-test='job-snippet']",
            "div[class*='job-snippet']",
            "p[class*='snippet']"
        };
        for (String selector : descSelectors) {
            try {
                Locator descLocator = jobElement.locator(selector).first();
                if (descLocator.count() > 0) {
                    String descText = descLocator.textContent();
                    if (descText != null && !descText.trim().isEmpty()) {
                        description = descText.trim();
                        System.out.println("[" + getSource() + "] 📝 Found snippet from search results (" + description.length() + " chars)");
                        break;
                    }
                }
            } catch (Exception e) {
                continue;
            }
        }
        
        // If snippet is short or not found, try to get full description from detail page
        // BUT: Skip if we've already encountered Cloudflare (to avoid repeated blocks)
        if (!cloudflareEncountered && (description.equals("No description available") || description.length() < 100)) {
            if (jobURL != null && !jobURL.isEmpty()) {
                try {
                    System.out.println("[" + getSource() + "] 📄 Navigating to job detail page to extract full description...");
                    System.out.println("[" + getSource() + "]    URL: " + jobURL);
                    
                    // Save current URL to navigate back
                    String originalUrl = page.url();
                    
                    // Add human-like delay before navigation
                    humanDelay(page, 1500, 3000);
                    
                    // Navigate to job detail page
                    page.navigate(jobURL);
                    page.waitForLoadState();
                    humanDelay(page, 3000, 5000); // Human-like wait for initial load
                    
                    // Check page state
                    String pageTitle = page.title();
                    String pageUrl = page.url();
                    String pageContent = "";
                    try {
                        pageContent = page.content().toLowerCase();
                    } catch (Exception e) {
                        // Continue
                    }
                    System.out.println("[" + getSource() + "]    Page loaded - Title: " + pageTitle);
                    System.out.println("[" + getSource() + "]    Page loaded - URL: " + pageUrl);
                    
                    // Comprehensive Cloudflare detection
                    String pageTitleLower = pageTitle.toLowerCase();
                    String pageUrlLower = pageUrl.toLowerCase();
                    boolean isCloudflarePage = 
                        pageTitleLower.contains("just a moment") ||
                        pageTitleLower.contains("verification required") ||
                        pageUrlLower.contains("verify") || 
                        pageUrlLower.contains("challenge") ||
                        pageUrlLower.contains("cf-") ||
                        pageContent.contains("additional verification required") ||
                        pageContent.contains("help us protect") ||
                        pageContent.contains("verify you are human") ||
                        pageContent.contains("cloudflare") ||
                        pageContent.contains("ray id");
                    
                    // Check if login/verification is required
                    boolean needsLogin = pageTitleLower.contains("sign in") || pageTitleLower.contains("login") || 
                        pageUrlLower.contains("login") || pageUrlLower.contains("account") ||
                        pageUrlLower.contains("authwall");
                    
                    if (isCloudflarePage) {
                        System.err.println("[" + getSource() + "] ⚠️  Cloudflare challenge detected on job detail page!");
                        System.err.println("[" + getSource() + "]    Cannot extract description - Cloudflare is blocking access");
                        System.err.println("[" + getSource() + "]    Page URL: " + pageUrl);
                        System.err.println("[" + getSource() + "]    Using snippet from search results instead");
                        System.err.println("[" + getSource() + "]    ⚠️  Skipping detail pages for remaining jobs to avoid more Cloudflare blocks");
                        
                        // Mark that we've hit Cloudflare - stop trying detail pages
                        cloudflareEncountered = true;
                        
                        // Navigate back to search results
                        humanDelay(page, 1000, 2000);
                        page.navigate(originalUrl);
                        page.waitForLoadState();
                    } else if (needsLogin) {
                        System.err.println("[" + getSource() + "] ⚠️  Login/verification required to view job description");
                        System.err.println("[" + getSource() + "]    Page redirected to: " + pageUrl);
                        System.err.println("[" + getSource() + "]    Please log in to Glassdoor in the main browser window");
                        System.err.println("[" + getSource() + "]    Then descriptions will be available on next run");
                        
                        // Navigate back to search results
                        humanDelay(page, 1000, 2000);
                        page.navigate(originalUrl);
                        page.waitForLoadState();
                    } else {
                        // Wait more for dynamic content to load
                        page.waitForTimeout(3000);
                        
                        // Try to find and click "Show more" button if it exists
                        try {
                            String[] showMoreSelectors = {
                                "button:has-text('Show more')",
                                "button:has-text('see more')",
                                "button:has-text('See more')",
                                "span:has-text('Show more')",
                                "button[aria-label*='more']",
                                "button[class*='show-more']"
                            };
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
                        
                        // Try multiple selectors for Glassdoor job description
                        String[] detailDescriptionSelectors = {
                            "div[data-test='jobDescription']",
                            "div.jobDescription",
                            "div[class*='jobDescription']",
                            "div[class*='job-description']",
                            "div.description",
                            "div[class*='description']",
                            "section[class*='description']",
                            "div[data-test='description']",
                            "div[class*='JobDescription']",
                            "div[data-test='jobDescription'] div",
                            "div[class*='JobDescription'] div",
                            "section[class*='JobDescription']",
                            "div[role='article']",
                            "main div[class*='description']",
                            "main section[class*='description']"
                        };
                        
                        boolean found = false;
                        for (String selector : detailDescriptionSelectors) {
                            try {
                                System.out.println("[" + getSource() + "]    Trying selector: " + selector);
                                Locator descLocator = page.locator(selector).first();
                                int count = descLocator.count();
                                System.out.println("[" + getSource() + "]    Found " + count + " elements with selector: " + selector);
                                
                                if (count > 0) {
                                    // Wait for element to be visible
                                    try {
                                        descLocator.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                                    } catch (Exception e) {
                                        System.out.println("[" + getSource() + "]    Wait timeout, continuing anyway");
                                    }
                                    
                                    // Try textContent first
                                    String descText = descLocator.textContent();
                                    if (descText != null && !descText.trim().isEmpty() && descText.trim().length() > 50) {
                                        description = descText.trim();
                                        System.out.println("[" + getSource() + "] ✅ Found description (" + description.length() + " chars) using selector: " + selector);
                                        found = true;
                                        break;
                                    }
                                    
                                    // Fallback to innerHTML if textContent is empty or too short
                                    if (!found) {
                                        String innerHtml = descLocator.innerHTML();
                                        if (innerHtml != null && !innerHtml.trim().isEmpty() && innerHtml.trim().length() > 50) {
                                            description = innerHtml.replaceAll("<[^>]*>", "").trim(); // Strip HTML tags
                                            System.out.println("[" + getSource() + "] ✅ Found description (innerHTML) (" + description.length() + " chars) using selector: " + selector);
                                            found = true;
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                System.out.println("[" + getSource() + "]    Selector failed: " + selector + " - " + e.getMessage());
                                continue;
                            }
                        }
                        
                        if (!found) {
                            System.err.println("[" + getSource() + "] ⚠️  Could not find description with any specific selector");
                            System.err.println("[" + getSource() + "]    Page title: " + page.title());
                            System.err.println("[" + getSource() + "]    Page URL: " + page.url());
                            
                            // Debug: Try to find any div with "description" in class name
                            try {
                                Locator allDivs = page.locator("div[class*='description'], section[class*='description']");
                                int divCount = allDivs.count();
                                System.out.println("[" + getSource() + "]    Found " + divCount + " elements with 'description' in class");
                                
                                if (divCount > 0) {
                                    // Try the first one
                                    String testText = allDivs.first().textContent();
                                    if (testText != null && testText.length() > 20) {
                                        description = testText.trim();
                                        System.out.println("[" + getSource() + "] ⚠️  Found description using generic search (" + description.length() + " chars)");
                                        found = true;
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("[" + getSource() + "]    Error in debug section: " + e.getMessage());
                            }
                            
                            // Last resort: Try to get any text content from the page
                            if (!found) {
                                try {
                                    String pageText = page.locator("body").textContent();
                                    if (pageText != null && pageText.length() > 100) {
                                        // Extract a reasonable portion (look for job description keywords)
                                        int startIdx = pageText.toLowerCase().indexOf("job description");
                                        if (startIdx == -1) startIdx = pageText.toLowerCase().indexOf("about the job");
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
                        }
                        
                        // Navigate back to search results
                        System.out.println("[" + getSource() + "]    Navigating back to search results...");
                        page.navigate(originalUrl);
                        page.waitForLoadState();
                        page.waitForTimeout(2000); // Wait for search results to reload
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "] ❌ Error opening detail page or extracting description: " + e.getMessage());
                    System.err.println("[" + getSource() + "]    Current URL: " + page.url());
                    System.err.println("[" + getSource() + "]    Current Title: " + page.title());
                    e.printStackTrace();
                    // Fallback to snippet from search results if detail page fails
                    try {
                        Locator descriptionElement = jobElement.locator("div[data-test='job-snippet'], p.job-snippet").first();
                        if (descriptionElement.count() > 0) {
                            String descriptionText = descriptionElement.textContent();
                            if (descriptionText != null) {
                                description = descriptionText.trim();
                                System.out.println("[" + getSource() + "] ⚠️  Using snippet from search results as fallback (" + description.length() + " chars)");
                            }
                        }
                    } catch (Exception e2) {
                        // Keep default "No description available"
                    }
                }
            }
        }
        
        // Final check for description length
        if (description.length() < 50 && !description.equals("No description available")) {
            System.out.println("[" + getSource() + "] ⚠️  Description is too short (" + description.length() + " chars), falling back to 'No description available'");
            description = "No description available";
        }
        
        return new Job()
            .id(UUID.randomUUID().toString())
            .source("Glassdoor")
            .title(title != null ? title : "")
            .company(company != null ? company : "")
            .url(jobURL != null ? jobURL : "")
            .location(location)
            .description(description)
            .postedDate(OffsetDateTime.now());
    }

}
