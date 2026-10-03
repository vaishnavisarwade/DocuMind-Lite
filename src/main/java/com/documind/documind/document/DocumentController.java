package com.documind.documind.document;

import com.documind.documind.workspace.Workspace;
import com.documind.documind.workspace.WorkspaceAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
@RequiredArgsConstructor
public class DocumentController {

    private final WorkspaceAccess access;
    private final DocumentRepository documentRepository;
    private final IngestionService ingestionService;
    private final VectorStore vectorStore;

    public record DocumentResponse(Long id, String fileName, String status, Integer chunkCount,
                                   String errorMessage, Instant uploadedAt,
                                   String summary, List<String> suggestedQuestions) {}

    public record SearchHit(String fileName, Object chunkIndex, Double score, String text) {}

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentResponse upload(@PathVariable Long workspaceId,
                                   @RequestParam("file") MultipartFile file,
                                   Authentication authentication) throws IOException {
        Workspace workspace = access.requireEditor(workspaceId, authentication.getName());
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        String fileName = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();

        UploadedDocument saved = documentRepository.save(
                UploadedDocument.builder().fileName(fileName).workspace(workspace).build());

        ingestionService.ingest(saved.getId(), workspaceId, fileName, file.getBytes());
        return toResponse(saved);
    }

    @GetMapping("/documents")
    public List<DocumentResponse> list(@PathVariable Long workspaceId, Authentication authentication) {
        access.require(workspaceId, authentication.getName());
        return documentRepository.findByWorkspaceIdOrderByUploadedAtDesc(workspaceId)
                .stream().map(this::toResponse).toList();
    }

    @GetMapping("/search")
    public List<SearchHit> search(@PathVariable Long workspaceId,
                                  @RequestParam("q") String query,
                                  Authentication authentication) {
        access.require(workspaceId, authentication.getName());
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(5)
                        .filterExpression("workspaceId == " + workspaceId)
                        .build());
        return results.stream().map(d -> {
            String text = d.getText() == null ? "" : d.getText();
            if (text.length() > 300) text = text.substring(0, 300) + "...";
            return new SearchHit(
                    String.valueOf(d.getMetadata().get("fileName")),
                    d.getMetadata().get("chunkIndex"),
                    d.getScore(),
                    text);
        }).toList();
    }

    private DocumentResponse toResponse(UploadedDocument d) {
        List<String> questions = (d.getSuggestedQuestions() == null || d.getSuggestedQuestions().isBlank())
                ? List.of()
                : Arrays.stream(d.getSuggestedQuestions().split("\\R"))
                  .map(String::trim)
                  .filter(s -> !s.isEmpty())
                  .toList();
        return new DocumentResponse(d.getId(), d.getFileName(), d.getStatus().name(),
                d.getChunkCount(), d.getErrorMessage(), d.getUploadedAt(),
                d.getSummary(), questions);
    }
}