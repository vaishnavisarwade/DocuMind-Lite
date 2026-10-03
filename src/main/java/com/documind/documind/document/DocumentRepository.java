package com.documind.documind.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<UploadedDocument, Long> {
    List<UploadedDocument> findByWorkspaceIdOrderByUploadedAtDesc(Long workspaceId);
    Optional<UploadedDocument> findByIdAndWorkspaceId(Long id, Long workspaceId);
}