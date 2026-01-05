package com.jobScrapper.scraper.impl;

import com.jobScrapper.scraper.BaseJobScraper;
import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Indeed scraper implementation.
 * 
 * Only implements site-specific parts:
 * - URL building (Indeed's URL format uses + for spaces)
 * - CSS selectors (Indeed's HTML structure)
 * - Data extraction (Indeed's job card format)
 * - No login needed for Indeed
 * 
 * ANTI-CLOUDFLARE FEATURES:
 * - Extended scrolling to load all jobs (Indeed uses infinite scroll)
 * - Human-like delays between actions
 * - Fallback to snippet when Cloudflare blocks detail pages
 */
public class IndeedScraper extends BaseJobScraper {

    private static final String INDEED_JOBS_URL = "https://www.indeed.com/jobs";
    
    // Track if we've encountered Cloudflare to avoid hammering detail pages
    private boolean cloudflareEncountered = false;

    @Override
    public String getSource() {
        return "Indeed";
    }

    @Override
    protected String buildSearchURL(ScrapingJobRequest request) {
        StringBuilder url = new StringBuilder(INDEED_JOBS_URL);
        url.append("?");

        // Add keywords (Indeed uses + for spaces, not %20)
        List<String> keywords = request.getKeywords();
        if (keywords != null && !keywords.isEmpty()) {
            // Replace spaces within each keyword with +, then join keywords with +
            StringBuilder keywordsBuilder = new StringBuilder();
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) keywordsBuilder.append("+");
                keywordsBuilder.append(keywords.get(i).replace(" ", "+"));
            }
            url.append("q=").append(keywordsBuilder.toString());
        }

        // Add location if provided (Indeed uses + for spaces, %2C for commas)
        String location = request.getLocation();
        if (location != null && !location.isEmpty()) {
            url.append("&l=").append(location.replace(" ", "+").replace(",", "%2C"));
        }
        
        // Request more results per page
        url.append("&limit=50");  // Request 50 results per page

        return url.toString();
    }

    @Override
    protected String getJobListSelector() {
        // Indeed's job listing selector - UPDATED for 2024 structure
        // Primary: job cards with data-jk attribute (most reliable)
        // Fallback: various container classes Indeed uses
        return "div[data-jk], li.css-5lfssm, div.job_seen_beacon, div.jobsearch-ResultsList > div, ul.jobsearch-ResultsList > li, div[class*='jobCard'], div.resultContent, div[class*='result']";
    }
    
    /**
     * Indeed needs more scrolling because it uses infinite scroll
     */
    @Override
    protected int getScrollCount(ScrapingJobRequest request) {
        Integer maxResults = request.getMaxResults();
        int requested = maxResults != null ? maxResults : 25;
        // Indeed shows about 15 jobs per page/scroll - need more scrolls
        return Math.max(5, (requested / 15) + 2);  // Extra scrolls for safety
    }
    
    /**
     * Skip detail pages if Cloudflare is actively blocking
     */
    @Override
    protected boolean shouldSkipDetailPages() {
        return cloudflareEncountered;
    }

    @Override
    protected void handleLoginIfNeeded(Page page) {
        // STRATEGY: Go directly to Indeed (skip Google - it flags automated browsers)
        
        try {
            System.out.println("[" + getSource() + "] 🌐 Navigating directly to Indeed...");
            System.out.println("[" + getSource() + "]    (Skipping Google to avoid detection)\n");
            
            // ═══════════════════════════════════════════════════════════════════
            // STEP 1: Go directly to Indeed homepage
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("[" + getSource() + "] 📍 Step 1: Going to Indeed...");
            page.navigate("https://www.indeed.com");
            humanDelay(page, 4000, 6000);
            
            // ═══════════════════════════════════════════════════════════════════
            // STEP 2: Handle Cloudflare if present
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("[" + getSource() + "] 🛡️  Step 2: Handling any Cloudflare checks...");
            if (waitForCloudflareIndeed(page, 60)) {
                System.out.println("[" + getSource() + "] ✅ Passed Cloudflare check");
            }
            
            humanDelay(page, 2000, 3000);
            System.out.println("[" + getSource() + "] ✅ Ready to search for jobs!\n");
            
            // Check current page state
            String currentUrl = page.url();
            String pageTitle = page.title().toLowerCase();
            String pageContent = "";
            try {
                pageContent = page.content().toLowerCase();
            } catch (Exception e) {
                // Continue
            }
            
            // Check for Cloudflare verification indicators
            boolean isVerificationPage = 
                currentUrl.contains("verify") || 
                currentUrl.contains("challenge") ||
                pageTitle.contains("just a moment") ||
                pageTitle.contains("verify") ||
                pageTitle.contains("captcha") ||
                pageContent.contains("additional verification required") ||
                pageContent.contains("verify you are human") ||
                pageContent.contains("verify you're human") ||
                pageContent.contains("i'm not a robot") ||
                pageContent.contains("challenge-form") ||
                pageContent.contains("cloudflare");
            
            if (isVerificationPage) {
                System.out.println("[" + getSource() + "] ⚠️  Detected Cloudflare verification page");
                System.out.println("[" + getSource() + "]    Current URL: " + currentUrl);
                System.out.println("[" + getSource() + "]    Page title: " + page.title());
                System.out.println("[" + getSource() + "]    Attempting to handle automatically...");
                
                // Wait longer for verification elements to fully load
                page.waitForTimeout(5000);
                
                // Strategy 0: Handle Cloudflare Turnstile in iframe (based on actual HTML structure)
                // Cloudflare uses an iframe with id like "cf-chl-widget-*" containing the challenge
                // The challenge often auto-verifies, so we wait for the response token
                try {
                    System.out.println("[" + getSource() + "]    Looking for Cloudflare Turnstile iframe...");
                    
                    // Find the Cloudflare challenge iframe
                    Locator cfIframe = page.locator("iframe[id^='cf-chl-widget'], iframe[title*='Cloudflare'], iframe[title*='challenge'], iframe[title*='Widget']").first();
                    if (cfIframe.count() > 0) {
                        System.out.println("[" + getSource() + "]    ✅ Found Cloudflare challenge iframe");
                        
                        // Try to click inside the iframe
                        try {
                            System.out.println("[" + getSource() + "]    Attempting to click checkbox inside iframe...");
                            // Wait for iframe to load
                            page.waitForTimeout(2000);
                            
                            // Try clicking on the iframe itself (sometimes the whole iframe is clickable)
                            try {
                                cfIframe.scrollIntoViewIfNeeded();
                                page.waitForTimeout(500);
                                cfIframe.click();
                                System.out.println("[" + getSource() + "]    ✅ Clicked Cloudflare iframe");
                            } catch (Exception e) {
                                System.out.println("[" + getSource() + "]    Could not click iframe directly: " + e.getMessage());
                            }
                            
                            // Also try clicking elements inside the frame using frameLocator
                            try {
                                com.microsoft.playwright.FrameLocator frameLocator = cfIframe.contentFrame();
                                Locator iframeCheckbox = frameLocator.locator("input[type='checkbox'], div[role='checkbox'], span[role='checkbox']").first();
                                if (iframeCheckbox.count() > 0) {
                                    iframeCheckbox.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                                    if (iframeCheckbox.isVisible()) {
                                        iframeCheckbox.click();
                                        System.out.println("[" + getSource() + "]    ✅ Clicked checkbox inside iframe");
                                    }
                                }
                            } catch (Exception e) {
                                System.out.println("[" + getSource() + "]    Could not click inside iframe (shadow DOM may be blocking): " + e.getMessage());
                            }
                        } catch (Exception e) {
                            System.out.println("[" + getSource() + "]    Could not access iframe content: " + e.getMessage());
                        }
                        
                        // Wait for the hidden input field to be populated (indicates challenge passed)
                        // Cloudflare Turnstile often auto-verifies, so we monitor for the response token
                        System.out.println("[" + getSource() + "]    Waiting for Cloudflare challenge to complete (may auto-verify)...");
                        
                        boolean challengePassed = false;
                        for (int i = 0; i < 60; i++) { // Wait up to 60 seconds
                            try {
                                // Check for the response token in hidden input fields
                                Locator responseInput = page.locator("input[name='cf-turnstile-response'], input[id*='cf-turnstile-response'], input[id*='_response'], input[name='cf_challenge_response']").first();
                                if (responseInput.count() > 0) {
                                    String value = responseInput.getAttribute("value");
                                    if (value != null && !value.isEmpty() && value.length() > 10) {
                                        System.out.println("[" + getSource() + "]    ✅ Cloudflare challenge completed! Response token received.");
                                        challengePassed = true;
                                        break;
                                    }
                                }
                                
                                // Also check if we're no longer on the verification page
                                String checkUrl = page.url();
                                String checkTitle = page.title().toLowerCase();
                                if (!checkUrl.contains("verify") && 
                                    !checkUrl.contains("challenge") && 
                                    !checkTitle.contains("just a moment") &&
                                    !checkTitle.contains("verification")) {
                                    System.out.println("[" + getSource() + "]    ✅ Redirected away from verification page!");
                                    challengePassed = true;
                                    break;
                                }
                            } catch (Exception e) {
                                // Continue waiting
                            }
                            page.waitForTimeout(1000);
                        }
                        
                        if (challengePassed) {
                            System.out.println("[" + getSource() + "]    ✅ Cloudflare verification successful!");
                            page.waitForTimeout(3000); // Wait for page to fully load
                            return; // Exit early if challenge passed
                        } else {
                            System.out.println("[" + getSource() + "]    ⚠️  Challenge did not auto-complete, trying manual interaction...");
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[" + getSource() + "]    Could not find Cloudflare iframe: " + e.getMessage());
                }
                
                boolean checkboxClicked = false;
                
                // Strategy 1: Try XPath to find checkbox near "Verify you are human" text
                try {
                    // XPath: find checkbox that is near text containing "Verify you are human"
                    String xpath = "//input[@type='checkbox'][following-sibling::text()[contains(., 'Verify you are human')] or preceding-sibling::text()[contains(., 'Verify you are human')] or parent::*/text()[contains(., 'Verify you are human')]]";
                    Locator xpathCheckbox = page.locator("xpath=" + xpath).first();
                    if (xpathCheckbox.count() > 0 && xpathCheckbox.isVisible()) {
                        System.out.println("[" + getSource() + "]    Found checkbox via XPath");
                        xpathCheckbox.scrollIntoViewIfNeeded();
                        page.waitForTimeout(500);
                        xpathCheckbox.click();
                        checkboxClicked = true;
                        System.out.println("[" + getSource() + "]    ✅ Clicked checkbox via XPath");
                    }
                } catch (Exception e) {
                    // Continue to next strategy
                }
                
                // Strategy 2: Find the checkbox by looking for text "Verify you are human" and clicking the parent container
                if (!checkboxClicked) {
                    try {
                        // Find any element containing "Verify you are human" text
                        Locator verifyContainer = page.locator("text=/Verify you are human/i").first();
                        if (verifyContainer.count() > 0) {
                            System.out.println("[" + getSource() + "]    Found 'Verify you are human' text");
                            verifyContainer.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                            
                            // Try multiple approaches to find and click the checkbox
                            try {
                                // Approach 1: Look for checkbox in parent elements
                                Locator parent = verifyContainer.locator("..");
                                Locator parentCheckbox = parent.locator("input[type='checkbox'], div[role='checkbox'], span[role='checkbox']").first();
                                if (parentCheckbox.count() > 0 && parentCheckbox.isVisible()) {
                                    parentCheckbox.scrollIntoViewIfNeeded();
                                    page.waitForTimeout(500);
                                    parentCheckbox.click();
                                    checkboxClicked = true;
                                    System.out.println("[" + getSource() + "]    ✅ Clicked checkbox in parent");
                                } else {
                                    // Approach 2: Look for checkbox in grandparent
                                    Locator grandparent = verifyContainer.locator("../..");
                                    Locator grandparentCheckbox = grandparent.locator("input[type='checkbox'], div[role='checkbox'], span[role='checkbox']").first();
                                    if (grandparentCheckbox.count() > 0 && grandparentCheckbox.isVisible()) {
                                        grandparentCheckbox.scrollIntoViewIfNeeded();
                                        page.waitForTimeout(500);
                                        grandparentCheckbox.click();
                                        checkboxClicked = true;
                                        System.out.println("[" + getSource() + "]    ✅ Clicked checkbox in grandparent");
                                    } else {
                                        // Approach 3: Click the container itself (often the whole box is clickable)
                                        verifyContainer.scrollIntoViewIfNeeded();
                                        page.waitForTimeout(500);
                                        verifyContainer.click();
                                        checkboxClicked = true;
                                        System.out.println("[" + getSource() + "]    ✅ Clicked verification container");
                                    }
                                }
                            } catch (Exception e) {
                                // Click the text element itself as last resort
                                verifyContainer.scrollIntoViewIfNeeded();
                                page.waitForTimeout(500);
                                verifyContainer.click();
                                checkboxClicked = true;
                                System.out.println("[" + getSource() + "]    ✅ Clicked verification text");
                            }
                        }
                    } catch (Exception e) {
                        // Continue to next strategy
                    }
                }
                
                // Strategy 1b: Try to find checkbox by looking for the label text first, then the associated input
                if (!checkboxClicked) {
                    try {
                        // Find the label containing "Verify you are human"
                        Locator label = page.locator("label:has-text('Verify you are human'), label:has-text('verify you are human')").first();
                        if (label.count() > 0) {
                            System.out.println("[" + getSource() + "]    Found label with 'Verify you are human' text");
                            label.scrollIntoViewIfNeeded();
                            page.waitForTimeout(500);
                            label.click();
                            checkboxClicked = true;
                            System.out.println("[" + getSource() + "]    ✅ Clicked label directly");
                        }
                    } catch (Exception e) {
                        // Continue to next strategy
                    }
                }
                
                // Strategy 3: Try direct checkbox selectors with more variations
                if (!checkboxClicked) {
                    String[] checkboxSelectors = {
                        "input[type='checkbox']",                    // Generic checkbox
                        "input#checkbox",                          // ID checkbox
                        "input[name='verify']",                     // Verify checkbox
                        "div[role='checkbox']",                    // ARIA checkbox
                        "span[role='checkbox']",                   // ARIA span checkbox
                        "input[aria-label*='human']",               // Checkbox with human in aria-label
                        "input[aria-label*='verify']",              // Checkbox with verify in aria-label
                        "input[aria-label*='robot']",               // Checkbox with robot in aria-label
                        "div[class*='checkbox']",                   // Div with checkbox class
                        "span[class*='checkbox']",                  // Span with checkbox class
                        "label input[type='checkbox']",            // Checkbox inside label
                        "label:has-text('Verify') input",          // Checkbox in label with Verify text
                    };
                    
                    for (String selector : checkboxSelectors) {
                        try {
                            Locator checkbox = page.locator(selector).first();
                            if (checkbox.count() > 0) {
                                checkbox.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                                if (checkbox.isVisible()) {
                                    System.out.println("[" + getSource() + "]    Found checkbox with selector: " + selector);
                                    checkbox.scrollIntoViewIfNeeded();
                                    page.waitForTimeout(500);
                                    checkbox.click();
                                    checkboxClicked = true;
                                    System.out.println("[" + getSource() + "]    ✅ Clicked verification checkbox");
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            // Try next selector
                            continue;
                        }
                    }
                }
                
                // Strategy 4: Try finding checkbox by clicking anywhere in the verification box area
                if (!checkboxClicked) {
                    try {
                        // Find the main verification container/box
                        Locator verifyBox = page.locator("div:has-text('Additional Verification'), div:has-text('Verify you are human'), form:has-text('Verify')").first();
                        if (verifyBox.count() > 0) {
                            System.out.println("[" + getSource() + "]    Found verification box, looking for checkbox inside...");
                            verifyBox.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                            
                            // Try to find checkbox inside this box
                            Locator boxCheckbox = verifyBox.locator("input[type='checkbox'], div[role='checkbox'], span[role='checkbox']").first();
                            if (boxCheckbox.count() > 0 && boxCheckbox.isVisible()) {
                                boxCheckbox.scrollIntoViewIfNeeded();
                                page.waitForTimeout(500);
                                boxCheckbox.click();
                                checkboxClicked = true;
                                System.out.println("[" + getSource() + "]    ✅ Clicked checkbox inside verification box");
                            } else {
                                // Click the center of the box
                                verifyBox.scrollIntoViewIfNeeded();
                                page.waitForTimeout(500);
                                verifyBox.click();
                                checkboxClicked = true;
                                System.out.println("[" + getSource() + "]    ✅ Clicked verification box center");
                            }
                        }
                    } catch (Exception e) {
                        // Continue to next strategy
                    }
                }
                
                // Strategy 3: Try clicking on the text "Verify you are human" directly
                if (!checkboxClicked) {
                    try {
                        Locator verifyText = page.locator("text='Verify you are human', text='verify you are human'").first();
                        if (verifyText.count() > 0) {
                            verifyText.waitFor(new Locator.WaitForOptions().setTimeout(2000));
                            if (verifyText.isVisible()) {
                                System.out.println("[" + getSource() + "]    Found 'Verify you are human' text, clicking...");
                                verifyText.scrollIntoViewIfNeeded();
                                page.waitForTimeout(500);
                                verifyText.click();
                                checkboxClicked = true;
                            }
                        }
                    } catch (Exception e) {
                        // Try next approach
                    }
                }
                
                // Strategy 4: Try clicking anywhere in the verification container
                if (!checkboxClicked) {
                    try {
                        Locator verifyBox = page.locator("div:has-text('Verify you are human'), div:has-text('Additional Verification')").first();
                        if (verifyBox.count() > 0) {
                            verifyBox.waitFor(new Locator.WaitForOptions().setTimeout(2000));
                            if (verifyBox.isVisible()) {
                                System.out.println("[" + getSource() + "]    Found verification box, clicking center...");
                                verifyBox.scrollIntoViewIfNeeded();
                                page.waitForTimeout(500);
                                // Click in the center of the box
                                verifyBox.click();
                                checkboxClicked = true;
                            }
                        }
                    } catch (Exception e) {
                        // Continue
                    }
                }
                
                if (checkboxClicked) {
                    // Wait for verification to process - Cloudflare can take time
                    System.out.println("[" + getSource() + "]    Waiting for verification to complete...");
                    
                    // Wait up to 30 seconds for redirect away from verification page
                    boolean verified = false;
                    for (int i = 0; i < 30; i++) {
                        page.waitForTimeout(1000);
                        try {
                            String newUrl = page.url();
                            String newTitle = page.title().toLowerCase();
                            // Check if we're past the verification page
                            if (!newUrl.contains("verify") && 
                                !newUrl.contains("challenge") && 
                                !newTitle.contains("just a moment") &&
                                !newTitle.contains("verification")) {
                                System.out.println("[" + getSource() + "]    ✅ Verification completed! Redirected to: " + newUrl);
                                page.waitForTimeout(3000); // Extra wait for page to fully load
                                verified = true;
                                break;
                            }
                        } catch (Exception e) {
                            // Continue waiting
                        }
                    }
                    
                    if (!verified) {
                        System.out.println("[" + getSource() + "]    ⚠️  Still on verification page after clicking");
                        System.out.println("[" + getSource() + "]    Cloudflare may require additional verification");
                        System.out.println("[" + getSource() + "]    Waiting 30 more seconds for manual verification...");
                        page.waitForTimeout(30000);
                    }
                } else {
                    System.out.println("[" + getSource() + "]    ⚠️  Could not find verification checkbox automatically");
                    System.out.println("[" + getSource() + "]    Please complete verification manually in the browser");
                    System.out.println("[" + getSource() + "]    Waiting up to 90 seconds for manual verification...");
                    
                    // Wait and check periodically if verification completed
                    boolean verified = false;
                    for (int i = 0; i < 90; i++) {
                        page.waitForTimeout(1000);
                        try {
                            String newUrl = page.url();
                            String newTitle = page.title().toLowerCase();
                            // Check if we're past the verification page
                            if (!newUrl.contains("verify") && 
                                !newUrl.contains("challenge") && 
                                !newTitle.contains("just a moment") &&
                                !newTitle.contains("checking your browser") &&
                                !newTitle.contains("please wait")) {
                                verified = true;
                                System.out.println("[" + getSource() + "]    ✅ Verification completed! Continuing...");
                                System.out.println("[" + getSource() + "]    Current URL: " + newUrl);
                                System.out.println("[" + getSource() + "]    Current title: " + page.title());
                                page.waitForTimeout(3000); // Extra wait for page to fully load
                                break;
                            }
                        } catch (Exception e) {
                            // Continue waiting
                        }
                    }
                    
                    if (!verified) {
                        System.out.println("[" + getSource() + "]    ⚠️  Still on verification page after 90 seconds");
                        System.out.println("[" + getSource() + "]    Current URL: " + page.url());
                        System.out.println("[" + getSource() + "]    Current title: " + page.title());
                        System.out.println("[" + getSource() + "]    ⚠️  Will attempt to continue anyway...");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "]    Error handling verification: " + e.getMessage());
            System.out.println("[" + getSource() + "]    Waiting 30 seconds for manual verification...");
            try {
                page.waitForTimeout(30000);
            } catch (Exception ex) {
                // Ignore
            }
        }
    }
    
    /**
     * Wait for Cloudflare challenge to pass on Indeed
     * @return true if Cloudflare was detected and passed, false if no Cloudflare
     */
    private boolean waitForCloudflareIndeed(Page page, int maxWaitSeconds) {
        try {
            String pageTitle = page.title().toLowerCase();
            String pageUrl = page.url().toLowerCase();
            
            boolean isCloudflare = pageTitle.contains("just a moment") || 
                                   pageUrl.contains("challenge") || 
                                   pageUrl.contains("verify") ||
                                   pageTitle.contains("additional verification");
            
            if (!isCloudflare) {
                return false;  // No Cloudflare detected
            }
            
            System.out.println("[" + getSource() + "] ⚠️  Cloudflare detected! Waiting for it to pass...");
            System.out.println("[" + getSource() + "]    If stuck, complete the challenge manually in the browser");
            
            for (int i = 0; i < maxWaitSeconds; i += 3) {
                humanDelay(page, 3000, 3500);
                pageTitle = page.title().toLowerCase();
                pageUrl = page.url().toLowerCase();
                
                if (!pageTitle.contains("just a moment") && 
                    !pageUrl.contains("challenge") && 
                    !pageUrl.contains("verify") &&
                    !pageTitle.contains("additional verification")) {
                    System.out.println("[" + getSource() + "] ✅ Cloudflare passed!");
                    return true;
                }
            }
            
            System.out.println("[" + getSource() + "] ⚠️  Cloudflare still present after " + maxWaitSeconds + " seconds");
            return true;  // Cloudflare was detected
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected Job extractJobData(Locator jobElement, Page page) {
        // Extract job title (Indeed's structure)
        // Use .first() to avoid strict mode violation when both a and span exist
        String title = "";
        try {
            // Try multiple selectors for title - Indeed has different structures
            String[] titleSelectors = {
                "h2.jobTitle span[title]",
                "h2.jobTitle span",
                "h2.jobTitle a span",
                "h2.jobTitle a",
                "a[data-jk] span",
                "a[data-jk]"
            };
            
            for (String selector : titleSelectors) {
                try {
                    Locator titleLocator = jobElement.locator(selector).first();
                    if (titleLocator.count() > 0) {
                        String titleText = titleLocator.textContent();
                        if (titleText != null && !titleText.trim().isEmpty()) {
                            title = titleText.trim();
                            break;
                        }
                        // Also try getting title attribute
                        String titleAttr = titleLocator.getAttribute("title");
                        if (titleAttr != null && !titleAttr.trim().isEmpty()) {
                            title = titleAttr.trim();
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue; // Try next selector
                }
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting title: " + e.getMessage());
        }

        // Extract company name - try multiple selectors
        String company = "";
        String[] companySelectors = {
            "span.companyName",
            "a[data-testid='company-name']",
            "div.companyName",
            "span[data-testid='company-name']",
            "a.companyName"
        };
        for (String selector : companySelectors) {
            try {
                Locator companyLocator = jobElement.locator(selector).first();
                if (companyLocator.count() > 0) {
                    String companyText = companyLocator.textContent();
                    if (companyText != null && !companyText.trim().isEmpty()) {
                        company = companyText.trim();
                        break;
                    }
                }
            } catch (Exception e) {
                continue; // Try next selector
            }
        }

        // Extract job URL - use .first() to avoid strict mode violation
        String jobURL = "";
        try {
            Locator urlLink = jobElement.locator("h2.jobTitle a").first();
            if (urlLink.count() > 0) {
                String relativeUrl = urlLink.getAttribute("href");
                if (relativeUrl != null) {
                    jobURL = relativeUrl.startsWith("http")
                        ? relativeUrl
                        : "https://www.indeed.com" + relativeUrl;
                }
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting URL: " + e.getMessage());
        }

        // Extract location
        String location = "Not Specified";
        try {
            location = jobElement.locator("div.companyLocation").textContent().trim();
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting location: " + e.getMessage());
        }

        // Extract description/snippet - first try from search results
        String description = "No description available";
        try {
            Locator snippetLocator = jobElement.locator("div.job-snippet, span.job-snippet, div.summary").first();
            if (snippetLocator.count() > 0) {
                String snippetText = snippetLocator.textContent();
                if (snippetText != null && !snippetText.trim().isEmpty()) {
                    description = snippetText.trim();
                    System.out.println("[" + getSource() + "] 📝 Found snippet from search results (" + description.length() + " chars)");
                }
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting snippet: " + e.getMessage());
        }
        
        // If snippet is short or not found, try to get full description from detail page
        // BUT: Skip if we've already encountered Cloudflare (to avoid repeated blocks)
        if (!cloudflareEncountered && (description.equals("No description available") || description.length() < 100)) {
            if (!jobURL.isEmpty()) {
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
                        pageContent.contains("ray id") ||
                        pageContent.contains("challenges.cloudflare.com") ||
                        pageContent.contains("enable javascript and cookies") ||
                        page.locator("text=/Additional Verification Required/i").count() > 0 ||
                        page.locator("text=/Help Us Protect/i").count() > 0 ||
                        page.locator("text=/Please unblock challenges.cloudflare.com/i").count() > 0;
                    
                    // Check if login/verification is required
                    boolean needsLogin = pageTitleLower.contains("sign in") || 
                                       pageTitleLower.contains("login") ||
                                       pageUrlLower.contains("login") || 
                                       pageUrlLower.contains("account");
                    
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
                        // Don't try to extract description - use what we have from snippet
                        // Continue to end of method with current description
                    } else if (needsLogin) {
                        System.err.println("[" + getSource() + "] ⚠️  Login required to view job description");
                        System.err.println("[" + getSource() + "]    Page redirected to: " + pageUrl);
                        System.err.println("[" + getSource() + "]    Please log in to Indeed in the main browser window");
                        System.err.println("[" + getSource() + "]    Then descriptions will be available on next run");
                        // Navigate back to search results
                        page.navigate(originalUrl);
                        page.waitForLoadState();
                        // Use snippet instead - continue to end of method
                    } else {
                        // Wait more for dynamic content to load
                        page.waitForTimeout(3000);
                        
                        // Try multiple selectors for Indeed job description
                        String[] descriptionSelectors = {
                            "div#jobDescriptionText",
                            "div.jobsearch-jobDescriptionText",
                            "div[class*='jobDescriptionText']",
                            "div[class*='job-description']",
                            "div[data-testid='job-description']",
                            "div#jobDescriptionText div",
                            "div.jobsearch-jobDescriptionText div",
                            "div.description",
                            "div[class*='description']",
                            "div[class*='jobDescription']",
                            "div[data-test='jobDescription']",
                            "section[class*='description']",
                            "div[role='article']",
                            "main div[class*='description']"
                        };
                        
                        boolean found = false;
                        for (String selector : descriptionSelectors) {
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
                                        // Check if description contains Cloudflare content
                                        String descTextLower = descText.toLowerCase();
                                        if (descTextLower.contains("additional verification required") ||
                                            descTextLower.contains("cloudflare") ||
                                            descTextLower.contains("ray id") ||
                                            descTextLower.contains("challenges.cloudflare.com") ||
                                            descTextLower.contains("enable javascript and cookies")) {
                                            System.err.println("[" + getSource() + "] ⚠️  Description contains Cloudflare content - rejecting");
                                            continue; // Try next selector
                                        }
                                        
                                        description = descText.trim();
                                        System.out.println("[" + getSource() + "] ✅ Found description (" + description.length() + " chars) using selector: " + selector);
                                        found = true;
                                        break;
                                    }
                                    
                                    // Fallback to innerHTML if textContent is empty or too short
                                    if (!found) {
                                        String innerHtml = descLocator.innerHTML();
                                        if (innerHtml != null && !innerHtml.trim().isEmpty() && innerHtml.trim().length() > 50) {
                                            // Check if innerHTML contains Cloudflare content
                                            String innerHtmlLower = innerHtml.toLowerCase();
                                            if (innerHtmlLower.contains("additional verification required") ||
                                                innerHtmlLower.contains("cloudflare") ||
                                                innerHtmlLower.contains("ray id") ||
                                                innerHtmlLower.contains("challenges.cloudflare.com")) {
                                                System.err.println("[" + getSource() + "] ⚠️  innerHTML contains Cloudflare content - rejecting");
                                                continue; // Try next selector
                                            }
                                            
                                            description = innerHtml.replaceAll("<[^>]*>", "").trim(); // Strip HTML tags
                                            
                                            // Final check on extracted text
                                            if (description.toLowerCase().contains("additional verification") ||
                                                description.toLowerCase().contains("ray id")) {
                                                System.err.println("[" + getSource() + "] ⚠️  Extracted text contains Cloudflare content - rejecting");
                                                continue; // Try next selector
                                            }
                                            
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
                                        // Check if page text contains Cloudflare indicators
                                        String pageTextLower = pageText.toLowerCase();
                                        if (pageTextLower.contains("additional verification required") ||
                                            pageTextLower.contains("cloudflare") ||
                                            pageTextLower.contains("ray id") ||
                                            pageTextLower.contains("challenges.cloudflare.com") ||
                                            pageTextLower.contains("enable javascript and cookies")) {
                                            System.err.println("[" + getSource() + "] ⚠️  Page text contains Cloudflare content - rejecting");
                                            // Navigate back and use snippet
                                            page.navigate(originalUrl);
                                            page.waitForLoadState();
                                            // Use snippet instead - don't update description
                                            found = false; // Mark as not found so we use snippet
                                        } else {
                                            // Extract a reasonable portion (look for job description keywords)
                                            int startIdx = pageText.toLowerCase().indexOf("job description");
                                            if (startIdx == -1) startIdx = pageText.toLowerCase().indexOf("about the job");
                                            if (startIdx == -1) startIdx = pageText.toLowerCase().indexOf("responsibilities");
                                            if (startIdx == -1) startIdx = 0;
                                            
                                            int endIdx = Math.min(startIdx + 3000, pageText.length());
                                            String extractedText = pageText.substring(startIdx, endIdx).trim();
                                            
                                            // Final check: reject if it looks like Cloudflare content
                                            if (extractedText.toLowerCase().contains("additional verification") ||
                                                extractedText.toLowerCase().contains("ray id") ||
                                                extractedText.toLowerCase().contains("challenges.cloudflare")) {
                                                System.err.println("[" + getSource() + "] ⚠️  Extracted text contains Cloudflare content - rejecting");
                                                page.navigate(originalUrl);
                                                page.waitForLoadState();
                                                // Use snippet instead - don't update description
                                                found = false; // Mark as not found so we use snippet
                                            } else {
                                                description = extractedText;
                                                System.out.println("[" + getSource() + "] ⚠️  Using page text as fallback (" + description.length() + " chars)");
                                                found = true;
                                            }
                                        }
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
                        Locator descriptionElement = jobElement.locator("div.job-snippet, span.job-snippet").first();
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
        
        // Final check: Reject description if it contains Cloudflare content
        String descLower = description.toLowerCase();
        if (descLower.contains("additional verification required") ||
            descLower.contains("cloudflare") ||
            descLower.contains("ray id") ||
            descLower.contains("challenges.cloudflare.com") ||
            descLower.contains("enable javascript and cookies") ||
            descLower.contains("please unblock challenges")) {
            System.err.println("[" + getSource() + "] ⚠️  Final check: Description contains Cloudflare content - rejecting");
            description = "No description available (Cloudflare blocked access)";
        }
        
        // Final check for description length
        if (description.length() < 50 && !description.equals("No description available") && 
            !description.equals("No description available (Cloudflare blocked access)")) {
            System.out.println("[" + getSource() + "] ⚠️  Description is too short (" + description.length() + " chars), falling back to 'No description available'");
            description = "No description available";
        }

        // Create and return Job object
        // Ensure we have at least some data before creating job
        if (title.isEmpty() && company.isEmpty()) {
            System.err.println("[" + getSource() + "] ⚠️  Warning: Job has no title or company, creating with minimal data");
        }
        
        return new Job()
            .id(UUID.randomUUID().toString())
            .source("Indeed")
            .title(title.isEmpty() ? "Job Title Not Available" : title)
            .company(company.isEmpty() ? "Company Not Available" : company)
            .url(jobURL.isEmpty() ? "https://www.indeed.com" : jobURL)
            .location(location)
            .description(description)
            .postedDate(OffsetDateTime.now());
    }
}
