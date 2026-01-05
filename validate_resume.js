#!/usr/bin/env node

/**
 * Resume Validation Script
 * Validates resume against a specific job posting
 */

const { readFileSync, existsSync } = require('fs');
const { join } = require('path');
// Use mammoth from mcp-server node_modules
const mammoth = require(join(__dirname, 'mcp-server', 'node_modules', 'mammoth'));

// Configuration
const DEFAULT_RESUME_PATH = "/Users/rishabhsharma/Downloads/Resume/Resume(WebScrapping)-Dec2025.docx";
const SCRAPED_JOBS_DIR = join(__dirname, "scraped_jobs");
const JOB_ID = "86451df2-56f3-4e51-ba8e-2396433b1550";

// Common technical keywords to extract
const COMMON_KEYWORDS = [
  // Programming Languages
  "java", "python", "javascript", "typescript", "c++", "c#", "go", "rust",
  "kotlin", "swift", "php", "ruby", "scala", "r", "sql",
  // Frameworks & Libraries
  "react", "angular", "vue", "node.js", "express", "spring", "django", "flask",
  "rails", "asp.net", "laravel",
  // Tools & Technologies
  "docker", "kubernetes", "aws", "azure", "gcp", "terraform", "jenkins", "git",
  "ci/cd", "microservices", "rest api", "graphql", "mongodb", "postgresql",
  "mysql", "redis", "elasticsearch",
  // Methodologies
  "agile", "scrum", "devops", "tdd", "test driven development", "api development",
  "full stack", "backend", "frontend",
  // Soft skills
  "collaboration", "leadership", "problem solving"
];

async function extractTextFromDocx(filePath) {
  try {
    const result = await mammoth.extractRawText({ path: filePath });
    return result.value;
  } catch (error) {
    throw new Error(`Failed to read DOCX file: ${error.message}`);
  }
}

function validateResumeAgainstJob(resume, jobDescription) {
  const resumeLower = resume.toLowerCase();
  const jobDescLower = jobDescription.toLowerCase();

  // Extract keywords from job description
  const jobKeywords = [];
  for (const keyword of COMMON_KEYWORDS) {
    if (jobDescLower.includes(keyword.toLowerCase())) {
      jobKeywords.push(keyword);
    }
  }

  // Also extract capitalized/technical terms
  const technicalTerms = jobDescription.match(/\b[A-Z][a-z]+(?:\.[a-z]+)?\b/g);
  if (technicalTerms) {
    for (const term of technicalTerms) {
      const termLower = term.toLowerCase();
      if (!jobKeywords.includes(termLower) && 
          COMMON_KEYWORDS.some(k => k.toLowerCase() === termLower)) {
        jobKeywords.push(termLower);
      }
    }
  }

  // Find which keywords are in resume
  const foundKeywords = [];
  const missingKeywords = [];

  for (const keyword of jobKeywords) {
    if (resumeLower.includes(keyword.toLowerCase())) {
      foundKeywords.push(keyword);
    } else {
      missingKeywords.push(keyword);
    }
  }

  // Calculate match score
  const matchScore = jobKeywords.length > 0
    ? Math.round((foundKeywords.length / jobKeywords.length) * 100)
    : 0;

  // Generate recommendations
  const recommendations = [];

  if (missingKeywords.length > 0) {
    recommendations.push(
      `Add these ${missingKeywords.length} missing keywords to your resume: ${missingKeywords
        .slice(0, 10)
        .join(", ")}${missingKeywords.length > 10 ? "..." : ""}`
    );
  }

  if (matchScore < 50) {
    recommendations.push(
      "Your resume has a low match score. Consider highlighting more relevant skills and experiences that align with the job requirements."
    );
  } else if (matchScore < 70) {
    recommendations.push(
      "Your resume has a moderate match. Focus on adding the missing keywords naturally in your experience descriptions."
    );
  } else {
    recommendations.push(
      "Good match! Your resume aligns well with the job description. Consider emphasizing the matching skills more prominently."
    );
  }

  // Generate analysis
  let analysis = `Resume Match Analysis:\n`;
  analysis += `- Match Score: ${matchScore}%\n`;
  analysis += `- Found ${foundKeywords.length} out of ${jobKeywords.length} required keywords\n`;
  analysis += `- Missing ${missingKeywords.length} keywords\n\n`;

  if (foundKeywords.length > 0) {
    analysis += `✅ Keywords found in your resume:\n${foundKeywords
      .slice(0, 15)
      .map(k => `  • ${k}`)
      .join("\n")}\n\n`;
  }

  if (missingKeywords.length > 0) {
    analysis += `❌ Keywords missing from your resume:\n${missingKeywords
      .slice(0, 15)
      .map(k => `  • ${k}`)
      .join("\n")}\n`;
  }

  return {
    matchScore,
    foundKeywords,
    missingKeywords,
    recommendations,
    analysis,
    jobKeywords
  };
}

