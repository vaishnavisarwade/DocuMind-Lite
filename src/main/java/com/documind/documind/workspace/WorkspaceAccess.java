package com.documind.documind.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class WorkspaceAccess {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;

    /** Any member (owner, editor or viewer). 404 if the workspace is not visible to this user. */
    public Workspace require(Long workspaceId, String email) {
        return workspaceRepository.findByIdAndOwnerEmail(workspaceId, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
    }

    public WorkspaceRole roleOf(Workspace workspace, String email) {
        if (workspace.getOwner().getEmail().equals(email)) {
            return WorkspaceRole.OWNER;
        }
        return memberRepository.findByWorkspaceIdAndUserEmail(workspace.getId(), email)
                .map(WorkspaceMember::getRole)
                .orElse(WorkspaceRole.VIEWER);
    }

    public Workspace requireEditor(Long workspaceId, String email) {
        Workspace w = require(workspaceId, email);
        if (!roleOf(w, email).canEdit()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Viewers cannot change this workspace");
        }
        return w;
    }

    public Workspace requireOwner(Long workspaceId, String email) {
        Workspace w = require(workspaceId, email);
        if (roleOf(w, email) != WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can manage members");
        }
        return w;
    }
}