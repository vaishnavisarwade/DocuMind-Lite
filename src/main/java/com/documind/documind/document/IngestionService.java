package com.documind.documind.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private static final String SUMMARY_PROMPT = """
            You summarize documents. Reply in exactly this format and nothing else:
            SUMMARY: <two or three sentences describing what the document covers>
            QUESTIONS:
            - <question 1>
            - <question 2>
            - <question 3>
            - <question 4>
            - <question 5>
            Rules: every question must be answerable from the document text, short, and something a real
            employee might ask. Write in English.
            """;

    private final DocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;

    @Async
    public void ingest(Long documentId, Long workspaceId, String fileName, byte[] bytes) {
        UploadedDocument doc = documentRepository.findById(documentId).orElseThrow();
        try {
            Resource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };

            List<Document> raw = new TikaDocumentReader(resource).get();
            List<Document> chunks = TokenTextSplitter.builder().build().apply(raw);

            List<Document> toStore = new ArrayList<>();
            int index = 0;
            for (Document chunk : chunks) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("workspaceId", workspaceId);
                meta.put("documentId", documentId);
                meta.put("fileName", fileName);
                meta.put("chunkIndex", index++);
                toStore.add(Document.builder().text(chunk.getText()).metadata(meta).build());
            }

            vectorStore.add(toStore);

            doc.setStatus(UploadedDocument.Status.READY);
            doc.setChunkCount(toStore.size());
            log.info("Ingested {} chunks from {}", toStore.size(), fileName);

            String fullText = raw.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
            generateSummary(doc, fullText);
        } catch (Exception e) {
            log.error("Ingestion failed for {}", fileName, e);
            doc.setStatus(UploadedDocument.Status.FAILED);
            String msg = String.valueOf(e.getMessage());
            doc.setErrorMessage(msg.length() > 900 ? msg.substring(0, 900) : msg);
        }
        documentRepository.save(doc);
    }

    /** A failure here never fails the upload: the document stays searchable without a summary. */
    private void generateSummary(UploadedDocument doc, String text) {
        try {
            if (text.isBlank()) {
                return;
            }
            String sample = text.length() > 8000 ? text.substring(0, 8000) : text;
            String out = chatClientBuilder.build().prompt()
                    .system(SUMMARY_PROMPT)
                    .user(u -> u.text("{doc}").param("doc", sample))
                    .call()
                    .content();
            if (out == null) {
                return;
            }

            StringBuilder summary = new StringBuilder();
            List<String> questions = new ArrayList<>();
            boolean inQuestions = false;
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.regionMatches(true, 0, "SUMMARY:", 0, 8)) {
                    summary.append(t.substring(8).trim());
                    inQuestions = false;
                } else if (t.regionMatches(true, 0, "QUESTIONS:", 0, 10)) {
                    inQuestions = true;
                } else if (inQuestions) {
                    String q = t.replaceFirst("^(?:[-*\u2022]|\\d+[.)])\\s*", "").trim();
                    if (!q.isEmpty() && questions.size() < 5) {
                        questions.add(q);
                    }
                } else {
                    summary.append(" ").append(t);
                }
            }
            doc.setSummary(summary.toString().trim());
            doc.setSuggestedQuestions(String.join("\n", questions));
        } catch (Exception e) {
            log.warn("Summary generation failed for {}", doc.getFileName(), e);
        }
    }
}