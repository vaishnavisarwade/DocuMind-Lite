package com.documind.documind.history;

import com.documind.documind.workspace.WorkspaceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final WorkspaceRepository workspaceRepository;
    private final QueryLogRepository queryLogRepository;

    public record FeedbackRequest(@NotNull Long queryId, @NotNull Integer value) {}

    public record BadAnswer(Long id, String question, String answer, Instant createdAt) {}

    @PostMapping
    public Map<String, String> submit(@PathVariable Long workspaceId,
                                      @Valid @RequestBody FeedbackRequest req,
                                      Authentication authentication) {
        checkOwner(workspaceId, authentication);
        if (req.value() != 1 && req.value() != -1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must be 1 or -1");
        }
        QueryLog entry = queryLogRepository.findByIdAndWorkspaceId(req.queryId(), workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Answer not found"));
        entry.setFeedback(req.value());
        queryLogRepository.save(entry);
        return Map.of("status", "saved");
    }

    @GetMapping("/negative")
    public List<BadAnswer> negative(@PathVariable Long workspaceId, Authentication authentication) {
        checkOwner(workspaceId, authentication);
        return queryLogRepository.findByWorkspaceIdAndFeedbackOrderByCreatedAtDesc(workspaceId, -1)
                .stream()
                .map(q -> new BadAnswer(q.getId(), q.getQuestion(), q.getAnswer(), q.getCreatedAt()))
                .toList();
    }

    private void checkOwner(Long workspaceId, Authentication authentication) {
        workspaceRepository.findByIdAndOwnerEmail(workspaceId, authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
    }
}