async function main() {
  console.log("🔍 Resume Validation Tool\n");
  console.log(`Job ID: ${JOB_ID}\n`);

  // Step 1: Read resume
  console.log("📄 Reading resume...");
  let resumeText = "";
  if (existsSync(DEFAULT_RESUME_PATH)) {
    if (DEFAULT_RESUME_PATH.endsWith('.docx')) {
      resumeText = await extractTextFromDocx(DEFAULT_RESUME_PATH);
    } else {
      resumeText = readFileSync(DEFAULT_RESUME_PATH, "utf-8");
    }
    console.log(`✅ Resume loaded (${resumeText.length} characters)\n`);
  } else {
    console.error(`❌ Resume file not found: ${DEFAULT_RESUME_PATH}`);
    process.exit(1);
  }

  // Step 2: Find job in scraped jobs
  console.log("🔍 Looking for job in scraped jobs...");
  let jobDescription = "";
  let jobTitle = "";
  let jobCompany = "";

  if (existsSync(SCRAPED_JOBS_DIR)) {
    const fs = require('fs');
    const files = fs.readdirSync(SCRAPED_JOBS_DIR)
      .filter(f => f.startsWith("jobs_") && f.endsWith(".json") && !f.includes("_interrupted"))
      .map(f => ({
        name: f,
        path: join(SCRAPED_JOBS_DIR, f),
        mtime: fs.statSync(join(SCRAPED_JOBS_DIR, f)).mtime.getTime()
      }))
      .sort((a, b) => b.mtime - a.mtime);

    for (const file of files.slice(0, 20)) {
      try {
        const jobs = JSON.parse(readFileSync(file.path, "utf-8"));
        const job = Array.isArray(jobs)
          ? jobs.find(j => j.id === JOB_ID)
          : null;
        if (job) {
          jobDescription = job.description || "";
          jobTitle = job.title || "";
          jobCompany = job.company || "";
          console.log(`✅ Found job: ${jobTitle} at ${jobCompany}`);
          if (jobDescription) {
            console.log(`✅ Job description found (${jobDescription.length} characters)\n`);
          } else {
            console.log(`⚠️  Job description is empty in JSON file\n`);
          }
          break;
        }
      } catch (e) {
        continue;
      }
    }
  }

  if (!jobDescription) {
    console.error(`❌ Job not found or description is empty. Job ID: ${JOB_ID}`);
    console.error(`\nPlease note: The job description needs to be fetched from LinkedIn.`);
    console.error(`You may need to re-scrape this job or fetch the description manually.\n`);
    process.exit(1);
  }

  // Step 3: Validate
  console.log("🔍 Validating resume against job description...\n");
  const result = validateResumeAgainstJob(resumeText, jobDescription);

  // Step 4: Display results
  console.log("=".repeat(80));
  console.log("VALIDATION RESULTS");
  console.log("=".repeat(80));
  console.log(`\nJob: ${jobTitle}`);
  console.log(`Company: ${jobCompany}`);
  console.log(`\n${result.analysis}`);
  console.log("\n" + "=".repeat(80));
  console.log("RECOMMENDATIONS");
  console.log("=".repeat(80));
  result.recommendations.forEach((rec, i) => {
    console.log(`${i + 1}. ${rec}`);
  });
  console.log("\n" + "=".repeat(80));
  console.log(`\n📊 Overall Match Score: ${result.matchScore}%`);
  console.log(`✅ Found Keywords: ${result.foundKeywords.length}`);
  console.log(`❌ Missing Keywords: ${result.missingKeywords.length}`);
  console.log(`📋 Total Keywords in Job: ${result.jobKeywords.length}`);
  console.log("\n" + "=".repeat(80));
}

main().catch(error => {
  console.error("❌ Error:", error.message);
  process.exit(1);
});

