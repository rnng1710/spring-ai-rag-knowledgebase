package net.topikachu.rag.business.document.access;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.entity.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeAccessPolicy {

    public static final String SPACE_CODE = "space_code";
    public static final String TAGS = "tags";
    public static final String IS_PUBLIC = "is_public";
    public static final String ALLOWED_ROLES = "allowed_roles";
    public static final String ALLOWED_DEPT_IDS = "allowed_dept_ids";
    public static final String OWNER_DEPT_ID = "owner_dept_id";
    public static final String ACL_VERSION = "acl_version";
    public static final String CHUNK_SCHEMA_VERSION = "chunk_schema_version";

    public QueryWrapper<Document> buildSqlDocumentQuery(CurrentUserContext currentUserContext, SearchScope searchScope) {
        QueryWrapper<Document> query = new QueryWrapper<>();
        applySqlAccessControl(query, currentUserContext);
        applySqlSpaceScope(query, searchScope);
        applySqlTagScope(query, searchScope == null ? List.of() : searchScope.requestedTags());
        return query;
    }

    public boolean canAccessDocument(CurrentUserContext currentUserContext, Document doc) {
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

    public String buildMilvusFilterExpr(CurrentUserContext currentUserContext, SearchScope searchScope,
                                        int chunkSchemaVersion) {
        List<String> clauses = new java.util.ArrayList<>();
        clauses.add("metadata[\"" + CHUNK_SCHEMA_VERSION + "\"] == " + chunkSchemaVersion);
        String accessClause = buildMilvusAccessClause(currentUserContext);
        if (StringUtils.hasText(accessClause)) {
            clauses.add("(" + accessClause + ")");
        }
        String spaceClause = buildMilvusSpaceClause(searchScope);
        if (StringUtils.hasText(spaceClause)) {
            clauses.add("(" + spaceClause + ")");
        }
        String tagClause = buildMilvusTagClause(searchScope == null ? List.of() : searchScope.requestedTags());
        if (StringUtils.hasText(tagClause)) {
            clauses.add("(" + tagClause + ")");
        }
        return String.join(" AND ", clauses);
    }

    public Map<String, Object> buildChunkAccessMetadata(Document storedDocument,
                                                        List<String> effectiveTags,
                                                        Integer aclVersion) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SPACE_CODE, resolveSpaceCode(storedDocument));
        if (storedDocument != null && StringUtils.hasText(storedDocument.getOwnerDeptId())) {
            metadata.put(OWNER_DEPT_ID, storedDocument.getOwnerDeptId().trim());
        }
        metadata.put(ALLOWED_ROLES, storedDocument == null || storedDocument.getAllowedRoles() == null
                ? List.of()
                : normalizeStringList(storedDocument.getAllowedRoles()));
        metadata.put(ALLOWED_DEPT_IDS, storedDocument == null || storedDocument.getAllowedDeptIds() == null
                ? List.of()
                : normalizeStringList(storedDocument.getAllowedDeptIds()));
        metadata.put(IS_PUBLIC, storedDocument == null || Boolean.TRUE.equals(storedDocument.getIsPublic()));
        metadata.put(ACL_VERSION, aclVersion == null ? 1 : aclVersion);

        List<String> normalizedTags = normalizeStringList(effectiveTags);
        if (!normalizedTags.isEmpty()) {
            metadata.put(TAGS, normalizedTags);
        }
        return metadata;
    }

    public List<String> normalizePermissionList(List<String> values) {
        List<String> normalized = normalizeStringList(values);
        return normalized.isEmpty() ? null : List.copyOf(new LinkedHashSet<>(normalized));
    }

    public List<String> normalizeStringList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void applySqlAccessControl(QueryWrapper<Document> query, CurrentUserContext currentUserContext) {
        if (currentUserContext == null || currentUserContext.isAdmin()) {
            return;
        }
        query.and(wrapper -> {
            wrapper.eq(IS_PUBLIC, 1);
            if (StringUtils.hasText(currentUserContext.role())) {
                wrapper.or().apply("JSON_CONTAINS(" + ALLOWED_ROLES + ", JSON_QUOTE({0}))", currentUserContext.role());
            }
            if (StringUtils.hasText(currentUserContext.deptId())) {
                wrapper.or().apply("JSON_CONTAINS(" + ALLOWED_DEPT_IDS + ", JSON_QUOTE({0}))", currentUserContext.deptId());
                wrapper.or().eq(OWNER_DEPT_ID, currentUserContext.deptId());
            }
        });
    }

    private void applySqlSpaceScope(QueryWrapper<Document> query, SearchScope searchScope) {
        List<String> spaceCodes = normalizeStringList(searchScope == null ? List.of() : searchScope.requestedSpaceCodes());
        if (spaceCodes.isEmpty()) {
            return;
        }
        query.and(wrapper -> {
            boolean first = true;
            for (String spaceCode : spaceCodes) {
                if (first) {
                    wrapper.eq(SPACE_CODE, spaceCode);
                    first = false;
                } else {
                    wrapper.or().eq(SPACE_CODE, spaceCode);
                }
            }
        });
    }

    private void applySqlTagScope(QueryWrapper<Document> query, List<String> requestedTags) {
        List<String> tags = normalizeStringList(requestedTags);
        if (tags.isEmpty()) {
            return;
        }
        query.and(wrapper -> {
            boolean first = true;
            for (String tag : tags) {
                if (first) {
                    wrapper.apply("JSON_CONTAINS(" + TAGS + ", JSON_QUOTE({0}))", tag);
                    first = false;
                } else {
                    wrapper.or().apply("JSON_CONTAINS(" + TAGS + ", JSON_QUOTE({0}))", tag);
                }
            }
        });
    }

    private String buildMilvusAccessClause(CurrentUserContext currentUserContext) {
        if (currentUserContext == null || currentUserContext.isAdmin()) {
            return null;
        }

        List<String> allowClauses = new java.util.ArrayList<>();
        allowClauses.add(metadataField(IS_PUBLIC) + " == true");

        if (StringUtils.hasText(currentUserContext.role())) {
            allowClauses.add("JSON_CONTAINS(" + metadataField(ALLOWED_ROLES) + ", "
                    + toJsonStringLiteral(currentUserContext.role()) + ")");
        }
        if (StringUtils.hasText(currentUserContext.deptId())) {
            String deptLiteral = quoteStringLiteral(currentUserContext.deptId());
            allowClauses.add("JSON_CONTAINS(" + metadataField(ALLOWED_DEPT_IDS) + ", "
                    + toJsonStringLiteral(currentUserContext.deptId()) + ")");
            allowClauses.add(metadataField(OWNER_DEPT_ID) + " == " + deptLiteral);
        }
        return String.join(" OR ", allowClauses);
    }

    private String buildMilvusSpaceClause(SearchScope searchScope) {
        List<String> spaceCodes = normalizeStringList(searchScope == null ? List.of() : searchScope.requestedSpaceCodes());
        if (spaceCodes.isEmpty()) {
            return null;
        }
        return spaceCodes.stream()
                .map(spaceCode -> metadataField(SPACE_CODE) + " == " + quoteStringLiteral(spaceCode))
                .reduce((left, right) -> left + " OR " + right)
                .orElse(null);
    }

    private String buildMilvusTagClause(List<String> filterTags) {
        List<String> tags = normalizeStringList(filterTags);
        if (tags.isEmpty()) {
            return null;
        }
        return tags.stream()
                .map(tag -> "JSON_CONTAINS(" + metadataField(TAGS) + ", " + toJsonStringLiteral(tag) + ")")
                .reduce((left, right) -> left + " OR " + right)
                .orElse(null);
    }

    private String metadataField(String fieldName) {
        return "metadata[\"" + fieldName + "\"]";
    }

    private String resolveSpaceCode(Document storedDocument) {
        if (storedDocument == null || !StringUtils.hasText(storedDocument.getSpaceCode())) {
            return "public";
        }
        return storedDocument.getSpaceCode().trim();
    }

    private String toJsonStringLiteral(String value) {
        return "\"" + escapeFilterLiteral(value) + "\"";
    }

    private String quoteStringLiteral(String value) {
        return "\"" + escapeFilterLiteral(value) + "\"";
    }

    private String escapeFilterLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
