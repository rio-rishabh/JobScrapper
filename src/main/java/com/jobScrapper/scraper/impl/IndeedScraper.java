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
 */
public class IndeedScraper extends BaseJobScraper {

    private static final String INDEED_JOBS_URL = "https://www.indeed.com/jobs";

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

        return url.toString();
    }

    @Override
    protected String getJobListSelector() {
        // Indeed's job listing selector - try multiple common selectors
        // Indeed uses various structures, so we'll try the most common ones
        return "div[data-jk], div.job_seen_beacon, ul#jobResultsList > li, div.jobsearch-SerpJobCard";
    }

    @Override
    protected void handleLoginIfNeeded(Page page) {
        // Indeed may show Cloudflare verification page
        // Check if we're on a verification page and handle it automatically
        
        try {
            // Wait for page to fully load
            page.waitForTimeout(3000);
            
            String currentUrl = page.url();
            String pageTitle = page.title().toLowerCase();
            String pageContent = "";
            try {
                pageContent = page.content().toLowerCase();
            } catch (Exception e) {
                // If we can't get content, try anyway
            }
            
            // Check for Cloudflare verification indicators (based on the image description)
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
                    System.out.println("[" + getSource() + "]    Waiting 45 seconds for manual verification...");
                    page.waitForTimeout(45000); // Increased wait time
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

        // Extract description/snippet
        String description = "No description available";
        try {
            description = jobElement.locator("div.job-snippet").textContent().trim();
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting description: " + e.getMessage());
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
