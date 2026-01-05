#!/usr/bin/env node

/**
 * Resume Validation Script for Meta Job
 * Validates resume against Meta Software Engineer position
 */

const { readFileSync, existsSync } = require('fs');
const { join } = require('path');
const mammoth = require(join(__dirname, 'mcp-server', 'node_modules', 'mammoth'));

// Configuration
const DEFAULT_RESUME_PATH = "/Users/rishabhsharma/Downloads/Resume/Resume(WebScrapping)-Dec2025.docx";
const META_JOB_URL = "https://www.metacareers.com/profile/job_details/727671609895617";

// Meta Software Engineer Keywords (based on typical Meta requirements)
const META_KEYWORDS = {
  // Programming Languages (Meta uses many)
  languages: [
    "python", "java", "c++", "javascript", "typescript", "php", "hack",
    "rust", "go", "swift", "kotlin", "objective-c", "scala", "erlang"
  ],
  
  // Frameworks & Technologies
  frameworks: [
    "react", "react native", "angular", "vue", "node.js", "django", "flask",
    "tornado", "thrift", "graphql", "apache thrift", "hadoop", "spark"
  ],
  
  // Systems & Infrastructure
  systems: [
    "distributed systems", "large scale", "scalability", "performance",
    "system design", "architecture", "microservices", "service oriented",
    "high availability", "fault tolerance", "load balancing"
  ],
  
  // Data & ML
  data: [
    "machine learning", "ml", "ai", "data science", "big data", "analytics",
    "recommendation systems", "ranking", "search", "nlp", "computer vision",
    "deep learning", "tensorflow", "pytorch"
  ],
  
  // Infrastructure & DevOps
  infrastructure: [
    "kubernetes", "docker", "containers", "ci/cd", "jenkins", "terraform",
    "infrastructure", "deployment", "automation", "monitoring", "observability"
  ],
  
  // Databases & Storage
  databases: [
    "mysql", "postgresql", "cassandra", "hbase", "redis", "memcached",
    "database", "sql", "nosql", "distributed storage", "tao"
  ],
  
  // APIs & Services
  apis: [
    "rest api", "graphql", "api", "web services", "backend", "backend services",
    "service architecture", "rpc", "grpc", "thrift"
  ],
  
  // Testing & Quality
  testing: [
    "testing", "unit test", "integration test", "test automation", "qa",
    "quality assurance", "tdd", "test driven development", "ci/cd"
  ],
  
  // Methodologies
  methodologies: [
    "agile", "scrum", "lean", "iterative development", "code review",
    "pair programming", "continuous integration", "continuous deployment"
  ],
  
  // Meta-Specific
  metaSpecific: [
    "social media", "social network", "ads", "advertising", "news feed",
    "messaging", "real-time", "streaming", "video", "mobile", "ios", "android",
    "web", "frontend", "backend", "full stack"
  ],
  
  // Soft Skills
  softSkills: [
    "collaboration", "teamwork", "communication", "leadership", "mentoring",
    "problem solving", "analytical", "creativity", "innovation"
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

function validateResume(resumeText, jobDescription = "") {
  const resumeLower = resumeText.toLowerCase();
  const jobDescLower = jobDescription.toLowerCase();
  
  const results = {
    found: {},
    missing: {},
    scores: {},
    overallScore: 0,
    rating: "",
    recommendations: [],
    jobKeywords: []
  };
  
  // If job description provided, extract keywords from it
  if (jobDescription && jobDescription.length > 50) {
    // Extract keywords from job description
    for (const [category, keywords] of Object.entries(META_KEYWORDS)) {
      for (const keyword of keywords) {
        if (jobDescLower.includes(keyword.toLowerCase())) {
          if (!results.jobKeywords.includes(keyword)) {
            results.jobKeywords.push(keyword);
          }
        }
      }
    }
  }
  
  // Check each category
  let totalKeywords = 0;
  let foundKeywords = 0;
  
  // If we have job-specific keywords, use those; otherwise use all Meta keywords
  const keywordsToCheck = results.jobKeywords.length > 0 
    ? results.jobKeywords 
    : Object.values(META_KEYWORDS).flat();
  
  // Group found/missing by category
  for (const [category, keywords] of Object.entries(META_KEYWORDS)) {
    const found = [];
    const missing = [];
    
    const categoryKeywords = results.jobKeywords.length > 0
      ? keywords.filter(k => results.jobKeywords.includes(k))
      : keywords;
    
    for (const keyword of categoryKeywords) {
      totalKeywords++;
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
    const categoryScore = categoryKeywords.length > 0 
      ? Math.round((found.length / categoryKeywords.length) * 100)
      : 0;
    results.scores[category] = categoryScore;
  }
  
  // Calculate overall score
  results.overallScore = totalKeywords > 0
    ? Math.round((foundKeywords / totalKeywords) * 100)
    : 0;
  
  // Determine rating
  if (results.overallScore >= 75) {
    results.rating = "⭐⭐⭐⭐⭐ EXCELLENT";
  } else if (results.overallScore >= 60) {
    results.rating = "⭐⭐⭐⭐ VERY GOOD";
  } else if (results.overallScore >= 45) {
    results.rating = "⭐⭐⭐ GOOD";
  } else if (results.overallScore >= 30) {
    results.rating = "⭐⭐ FAIR";
  } else {
    results.rating = "⭐ NEEDS IMPROVEMENT";
  }
  
  // Generate recommendations
  if (results.scores.systems < 40) {
    results.recommendations.push(
      "🔴 CRITICAL: Highlight distributed systems and scalability experience. Meta values engineers who can build at scale."
    );
  }
  
  if (results.scores.languages < 50) {
    results.recommendations.push(
      "🟠 IMPORTANT: Meta uses Python, Java, C++, JavaScript/TypeScript, PHP, and Hack. Highlight relevant languages."
    );
  }
  
  if (results.scores.data < 30 && results.scores.metaSpecific < 30) {
    results.recommendations.push(
      "🟡 Consider highlighting experience with large-scale systems, real-time systems, or social media platforms."
    );
  }
  
  if (results.scores.methodologies < 40) {
    results.recommendations.push(
      "🟡 Add Agile/Scrum methodologies and emphasize code review and collaborative development practices."
    );
  }
  
  // Top missing keywords
  const allMissing = Object.values(results.missing).flat();
  if (allMissing.length > 0) {
    const topMissing = [...new Set(allMissing)].slice(0, 15);
    results.recommendations.push(
      `📝 Top missing keywords to add: ${topMissing.join(", ")}`
    );
  }
  
  if (results.overallScore < 50) {
    results.recommendations.push(
      "💡 TIP: Review Meta's job description and add relevant keywords naturally in your experience descriptions."
    );
  }
  
  return results;
}

function formatResults(results, jobTitle = "Meta Software Engineer") {
  let output = "\n";
  output += "=".repeat(80) + "\n";
  output += `📊 RESUME VALIDATION RESULTS - ${jobTitle.toUpperCase()}\n`;
  output += "=".repeat(80) + "\n\n";
  
  output += `🎯 OVERALL RATING: ${results.rating}\n`;
  output += `📈 Overall Match Score: ${results.overallScore}%\n`;
  output += `✅ Keywords Found: ${Object.values(results.found).flat().length}\n`;
  output += `❌ Keywords Missing: ${Object.values(results.missing).flat().length}\n`;
  
  if (results.jobKeywords.length > 0) {
    output += `📋 Job-Specific Keywords Identified: ${results.jobKeywords.length}\n`;
  }
  output += "\n";
  
  output += "=".repeat(80) + "\n";
  output += "📋 CATEGORY BREAKDOWN\n";
  output += "=".repeat(80) + "\n\n";
  
  // Sort categories by score (lowest first)
  const categories = Object.entries(results.scores)
    .filter(([cat]) => results.found[cat].length > 0 || results.missing[cat].length > 0)
    .sort((a, b) => a[1] - b[1]);
  
  for (const [category, score] of categories) {
    const emoji = score >= 70 ? "✅" : score >= 50 ? "🟡" : "❌";
    const found = results.found[category].length;
    const total = results.found[category].length + results.missing[category].length;
    
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
    output += "✅ Your resume looks great! It aligns well with Meta Software Engineer positions.\n";
    output += "   Consider emphasizing your large-scale systems and distributed systems experience.\n\n";
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
  if (topCategories.length > 0) {
    output += "✅ Your Strengths:\n";
    topCategories.forEach(([cat, score]) => {
      if (score > 0) {
        output += `   • ${cat}: ${score}% match\n`;
      }
    });
    output += "\n";
  }
  
  // Areas for improvement
  const weakCategories = categories.slice(0, 3);
  if (weakCategories.length > 0) {
    output += "🔧 Areas for Improvement:\n";
    weakCategories.forEach(([cat, score]) => {
      output += `   • ${cat}: ${score}% match - Focus on adding ${cat}-related keywords\n`;
    });
    output += "\n";
  }
  
  output += "=".repeat(80) + "\n";
  output += "📝 NEXT STEPS\n";
  output += "=".repeat(80) + "\n\n";
  output += "1. Review the missing keywords above\n";
  output += "2. Add them naturally to your experience descriptions\n";
  output += "3. Highlight relevant projects that demonstrate these skills\n";
  output += "4. Emphasize large-scale systems and distributed systems experience\n";
  output += "5. Re-run this validation after updating your resume\n\n";
  
  return output;
}

async function main() {
  console.log("🔍 Meta Resume Validation Tool");
  console.log(`Job URL: ${META_JOB_URL}\n`);
  
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
  
  // Step 2: Try to get job description (if available)
  let jobDescription = "";
  console.log("🔍 Note: Job description will be extracted from Meta careers page if available.\n");
  console.log("   For best results, you can manually copy the job description and provide it.\n");
  
  // Step 3: Validate
  console.log("🔍 Analyzing resume against Meta Software Engineer position...\n");
  const results = validateResume(resumeText, jobDescription);
  
  // Step 4: Display results
  console.log(formatResults(results, "Meta Software Engineer"));
}

main().catch(error => {
  console.error("❌ Error:", error.message);
  process.exit(1);
});

