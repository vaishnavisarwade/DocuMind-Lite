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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/members")
@RequiredArgsConstructor
public class MemberController {

    private final WorkspaceAccess access;
    private final WorkspaceMemberRepository memberRepository;
    private final UserRepository userRepository;

    public record AddMemberRequest(@NotBlank String email, @NotBlank String role) {}

    public record MemberResponse(String email, String name, String role) {}

    @GetMapping
    public List<MemberResponse> list(@PathVariable Long workspaceId, Authentication authentication) {
        Workspace w = access.require(workspaceId, authentication.getName());
        List<MemberResponse> out = new ArrayList<>();
        out.add(new MemberResponse(w.getOwner().getEmail(), w.getOwner().getName(), "OWNER"));
        for (WorkspaceMember m : memberRepository.findByWorkspaceId(workspaceId)) {
            String name = userRepository.findByEmail(m.getUserEmail())
                    .map(User::getName).orElse(m.getUserEmail());
            out.add(new MemberResponse(m.getUserEmail(), name, m.getRole().name()));
        }
        return out;
    }

    @PostMapping
    public MemberResponse add(@PathVariable Long workspaceId,
                              @Valid @RequestBody AddMemberRequest req,
                              Authentication authentication) {
        Workspace w = access.requireOwner(workspaceId, authentication.getName());

        WorkspaceRole role;
        try {
            role = WorkspaceRole.valueOf(req.role().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be EDITOR or VIEWER");
        }
        if (role == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be EDITOR or VIEWER");
        }

        User user = userRepository.findByEmail(req.email().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No account with that email. Ask them to sign up first."));
        if (user.getEmail().equals(w.getOwner().getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The owner already has full access");
        }

        WorkspaceMember member = memberRepository
                .findByWorkspaceIdAndUserEmail(workspaceId, user.getEmail())
                .orElseGet(() -> WorkspaceMember.builder()
                        .workspaceId(workspaceId)
                        .userEmail(user.getEmail())
                        .build());
        member.setRole(role);
        memberRepository.save(member);
        return new MemberResponse(user.getEmail(), user.getName(), role.name());
    }

    @DeleteMapping
    public Map<String, String> remove(@PathVariable Long workspaceId,
                                      @RequestParam("email") String email,
                                      Authentication authentication) {
        access.requireOwner(workspaceId, authentication.getName());
        WorkspaceMember member = memberRepository.findByWorkspaceIdAndUserEmail(workspaceId, email.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        memberRepository.delete(member);
        return Map.of("status", "removed");
    }
}