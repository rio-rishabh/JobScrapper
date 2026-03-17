#!/usr/bin/env node

/**
 * Resume Validation Script for Specific Job
 * Validates resume against job ID: db60487a-5e2f-442c-895e-7ddd38d09f2d
 * Software Engineer 3 at Onto Innovation
 */

const { readFileSync, existsSync, readdirSync, statSync } = require('fs');
const { join } = require('path');
const mammoth = require(join(__dirname, 'mcp-server', 'node_modules', 'mammoth'));

// Configuration
const DEFAULT_RESUME_PATH = "/Users/rishabhsharma/Downloads/Resume/Resume(WebScrapping)-Dec2025.docx";
const SCRAPED_JOBS_DIR = join(__dirname, "scraped_jobs");
const JOB_ID = "db60487a-5e2f-442c-895e-7ddd38d09f2d";

// Industry-standard keywords for Software Engineer 3 position
// Onto Innovation is a semiconductor/metrology company, so they likely need:
const JOB_KEYWORDS = {
  // Programming Languages (High Priority)
  languages: [
    "java", "python", "c++", "c", "javascript", "typescript", "matlab",
    "sql", "shell scripting", "bash"
  ],
  
  // Software Engineering Skills
  software: [
    "software development", "software engineering", "object-oriented programming",
    "oop", "design patterns", "algorithms", "data structures", "code review",
    "version control", "git", "software architecture", "system design"
  ],
  
  // Testing & Quality
  testing: [
    "unit testing", "integration testing", "test automation", "qa", "quality assurance",
    "test driven development", "tdd", "continuous integration", "ci/cd"
  ],
  
  // Tools & Technologies
  tools: [
    "linux", "unix", "docker", "kubernetes", "jenkins", "jira", "confluence",
    "agile", "scrum", "devops", "microservices", "rest api"
  ],
  
  // Domain-Specific (Semiconductor/Metrology)
  domain: [
    "semiconductor", "metrology", "hardware", "embedded systems", "firmware",
    "real-time systems", "image processing", "computer vision", "signal processing",
    "instrumentation", "measurement", "calibration", "precision"
  ],
  
  // Databases & Data
  databases: [
    "database", "sql", "postgresql", "mysql", "oracle", "data analysis",
    "data processing", "etl"
  ],
  
  // Soft Skills
  softSkills: [
    "collaboration", "teamwork", "leadership", "problem solving", "communication",
    "mentoring", "technical writing", "documentation"
  ]
};

async function extractTextFromDocx(filePath) {
  try {
    const result = await mammoth.extractRawText({ path: filePath });
    return result.value;
  } catch (error) {
    throw new Error(`Failed to read DOCX file: ${error.message}`);
  }
}

