package com.documind.documind.rag;

import com.documind.documind.document.DocumentRepository;
import com.documind.documind.document.UploadedDocument;
import com.documind.documind.workspace.WorkspaceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/compare")
@RequiredArgsConstructor
@Slf4j
public class CompareController {

    // No curly braces in this text: Spring AI would treat them as template placeholders.
    private static final String SYSTEM = """
            You compare two documents using ONLY the excerpts provided. Never use outside knowledge.
            Reply with valid JSON only: no markdown fences and no text before or after it.
            Rules:
            - "a" is what DOCUMENT A says about a topic and "b" is what DOCUMENT B says. Never swap them.
            - Write "Not mentioned" when that document does not cover the topic.
            - Only include topics that at least one document actually covers. Use at most 8 rows, each value under 20 words.
            - If the documents are about completely different subjects, return an empty rows array and explain that in the conclusion.
            - The conclusion is one or two sentences about the key differences.
            """;

    private static final String FORMAT =
            "{\"rows\":[{\"topic\":\"...\",\"a\":\"...\",\"b\":\"...\"}],\"conclusion\":\"...\"}";

    private final WorkspaceRepository workspaceRepository;
    private final DocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;

    public record CompareRequest(@NotNull Long documentA, @NotNull Long documentB, String focus) {}

    public record CompareResponse(String fileA, String fileB, String comparison) {}

    @PostMapping
    public CompareResponse compare(@PathVariable Long workspaceId,
                                   @Valid @RequestBody CompareRequest req,
                                   Authentication authentication) {
        workspaceRepository.findByIdAndOwnerEmail(workspaceId, authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));

        if (req.documentA().equals(req.documentB())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick two different documents");
        }
        UploadedDocument a = documentRepository.findByIdAndWorkspaceId(req.documentA(), workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        UploadedDocument b = documentRepository.findByIdAndWorkspaceId(req.documentB(), workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        String focus = (req.focus() == null || req.focus().isBlank())
                ? "key rules, limits, numbers and requirements"
                : req.focus().trim();

        String textA = excerpts(workspaceId, a.getId(), focus);
        String textB = excerpts(workspaceId, b.getId(), focus);
        if (textA.isBlank() || textB.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "One of the documents has no readable text yet");
        }

        String result;
        try {
            result = chatClientBuilder.build().prompt()
                    .system(SYSTEM)
                    .user(u -> u.text("Focus: {focus}\n\nJSON shape: {format}\n\n"
                                    + "Document A ({nameA}):\n{textA}\n\nDocument B ({nameB}):\n{textB}")
                            .param("focus", focus)
                            .param("format", FORMAT)
                            .param("nameA", a.getFileName())
                            .param("textA", textA)
                            .param("nameB", b.getFileName())
                            .param("textB", textB))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Compare model call failed", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The AI service is busy or unavailable. Please try again in a minute.");
        }

        return new CompareResponse(a.getFileName(), b.getFileName(), result == null ? "" : result);
    }

    private String excerpts(Long workspaceId, Long documentId, String focus) {
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(focus)
                        .topK(6)
                        .filterExpression("workspaceId == " + workspaceId + " && documentId == " + documentId)
                        .build());
        StringBuilder sb = new StringBuilder();
        for (Document d : hits) {
            if (d.getText() != null) {
                sb.append(d.getText()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }
}