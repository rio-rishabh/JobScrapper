package com.jobScrapper.scraper;

import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingJobRequest;

import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;

public class ScraperManager {

    private final Map<String, JobScraper> scrapers;

    //Constructor that takes a list of JobScraper objects
    public ScraperManager(List<JobScraper> scraperList){
        this.scrapers = scraperList.stream()
            .collect(Collectors.toMap(scraper -> scraper.getSource().toLowerCase(), scraper -> scraper));
    }

    //Constructor that takes a map of JobScraper objects
    public ScraperManager(Map<String, JobScraper> scraperMap){
        this.scrapers = scraperMap.entrySet().stream()
            .collect(Collectors.toMap(entry -> entry.getKey().toLowerCase(), Map.Entry::getValue));
    }

    public Optional<JobScraper> getScraper(String source){
        return Optional.ofNullable(scrapers.get(source.toLowerCase()));
    }

    public void 
    
    
    runScraper(String source, ScrapingJobRequest request, Consumer<Job> sink) throws Exception {
        JobScraper scraper = getScraper(source).orElseThrow(() -> new IllegalArgumentException("No scraper for source: " + source));
        scraper.scrape(request, sink);
    }

    //Get All the available sources names
    public List<String> getAvailableSources(){
        return new ArrayList<>(scrapers.keySet());
    }
}
