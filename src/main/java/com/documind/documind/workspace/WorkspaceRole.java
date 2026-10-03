package com.documind.documind.workspace;

public enum WorkspaceRole {
    OWNER, EDITOR, VIEWER;

    public boolean canEdit() {
        return this != VIEWER;
    }
}