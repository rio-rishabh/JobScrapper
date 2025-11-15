package com.jobScrapper.service;

public interface ScrapingService {

    ScrapingJob startScraping(ScrapingJobRequest request);

    JobListResponse getJobs(String scrapingId, int page, int limit);
    
    Job getJob(String id);

    ScrapingStatus stopScraping(String id);
}
