package com.documind.documind.gaps;

import com.documind.documind.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/gaps")
@RequiredArgsConstructor
public class GapController {

    private final WorkspaceRepository workspaceRepository;
    private final UnansweredQuestionRepository repository;

    public record GapResponse(Long id, String question, Double bestScore, Instant askedAt) {}

    @GetMapping
    public List<GapResponse> list(@PathVariable Long workspaceId, Authentication authentication) {
        workspaceRepository.findByIdAndOwnerEmail(workspaceId, authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
        return repository.findByWorkspaceIdOrderByAskedAtDesc(workspaceId).stream()
                .map(g -> new GapResponse(g.getId(), g.getQuestion(), g.getBestScore(), g.getAskedAt()))
                .toList();
    }
}