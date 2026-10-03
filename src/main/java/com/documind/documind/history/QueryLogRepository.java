package com.documind.documind.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QueryLogRepository extends JpaRepository<QueryLog, Long> {
    Optional<QueryLog> findByIdAndWorkspaceId(Long id, Long workspaceId);
    List<QueryLog> findByWorkspaceIdAndFeedbackOrderByCreatedAtDesc(Long workspaceId, Integer feedback);
    List<QueryLog> findByWorkspaceId(Long workspaceId);
}