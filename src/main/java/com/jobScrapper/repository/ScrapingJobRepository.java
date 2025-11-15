package com.jobScrapper.repository;

import com.jobScrapper.model.ScrapingJob;
import com.jobScrapper.model.ScrapingStatus;

import java.util.Optional;
public interface ScrapingJobRepository {

    ScrapingJob save(ScrapingJob job);
    Optional<ScrapingJob> findById(String id);
    void updateStatus(String id, ScrapingStatus status);
}
