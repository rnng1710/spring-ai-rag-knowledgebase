package net.topikachu.rag.business.document.entity;

public enum DocumentStatus {
    UPLOADED,
    READING,
    SPLITTING,
    VECTORIZING,
    COMPLETED,
    FAILED
}