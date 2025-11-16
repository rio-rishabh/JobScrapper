package com.jobScrapper.service;

import com.jobscrapper.model.Job;
import com.jobscrapper.model.JobListResponse;
import com.jobscrapper.model.ScrapingJob;
import com.jobscrapper.model.ScrapingJobRequest;
import com.jobscrapper.model.ScrapingStatus;
public interface ScrapingService {

    ScrapingJob startScraping(ScrapingJobRequest request);

    JobListResponse getJobs(String scrapingId, int page, int limit);
    
    Job getJob(String id);

    ScrapingStatus stopScraping(String id);
}
