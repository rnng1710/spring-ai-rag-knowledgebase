package net.topikachu.rag.business.document.access;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class KnowledgeAccessPolicyTest {

    private final KnowledgeAccessPolicy policy = new KnowledgeAccessPolicy();

    @Test
    void normalUserSqlQueryContainsAccessSpaceAndTagRules() {
        QueryWrapper<Document> query = policy.buildSqlDocumentQuery(
                user(false),
                new SearchScope(List.of(" space-a ", "space-b", ""), List.of("tag-a", " tag-b ", "")));

        String sql = query.getSqlSegment();

        assert sql.contains("is_public");
        assert sql.contains("JSON_CONTAINS(allowed_roles");
        assert sql.contains("JSON_CONTAINS(allowed_dept_ids");
        assert sql.contains("owner_dept_id");
        assert sql.contains("space_code");
        assert sql.contains("JSON_CONTAINS(tags");
    }

    @Test
    void normalUserMilvusExprContainsEquivalentAccessRules() {
        String expr = policy.buildMilvusFilterExpr(
                user(false),
                new SearchScope(List.of("space-a", "space-b"), List.of("tag-a", "tag-b")),
                KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);

        assert expr.contains("metadata[\"chunk_schema_version\"] == 2");
        assert expr.contains("metadata[\"is_public\"] == true");
        assert expr.contains("JSON_CONTAINS(metadata[\"allowed_roles\"], \"USER\")");
        assert expr.contains("JSON_CONTAINS(metadata[\"allowed_dept_ids\"], \"dept-1\")");
        assert expr.contains("metadata[\"owner_dept_id\"] == \"dept-1\"");
        assert expr.contains("metadata[\"space_code\"] == \"space-a\" OR metadata[\"space_code\"] == \"space-b\"");
        assert expr.contains("JSON_CONTAINS(metadata[\"tags\"], \"tag-a\") OR JSON_CONTAINS(metadata[\"tags\"], \"tag-b\")");
    }

    @Test
    void adminMilvusExprKeepsOnlySchemaAndScopeFilters() {
        String expr = policy.buildMilvusFilterExpr(
                user(true),
                new SearchScope(List.of("space-a"), List.of()),
                KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);

        assert expr.contains("metadata[\"chunk_schema_version\"] == 2");
        assert expr.contains("metadata[\"space_code\"] == \"space-a\"");
        assert !expr.contains("allowed_roles");
        assert !expr.contains("allowed_dept_ids");
        assert !expr.contains("is_public");
    }

    @Test
    void metadataUsesSharedAccessFieldShape() {
        Document doc = new Document();
        doc.setSpaceCode(" space-a ");
        doc.setOwnerDeptId(" dept-1 ");
        doc.setAllowedRoles(List.of(" USER ", "", "USER"));
        doc.setAllowedDeptIds(List.of(" dept-1 ", "", "dept-1"));
        doc.setIsPublic(false);

        Map<String, Object> metadata = policy.buildChunkAccessMetadata(doc, List.of(" tag-a ", "", "tag-a"), null);

        assert "space-a".equals(metadata.get("space_code"));
        assert "dept-1".equals(metadata.get("owner_dept_id"));
        assert List.of("USER").equals(metadata.get("allowed_roles"));
        assert List.of("dept-1").equals(metadata.get("allowed_dept_ids"));
        assert Boolean.FALSE.equals(metadata.get("is_public"));
        assert Integer.valueOf(1).equals(metadata.get("acl_version"));
        assert List.of("tag-a").equals(metadata.get("tags"));
    }

    @Test
    void metadataUsesEmptyPermissionListsForNullDocumentLists() {
        Document doc = new Document();

        Map<String, Object> metadata = policy.buildChunkAccessMetadata(doc, null, 3);

        assert List.of().equals(metadata.get("allowed_roles"));
        assert List.of().equals(metadata.get("allowed_dept_ids"));
        assert Integer.valueOf(3).equals(metadata.get("acl_version"));
    }

    private CurrentUserContext user(boolean admin) {
        return new CurrentUserContext("u1", "alice", "USER", "dept-1", "Engineering", "space-a", admin);
    }
}
