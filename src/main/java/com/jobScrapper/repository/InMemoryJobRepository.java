package com.jobScrapper.repository;

import com.jobscrapper.model.Job;
import com.jobscrapper.model.JobListResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryJobRepository implements JobRepository {

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(Job job) {
        jobs.put(job.getId(), job);
    }

    @Override
    public Optional<Job> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public JobListResponse findByScrapingId(String scrapingId, int page, int limit) {
        List<Job> filtered = jobs.values().stream()
                .filter(job -> scrapingId == null || scrapingId.equals(job.getSource()))
                .sorted((a, b) -> b.getPostedDate().compareTo(a.getPostedDate()))
                .collect(Collectors.toList());

        int from = Math.max((page - 1) * limit, 0);
        int to = Math.min(from + limit, filtered.size());

        JobListResponse response = new JobListResponse();
        response.setJobs(filtered.subList(from, to));
        response.setTotal(filtered.size());
        response.setPage(page);
        response.setLimit(limit);
        return response;
    }
}
