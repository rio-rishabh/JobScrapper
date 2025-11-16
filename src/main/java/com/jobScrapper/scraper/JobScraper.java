 package com.jobScrapper.scraper;
 import com.jobscrapper.model.ScrapingJobRequest;
import com.jobscrapper.model.Job;
import java.util.function.Consumer;

public interface JobScraper {

    String getSource();
    void scrape(ScrapingJobRequest request, Consumer<Job> sink) throws Exception;
}
