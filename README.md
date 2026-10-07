# DocuMind Lite

Ask questions about your own documents and get answers with numbered source citations. If the answer isn't in your documents, DocuMind says so instead of guessing.

A full-stack RAG (retrieval-augmented generation) application built with Java, Spring Boot, Spring AI, PostgreSQL + pgvector and Gemini.

![Answer with sources](docs/answer.png)

## Features

- **Cited answers.** Every answer lists the files and passages it came from, with match scores.
- **Refuses instead of hallucinating.** If no passage is similar enough to the question, the app declines and logs the question.
- **Documentation gaps.** Questions your documents couldn't answer are collected, showing what is missing from your knowledge base.
- **Conflict detection.** If two documents give different values for the same fact, the answer starts with a warning and lists both values with their sources.
- **Chat memory.** Follow-up questions work ("And when do I need a doctor's note?"). They are rewritten into standalone questions before searching.
- **Streaming answers** over Server-Sent Events.
- **Multilingual.** Ask in English, Hindi or Marathi about English documents and get the answer in your language. Voice input uses the browser's speech recognition.
- **Document summaries** with five suggested questions generated on upload.
- **Compare two documents** side by side, with differences highlighted.
- **Analytics.** Question volume, answer rate, average response time, most-asked questions and feedback counts.
- **Workspaces and roles.** Private workspaces with Owner, Editor and Viewer roles. Search is always filtered by workspace, so one workspace never sees another's documents.
- **Feedback.** Thumbs up and down on every answer, with a review list for the thumbs-down ones.

## How it works

```
Upload  ->  Apache Tika (extract text)  ->  token-based chunking
        ->  Gemini embeddings  ->  PostgreSQL + pgvector

Question -> (rewrite follow-ups) -> embed -> similarity search (filtered by workspace)
         -> score below threshold?  yes: refuse and log a gap
                                    no:  send the passages to Gemini with a grounded prompt
         -> streamed answer with citations (or a conflict warning)
```

Ingestion runs asynchronously, so a document moves from `PROCESSING` to `READY` without blocking the upload.

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot, Spring Security, Spring Data JPA |
| Auth | JWT with BCrypt password hashing |
| GenAI | Spring AI, Gemini (chat and embeddings) |
| Vector store | PostgreSQL with pgvector (HNSW index, cosine distance) |
| Parsing | Apache Tika |
| Streaming | Server-Sent Events |
| Frontend | Plain HTML, CSS and JavaScript (single page) |
| Infra | Docker Compose for the database |

## Run it locally

You need Java 21, Maven, Docker Desktop and a free Gemini API key from https://aistudio.google.com/apikey.

1. Start the database:
```
   docker compose up -d
```
2. Create `secret.yml` in the project root (it is git-ignored):
```yaml
   spring:
     ai:
       google:
         genai:
           api-key: YOUR_GEMINI_KEY
           embedding:
             api-key: YOUR_GEMINI_KEY
```
3. Run the app:
```
   mvnw.cmd spring-boot:run
```
(or `./mvnw spring-boot:run` on macOS and Linux)
4. Open http://localhost:8081, sign up, create a workspace and upload a document.

The chat model is set in `src/main/resources/application.yml`. The free Gemini tier has daily limits that differ by model, so if you see rate-limit errors, switch to a model with a higher allowance.

## Testing

`eval.ps1` runs a 22-question check against a sample leave policy: 12 questions the document answers and 10 it doesn't, where the correct behavior is to refuse. Run it with the app started and the sample policy loaded:

```
powershell -ExecutionPolicy Bypass -File eval.ps1
```

## Design decisions

- **pgvector instead of a separate vector database.** One database holds users, documents and vectors, which keeps setup and transactions simple.
- **Similarity threshold.** Retrieval alone can't tell "no answer" from "weak answer", so a minimum score gates the model call. This also saves model calls on off-topic questions.
- **Two layers of refusal.** The threshold catches unrelated questions. The prompt catches questions that sound related but aren't covered, and the app logs those as gaps too.
- **Workspace filter on every query.** The filter is applied inside the vector search, not after it.

## Limitations and next steps

- The sample evaluation is small and uses short documents, so treat its result as a smoke test rather than a benchmark.
- Retrieval is vector-only. Hybrid search (keyword plus vector) would help with exact terms such as IDs and error codes.
- Chunk citations show the file and passage but not page numbers.
- Not deployed yet.