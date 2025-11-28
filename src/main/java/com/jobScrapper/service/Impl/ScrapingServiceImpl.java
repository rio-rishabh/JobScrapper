package com.jobScrapper.service.Impl;

import com.jobScrapper.service.ScrapingService;
import com.jobScrapper.scraper.ScraperManager;
import com.jobScrapper.repository.JobRepository;
import com.jobScrapper.repository.ScrapingJobRepository;
import com.jobscrapper.model.ScrapingJobRequest;
import com.jobscrapper.model.ScrapingJob;
import java.util.UUID;
import java.time.OffsetDateTime;
import com.jobscrapper.model.JobListResponse;
import com.jobscrapper.model.Job;
import com.jobscrapper.model.ScrapingStatus; 
public class ScrapingServiceImpl implements ScrapingService {

    private final ScraperManager scraperManager;
    private final JobRepository jobRepository;
    private final ScrapingJobRepository scrapingJobRepository;

    public ScrapingServiceImpl(JobRepository jobRepository, ScrapingJobRepository scrapingJobRepository, ScraperManager scraperManager){

        this.jobRepository = jobRepository;
        this.scrapingJobRepository = scrapingJobRepository;
        this.scraperManager = scraperManager;
    }

    @Override
    public ScrapingJob startScraping(ScrapingJobRequest request){

        String scrapingId = UUID.randomUUID().toString();

        ScrapingJob scrapingJob = new ScrapingJob()
        .scrapingId(scrapingId)
        .status(ScrapingJob.StatusEnum.RUNNING)
        .startedAt(OffsetDateTime.now());

        scrapingJobRepository.save(scrapingJob);
        
        request.getSources().forEach(source ->{
            try{
                scraperManager.runScraper(source.getValue(), request,job ->{

                    job.setSource(source.getValue());
                    jobRepository.save(job);
                });
            } catch(Exception e){
                scrapingJobRepository.updateStatus(scrapingId, new ScrapingStatus().scrapingId(scrapingId).status(ScrapingStatus.StatusEnum.FAILED));
            }
        });

        scrapingJobRepository.updateStatus(scrapingId, new ScrapingStatus().scrapingId(scrapingId).status(ScrapingStatus.StatusEnum.COMPLETED));

        return scrapingJobRepository.findById(scrapingId)
            .orElse(scrapingJob);
    }
    @Override
    public JobListResponse getJobs(String scrapidId, int page, int limit){
        return jobRepository.findByScrapingId(scrapidId, page, limit);
    }
    @Override
    public Job getJob(String id){
        return jobRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Job not found"));
    }
    @Override
    public ScrapingStatus stopScraping(String id){
        ScrapingStatus status = new ScrapingStatus()
        .scrapingId(id)
        .status(ScrapingStatus.StatusEnum.CANCELLED);
        scrapingJobRepository.updateStatus(id, status);
        return status;
    
        }
}
