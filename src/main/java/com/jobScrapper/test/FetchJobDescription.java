package com.jobScrapper.test;

import com.jobScrapper.scraper.impl.LinkedInScraper;
import com.jobscrapper.model.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Simple utility to fetch job description from a LinkedIn job URL
 */
public class FetchJobDescription {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: FetchJobDescription <jobId> <jobUrl>");
            System.exit(1);
        }
        
        String jobId = args[0];
        String jobUrl = args[1];
        
        System.out.println("🔍 Fetching job description from LinkedIn...");
        System.out.println("Job ID: " + jobId);
        System.out.println("URL: " + jobUrl);
        System.out.println();
        
        LinkedInScraper scraper = new LinkedInScraper();
        
        try {
            // Use the scraper's method to extract description from detail page
            String description = scraper.extractDescriptionFromDetailPage(null, jobUrl);
            
            if (description != null && !description.isEmpty() && !description.equals("No description available")) {
                System.out.println("✅ Successfully fetched description (" + description.length() + " characters)");
                System.out.println();
                System.out.println("Description:");
                System.out.println("=".repeat(80));
                System.out.println(description);
                System.out.println("=".repeat(80));
                
                // Update the JSON file
                updateJobDescription(jobId, description);
            } else {
                System.err.println("❌ Could not fetch description. LinkedIn may require login.");
            }
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void updateJobDescription(String jobId, String description) {
        try {
            // Find the job in the scraped jobs files
            java.io.File scrapedJobsDir = new java.io.File("scraped_jobs");
            if (!scrapedJobsDir.exists()) {
                System.err.println("⚠️  scraped_jobs directory not found");
                return;
            }
            
            java.io.File[] files = scrapedJobsDir.listFiles((dir, name) -> 
                name.startsWith("jobs_") && name.endsWith(".json") && !name.contains("_interrupted")
            );
            
            if (files == null || files.length == 0) {
                System.err.println("⚠️  No job files found");
                return;
            }
            
            // Sort by modification time (newest first)
            java.util.Arrays.sort(files, (a, b) -> 
                Long.compare(b.lastModified(), a.lastModified())
            );
            
            ObjectMapper mapper = new ObjectMapper();
            
            for (java.io.File file : files) {
                try {
                    String content = new String(Files.readAllBytes(file.toPath()));
                    Object jobsObj = mapper.readValue(content, Object.class);
                    
                    if (jobsObj instanceof java.util.List) {
                        @SuppressWarnings("unchecked")
                        java.util.List<java.util.Map<String, Object>> jobs = 
                            (java.util.List<java.util.Map<String, Object>>) jobsObj;
                        
                        for (java.util.Map<String, Object> job : jobs) {
                            if (jobId.equals(job.get("id"))) {
                                job.put("description", description);
                                mapper.writerWithDefaultPrettyPrinter().writeValue(file, jobs);
                                System.out.println("✅ Updated job description in: " + file.getName());
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            
            System.err.println("⚠️  Job not found in scraped jobs files");
        } catch (Exception e) {
            System.err.println("⚠️  Error updating job file: " + e.getMessage());
        }
    }
}

