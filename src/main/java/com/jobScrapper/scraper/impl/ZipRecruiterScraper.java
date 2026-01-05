package com.jobScrapper.scraper.impl;

import com.jobScrapper.scraper.BaseJobScraper;
import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ZipRecruiter scraper implementation.
 * 
 * Extracts complete job data like LinkedIn:
 * - Title, Company, Location, URL from job cards
 * - Full description by navigating to job detail pages
 */
public class ZipRecruiterScraper extends BaseJobScraper {

    private static final String ZIPRECRUITER_JOBS_URL = "https://www.ziprecruiter.com/jobs-search";
    private String searchResultsUrl = ""; // Store search results URL to navigate back

    @Override
    public String getSource() {
        return "ZipRecruiter";
    }

    @Override
    protected String buildSearchURL(ScrapingJobRequest request) {
        StringBuilder url = new StringBuilder(ZIPRECRUITER_JOBS_URL);
        url.append("?");

        List<String> keywords = request.getKeywords();
        if (keywords != null && !keywords.isEmpty()) {
            String searchTerm = String.join(" ", keywords);
            url.append("search=").append(searchTerm.replace(" ", "+"));
        }

        String location = request.getLocation();
        if (location != null && !location.isEmpty()) {
            url.append("&location=").append(location.replace(" ", "+").replace(",", "%2C"));
        }

        return url.toString();
    }

    @Override
    protected String getJobListSelector() {
        // ZipRecruiter uses <article> elements for job cards
        return "article";
    }

    @Override
    protected void handleLoginIfNeeded(Page page) {
        String currentUrl = page.url();
        String pageTitle = page.title();
        System.out.println("[" + getSource() + "] Current URL: " + currentUrl);
        System.out.println("[" + getSource() + "] Page title: " + pageTitle);

        // Store search results URL for navigation back
        searchResultsUrl = currentUrl;

        // Comprehensive Cloudflare detection and handling
        String pageTitleLower = pageTitle.toLowerCase();
        String currentUrlLower = currentUrl.toLowerCase();
        String pageContent = "";
        try {
            pageContent = page.content().toLowerCase();
        } catch (Exception e) {
            // Continue
        }
        
        // Check for Cloudflare challenge
        boolean isCloudflare = 
            pageTitleLower.contains("just a moment") || 
            pageTitleLower.contains("checking your browser") ||
            pageTitleLower.contains("please wait") ||
            pageTitleLower.contains("verification required") ||
            currentUrlLower.contains("challenge") ||
            currentUrlLower.contains("verify") ||
            currentUrlLower.contains("cf-") ||
            pageContent.contains("additional verification required") ||
            pageContent.contains("help us protect") ||
            pageContent.contains("verify you are human") ||
            pageContent.contains("cloudflare") ||
            pageContent.contains("challenges.cloudflare.com") ||
            pageContent.contains("ray id") ||
            page.locator("text=/Additional Verification Required/i").count() > 0 ||
            page.locator("text=/Help Us Protect/i").count() > 0 ||
            page.locator("text=/Please unblock challenges.cloudflare.com/i").count() > 0;
        
        if (isCloudflare) {
            System.err.println("\n[" + getSource() + "] ⚠️  ⚠️  CLOUDFLARE VERIFICATION DETECTED ⚠️  ⚠️");
            System.err.println("[" + getSource() + "]    URL: " + currentUrl);
            System.err.println("[" + getSource() + "]    Title: " + pageTitle);
            System.err.println("[" + getSource() + "]    Please complete the Cloudflare challenge manually in the browser window!");
            System.err.println("[" + getSource() + "]    Waiting up to 90 seconds for you to complete verification...\n");
            
            // Wait for Cloudflare to pass (check every 3 seconds, up to 90 seconds)
            for (int check = 0; check < 30; check++) {
                try {
                    page.waitForTimeout(3000); // Wait 3 seconds between checks
                    
                    // Re-check if Cloudflare is still present
                    String newTitle = page.title().toLowerCase();
                    String newUrl = page.url().toLowerCase();
                    String newContent = "";
                    try {
                        newContent = page.content().toLowerCase();
                    } catch (Exception e) {
                        // Continue
                    }
                    
                    boolean stillCloudflare = 
                        newTitle.contains("just a moment") || 
                        newTitle.contains("checking your browser") ||
                        newTitle.contains("please wait") ||
                        newTitle.contains("verification required") ||
                        newUrl.contains("challenge") ||
                        newUrl.contains("verify") ||
                        newContent.contains("challenges.cloudflare.com") ||
                        page.locator("text=/Please unblock challenges.cloudflare.com/i").count() > 0;
                    
                    if (!stillCloudflare) {
                        System.out.println("[" + getSource() + "] ✅ Cloudflare verification passed!");
                        // Update search results URL in case it changed
                        searchResultsUrl = page.url();
                        return;
                    }
                    
                    if (check % 5 == 0 && check > 0) {
                        System.out.println("[" + getSource() + "]    Still waiting for Cloudflare... (" + (90 - check * 3) + " seconds remaining)");
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "]    Error while waiting for Cloudflare: " + e.getMessage());
                }
            }
            
            System.err.println("\n[" + getSource() + "] ⚠️  WARNING: Still on Cloudflare verification page after 90 seconds!");
            System.err.println("[" + getSource() + "]    The scraper will attempt to continue, but may fail to find job listings.");
            System.err.println("[" + getSource() + "]    Please complete verification in the browser and restart the scraper.\n");
        } else {
            System.out.println("[" + getSource() + "] ✅ No Cloudflare challenge detected");
        }
    }

