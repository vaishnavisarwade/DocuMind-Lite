package com.documind.documind.gaps;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "unanswered_questions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UnansweredQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;

    @Column(length = 2000)
    private String question;

    private Double bestScore;

    private String askedBy;

    @Builder.Default
    private Instant askedAt = Instant.now();
}