package net.topikachu.rag.business.document.vo;

import lombok.Data;

import java.util.List;

@Data
public class DocumentPermissionUpdateRequest {

    private String spaceCode;
    private String ownerDeptId;
    private List<String> allowedRoles;
    private List<String> allowedDeptIds;
    private Boolean isPublic;
}
