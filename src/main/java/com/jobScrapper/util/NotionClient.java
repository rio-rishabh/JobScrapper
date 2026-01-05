package com.jobScrapper.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;

/**
 * Simple Notion client to query the database for existing job URLs.
 * Used for deduplication - skip jobs that already exist in Notion.
 */
public class NotionClient {
    
    private static final String NOTION_API_URL = "https://api.notion.com/v1/databases/%s/query";
    private static final String NOTION_VERSION = "2022-06-28";
    
    private final String databaseId;
    private final String apiToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // Cache of existing URLs from Notion
    private Set<String> existingUrls = new HashSet<>();
    private boolean cacheLoaded = false;
    
    public NotionClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        
        // Load config from config.json
        String[] config = loadConfig();
        this.databaseId = config[0];
        this.apiToken = config[1];
    }
    
    private String[] loadConfig() {
        String databaseId = "";
        String apiToken = "";
        
        try {
            File configFile = new File("config.json");
            if (configFile.exists()) {
                JsonNode config = objectMapper.readTree(configFile);
                databaseId = config.has("NOTION_DATABASE_ID") ? config.get("NOTION_DATABASE_ID").asText() : "";
                apiToken = config.has("NOTION_API_TOKEN") ? config.get("NOTION_API_TOKEN").asText() : "";
            }
        } catch (Exception e) {
            System.err.println("⚠️  Error loading Notion config: " + e.getMessage());
        }
        
        // Also check environment variables
        if (databaseId.isEmpty()) {
            databaseId = System.getenv("NOTION_DATABASE_ID");
            if (databaseId == null) databaseId = "";
        }
        if (apiToken.isEmpty()) {
            apiToken = System.getenv("NOTION_API_TOKEN");
            if (apiToken == null) apiToken = "";
        }
        
        return new String[] { databaseId, apiToken };
    }
    
    /**
     * Check if Notion is configured properly
     */
    public boolean isConfigured() {
        return databaseId != null && !databaseId.isEmpty() 
            && apiToken != null && !apiToken.isEmpty();
    }
    
    /**
     * Load all existing job URLs from Notion database into cache.
     * Call this once before scraping starts.
     */
    public void loadExistingUrls() {
        if (!isConfigured()) {
            System.out.println("⚠️  Notion not configured - skipping deduplication");
            return;
        }
        
        System.out.println("\n🔍 Querying Notion database for existing jobs...");
        
        try {
            String startCursor = null;
            boolean hasMore = true;
            int totalLoaded = 0;
            
            while (hasMore) {
                String requestBody = startCursor == null 
                    ? "{\"page_size\": 100}"
                    : String.format("{\"page_size\": 100, \"start_cursor\": \"%s\"}", startCursor);
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(NOTION_API_URL, databaseId)))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Notion-Version", NOTION_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    System.err.println("⚠️  Notion API error: " + response.statusCode());
                    System.err.println("   Response: " + response.body().substring(0, Math.min(200, response.body().length())));
                    break;
                }
                
                JsonNode data = objectMapper.readTree(response.body());
                JsonNode results = data.get("results");
                
                if (results != null && results.isArray()) {
                    for (JsonNode page : results) {
                        JsonNode properties = page.get("properties");
                        if (properties != null) {
                            // Try different property names for URL
                            JsonNode urlProperty = properties.get("url");
                            if (urlProperty == null) urlProperty = properties.get("URL");
                            if (urlProperty == null) urlProperty = properties.get("link");
                            if (urlProperty == null) urlProperty = properties.get("Link");
                            
                            if (urlProperty != null) {
                                String url = null;
                                
                                // Handle different property types
                                if (urlProperty.has("url") && !urlProperty.get("url").isNull()) {
                                    url = urlProperty.get("url").asText();
                                } else if (urlProperty.has("rich_text")) {
                                    JsonNode richText = urlProperty.get("rich_text");
                                    if (richText.isArray() && richText.size() > 0) {
                                        url = richText.get(0).get("plain_text").asText();
                                    }
                                }
                                
                                if (url != null && !url.isEmpty()) {
                                    // Add URL and normalized versions
                                    existingUrls.add(url);
                                    existingUrls.add(normalizeUrl(url));
                                    totalLoaded++;
                                }
                            }
                        }
                    }
                }
                
                hasMore = data.has("has_more") && data.get("has_more").asBoolean();
                if (hasMore && data.has("next_cursor") && !data.get("next_cursor").isNull()) {
                    startCursor = data.get("next_cursor").asText();
                } else {
                    hasMore = false;
                }
            }
            
            cacheLoaded = true;
            System.out.println("✅ Loaded " + totalLoaded + " existing job URLs from Notion");
            System.out.println("   These jobs will be SKIPPED during scraping\n");
            
        } catch (Exception e) {
            System.err.println("⚠️  Error querying Notion: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Check if a URL already exists in Notion.
     * Returns true if the job should be SKIPPED (already exists).
     */
    public boolean urlExists(String url) {
        if (!cacheLoaded || url == null || url.isEmpty()) {
            return false;
        }
        
        String normalized = normalizeUrl(url);
        return existingUrls.contains(url) || existingUrls.contains(normalized);
    }
    
    /**
     * Normalize URL by removing query parameters and trailing slashes
     */
    private String normalizeUrl(String url) {
        if (url == null) return "";
        
        // Remove query parameters
        int queryIndex = url.indexOf('?');
        if (queryIndex > 0) {
            url = url.substring(0, queryIndex);
        }
        
        // Remove fragments
        int fragmentIndex = url.indexOf('#');
        if (fragmentIndex > 0) {
            url = url.substring(0, fragmentIndex);
        }
        
        // Remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        
        return url.toLowerCase();
    }
    
    /**
     * Get count of URLs in cache
     */
    public int getCacheSize() {
        return existingUrls.size();
    }
}




