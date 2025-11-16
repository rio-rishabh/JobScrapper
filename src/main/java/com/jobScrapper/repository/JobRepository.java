package com.jobScrapper.repository;

import com.jobscrapper.model.Job;
import com.jobscrapper.model.JobListResponse;

import java.util.Optional;

public interface JobRepository {

    void save(Job job);
    Optional<Job> findById(String id);
    JobListResponse findByScrapingId(String scrapingId, int page, int limit);
}
