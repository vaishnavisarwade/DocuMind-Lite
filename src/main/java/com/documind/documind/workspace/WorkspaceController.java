package com.documind.documind.workspace;

import com.documind.documind.auth.User;
import com.documind.documind.auth.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final WorkspaceAccess access;

    public record CreateWorkspaceRequest(@NotBlank String name) {}

    public record WorkspaceResponse(Long id, String name, Instant createdAt, String role) {}

    @PostMapping
    public WorkspaceResponse create(@Valid @RequestBody CreateWorkspaceRequest req,
                                    Authentication authentication) {
        User owner = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        Workspace saved = workspaceRepository.save(
                Workspace.builder().name(req.name()).owner(owner).build());
        return new WorkspaceResponse(saved.getId(), saved.getName(), saved.getCreatedAt(), "OWNER");
    }

    @GetMapping
    public List<WorkspaceResponse> list(Authentication authentication) {
        String email = authentication.getName();
        return workspaceRepository.findAccessible(email).stream()
                .map(w -> new WorkspaceResponse(w.getId(), w.getName(), w.getCreatedAt(),
                        access.roleOf(w, email).name()))
                .toList();
    }
}