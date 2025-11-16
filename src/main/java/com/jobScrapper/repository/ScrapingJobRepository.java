package com.jobScrapper.repository;

import com.jobscrapper.model.ScrapingJob;
import com.jobscrapper.model.ScrapingStatus;

import java.util.Optional;
import javax.annotation.Nonnull;
public interface ScrapingJobRepository {

    ScrapingJob save(ScrapingJob job);
    Optional<ScrapingJob> findById(String id);
    void updateStatus(@Nonnull String id, @Nonnull ScrapingStatus status);
}
