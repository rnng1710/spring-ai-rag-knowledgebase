package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.access.KnowledgeAccessPolicy;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.vo.DocumentPermissionUpdateRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DocumentPermissionManager {

    private final KnowledgeAccessPolicy accessPolicy;

    public DocumentPermissionManager(KnowledgeAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    QueryWrapper<Document> buildAccessibleDocumentQuery(CurrentUserContext currentUserContext, SearchScope searchScope) {
        return accessPolicy.buildSqlDocumentQuery(currentUserContext, searchScope);
    }

    boolean canAccessDocument(CurrentUserContext currentUserContext, Document doc) {
        return accessPolicy.canAccessDocument(currentUserContext, doc);
    }

    void applyPermissionUpdate(Document doc, DocumentPermissionUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Permission payload is required");
        }
        doc.setSpaceCode(StringUtils.hasText(request.getSpaceCode()) ? request.getSpaceCode().trim() : "public");
        doc.setOwnerDeptId(StringUtils.hasText(request.getOwnerDeptId()) ? request.getOwnerDeptId().trim() : null);
        doc.setAllowedRoles(accessPolicy.normalizePermissionList(request.getAllowedRoles()));
        doc.setAllowedDeptIds(accessPolicy.normalizePermissionList(request.getAllowedDeptIds()));
        doc.setIsPublic(Boolean.TRUE.equals(request.getIsPublic()));
    }
}
