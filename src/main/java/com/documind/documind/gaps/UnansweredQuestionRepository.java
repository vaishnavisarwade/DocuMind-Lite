package com.documind.documind.gaps;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UnansweredQuestionRepository extends JpaRepository<UnansweredQuestion, Long> {
    List<UnansweredQuestion> findByWorkspaceIdOrderByAskedAtDesc(Long workspaceId);
}