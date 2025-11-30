package com.jobScrapper.scraper.impl;

import com.jobScrapper.scraper.BaseJobScraper;
import com.jobscrapper.model.ScrapingJobRequest;
import java.util.List;

public class GlassDoorScraper extends BaseJobScraper{
    
    private static final String GLASSDOOR_JOBS_URL ="https://www.glassdoor.com/Jobs";
    @Override
    public String getSource(){
        return "GlassDoor";
    }

    @Override
    protected String buildSearchURL(ScrapingJobRequest request){
        StringBuilder url = new StringBuilder(GLASSDOOR_JOBS_URL);
        url.append("?");


        List<String> keywords = request.getKeywords();
        if(keywords !=null && !keywords.isEmpty()){
            String keywordsParam = String.join(" ", keywords);
            url.append("keywords=").append(keywordsParam.replace(" ", "%20"));
        }

        String location = request.getLocation();
        if(location !=null && !location.isEmpty()){
            url.append("&location=").append(location.replace(" ", "%20"));
        }
        
        return url.toString();
    }

    @Override
    protected String getJobListSelector(){
        return "ul.jobs-search__results-list > li";       
    }


    @Override
    protected void handleLoginIfNeeded(Page page){
        
        String currentUrl = page.url();
        System.out.println("[" + getSource() + "] Current URL:" + currentUrl);

        if(currentUrl.contains("/login") || currentUrl.contains("/checkpoint")){
            System.out.println("[" + getSource() + "] ⚠️  WARNING: GlassDoor is showing a login page!");
            System.out.println("[" + getSource() + "]    Please log in manually in the browser window.");
            System.out.println("[" + getSource() + "]    Waiting 30 seconds for you to log in...");
            page.waitForTimeout(30000); // Wait 30 seconds for manual login
            System.out.println("[" + getSource() + "]    Continuing after login wait...");
        }

    }

    @Override

}
