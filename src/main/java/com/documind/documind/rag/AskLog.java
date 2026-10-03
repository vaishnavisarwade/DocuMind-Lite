package com.documind.documind.rag;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "ask_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AskLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;

    private String userEmail;

    @Column(length = 2000)
    private String question;

    @Column(length = 8000)
    private String answer;

    private boolean answered;

    private Double bestScore;

    private Long latencyMs;

    private Integer feedback;

    @Builder.Default
    private Instant createdAt = Instant.now();
}