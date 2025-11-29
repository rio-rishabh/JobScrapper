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
        // Extract job title
        String title = "";
        try {
            title = jobElement.locator("a.job-card-list__title").first().textContent().trim();
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting title: " + e.getMessage());
        }

        // Extract company
        String company = "";
        try {
            company = jobElement.locator("a.job-card-container__company-name").first().textContent().trim();
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting company: " + e.getMessage());
        }

        // Extract job URL
        String jobURL = "";
        try {
            String relativeUrl = jobElement.locator("a.job-card-list__title").first().getAttribute("href");
            jobURL = relativeUrl != null && relativeUrl.startsWith("https") 
                ? relativeUrl 
                : "https://www.linkedin.com" + relativeUrl;
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting URL: " + e.getMessage());
        }

        // Extract job location
        String location = "Not Specified";
        try {
            Locator locationElement = jobElement.locator("li.job-card-container__metadata-item").first();
            String locationText = locationElement.textContent();
            if (locationText != null) {
                location = locationText.trim();
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting location: " + e.getMessage());
        }

        // Extract description
        String description = "No description available";
        try {
            Locator descriptionElement = jobElement.locator("p.job-card-list__description");
            String descriptionText = descriptionElement.textContent();
            if (descriptionText != null) {
                description = descriptionText.trim();
            }
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting description: " + e.getMessage());
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
