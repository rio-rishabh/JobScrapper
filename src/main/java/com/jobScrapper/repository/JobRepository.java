package com.jobScrapper.repository;

import com.jobScrapper.model.Job;
import com.jobscrapper.model.JobListResponse;

import java.util.Optional;

public interface JobRepository {

    void save(Job job);
    Optional<Job> findById(String id);
    JobListResponse findByScrapingId(string scrapingId, int page, int limit);
}
