package com.documind.documind.history;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "query_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QueryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;

    private String userEmail;

    @Column(length = 2000)
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    private boolean answered;

    private Double bestScore;

    private Long latencyMs;

    // 1 = thumbs up, -1 = thumbs down, null = no feedback yet
    private Integer feedback;

    @Builder.Default
    private Instant createdAt = Instant.now();
}