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
        // Indeed's job listing selector
        // Note: You may need to update this based on Indeed's actual HTML structure
        return "div[data-jk]";  // Indeed uses data-jk attribute for job IDs
    }

    @Override
    protected void handleLoginIfNeeded(Page page) {
        // Indeed may show CAPTCHA/verification page
        // Check if we're on a verification page and handle it automatically
        
        String currentUrl = page.url();
        String pageTitle = page.title().toLowerCase();
        String pageContent = page.content();
        
        // Check for various CAPTCHA/verification indicators
        boolean isVerificationPage = 
            currentUrl.contains("verify") || 
            currentUrl.contains("challenge") ||
            pageTitle.contains("verify") ||
            pageTitle.contains("captcha") ||
            pageContent.contains("verify you're human") ||
            pageContent.contains("I'm not a robot") ||
            pageContent.contains("challenge-form");
        
        if (isVerificationPage) {
            System.out.println("[" + getSource() + "] ⚠️  Detected verification/CAPTCHA page");
            System.out.println("[" + getSource() + "]    Attempting to handle automatically...");
            
            try {
                // Wait a bit for the page to fully load
                page.waitForTimeout(2000);
                
                // Try multiple common CAPTCHA checkbox selectors
                String[] checkboxSelectors = {
                    "input[type='checkbox']",                    // Generic checkbox
                    "#checkbox",                                 // ID checkbox
                    ".recaptcha-checkbox",                       // reCAPTCHA checkbox
                    "input[name='verify']",                     // Verify checkbox
                    "iframe[title*='reCAPTCHA']",                // reCAPTCHA iframe
                    "div[role='checkbox']",                      // ARIA checkbox
                    "span.recaptcha-checkbox",                   // reCAPTCHA span
                    "input#challenge-form-checkbox",            // Challenge form checkbox
                    "input[name='challenge']"                    // Challenge checkbox
                };
                
                boolean checkboxClicked = false;
                for (String selector : checkboxSelectors) {
                    try {
                        Locator checkbox = page.locator(selector).first();
                        if (checkbox.isVisible()) {
                            System.out.println("[" + getSource() + "]    Found checkbox with selector: " + selector);
                            checkbox.click();
                            checkboxClicked = true;
                            System.out.println("[" + getSource() + "]    ✅ Clicked verification checkbox");
                            break;
                        }
                    } catch (Exception e) {
                        // Try next selector
                        continue;
                    }
                }
                
                if (!checkboxClicked) {
                    // Try clicking on iframe first (reCAPTCHA is often in an iframe)
                    try {
                        Locator iframe = page.locator("iframe[title*='reCAPTCHA'], iframe[src*='recaptcha']").first();
                        if (iframe.isVisible()) {
                            System.out.println("[" + getSource() + "]    Found reCAPTCHA iframe, clicking...");
                            iframe.click();
                            checkboxClicked = true;
                        }
                    } catch (Exception e) {
                        // No iframe found
                    }
                }
                
                if (checkboxClicked) {
                    // Wait for verification to process
                    System.out.println("[" + getSource() + "]    Waiting for verification to complete...");
                    page.waitForTimeout(5000); // Wait 5 seconds for verification
                    
                    // Check if we're still on verification page
                    String newUrl = page.url();
                    if (!newUrl.contains("verify") && !newUrl.contains("challenge")) {
                        System.out.println("[" + getSource() + "]    ✅ Verification completed, continuing...");
                    } else {
                        System.out.println("[" + getSource() + "]    ⚠️  Still on verification page, waiting longer...");
                        page.waitForTimeout(10000); // Wait 10 more seconds
                    }
                } else {
                    System.out.println("[" + getSource() + "]    ⚠️  Could not find verification checkbox automatically");
                    System.out.println("[" + getSource() + "]    Please complete verification manually in the browser");
                    System.out.println("[" + getSource() + "]    Waiting 30 seconds for manual verification...");
                    page.waitForTimeout(30000); // Wait 30 seconds for manual completion
                }
                
            } catch (Exception e) {
                System.err.println("[" + getSource() + "]    Error handling verification: " + e.getMessage());
                System.out.println("[" + getSource() + "]    Waiting 30 seconds for manual verification...");
                page.waitForTimeout(30000);
            }
        }
    }

    @Override
    protected Job extractJobData(Locator jobElement, Page page) {
        // Extract job title (Indeed's structure)
        String title = "";
        try {
            title = jobElement.locator("h2.jobTitle a, h2.jobTitle span").textContent().trim();
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting title: " + e.getMessage());
        }

        // Extract company name
        String company = "";
        try {
            company = jobElement.locator("span.companyName").textContent().trim();
        } catch (Exception e) {
            System.err.println("[" + getSource() + "] Error extracting company: " + e.getMessage());
        }

        // Extract job URL
        String jobURL = "";
        try {
            String relativeUrl = jobElement.locator("h2.jobTitle a").getAttribute("href");
            jobURL = relativeUrl != null && relativeUrl.startsWith("http")
                ? relativeUrl
                : "https://www.indeed.com" + relativeUrl;
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
        return new Job()
            .id(UUID.randomUUID().toString())
            .source("Indeed")
            .title(title)
            .company(company)
            .url(jobURL)
            .location(location)
            .description(description)
            .postedDate(OffsetDateTime.now());
    }
}