function validateResume(resumeText) {
  const resumeLower = resumeText.toLowerCase();
  const results = {
    found: {},
    missing: {},
    scores: {},
    overallScore: 0,
    rating: "",
    recommendations: []
  };
  
  // Check each category
  let totalKeywords = 0;
  let foundKeywords = 0;
  
  for (const [category, keywords] of Object.entries(JOB_KEYWORDS)) {
    const found = [];
    const missing = [];
    
    for (const keyword of keywords) {
      totalKeywords++;
      // Check for keyword (case-insensitive, whole word or as part of phrase)
      const regex = new RegExp(`\\b${keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'i');
      if (regex.test(resumeLower) || resumeLower.includes(keyword.toLowerCase())) {
        found.push(keyword);
        foundKeywords++;
      } else {
        missing.push(keyword);
      }
    }
    
    results.found[category] = found;
    results.missing[category] = missing;
    
    // Calculate category score
    const categoryScore = keywords.length > 0 
      ? Math.round((found.length / keywords.length) * 100)
      : 0;
    results.scores[category] = categoryScore;
  }
  
  // Calculate overall score
  results.overallScore = totalKeywords > 0
    ? Math.round((foundKeywords / totalKeywords) * 100)
    : 0;
  
  // Determine rating
  if (results.overallScore >= 80) {
    results.rating = "⭐⭐⭐⭐⭐ EXCELLENT";
  } else if (results.overallScore >= 65) {
    results.rating = "⭐⭐⭐⭐ VERY GOOD";
  } else if (results.overallScore >= 50) {
    results.rating = "⭐⭐⭐ GOOD";
  } else if (results.overallScore >= 35) {
    results.rating = "⭐⭐ FAIR";
  } else {
    results.rating = "⭐ NEEDS IMPROVEMENT";
  }
  
  // Generate recommendations
  if (results.scores.software < 40) {
    results.recommendations.push(
      "🔴 CRITICAL: Emphasize software engineering fundamentals - object-oriented programming, design patterns, algorithms, data structures."
    );
  }
  
  if (results.scores.languages < 40) {
    results.recommendations.push(
      "🟠 IMPORTANT: Highlight programming languages. Software Engineer 3 typically requires strong proficiency in Java, Python, C++, or similar."
    );
  }
  
  if (results.scores.testing < 30) {
    results.recommendations.push(
      "🟠 IMPORTANT: Add testing experience - unit testing, test automation, CI/CD, quality assurance."
    );
  }
  
  if (results.scores.domain < 20) {
    results.recommendations.push(
      "🟡 Consider adding domain-specific experience if applicable: embedded systems, real-time systems, hardware integration, or instrumentation."
    );
  }
  
  // Top missing keywords
  const allMissing = Object.values(results.missing).flat();
  if (allMissing.length > 0) {
    const topMissing = allMissing.slice(0, 15);
    results.recommendations.push(
      `📝 Top missing keywords to add: ${topMissing.join(", ")}`
    );
  }
  
  return results;
}

function formatResults(results, jobInfo) {
  let output = "\n";
  output += "=".repeat(80) + "\n";
  output += "📊 RESUME VALIDATION RESULTS\n";
  output += "=".repeat(80) + "\n\n";
  
  output += `Job: ${jobInfo.title}\n`;
  output += `Company: ${jobInfo.company}\n`;
  output += `Location: ${jobInfo.location}\n`;
  output += `Job ID: ${jobInfo.id}\n\n`;
  
  output += `🎯 OVERALL RATING: ${results.rating}\n`;
  output += `📈 Overall Match Score: ${results.overallScore}%\n`;
  output += `✅ Keywords Found: ${Object.values(results.found).flat().length}\n`;
  output += `❌ Keywords Missing: ${Object.values(results.missing).flat().length}\n\n`;
  
  output += "=".repeat(80) + "\n";
  output += "📋 CATEGORY BREAKDOWN\n";
  output += "=".repeat(80) + "\n\n";
  
  // Sort categories by score (lowest first to highlight areas for improvement)
  const categories = Object.entries(results.scores)
    .sort((a, b) => a[1] - b[1]);
  
  for (const [category, score] of categories) {
    const emoji = score >= 70 ? "✅" : score >= 50 ? "🟡" : "❌";
    const found = results.found[category].length;
    const total = JOB_KEYWORDS[category].length;
    
    output += `${emoji} ${category.toUpperCase().padEnd(15)}: ${score}% (${found}/${total} keywords)\n`;
    
    if (found > 0 && found <= 5) {
      output += `   Found: ${results.found[category].join(", ")}\n`;
    }
    
    if (results.missing[category].length > 0 && results.missing[category].length <= 5) {
      output += `   Missing: ${results.missing[category].slice(0, 5).join(", ")}${results.missing[category].length > 5 ? "..." : ""}\n`;
    }
    output += "\n";
  }
  
  output += "=".repeat(80) + "\n";
  output += "💡 RECOMMENDATIONS\n";
  output += "=".repeat(80) + "\n\n";
  
  if (results.recommendations.length === 0) {
    output += "✅ Your resume looks great! It aligns well with Software Engineer 3 positions.\n";
    output += "   Consider emphasizing your software engineering experience even more.\n\n";
  } else {
    results.recommendations.forEach((rec, i) => {
      output += `${i + 1}. ${rec}\n\n`;
    });
  }
  
  output += "=".repeat(80) + "\n";
  output += "📌 KEY INSIGHTS\n";
  output += "=".repeat(80) + "\n\n";
  
  // Top strengths
  const topCategories = categories.slice(-3).reverse();
  output += "✅ Your Strengths:\n";
  topCategories.forEach(([cat, score]) => {
    if (score > 0) {
      output += `   • ${cat}: ${score}% match\n`;
    }
  });
  output += "\n";
  
  // Areas for improvement
  const weakCategories = categories.slice(0, 3);
  output += "🔧 Areas for Improvement:\n";
  weakCategories.forEach(([cat, score]) => {
    output += `   • ${cat}: ${score}% match - Focus on adding ${cat}-related keywords\n`;
  });
  output += "\n";
  
  output += "=".repeat(80) + "\n";
  output += "📝 NEXT STEPS\n";
  output += "=".repeat(80) + "\n\n";
  output += "1. Review the missing keywords above\n";
  output += "2. Add them naturally to your experience descriptions\n";
  output += "3. Highlight relevant projects that demonstrate these skills\n";
  output += "4. Visit the job posting to see specific requirements: " + jobInfo.url + "\n";
  output += "5. Re-run this validation after updating your resume\n\n";
  
  return output;
}

async function main() {
  console.log("🔍 Resume Validation Tool");
  console.log(`Job ID: ${JOB_ID}\n`);
  
  // Step 1: Find job
  console.log("🔍 Looking for job in scraped jobs...");
  let jobInfo = null;
  
  if (existsSync(SCRAPED_JOBS_DIR)) {
    const files = readdirSync(SCRAPED_JOBS_DIR)
      .filter(f => f.startsWith("jobs_") && f.endsWith(".json") && !f.includes("_interrupted"))
      .map(f => ({
        name: f,
        path: join(SCRAPED_JOBS_DIR, f),
        mtime: statSync(join(SCRAPED_JOBS_DIR, f)).mtime.getTime()
      }))
      .sort((a, b) => b.mtime - a.mtime);
    
    for (const file of files.slice(0, 20)) {
      try {
        const jobs = JSON.parse(readFileSync(file.path, "utf-8"));
        const job = Array.isArray(jobs)
          ? jobs.find(j => j.id === JOB_ID)
          : null;
        if (job) {
          jobInfo = {
            id: job.id,
            title: job.title?.replace(/\s+/g, ' ').trim() || "Software Engineer 3",
            company: job.company || "Onto Innovation",
            location: job.location || "Wilmington, MA (On-site)",
            url: job.url || ""
          };
          console.log(`✅ Found job: ${jobInfo.title} at ${jobInfo.company}`);
          break;
        }
      } catch (e) {
        continue;
      }
    }
  }
  
  if (!jobInfo) {
    console.error(`❌ Job not found with ID: ${JOB_ID}`);
    process.exit(1);
  }
  
  // Step 2: Read resume
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
  
  // Step 3: Validate
  console.log(`🔍 Analyzing resume against ${jobInfo.title} at ${jobInfo.company}...\n`);
  const results = validateResume(resumeText);
  
  // Step 4: Display results
  console.log(formatResults(results, jobInfo));
}

main().catch(error => {
  console.error("❌ Error:", error.message);
  process.exit(1);
});
