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
 * LinkedIn scraper implementation.
 * 
 * Only implements site-specific parts:
 * - URL building (LinkedIn's URL format)
 * - CSS selectors (LinkedIn's HTML structure)
 * - Data extraction (LinkedIn's job card format)
 * - Login handling (LinkedIn requires login)
 */
public class LinkedInScraper extends BaseJobScraper {

    private static final String LINKEDIN_JOBS_URL = "https://www.linkedin.com/jobs/search";

    @Override
    public String getSource() {
        return "LinkedIn";
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

        return url.toString();
    }

    @Override
    protected String getJobListSelector() {
        // LinkedIn's job listing selector
        return "ul.jobs-search__results-list > li";
    }

    @Override
    protected void handleLoginIfNeeded(Page page) {
        // Check if we're on a login page
        String currentUrl = page.url();
        System.out.println("[" + getSource() + "] Current URL: " + currentUrl);

        if (currentUrl.contains("/login") || currentUrl.contains("/checkpoint")) {
            System.out.println("[" + getSource() + "] ⚠️  WARNING: LinkedIn is showing a login page!");
            System.out.println("[" + getSource() + "]    Please log in manually in the browser window.");
            System.out.println("[" + getSource() + "]    Waiting 30 seconds for you to log in...");
            page.waitForTimeout(30000); // Wait 30 seconds for manual login
            System.out.println("[" + getSource() + "]    Continuing after login wait...");
        }
    }

    @Override
    protected Job extractJobData(Locator jobElement, Page page) {
        // Wait a bit for content to load within the job element
        try {
            page.waitForTimeout(300); // Small wait for dynamic content
        } catch (Exception e) {
            // Ignore
        }
        
        // Extract job title with better error handling - try multiple selectors
        // LinkedIn job titles are often in nested spans or links
        String title = "";
        String[] titleSelectors = {
            "a.job-card-list__title",
            "h3.job-card-list__title",
            "a[data-tracking-control-name='job-card-title']",
            "span.job-card-list__title",
            "h3 a",
            "a.base-search-card__title",
            "h3.base-search-card__title",
            "a[class*='job-card-list__title']",
            // Try finding any link or heading in the job card
            "h3",
            "a[href*='/jobs/view/']",
            "a[href*='/jobs/']"
        };
        for (String selector : titleSelectors) {
            try {
                Locator titleLocator = jobElement.locator(selector).first();
                if (titleLocator.count() > 0) {
                    // Wait for element to be visible
                    try {
                        titleLocator.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                    } catch (Exception e) {
                        // Continue even if wait fails
                    }
                    String titleText = titleLocator.textContent();
                    if (titleText != null && !titleText.trim().isEmpty()) {
                        title = titleText.trim();
                        break;
                    }
                }
            } catch (Exception e) {
                continue; // Try next selector
            }
        }
        if (title.isEmpty()) {
            System.err.println("[" + getSource() + "] Error extracting title: No matching selector found");
        }

        // Extract company with better error handling - try multiple selectors
        String company = "";
        String[] companySelectors = {
            "a.job-card-container__company-name",
            "h4.job-card-container__company-name",
            "span.job-card-container__company-name",
            "a[data-tracking-control-name='job-card-company']",
            "h4 a"
        };
        for (String selector : companySelectors) {
            try {
                Locator companyLocator = jobElement.locator(selector).first();
                if (companyLocator.count() > 0) {
                    companyLocator.waitFor(new Locator.WaitForOptions().setTimeout(2000));
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
        if (company.isEmpty()) {
            System.err.println("[" + getSource() + "] Error extracting company: No matching selector found");
        }

        // Extract job URL with better error handling - try multiple selectors
        String jobURL = "";
        String[] urlSelectors = {
            "a.job-card-list__title",
            "h3.job-card-list__title a",
            "a[data-tracking-control-name='job-card-title']",
            "a.base-search-card__title",
            "a[href*='/jobs/view/']",
            "a[href*='/jobs/']",
            "a[class*='job-card-list__title']",
            // Try finding any link with job URL pattern
            "a[href*='linkedin.com/jobs']"
        };
        for (String selector : urlSelectors) {
            try {
                Locator urlLocator = jobElement.locator(selector).first();
                if (urlLocator.count() > 0) {
                    try {
                        urlLocator.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                    } catch (Exception e) {
                        // Continue even if wait fails
                    }
                    String relativeUrl = urlLocator.getAttribute("href");
                    if (relativeUrl != null && !relativeUrl.isEmpty()) {
                        jobURL = relativeUrl.startsWith("https") 
                            ? relativeUrl 
                            : "https://www.linkedin.com" + relativeUrl;
                        break;
                    }
                }
            } catch (Exception e) {
                continue; // Try next selector
            }
        }
        if (jobURL.isEmpty()) {
            System.err.println("[" + getSource() + "] Error extracting URL: No matching selector found");
        }

        // Extract job location with better error handling
        String location = "Not Specified";
        try {
            Locator locationElement = jobElement.locator("li.job-card-container__metadata-item").first();
            if (locationElement.count() > 0) {
                String locationText = locationElement.textContent();
                if (locationText != null) {
                    location = locationText.trim();
                }
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting location: " + e.getMessage());
        }

        // Extract description with better error handling
        String description = "No description available";
        
        // Strategy 1: Try to get description from expanded job card on search results page
        // LinkedIn sometimes shows full description when you click/expand the job card
        try {
            System.out.println("[" + getSource() + "] 📄 Trying to extract description from job card...");
            
            // Try clicking on the job card to expand it (if not already expanded)
            try {
                Locator jobCard = jobElement.locator("div.job-card-container, li.jobs-search-results__list-item");
                if (jobCard.count() > 0) {
                    jobCard.scrollIntoViewIfNeeded();
                    page.waitForTimeout(500);
                    // Try clicking to expand
                    try {
                        jobCard.click();
                        page.waitForTimeout(2000); // Wait for expansion
                    } catch (Exception e) {
                        // Card might already be expanded or not clickable
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
            
            // Try multiple selectors for description in expanded card
            String[] cardDescriptionSelectors = {
                "div.job-card-list__description",
                "p.job-card-list__description",
                "span.job-card-list__description",
                "div[class*='job-card-list__description']",
                "div[class*='job-snippet']",
                "div[class*='description']",
                "p[class*='description']",
                "span[class*='description']"
            };
            
            for (String selector : cardDescriptionSelectors) {
                try {
                    Locator descLocator = jobElement.locator(selector);
                    if (descLocator.count() > 0) {
                        String descText = descLocator.first().textContent();
                        if (descText != null && !descText.trim().isEmpty() && descText.trim().length() > 50) {
                            description = descText.trim();
                            System.out.println("[" + getSource() + "] ✅ Found description from job card (" + description.length() + " chars)");
                            // If we got a good description from card, mark it so we can skip detail page
                            if (description.length() > 100) {
                                // We'll use this description, but continue to try detail page for even better one
                            }
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        } catch (Exception e) {
            System.out.println("[" + getSource() + "]    Error extracting from card: " + e.getMessage());
        }

        // Strategy 2: Navigate to detail page in same browser tab (only if card extraction didn't work or got short description)
        if(description.equals("No description available") || description.length() < 100){
            try{
                System.out.println("[" + getSource() + "] 📄 Navigating to job detail page to extract full description...");
                System.out.println("[" + getSource() + "]    URL: " + jobURL);

                // Save current URL to navigate back later
                String originalUrl = page.url();
                
                // Navigate to job detail page in the same page
                page.navigate(jobURL);
                page.waitForLoadState();
                page.waitForTimeout(5000); // Increased wait for initial load
                
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
                    System.err.println("[" + getSource() + "]    Please log in to LinkedIn in the main browser window");
                    System.err.println("[" + getSource() + "]    Then descriptions will be available on next run");
                    // Navigate back to search results
                    page.navigate(originalUrl);
                    page.waitForLoadState();
                } else if (pageTitleLower.contains("job") && !pageUrlLower.contains("view")) {
                    // Check if we got redirected away from job page
                    System.err.println("[" + getSource() + "] ⚠️  Unexpected redirect - not on job detail page");
                    System.err.println("[" + getSource() + "]    Current URL: " + pageUrl);
                    // Navigate back to search results
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
                            "button.show-more-less-html__button",
                            "button[aria-label*='more']"
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

                    //Multiple Selectors for Description - updated with current LinkedIn structure
                    String[] descriptionSelectors = {
                        // Most common current selectors (try these first)
                        "div.show-more-less-html__markup",
                        "div.description__text",
                        "div[class*='show-more-less-html__markup']",
                        "div[class*='description__text']",
                        "section.job-details__description",
                        "div.job-details__job-details",
                        "div[data-test-id='job-details-description']",
                        // Try innerHTML approach with these
                        "div.description__text-content",
                        "div[class*='job-details__description']",
                        "section[class*='description']",
                        "div[class*='job-details']",
                        "section.description",
                        "div[class*='description']",
                        // More generic fallbacks
                        "div.description",
                        "section[class*='description']",
                        "div[role='article']",
                        "article[class*='description']",
                        // Try main content area
                        "main div[class*='description']",
                        "main section[class*='description']"
                    };

                    boolean found = false;
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
                        
                        // Debug: Print page HTML structure
                        try {
                            String bodyHtml = page.locator("body").innerHTML();
                            System.out.println("[" + getSource() + "]    Page HTML length: " + (bodyHtml != null ? bodyHtml.length() : 0));
                            
                            // Try to find any div with "description" in class name
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
                    }
                    
                    // Navigate back to search results page
                    System.out.println("[" + getSource() + "]    Navigating back to search results...");
                    page.navigate(originalUrl);
                    page.waitForLoadState();
                    page.waitForTimeout(2000); // Wait for search results to reload
                }

            } catch (Exception e){
                System.err.println("[" + getSource() + "] ❌ Error extracting description from URL: " + jobURL);
                System.err.println("[" + getSource() + "]    Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // Fallback: Try to get snippet from search results page
        if (description.equals("No description available") || description.length() < 20) {
            try {
                Locator descriptionElement = jobElement.locator("p.job-card-list__description, span.job-card-list__description, div.job-card-list__description");
                if (descriptionElement.count() > 0) {
                    String descriptionText = descriptionElement.textContent();
                    if (descriptionText != null && !descriptionText.trim().isEmpty()) {
                        description = descriptionText.trim();
                        System.out.println("[" + getSource() + "] 📝 Using snippet from search results (" + description.length() + " chars)");
                    }
                }
            } catch (Exception e) {
                // Keep default "No description available"
            }
        }

        // Create and return Job object
        return new Job()
            .id(UUID.randomUUID().toString())
            .source("LinkedIn")
            .title(title != null ? title : "")
            .company(company != null ? company : "")
            .url(jobURL != null ? jobURL : "")
            .location(location)
            .description(description)
            .postedDate(OffsetDateTime.now());
    }
}
