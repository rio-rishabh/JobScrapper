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

        // Extract description from job card (if available on search results page)
        String description = "No description available";
        
        // Try to get description snippet from job card on search results page
        try {
            Locator descriptionElement = jobElement.locator("p.job-card-list__description, span.job-card-list__description, div.job-card-list__description, div[class*='job-snippet']");
            if (descriptionElement.count() > 0) {
                String descriptionText = descriptionElement.textContent();
                if (descriptionText != null && !descriptionText.trim().isEmpty() && descriptionText.trim().length() > 20) {
                    description = descriptionText.trim();
                    System.out.println("[" + getSource() + "] 📝 Found description snippet from job card (" + description.length() + " chars)");
                }
            }
        } catch (Exception e) {
            // Keep default "No description available"
        }
        
        // Note: Full description extraction from detail page is now handled by BaseJobScraper
        // after extractJobData() returns. The base class will automatically enrich the job
        // with description if it's missing or too short.

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
    
    /**
     * Returns CSS selectors for finding job descriptions on LinkedIn detail pages.
     * These selectors are tried in order until one finds a description.
     */
    @Override
    protected String[] getDescriptionSelectors() {
        return new String[]{
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
    }
    
    /**
     * Returns selectors for "Show more" buttons on LinkedIn detail pages.
     */
    @Override
    protected String[] getShowMoreButtonSelectors() {
        return new String[]{
            "button.show-more-less-html__button",
            "button:has-text('Show more')",
            "button:has-text('see more')",
            "button:has-text('See more')",
            "span:has-text('Show more')",
            "button[aria-label*='more']"
        };
    }
}
