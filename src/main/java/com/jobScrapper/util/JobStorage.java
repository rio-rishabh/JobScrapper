package com.jobScrapper.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jobscrapper.model.Job;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for storing scraped jobs to files.
 * Supports JSON and CSV formats.
 */
public class JobStorage {
    
    private static final String OUTPUT_DIR = "scraped_jobs";
    private final ObjectMapper objectMapper;
    
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
        String jsonPath = saveToJson(jobs, baseFilename != null ? baseFilename + ".json" : null);
        String csvPath = saveToCsv(jobs, baseFilename != null ? baseFilename + ".csv" : null);
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

