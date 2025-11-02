# Job Scraper API

Enterprise-grade job scraping application built with Java, Playwright, and OpenAPI.

## Architecture

- **Backend**: Java (Gradle) with Playwright for browser automation
- **Frontend**: React with TypeScript
- **API Contract**: OpenAPI 3.0 (YAML)
- **Code Generation**: Models and clients generated from YAML schemas

## Features

- Scrape jobs from multiple job boards (LinkedIn, Indeed, Glassdoor, etc.)
- MCP agent integration for automated scraping
- RESTful API with OpenAPI specification
- Type-safe TypeScript client for React frontend
- Automatic code generation from YAML schemas

## Tech Stack

- **Build Tool**: Gradle
- **Language**: Java 21
- **Browser Automation**: Playwright
- **HTTP Client**: Apache HTTP Components
- **API Specification**: OpenAPI 3.0.3
- **Documentation**: OpenAPI/Swagger

## Project Structure

```
JobScrapper/
├── models/
│   └── schemas/
│       └── job.yaml          # OpenAPI specification
├── src/
│   ├── main/java/            # Source code
│   └── test/java/            # Tests
├── build.gradle              # Build configuration
└── README.md                # This file
```

## Setup

### Prerequisites

- Java 21+
- Gradle (or use gradlew wrapper)
- Node.js (for OpenAPI Generator CLI)

### Build

```bash
./gradlew clean build
```

This will:
1. Generate Java models from `models/schemas/*.yaml`
2. Compile the project
3. Create JAR file

### Generate Models

```bash
./gradlew generateAllOpenApiModels
```

## API Endpoints

- `POST /scraping/start` - Start a new scraping job
- `GET /jobs` - Get all scraped jobs (with pagination)
- `GET /jobs/{id}` - Get a specific job
- `DELETE /scraping/{id}` - Stop/cancel a scraping job

## Development

### Adding New YAML Schemas

1. Add your `.yaml` file to `models/schemas/`
2. Run `./gradlew generateAllOpenApiModels`
3. Java models will be automatically generated

### Generated Code

Generated files are in `build/generated/openapi/` and are automatically included in the build.

## License

[Add your license here]

