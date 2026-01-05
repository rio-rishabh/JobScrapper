package com.jobScrapper.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.jobscrapper.model.Job;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Utility class for storing scraped jobs to files.
 * Supports JSON and CSV formats.
 * 
 * DEDUPLICATION: Can load existing jobs and check for duplicates
 * to avoid re-scraping the same jobs.
 */
public class JobStorage {
    
    private static final String OUTPUT_DIR = "scraped_jobs";
    private final ObjectMapper objectMapper;
    
    // Cache of existing job signatures for deduplication
    private Set<String> existingJobSignatures = new HashSet<>();
    
    // URLs from Notion database to skip
    private Set<String> notionUrls = new HashSet<>();
    
    public JobStorage() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        // Create output directory if it doesn't exist
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
    }
    
    /**
     * Load Notion URLs from an array to skip jobs that already exist in Notion.
     * This is called before scraping starts to avoid scraping duplicate jobs.
     * URLs are stored in memory for fast lookup during scraping.
     * 
     * @param urls List of URLs from Notion database
     */
    public void loadNotionUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            System.out.println("⚠️  No Notion URLs provided - deduplication against Notion will be disabled");
            return;
        }
        
        // Add all URLs and their normalized versions for matching
        for (String url : urls) {
            if (url != null && !url.isEmpty()) {
                notionUrls.add(url);
                // Also add normalized version (without query params)
                String normalized = normalizeUrl(url);
                notionUrls.add(normalized);
                // Add with/without trailing slash variations
                if (!normalized.endsWith("/")) {
                    notionUrls.add(normalized + "/");
                } else {
                    notionUrls.add(normalized.substring(0, normalized.length() - 1));
                }
            }
        }
        
        System.out.println("✅ Loaded " + urls.size() + " existing job URLs from Notion (" + notionUrls.size() + " variations for matching)");
        System.out.println("   These jobs will be skipped during scraping to avoid duplicates.");
    }
    
    /**
     * Load all existing jobs from JSON files in the output directory.
     * Used for deduplication.
     * 
     * @return List of all previously scraped jobs
     */
    public List<Job> loadExistingJobs() {
        List<Job> allJobs = new ArrayList<>();
        File outputDir = new File(OUTPUT_DIR);
        
        if (!outputDir.exists() || !outputDir.isDirectory()) {
            return allJobs;
        }
        
        File[] jsonFiles = outputDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (jsonFiles == null) {
            return allJobs;
        }
        
        System.out.println("📂 Loading existing jobs for deduplication...");
        int filesLoaded = 0;
        
        for (File file : jsonFiles) {
            try {
                List<Job> jobs = objectMapper.readValue(file, new TypeReference<List<Job>>() {});
                if (jobs != null) {
                    allJobs.addAll(jobs);
                    filesLoaded++;
                }
            } catch (Exception e) {
                // Skip files that can't be parsed (might be corrupted or different format)
                System.err.println("   ⚠️  Could not load: " + file.getName() + " - " + e.getMessage());
            }
        }
        
        System.out.println("   ✅ Loaded " + allJobs.size() + " existing jobs from " + filesLoaded + " files");
        return allJobs;
    }
    
    /**
     * Build the deduplication cache from existing jobs.
     * Call this once before scraping to enable deduplication.
     */
    public void buildDeduplicationCache() {
        existingJobSignatures.clear();
        List<Job> existingJobs = loadExistingJobs();
        
        for (Job job : existingJobs) {
            String signature = getJobSignature(job);
            existingJobSignatures.add(signature);
        }
        
        System.out.println("   🔍 Built deduplication cache with " + existingJobSignatures.size() + " unique job signatures");
    }
    
    /**
     * Check if a job already exists in our database or Notion.
     * Uses URL as primary identifier, falls back to title+company.
     * 
     * @param job The job to check
     * @return true if this job already exists
     */
    public boolean isDuplicate(Job job) {
        // First check against Notion URLs (highest priority)
        if (job.getUrl() != null && !job.getUrl().isEmpty()) {
            if (matchesNotionUrl(job.getUrl())) {
                System.out.println("   🚫 SKIPPED (exists in Notion): " + job.getTitle() + " at " + job.getCompany());
                return true;  // Job exists in Notion, skip it
            }
        }
        
        // Then check against local cache
        if (existingJobSignatures.isEmpty()) {
            return false;  // No cache built, assume not duplicate
        }
        
        String signature = getJobSignature(job);
        return existingJobSignatures.contains(signature);
    }
    
    /**
     * Normalize URL by removing query parameters and fragments.
     * Returns multiple variations for comparison.
     */
    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        try {
            // Remove query params
            int queryIndex = url.indexOf('?');
            if (queryIndex > 0) {
                url = url.substring(0, queryIndex);
            }
            // Remove fragments
            int fragmentIndex = url.indexOf('#');
            if (fragmentIndex > 0) {
                url = url.substring(0, fragmentIndex);
            }
            return url.toLowerCase();
        } catch (Exception e) {
            return url.toLowerCase();
        }
    }
    
    /**
     * Check if a URL matches any Notion URL (with various normalizations)
     */
    private boolean matchesNotionUrl(String jobUrl) {
        if (jobUrl == null || jobUrl.isEmpty() || notionUrls.isEmpty()) {
            return false;
        }
        
        // Try multiple variations
        String normalized = normalizeUrl(jobUrl);
        
        // Check exact match
        if (notionUrls.contains(jobUrl) || notionUrls.contains(normalized)) {
            return true;
        }
        
        // Check with/without trailing slash
        String withSlash = normalized.endsWith("/") ? normalized : normalized + "/";
        String withoutSlash = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        
        if (notionUrls.contains(withSlash) || notionUrls.contains(withoutSlash)) {
            return true;
        }
        
        // Check if any Notion URL matches when normalized
        for (String notionUrl : notionUrls) {
            String normalizedNotion = normalizeUrl(notionUrl);
            if (normalized.equals(normalizedNotion)) {
                return true;
            }
            // Check with/without trailing slash
            String notionWithSlash = normalizedNotion.endsWith("/") ? normalizedNotion : normalizedNotion + "/";
            String notionWithoutSlash = normalizedNotion.endsWith("/") ? normalizedNotion.substring(0, normalizedNotion.length() - 1) : normalizedNotion;
            if (normalized.equals(notionWithSlash) || normalized.equals(notionWithoutSlash)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Add a job to the deduplication cache (call after successfully storing a new job).
     */
    public void addToCache(Job job) {
        String signature = getJobSignature(job);
        existingJobSignatures.add(signature);
    }
    
    /**
     * Generate a unique signature for a job.
     * Primary: URL (most unique)
     * Fallback: normalized title + company
     */
    private String getJobSignature(Job job) {
        // Try URL first (most reliable unique identifier)
        String url = job.getUrl();
        if (url != null && !url.isEmpty() && !url.equals("https://www.linkedin.com") 
            && !url.equals("https://www.indeed.com") && !url.equals("https://www.glassdoor.com")) {
            // Normalize URL (remove tracking parameters)
            url = url.split("\\?")[0];  // Remove query params
            return "URL:" + url.toLowerCase();
        }
        
        // Fallback: title + company combination
        String title = job.getTitle() != null ? job.getTitle().toLowerCase().trim() : "";
        String company = job.getCompany() != null ? job.getCompany().toLowerCase().trim() : "";
        
        // Normalize common variations
        title = title.replaceAll("[^a-z0-9\\s]", "").replaceAll("\\s+", " ");
        company = company.replaceAll("[^a-z0-9\\s]", "").replaceAll("\\s+", " ");
        
        return "TC:" + title + "|" + company;
    }
    
    /**
     * Get statistics about existing jobs.
     */
    public int getExistingJobCount() {
        return existingJobSignatures.size();
    }
    
    /**
     * Save jobs to a JSON file.
     * 
     * @param jobs List of jobs to save
     * @param filename Optional filename (if null, generates timestamp-based name)
     * @return Path to the saved file
     */
    public String saveToJson(List<Job> jobs, String filename) throws IOException {
        if (jobs == null || jobs.isEmpty()) {
            System.out.println("⚠️  No jobs to save (list is null or empty)");
            return null;
        }
        
        if (filename == null || filename.isEmpty()) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            filename = "jobs_" + timestamp + ".json";
        }
        
        if (!filename.endsWith(".json")) {
            filename += ".json";
        }
        
        File file = new File(OUTPUT_DIR, filename);
        
        // Double-check: Don't create empty files
        if (jobs == null || jobs.isEmpty()) {
            System.out.println("⚠️  Cannot save: jobs list is null or empty");
            // Delete file if it exists and is empty
            if (file.exists() && file.length() == 0) {
                file.delete();
            }
            return null;
        }
        
        try {
            objectMapper.writeValue(file, jobs);
            
            // Verify file was written and has content
            if (!file.exists() || file.length() == 0) {
                System.err.println("⚠️  Warning: File was created but appears to be empty!");
                if (file.exists()) {
                    file.delete(); // Delete empty file
                }
                return null;
            }
            
            System.out.println("💾 Saved " + jobs.size() + " jobs to: " + file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (Exception e) {
            System.err.println("❌ Error saving JSON file: " + e.getMessage());
            // Clean up empty file if it was created
            if (file.exists() && file.length() == 0) {
                file.delete();
            }
            throw e;
        }
    }
    
    /**
     * Save jobs to a CSV file for easy viewing in Excel.
     * 
     * @param jobs List of jobs to save
     * @param filename Optional filename (if null, generates timestamp-based name)
     * @return Path to the saved file
     */
    public String saveToCsv(List<Job> jobs, String filename) throws IOException {
        if (jobs == null || jobs.isEmpty()) {
            System.out.println("⚠️  No jobs to save (list is null or empty)");
            return null;
        }
        
        if (filename == null || filename.isEmpty()) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            filename = "jobs_" + timestamp + ".csv";
        }
        
        if (!filename.endsWith(".csv")) {
            filename += ".csv";
        }
        
        File file = new File(OUTPUT_DIR, filename);
        
        // Double-check: Don't create empty files
        if (jobs == null || jobs.isEmpty()) {
            System.out.println("⚠️  Cannot save CSV: jobs list is null or empty");
            // Delete file if it exists and is empty
            if (file.exists() && file.length() == 0) {
                file.delete();
            }
            return null;
        }
        
        try (FileWriter writer = new FileWriter(file)) {
            // Write CSV header
            writer.append("ID,Title,Company,Location,URL,Source,Posted Date,Description\n");
            
            // Write each job as a CSV row
            for (Job job : jobs) {
                writer.append(escapeCsv(job.getId())).append(",");
                writer.append(escapeCsv(job.getTitle())).append(",");
                writer.append(escapeCsv(job.getCompany())).append(",");
                writer.append(escapeCsv(job.getLocation())).append(",");
                writer.append(escapeCsv(job.getUrl())).append(",");
                writer.append(escapeCsv(job.getSource())).append(",");
                writer.append(escapeCsv(job.getPostedDate() != null ? job.getPostedDate().toString() : "")).append(",");
                writer.append(escapeCsv(job.getDescription())).append("\n");
            }
        }
        
        // Verify file was written and has content
        if (!file.exists() || file.length() == 0) {
            System.err.println("⚠️  Warning: CSV file was created but appears to be empty!");
            if (file.exists()) {
                file.delete(); // Delete empty file
            }
            return null;
        }
        
        System.out.println("💾 Saved " + jobs.size() + " jobs to CSV: " + file.getAbsolutePath());
        return file.getAbsolutePath();
    }
    
    /**
     * Save jobs to both JSON and CSV formats.
     * 
     * @param jobs List of jobs to save
     * @param baseFilename Optional base filename (without extension)
     * @return Array with [JSON path, CSV path]
     */
    public String[] saveAll(List<Job> jobs, String baseFilename) throws IOException {
        System.out.println("💾 [JobStorage] Attempting to save " + (jobs != null ? jobs.size() : 0) + " jobs with baseFilename: " + baseFilename);
        
        if (jobs == null || jobs.isEmpty()) {
            System.err.println("⚠️  [JobStorage] Cannot save: jobs list is null or empty");
            return new String[]{null, null};
        }
        
        String jsonPath = null;
        String csvPath = null;
        
        try {
            jsonPath = saveToJson(jobs, baseFilename != null ? baseFilename + ".json" : null);
            System.out.println("💾 [JobStorage] JSON save result: " + (jsonPath != null ? "SUCCESS - " + jsonPath : "FAILED"));
        } catch (Exception e) {
            System.err.println("❌ [JobStorage] Error saving JSON: " + e.getMessage());
            e.printStackTrace();
        }
        
        try {
            csvPath = saveToCsv(jobs, baseFilename != null ? baseFilename + ".csv" : null);
            System.out.println("💾 [JobStorage] CSV save result: " + (csvPath != null ? "SUCCESS - " + csvPath : "FAILED"));
        } catch (Exception e) {
            System.err.println("❌ [JobStorage] Error saving CSV: " + e.getMessage());
            e.printStackTrace();
        }
        
        return new String[]{jsonPath, csvPath};
    }
    
    /**
     * Escape CSV special characters.
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        // Replace newlines and commas, wrap in quotes if needed
        String escaped = value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}

