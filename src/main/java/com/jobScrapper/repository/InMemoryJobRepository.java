package com.jobScrapper.repository;

public class InMemoryJobRepository implements JobRepository{
    
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(Job job){
        jobs.put(job.getId(), job);
    }

    @Override
    public Optional<Job> findById(String id){
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public JobListReponse findByScrapingId(String scrapinId, int page, int limit){
        List<Job> jobList = jobs.values().stream() // using stream to filter and sort the jobs
                .filter(job -> scrapingId == null || scrapingId.equals(job.getSource()))
                .sorted((a, b) -> b.getPostedDate().compareTo(a.getPostedDate()))
                .collect(Collectors.toList());


    int from = Math.max((page -1) * limit, 0);
    int to = Math.min(from + limit, filtered.size());

    JobListResponse response = new JobListResponse();
    response.setJobs(filtered.subList(from, to));
    resposnse.setTotal(filtered.size());
    response.setPage(page);
    response.setLimit(limit);
    return response;
    }
}
