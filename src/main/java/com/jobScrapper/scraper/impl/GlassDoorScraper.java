package com.jobScrapper.scraper.impl;

import com.jobScrapper.scraper.BaseJobScraper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;
public class GlassDoorScraper extends BaseJobScraper{
    
    private static final String GLASSDOOR_JOBS_URL ="https://www.glassdoor.com/Jobs";
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
        // Glassdoor uses various selectors - try multiple common ones
        // Based on actual Glassdoor structure: job cards are in ul with class or data attributes
        return "ul[data-test='jobListing'] > li, li[data-test='job-listing'], article[data-test='jobListing'], div[data-test='jobListing'], ul.jobsList > li, li.react-job-listing, div.jobContainer";
    }


    @Override
    protected void handleLoginIfNeeded(Page page){
        try {
            String currentUrl = page.url();
            System.out.println("[" + getSource() + "] Current URL: " + currentUrl);
            
            // Wait for page to load
            page.waitForTimeout(3000);

            // Check for login/verification pages
            String pageTitle = page.title().toLowerCase();
            String pageContent = "";
            try {
                pageContent = page.content().toLowerCase();
            } catch (Exception e) {
                // Continue
            }
            
            boolean needsLogin = currentUrl.contains("/login") || 
                                currentUrl.contains("/checkpoint") ||
                                currentUrl.contains("/signin") ||
                                pageTitle.contains("sign in") ||
                                pageContent.contains("sign in") ||
                                pageContent.contains("log in");
            
            // Check for Cloudflare/verification
            boolean needsVerification = currentUrl.contains("verify") ||
                                       currentUrl.contains("challenge") ||
                                       pageTitle.contains("just a moment") ||
                                       pageContent.contains("verify you are human") ||
                                       pageContent.contains("cloudflare");
            
            if (needsVerification) {
                System.out.println("[" + getSource() + "] ⚠️  Detected verification page");
                System.out.println("[" + getSource() + "]    Attempting to handle automatically...");
                
                // Try to click verification checkbox (similar to Indeed)
                try {
                    Locator checkbox = page.locator("input[type='checkbox'], div[role='checkbox'], label:has-text('Verify')").first();
                    if (checkbox.count() > 0 && checkbox.isVisible()) {
                        checkbox.click();
                        System.out.println("[" + getSource() + "]    ✅ Clicked verification checkbox");
                        page.waitForTimeout(5000);
                    }
                } catch (Exception e) {
                    System.out.println("[" + getSource() + "]    Could not auto-click verification");
                }
            }
            
            if(needsLogin){
                System.out.println("[" + getSource() + "] ⚠️  WARNING: Glassdoor is showing a login page!");
                System.out.println("[" + getSource() + "]    Please log in manually in the browser window.");
                System.out.println("[" + getSource() + "]    Waiting 30 seconds for you to log in...");
                page.waitForTimeout(30000); // Wait 30 seconds for manual login
                System.out.println("[" + getSource() + "]    Continuing after login wait...");
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error in handleLoginIfNeeded: " + e.getMessage());
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

        //Step 5: Get the job Description
        String description = "No description available";
        String[] descSelectors = {
            "div[data-test='job-snippet']",
            "p.job-snippet",
            "div.job-snippet",
            "span[data-test='job-snippet']"
        };
        for (String selector : descSelectors) {
            try {
                Locator descLocator = jobElement.locator(selector).first();
                if (descLocator.count() > 0) {
                    String descText = descLocator.textContent();
                    if (descText != null && !descText.trim().isEmpty()) {
                        description = descText.trim();
                        break;
                    }
                }
            } catch (Exception e) {
                continue;
            }
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