    @Override
    protected Job extractJobData(Locator jobElement, Page page) {
        try {
            page.waitForTimeout(500); // Wait for content to load
        } catch (Exception e) {
            // Ignore
        }
        
        // ============================================================
        // STEP 1: Extract job title from button or link
        // ============================================================
        String title = "";
        
        // First try: buttons with "View {title}" in the name attribute
        try {
            Locator viewButton = jobElement.locator("button[name^='View']").first();
            if (viewButton.count() > 0) {
                String buttonName = viewButton.getAttribute("name");
                if (buttonName != null && buttonName.startsWith("View ")) {
                    title = buttonName.substring(5).trim(); // Remove "View " prefix
                }
            }
        } catch (Exception e) {
            // Continue to fallback
        }
        
        // Fallback: try other selectors
        if (title.isEmpty()) {
            String[] titleSelectors = {
                "a[class*='job_link']", "a[class*='title']", "h2 a", "h2",
                "strong", "[class*='JobTitle']", "[class*='job-title']"
            };
            for (String selector : titleSelectors) {
                try {
                    Locator titleLocator = jobElement.locator(selector).first();
                    if (titleLocator.count() > 0) {
                        String text = titleLocator.textContent();
                        if (text != null && !text.trim().isEmpty() && text.trim().length() > 3) {
                            title = text.trim();
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        }
        
        if (title.isEmpty()) {
            System.err.println("[" + getSource() + "] ⚠️  Could not extract title");
        }

        // ============================================================
        // STEP 2: Extract company name
        // ============================================================
        String company = "";
        
        // ZipRecruiter has company names in various elements
        // Try to find links or spans that look like company names (not job titles)
        try {
            // Look for all links in the job card
            Locator allLinks = jobElement.locator("a");
            int linkCount = allLinks.count();
            
            for (int i = 0; i < linkCount && i < 10; i++) {
                try {
                    Locator link = allLinks.nth(i);
                    String href = link.getAttribute("href");
                    String text = link.textContent();
                    
                    // Skip if it's the job title link (contains job path)
                    if (href != null && (href.contains("/job/") || href.contains("/jobs/"))) {
                        continue;
                    }
                    
                    // Skip if text looks like job title (too long or contains "View")
                    if (text != null && text.length() > 3 && text.length() < 60 
                        && !text.contains("View") && !text.contains("Save")
                        && !text.toLowerCase().contains("apply")) {
                        // This might be the company name
                        company = text.trim();
                        break;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        } catch (Exception e) {
            // Continue to fallback
        }
        
        // Fallback selectors for company
        if (company.isEmpty()) {
            String[] companySelectors = {
                "[class*='company']", "[class*='Company']", "[class*='employer']",
                "[class*='Employer']", "span[class*='name']"
            };
            for (String selector : companySelectors) {
                try {
                    Locator companyLocator = jobElement.locator(selector).first();
                    if (companyLocator.count() > 0) {
                        String text = companyLocator.textContent();
                        if (text != null && !text.trim().isEmpty() && text.trim().length() < 60) {
                            company = text.trim();
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        }
        
        if (company.isEmpty()) {
            System.err.println("[" + getSource() + "] ⚠️  Could not extract company for: " + title);
        }

        // ============================================================
        // STEP 3: Extract location (usually contains city, state pattern)
        // ============================================================
        String location = "";
        
        try {
            // Look for text that looks like a location (City, ST pattern or contains "Remote")
            Locator allText = jobElement.locator("span, p, div");
            int textCount = allText.count();
            
            for (int i = 0; i < textCount && i < 20; i++) {
                try {
                    String text = allText.nth(i).textContent();
                    if (text != null) {
                        text = text.trim();
                        // Match patterns like "Boston, MA" or "Remote" or "Hybrid"
                        if ((text.matches(".*,\\s*[A-Z]{2}.*") || 
                             text.toLowerCase().contains("remote") ||
                             text.toLowerCase().contains("hybrid") ||
                             text.toLowerCase().contains("on-site")) &&
                            text.length() < 50 && text.length() > 3) {
                            location = text;
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        } catch (Exception e) {
            // Continue to fallback
        }
        
        // Fallback selectors for location
        if (location.isEmpty()) {
            String[] locationSelectors = {
                "[class*='location']", "[class*='Location']", "[class*='city']",
                "[class*='City']", "[class*='place']"
            };
            for (String selector : locationSelectors) {
                try {
                    Locator locationLocator = jobElement.locator(selector).first();
                    if (locationLocator.count() > 0) {
                        String text = locationLocator.textContent();
                        if (text != null && !text.trim().isEmpty()) {
                            location = text.trim();
                            break;
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        }

        // ============================================================
        // STEP 4: Click on job to navigate to detail page and get URL + Description
        // ============================================================
        String jobUrl = "";
        String description = "";
        
        // ZipRecruiter jobs open in the same page when clicked
        // We need to click the job card to navigate to the specific job detail page
        System.out.println("[" + getSource() + "] 📄 Clicking job to get specific job URL and description...");
        
        try {
            // First, close any modal/overlay that might be open from previous job
            try {
                // Try multiple ways to close modals
                Locator overlay = page.locator("div[class*='modal'], div[class*='overlay'], div[role='dialog'], div[class*='z-max']").first();
                if (overlay.count() > 0 && overlay.isVisible()) {
                    // Try clicking close button first
                    try {
                        Locator closeBtn = page.locator("button[aria-label*='Close'], button[class*='close'], [class*='close-button']").first();
                        if (closeBtn.count() > 0 && closeBtn.isVisible()) {
                            closeBtn.click();
                            humanDelay(page, 500, 800);
                        } else {
                            page.keyboard().press("Escape");
                            humanDelay(page, 500, 800);
                        }
                    } catch (Exception e) {
                        page.keyboard().press("Escape");
                        humanDelay(page, 500, 800);
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
            
            // Store current URL before clicking
            String currentUrl = page.url();
            
            // FIRST: Try to find job URL from data attributes on the button or article
            try {
                // Check the "View" button for data attributes
                Locator viewButton = jobElement.locator("button[name^='View']").first();
                if (viewButton.count() > 0) {
                    // Try various data attributes that might contain the URL
                    String[] dataAttrs = {"data-href", "data-url", "data-job-url", "data-link", "href"};
                    for (String attr : dataAttrs) {
                        try {
                            String attrValue = viewButton.getAttribute(attr);
                            if (attrValue != null && !attrValue.isEmpty() && 
                                (attrValue.contains("/job/") || attrValue.contains("/jobs/") || attrValue.startsWith("http"))) {
                                jobUrl = attrValue.startsWith("http") ? attrValue : "https://www.ziprecruiter.com" + attrValue;
                                System.out.println("[" + getSource() + "]    ✅ Found job URL from button data attribute (" + attr + "): " + jobUrl);
                                break;
                            }
                        } catch (Exception e) {
                            continue;
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
            
            // SECOND: Try to find job detail link directly in HTML
            if (jobUrl.isEmpty()) {
                Locator allLinks = jobElement.locator("a[href]");
                for (int i = 0; i < allLinks.count() && i < 15; i++) {
                    try {
                        Locator link = allLinks.nth(i);
                        String href = link.getAttribute("href");
                        String linkText = link.textContent();
                        
                        if (href != null && !href.isEmpty()) {
                            // Look for job detail links - they usually contain /job/ or /jobs/ or have job title in text
                            if ((href.contains("/job/") || href.contains("/jobs/") || 
                                 (linkText != null && title != null && 
                                  linkText.toLowerCase().contains(title.toLowerCase().substring(0, Math.min(15, title.length()))))) &&
                                !href.contains("/co/") && // Not a company listing
                                !href.contains("javascript:") && 
                                !href.equals("#") && 
                                !href.contains("facebook.com") && 
                                !href.contains("linkedin.com/share") &&
                                !href.contains("twitter.com") &&
                                !href.contains("/jobs-search")) { // Not a search link
                                
                                jobUrl = href.startsWith("http") ? href : "https://www.ziprecruiter.com" + href;
                                System.out.println("[" + getSource() + "]    ✅ Found job detail link in HTML: " + jobUrl);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
            }
            
            // THIRD: Try to get URL from article data attributes
            if (jobUrl.isEmpty()) {
                try {
                    String[] dataAttrs = {"data-href", "data-url", "data-job-url", "data-link", "data-job-id"};
                    for (String attr : dataAttrs) {
                        try {
                            String attrValue = jobElement.getAttribute(attr);
                            if (attrValue != null && !attrValue.isEmpty()) {
                                if (attr.equals("data-job-id")) {
                                    // Construct URL from job ID
                                    jobUrl = "https://www.ziprecruiter.com/job/" + attrValue;
                                    System.out.println("[" + getSource() + "]    ✅ Constructed job URL from job ID: " + jobUrl);
                                } else if (attrValue.contains("/job/") || attrValue.contains("/jobs/") || attrValue.startsWith("http")) {
                                    jobUrl = attrValue.startsWith("http") ? attrValue : "https://www.ziprecruiter.com" + attrValue;
                                    System.out.println("[" + getSource() + "]    ✅ Found job URL from article data attribute (" + attr + "): " + jobUrl);
                                }
                                if (!jobUrl.isEmpty()) break;
                            }
                        } catch (Exception e) {
                            continue;
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
            
            // If we found a URL, navigate to it
            if (!jobUrl.isEmpty()) {
                try {
                    page.navigate(jobUrl);
                    page.waitForLoadState();
                    humanDelay(page, 2000, 3000);
                    // Update URL in case of redirects
                    jobUrl = page.url();
                    System.out.println("[" + getSource() + "]    Final job URL: " + jobUrl);
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "]    ⚠️  Could not navigate to job URL: " + e.getMessage());
                }
            }
            
            // If we didn't find a direct link, try clicking and capturing navigation URL
            if (jobUrl.isEmpty()) {
                System.out.println("[" + getSource() + "]    No direct URL found, clicking to capture navigation...");
                
                // Use JavaScript to click and wait for navigation
                try {
                    // Find the "View" button or any clickable element
                    Locator clickTarget = jobElement.locator("button[name^='View']").first();
                    if (clickTarget.count() == 0) {
                        clickTarget = jobElement.locator("h2 a, h3 a, [class*='title'] a").first();
                    }
                    if (clickTarget.count() == 0) {
                        clickTarget = jobElement;
                    }
                    
                    if (clickTarget.count() > 0) {
                        // Set up a navigation listener to capture the URL
                        final String[] capturedUrl = {""};
                        page.onResponse(response -> {
                            String url = response.url();
                            if ((url.contains("/job/") || url.contains("/jobs/") || url.contains("/c/")) && 
                                !url.contains("/co/") && !url.contains("/jobs-search")) {
                                capturedUrl[0] = url;
                            }
                        });
                        
                        // Click using JavaScript to bypass any overlays
                        clickTarget.evaluate("el => el.click()");
                        humanDelay(page, 2000, 3000);
                        page.waitForLoadState();
                        
                        // Check if URL changed
                        String newUrl = page.url();
                        System.out.println("[" + getSource() + "]    Current URL after click: " + newUrl);
                        
                        // Use captured URL or current URL
                        if (!capturedUrl[0].isEmpty()) {
                            jobUrl = capturedUrl[0];
                            System.out.println("[" + getSource() + "]    ✅ Captured job URL from navigation: " + jobUrl);
                        } else if (!newUrl.equals(currentUrl)) {
                            // Check if we're on a job detail page (not a company listing page)
                            if (newUrl.contains("/co/") && newUrl.contains("/Jobs/")) {
                                System.out.println("[" + getSource() + "]    ⚠️  Navigated to company listing page, looking for specific job link...");
                                
                                // Look for the specific job link on the company page
                                try {
                                    Locator jobLinks = page.locator("a[href*='/job/'], a[href*='/jobs/']");
                                    for (int i = 0; i < jobLinks.count() && i < 20; i++) {
                                        try {
                                            Locator link = jobLinks.nth(i);
                                            String linkText = link.textContent();
                                            // Check if link text contains our job title
                                            if (linkText != null && title != null && 
                                                linkText.toLowerCase().contains(title.toLowerCase().substring(0, Math.min(20, title.length())))) {
                                                String href = link.getAttribute("href");
                                                if (href != null && !href.isEmpty()) {
                                                    jobUrl = href.startsWith("http") ? href : "https://www.ziprecruiter.com" + href;
                                                    System.out.println("[" + getSource() + "]    ✅ Found specific job link: " + jobUrl);
                                                    
                                                    // Navigate to the specific job page
                                                    page.navigate(jobUrl);
                                                    page.waitForLoadState();
                                                    humanDelay(page, 1500, 2000);
                                                    jobUrl = page.url();
                                                    break;
                                                }
                                            }
                                        } catch (Exception e) {
                                            continue;
                                        }
                                    }
                                } catch (Exception e) {
                                    // If we can't find the specific job, use the company page URL as fallback
                                    jobUrl = newUrl;
                                }
                            } else if (newUrl.contains("/job/") || newUrl.contains("/jobs/") || newUrl.contains("/c/")) {
                                // This looks like a specific job detail page
                                jobUrl = newUrl;
                                System.out.println("[" + getSource() + "]    ✅ Got specific job URL: " + jobUrl);
                            } else {
                                // Unknown page type, use it anyway
                                jobUrl = newUrl;
                                System.out.println("[" + getSource() + "]    ⚠️  Unknown page type, using URL: " + jobUrl);
                            }
                        } else {
                            System.out.println("[" + getSource() + "]    ⚠️  URL did not change after click (modal likely opened)");
                            
                            // Wait for modal to appear
                            humanDelay(page, 1000, 1500);
                            
                            // Debug: Check if modal is actually visible
                            try {
                                Locator modalCheck = page.locator("div[class*='modal'], div[role='dialog']").first();
                                if (modalCheck.count() > 0) {
                                    System.out.println("[" + getSource() + "]    🔍 Modal found, checking for URLs...");
                                } else {
                                    System.out.println("[" + getSource() + "]    ⚠️  No modal found after click");
                                }
                            } catch (Exception e) {
                                System.out.println("[" + getSource() + "]    ⚠️  Error checking for modal: " + e.getMessage());
                            }
                            
                            // Try to find URL and description in modal
                            try {
                                // Method 1: Look for "Apply" or "View Job" button/link - search more broadly
                                String[] modalLinkSelectors = {
                                    "a[href*='/job/']", "a[href*='/jobs/']", "a[href*='/c/']",
                                    "a[href]", "button[class*='apply']", "a[class*='apply']",
                                    "[class*='apply-button']", "[class*='view-job']", "a[class*='button']"
                                };
                                
                                System.out.println("[" + getSource() + "]    🔍 Searching for links in modal...");
                                int linkCount = 0;
                                for (String selector : modalLinkSelectors) {
                                    try {
                                        Locator links = page.locator(selector);
                                        int count = links.count();
                                        if (count > 0) {
                                            linkCount += count;
                                            System.out.println("[" + getSource() + "]    Found " + count + " elements with selector: " + selector);
                                            for (int i = 0; i < count && i < 5; i++) {
                                                try {
                                                    Locator link = links.nth(i);
                                                    if (link.isVisible()) {
                                                        String href = link.getAttribute("href");
                                                        String text = link.textContent();
                                                System.out.println("[" + getSource() + "]      Link " + i + ": href='" + href + "', text='" + (text != null ? text.substring(0, Math.min(50, text.length())) : "null") + "'");
                                                if (href != null && !href.isEmpty()) {
                                                    // Check for direct job URLs
                                                    if (href.contains("/job/") || href.contains("/jobs/") || href.contains("/c/")) {
                                                        jobUrl = href.startsWith("http") ? href : "https://www.ziprecruiter.com" + href;
                                                        System.out.println("[" + getSource() + "]    ✅ Got direct job URL from modal link: " + jobUrl);
                                                        break;
                                                    }
                                                    // Check for job-redirect URLs (fallback - we'll try to get the actual URL)
                                                    if (href.contains("job-redirect") && jobUrl.isEmpty()) {
                                                        // Store this as a fallback, but try to get the actual job URL
                                                        String redirectUrl = href.startsWith("http") ? href : "https://www.ziprecruiter.com" + href;
                                                        System.out.println("[" + getSource() + "]    🔍 Found job-redirect URL: " + redirectUrl);
                                                        
                                                        // Try clicking "View Job" or similar link first
                                                        if (text != null && (text.toLowerCase().contains("view") || text.toLowerCase().contains("see"))) {
                                                            try {
                                                                System.out.println("[" + getSource() + "]    Clicking 'View Job' link to get actual URL...");
                                                                link.click();
                                                                humanDelay(page, 2000, 3000);
                                                                page.waitForLoadState();
                                                                String clickedUrl = page.url();
                                                                if (!clickedUrl.equals(currentUrl) && (clickedUrl.contains("/job/") || clickedUrl.contains("/jobs/"))) {
                                                                    jobUrl = clickedUrl;
                                                                    System.out.println("[" + getSource() + "]    ✅ Got URL from 'View Job' click: " + jobUrl);
                                                                    // Go back to search results
                                                                    page.goBack();
                                                                    page.waitForLoadState();
                                                                    humanDelay(page, 1000, 1500);
                                                                    break;
                                                                } else {
                                                                    // Go back if it didn't work
                                                                    page.goBack();
                                                                    page.waitForLoadState();
                                                                    humanDelay(page, 1000, 1500);
                                                                }
                                                            } catch (Exception e) {
                                                                System.err.println("[" + getSource() + "]    ⚠️  Error clicking view link: " + e.getMessage());
                                                            }
                                                        }
                                                        
                                                        // If we still don't have a URL, use the redirect URL as fallback
                                                        if (jobUrl.isEmpty()) {
                                                            jobUrl = redirectUrl;
                                                            System.out.println("[" + getSource() + "]    ⚠️  Using job-redirect URL as fallback: " + jobUrl);
                                                        }
                                                    }
                                                }
                                                    }
                                                } catch (Exception e) {
                                                    continue;
                                                }
                                            }
                                        }
                                        if (!jobUrl.isEmpty()) break;
                                    } catch (Exception e) {
                                        continue;
                                    }
                                }
                                if (linkCount == 0) {
                                    System.out.println("[" + getSource() + "]    ⚠️  No links found in modal");
                                }
                                
                                // Method 2: Check if modal has an iframe with the job page
                                try {
                                    Locator iframe = page.locator("iframe[src*='/job/'], iframe[src*='/jobs/']").first();
                                    if (iframe.count() > 0) {
                                        String src = iframe.getAttribute("src");
                                        if (src != null && !src.isEmpty()) {
                                            jobUrl = src.startsWith("http") ? src : "https://www.ziprecruiter.com" + src;
                                            System.out.println("[" + getSource() + "]    ✅ Got URL from modal iframe: " + jobUrl);
                                        }
                                    }
                                } catch (Exception e) {
                                    // Ignore
                                }
                                
                                // Method 3: Extract description from modal while we have it open
                                // Make sure we're getting the description from the CURRENT modal, not a stale one
                                if (description.isEmpty()) {
                                    // Wait a bit more for modal content to load
                                    humanDelay(page, 500, 800);
                                    
                                    // First, find the modal element
                                    Locator modal = page.locator("div[class*='modal']:visible, div[role='dialog']:visible").first();
                                    if (modal.count() > 0) {
                                        String[] modalDescSelectors = {
                                            "div[class*='jobDescription']", "div[class*='job_description']",
                                            "div[class*='description']", "[class*='job-details']",
                                            "[class*='jobDetail']", "[class*='job-detail']",
                                            "div[class*='content']", "article", "p"
                                        };
                                        
                                        for (String selector : modalDescSelectors) {
                                            try {
                                                // Search within the modal, not the whole page
                                                Locator descLocator = modal.locator(selector).first();
                                                if (descLocator.count() > 0 && descLocator.isVisible()) {
                                                    String fullDesc = descLocator.textContent();
                                                    if (fullDesc != null && fullDesc.length() > 50) {
                                                        // Make sure it's not just the title/company repeated
                                                        if (!fullDesc.equals(title) && !fullDesc.equals(company) && 
                                                            !fullDesc.contains(title + company) && fullDesc.length() > 100) {
                                                            description = fullDesc.trim();
                                                            if (description.length() > 5000) {
                                                                description = description.substring(0, 5000) + "...";
                                                            }
                                                            System.out.println("[" + getSource() + "]    ✅ Got description from modal (" + description.length() + " chars)");
                                                            break;
                                                        }
                                                    }
                                                }
                                            } catch (Exception e) {
                                                continue;
                                            }
                                        }
                                    }
                                }
                                
                                // Method 4: Use JavaScript to search for job URLs in modal
                                if (jobUrl.isEmpty()) {
                                    try {
                                        System.out.println("[" + getSource() + "]    🔍 Using JavaScript to search modal for URLs...");
                                        // Use JavaScript to find all links in the modal that might be job URLs
                                        String jsCode = 
                                            "(() => {" +
                                            "  const modal = document.querySelector('div[class*=\"modal\"], div[role=\"dialog\"], div[class*=\"overlay\"]');" +
                                            "  if (!modal) { console.log('No modal found'); return null; }" +
                                            "  console.log('Modal found, searching for links...');" +
                                            "  const links = modal.querySelectorAll('a[href]');" +
                                            "  console.log('Found ' + links.length + ' links in modal');" +
                                            "  const results = [];" +
                                            "  for (let link of links) {" +
                                            "    const href = link.getAttribute('href');" +
                                            "    const text = link.textContent || '';" +
                                            "    results.push({href: href, text: text.substring(0, 50)});" +
                                            "    if (href && (href.includes('/job/') || href.includes('/jobs/') || href.includes('/c/'))) {" +
                                            "      return href.startsWith('http') ? href : 'https://www.ziprecruiter.com' + href;" +
                                            "    }" +
                                            "  }" +
                                            "  console.log('Links found:', JSON.stringify(results));" +
                                            "  return null;" +
                                            "})()";
                                        
                                        Object result = page.evaluate(jsCode);
                                        if (result != null && !result.toString().isEmpty() && !result.toString().equals("null")) {
                                            jobUrl = result.toString();
                                            System.out.println("[" + getSource() + "]    ✅ Got URL from modal via JavaScript: " + jobUrl);
                                        } else {
                                            System.out.println("[" + getSource() + "]    ⚠️  JavaScript search returned: " + (result != null ? result.toString() : "null"));
                                        }
                                    } catch (Exception e) {
                                        System.err.println("[" + getSource() + "]    ⚠️  Error in JavaScript extraction: " + e.getMessage());
                                    }
                                }
                                
                                // Method 5: Try to get URL from window.location or data attributes in modal
                                if (jobUrl.isEmpty()) {
                                    try {
                                        // Check modal for data attributes
                                        Locator modal = page.locator("div[class*='modal'], div[role='dialog'], div[class*='overlay']").first();
                                        if (modal.count() > 0) {
                                            String[] dataAttrs = {"data-job-url", "data-url", "data-href", "data-job-id"};
                                            for (String attr : dataAttrs) {
                                                String attrValue = modal.getAttribute(attr);
                                                if (attrValue != null && !attrValue.isEmpty()) {
                                                    if (attr.equals("data-job-id")) {
                                                        jobUrl = "https://www.ziprecruiter.com/job/" + attrValue;
                                                    } else if (attrValue.contains("/job/") || attrValue.contains("/jobs/")) {
                                                        jobUrl = attrValue.startsWith("http") ? attrValue : "https://www.ziprecruiter.com" + attrValue;
                                                    }
                                                    if (!jobUrl.isEmpty()) {
                                                        System.out.println("[" + getSource() + "]    ✅ Got URL from modal data attribute: " + jobUrl);
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        // Ignore
                                    }
                                }
                                
                                // Method 6: Try to extract job ID from button name and construct URL
                                if (jobUrl.isEmpty()) {
                                    try {
                                        // The button name might contain job identifier
                                        Locator viewButton = jobElement.locator("button[name^='View']").first();
                                        if (viewButton.count() > 0) {
                                            String buttonName = viewButton.getAttribute("name");
                                            // Try to extract any ID from the button or its parent
                                            String buttonId = viewButton.getAttribute("id");
                                            if (buttonId != null && buttonId.contains("job")) {
                                                // Try to construct URL from ID
                                                System.out.println("[" + getSource() + "]    Found button ID: " + buttonId);
                                            }
                                        }
                                    } catch (Exception e) {
                                        // Ignore
                                    }
                                }
                                
                            } catch (Exception e) {
                                System.err.println("[" + getSource() + "]    ⚠️  Error extracting from modal: " + e.getMessage());
                            }
                            
                            // Close modal
                            try {
                                // Try clicking close button first
                                Locator closeBtn = page.locator("button[aria-label*='Close'], button[class*='close'], [class*='close-button'], button[class*='modal-close']").first();
                                if (closeBtn.count() > 0 && closeBtn.isVisible()) {
                                    closeBtn.click();
                                    humanDelay(page, 500, 800);
                                } else {
                                    page.keyboard().press("Escape");
                                    humanDelay(page, 500, 800);
                                }
                            } catch (Exception e) {
                                page.keyboard().press("Escape");
                                humanDelay(page, 300, 500);
                            }
                        }
                    } else {
                        System.out.println("[" + getSource() + "]    ⚠️  No clickable element found");
                    }
                } catch (Exception e) {
                    System.err.println("[" + getSource() + "]    ⚠️  Error clicking job: " + e.getMessage());
                }
            }
            
            // Extract full description from detail page (if we have a job URL)
            if (!jobUrl.isEmpty()) {
                // If we haven't navigated yet (found URL directly), navigate now
                if (!page.url().equals(jobUrl) && !page.url().contains("/job/") && !page.url().contains("/jobs/")) {
                    try {
                        page.navigate(jobUrl);
                        page.waitForLoadState();
                        humanDelay(page, 2000, 3000);
                    } catch (Exception e) {
                        System.err.println("[" + getSource() + "]    ⚠️  Could not navigate to job URL: " + e.getMessage());
                    }
                }
                
                System.out.println("[" + getSource() + "]    Extracting description from detail page...");
                
                String[] detailDescSelectors = {
                    "div[class*='jobDescription']", "div[class*='job_description']",
                    "div[class*='description']", "div[class*='Description']",
                    "section[class*='description']", "[class*='job-details']",
                    "[class*='jobDetail']", "[class*='job-detail']",
                    "#job-description", "[data-testid='job-description']",
                    "div[class*='content']", "article[class*='description']"
                };
                
                for (String selector : detailDescSelectors) {
                    try {
                        Locator descLocator = page.locator(selector).first();
                        if (descLocator.count() > 0) {
                            descLocator.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                            String fullDesc = descLocator.textContent();
                            if (fullDesc != null && fullDesc.length() > 100) {
                                description = fullDesc.trim();
                                // Limit description length
                                if (description.length() > 5000) {
                                    description = description.substring(0, 5000) + "...";
                                }
                                System.out.println("[" + getSource() + "]    ✅ Got description (" + description.length() + " chars)");
                                break;
                            }
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
                
                // Navigate back to search results
                System.out.println("[" + getSource() + "]    Navigating back to search results...");
                page.goBack();
                page.waitForLoadState();
                humanDelay(page, 1000, 1500);
                
                // If we went back to a company page, go back once more
                if (page.url().contains("/co/")) {
                    page.goBack();
                    page.waitForLoadState();
                    humanDelay(page, 800, 1200);
                }
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] ⚠️  Error clicking job: " + e.getMessage());
            // Try to recover by going back to search results
            try {
                if (!searchResultsUrl.isEmpty()) {
                    page.navigate(searchResultsUrl);
                    page.waitForLoadState();
                    humanDelay(page, 500, 800);
                }
            } catch (Exception ex) {
                // Ignore
            }
        }

        // ============================================================
        // STEP 7: Build and return job object
        // ============================================================
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setTitle(title.isEmpty() ? "Unknown Title" : title);
        job.setCompany(company.isEmpty() ? "Unknown Company" : company);
        job.setLocation(location.isEmpty() ? "Not Specified" : location);
        job.setUrl(jobUrl);
        job.setSource(getSource());
        job.setPostedDate(OffsetDateTime.now());
        job.setDescription(description);

        // Print extracted data for debugging
        System.out.println("[" + getSource() + "] 📝 Extracted: " + 
            "title='" + (title.isEmpty() ? "❌" : "✅") + "', " +
            "company='" + (company.isEmpty() ? "❌" : "✅") + "', " +
            "location='" + (location.isEmpty() ? "❌" : "✅") + "', " +
            "url='" + (jobUrl.isEmpty() ? "❌" : "✅") + "', " +
            "desc='" + (description.isEmpty() ? "❌" : description.length() + " chars") + "'");

        return job;
    }
}
