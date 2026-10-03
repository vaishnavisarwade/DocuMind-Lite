package com.documind.documind.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    // Despite the name, this returns the workspace for the owner AND for invited members.
    @Query("""
            select w from Workspace w join fetch w.owner o
            where w.id = :id and (o.email = :email or exists
                (select m.id from WorkspaceMember m where m.workspaceId = w.id and m.userEmail = :email))
            """)
    Optional<Workspace> findByIdAndOwnerEmail(@Param("id") Long id, @Param("email") String email);

    @Query("""
            select w from Workspace w join fetch w.owner o
            where o.email = :email or exists
                (select m.id from WorkspaceMember m where m.workspaceId = w.id and m.userEmail = :email)
            order by w.createdAt desc
            """)
    List<Workspace> findAccessible(@Param("email") String email);
}