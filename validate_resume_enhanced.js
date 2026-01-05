#!/usr/bin/env node

/**
 * Enhanced Resume Validation Script
 * Validates resume against Software Engineer position at Snyk
 * Works even without full job description by using industry-standard keywords
 */

const { readFileSync, existsSync } = require('fs');
const { join } = require('path');
// Use mammoth from mcp-server node_modules
const mammoth = require(join(__dirname, 'mcp-server', 'node_modules', 'mammoth'));

// Configuration
const DEFAULT_RESUME_PATH = "/Users/rishabhsharma/Downloads/Resume/Resume(WebScrapping)-Dec2025.docx";
const JOB_ID = "86451df2-56f3-4e51-ba8e-2396433b1550";

// Industry-standard keywords for Software Engineer at Snyk (security/DevSecOps company)
const SNYK_KEYWORDS = {
  // Programming Languages (High Priority)
  languages: [
    "javascript", "typescript", "python", "java", "go", "ruby", "node.js",
    "c++", "c#", "rust", "kotlin", "swift"
  ],
  
  // Frameworks & Libraries
  frameworks: [
    "react", "angular", "vue", "express", "spring", "django", "flask",
    "rails", "asp.net", "laravel", "next.js", "nestjs"
  ],
  
  // Security & DevOps (Critical for Snyk)
  security: [
    "security", "vulnerability", "scanning", "sast", "dast", "dependency",
    "container security", "infrastructure as code", "iac", "devsecops",
    "security scanning", "vulnerability detection", "threat", "compliance"
  ],
  
  // DevOps & Infrastructure
  devops: [
    "docker", "kubernetes", "k8s", "ci/cd", "jenkins", "github actions",
    "gitlab ci", "terraform", "ansible", "chef", "puppet", "microservices",
    "container", "orchestration", "deployment", "automation"
  ],
  
  // Cloud Platforms
  cloud: [
    "aws", "amazon web services", "azure", "gcp", "google cloud",
    "cloud", "serverless", "lambda", "ec2", "s3", "ecs", "eks"
  ],
  
  // APIs & Services
  apis: [
    "rest api", "graphql", "api development", "microservices", "service",
    "backend", "backend development", "api design", "web services"
  ],
  
  // Databases
  databases: [
    "postgresql", "postgres", "mongodb", "mysql", "redis", "elasticsearch",
    "database", "sql", "nosql", "dynamodb", "cassandra"
  ],
  
  // Testing & Quality
  testing: [
    "testing", "unit test", "integration test", "tdd", "test driven development",
    "qa", "quality assurance", "automated testing", "jest", "junit", "pytest"
  ],
  
  // Methodologies
  methodologies: [
    "agile", "scrum", "devops", "lean", "kanban", "sprint", "sprint planning"
  ],
  
  // Tools & Technologies
  tools: [
    "git", "github", "gitlab", "bitbucket", "jira", "confluence", "slack",
    "monitoring", "logging", "observability", "prometheus", "grafana"
  ],
  
  // Soft Skills
  softSkills: [
    "collaboration", "teamwork", "leadership", "problem solving", "communication",
    "mentoring", "code review", "pair programming"
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
  
  for (const [category, keywords] of Object.entries(SNYK_KEYWORDS)) {
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
  if (results.scores.security < 30) {
    results.recommendations.push(
      "🔴 CRITICAL: Add security-related keywords! Snyk is a security company. Add terms like: security scanning, vulnerability detection, SAST, DAST, DevSecOps, container security."
    );
  }
  
  if (results.scores.devops < 40) {
    results.recommendations.push(
      "🟠 IMPORTANT: Highlight DevOps experience. Add: Docker, Kubernetes, CI/CD, infrastructure automation, microservices."
    );
  }
  
  if (results.scores.languages < 50) {
    results.recommendations.push(
      "🟡 Add more programming languages. Snyk uses JavaScript/TypeScript, Python, Java, Go, and Ruby."
    );
  }
  
  if (results.scores.cloud < 40) {
    results.recommendations.push(
      "🟡 Include cloud platform experience: AWS, Azure, or GCP."
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
  
  if (results.overallScore < 50) {
    results.recommendations.push(
      "💡 TIP: Review Snyk's job description and add relevant keywords naturally in your experience descriptions."
    );
  }
  
  return results;
}

function formatResults(results) {
  let output = "\n";
  output += "=".repeat(80) + "\n";
  output += "📊 RESUME VALIDATION RESULTS - SNYK SOFTWARE ENGINEER\n";
  output += "=".repeat(80) + "\n\n";
  
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
    const total = SNYK_KEYWORDS[category].length;
    
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
    output += "✅ Your resume looks great! It aligns well with Software Engineer positions at Snyk.\n";
    output += "   Consider emphasizing your security and DevOps experience even more.\n\n";
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
  output += "4. Use the exact terminology from Snyk's job description\n";
  output += "5. Re-run this validation after updating your resume\n\n";
  
  return output;
}

async function main() {
  console.log("🔍 Enhanced Resume Validation Tool");
  console.log("Job: Software Engineer at Snyk (Boston, MA)\n");
  
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
  
  // Step 2: Validate
  console.log("🔍 Analyzing resume against Software Engineer position at Snyk...\n");
  const results = validateResume(resumeText);
  
  // Step 3: Display results
  console.log(formatResults(results));
}

main().catch(error => {
  console.error("❌ Error:", error.message);
  process.exit(1);
});

