package com.documind.documind.workspace;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "workspace_members")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;

    private String userEmail;

    @Enumerated(EnumType.STRING)
    private WorkspaceRole role;

    @Builder.Default
    private Instant addedAt = Instant.now();
}