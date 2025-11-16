package com.jobScrapper.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.jobscrapper.model.ScrapingJob;
import com.jobscrapper.model.ScrapingStatus;
import java.util.Optional;

public class InMemoryScrapingJobRepository implements ScrapingJobRepository {

    private final Map<String, ScrapingJob> jobs = new ConcurrentHashMap<>();

    @Override
    public ScrapingJob save(ScrapingJob job){
        jobs.put(job.getScrapingId(), job);
        return job;
    }

    @Override
    public Optional<ScrapingJob> findById(String id){
        return Optional.ofNullable(jobs.get(id));
    }
    @Override
    public void updateStatus(@Nonnull String id, @Nonnull ScrapingStatus status){
        jobs.computeIfPresent(id, (k, v) -> {
            ScrapingJob.StatusEnum target = java.util.Optional.ofNullable(status.getStatus())
                    .map(ScrapingStatus.StatusEnum::getValue)
                    .map(ScrapingJob.StatusEnum::fromValue)
                    .orElse(null);
            return target != null ? v.status(target) : v;
        });
    }
}
