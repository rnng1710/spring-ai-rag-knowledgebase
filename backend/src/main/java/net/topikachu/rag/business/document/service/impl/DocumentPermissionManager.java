package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.vo.DocumentPermissionUpdateRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

@Component
public class DocumentPermissionManager {

    QueryWrapper<Document> buildAccessibleDocumentQuery(CurrentUserContext currentUserContext, SearchScope searchScope) {
        QueryWrapper<Document> query = new QueryWrapper<>();
        applyAccessControl(query, currentUserContext);
        applySpaceScope(query, searchScope);
        applyTagScope(query, searchScope == null ? List.of() : searchScope.requestedTags());
        return query;
    }

    boolean canAccessDocument(CurrentUserContext currentUserContext, Document doc) {
        if (doc == null) {
            return false;
        }
        if (currentUserContext == null || currentUserContext.isAdmin()) {
            return true;
        }
        if (Boolean.TRUE.equals(doc.getIsPublic())) {
            return true;
        }
        if (StringUtils.hasText(currentUserContext.role())
                && doc.getAllowedRoles() != null
                && doc.getAllowedRoles().contains(currentUserContext.role())) {
            return true;
        }
        if (StringUtils.hasText(currentUserContext.deptId())) {
            if (currentUserContext.deptId().equals(doc.getOwnerDeptId())) {
                return true;
            }
            return doc.getAllowedDeptIds() != null
                    && doc.getAllowedDeptIds().contains(currentUserContext.deptId());
        }
        return false;
    }

    void applyPermissionUpdate(Document doc, DocumentPermissionUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Permission payload is required");
        }
        doc.setSpaceCode(StringUtils.hasText(request.getSpaceCode()) ? request.getSpaceCode().trim() : "public");
        doc.setOwnerDeptId(StringUtils.hasText(request.getOwnerDeptId()) ? request.getOwnerDeptId().trim() : null);
        doc.setAllowedRoles(normalizePermissionList(request.getAllowedRoles()));
        doc.setAllowedDeptIds(normalizePermissionList(request.getAllowedDeptIds()));
        doc.setIsPublic(Boolean.TRUE.equals(request.getIsPublic()));
    }

    private void applyAccessControl(QueryWrapper<Document> query, CurrentUserContext currentUserContext) {
        if (currentUserContext == null || currentUserContext.isAdmin()) {
            return;
        }
        query.and(wrapper -> {
            wrapper.eq("is_public", 1);
            if (StringUtils.hasText(currentUserContext.role())) {
                wrapper.or().apply("JSON_CONTAINS(allowed_roles, JSON_QUOTE({0}))", currentUserContext.role());
            }
            if (StringUtils.hasText(currentUserContext.deptId())) {
                wrapper.or().apply("JSON_CONTAINS(allowed_dept_ids, JSON_QUOTE({0}))", currentUserContext.deptId());
                wrapper.or().eq("owner_dept_id", currentUserContext.deptId());
            }
        });
    }

    private void applySpaceScope(QueryWrapper<Document> query, SearchScope searchScope) {
        if (searchScope == null || searchScope.requestedSpaceCodes().isEmpty()) {
            return;
        }
        query.and(wrapper -> {
            boolean first = true;
            for (String spaceCode : searchScope.requestedSpaceCodes()) {
                if (first) {
                    wrapper.eq("space_code", spaceCode);
                    first = false;
                } else {
                    wrapper.or().eq("space_code", spaceCode);
                }
            }
        });
    }

    private void applyTagScope(QueryWrapper<Document> query, List<String> requestedTags) {
        List<String> normalizedTags = normalizeStringList(requestedTags);
        if (normalizedTags.isEmpty()) {
            return;
        }
        query.and(wrapper -> {
            boolean first = true;
            for (String tag : normalizedTags) {
                if (first) {
                    wrapper.apply("JSON_CONTAINS(tags, JSON_QUOTE({0}))", tag);
                    first = false;
                } else {
                    wrapper.or().apply("JSON_CONTAINS(tags, JSON_QUOTE({0}))", tag);
                }
            }
        });
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> normalizePermissionList(List<String> values) {
        List<String> normalized = normalizeStringList(values);
        return normalized.isEmpty() ? null : List.copyOf(new LinkedHashSet<>(normalized));
    }
}
