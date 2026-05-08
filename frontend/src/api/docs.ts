import { apiUrl, authFetch } from "./client";

export interface Doc {
    id: string;
    docUuid: string;
    fileName: string;
    fileHash: string;
    status: string;
    tags?: string[];
    spaceCode?: string;
    ownerDeptId?: string | null;
    allowedRoles?: string[];
    allowedDeptIds?: string[];
    isPublic?: boolean;
    createDate: string;
    updateDate: string;
}

export interface DocPermissionPayload {
    spaceCode: string;
    ownerDeptId?: string | null;
    allowedRoles?: string[];
    allowedDeptIds?: string[];
    isPublic: boolean;
}

export interface PageResult<T> {
    records: T[];
    total: number;
    current: number;
    size: number;
}

export const listDocs = async (page: number, size: number, keyword?: string) => {
    const params = new URLSearchParams({
        page: page.toString(),
        size: size.toString()
    });
    if (keyword) {
        params.append("keyword", keyword);
    }

    const res = await authFetch(apiUrl(`/api/v1/docs?${params.toString()}`));
    const json = await res.json();
    if (json.code === 0) {
        return json.data as PageResult<Doc>;
    }
    throw new Error(json.msg || "Fetch docs failed");
};

export const deleteDoc = async (id: string) => {
    const res = await authFetch(apiUrl(`/api/v1/docs/${id}`), {
        method: "DELETE"
    });
    const json = await res.json();
    if (json.code !== 0) throw new Error(json.msg || "Delete failed");
};

export const deleteDocsBatch = async (ids: string[]) => {
    const res = await authFetch(apiUrl(`/api/v1/docs`), {
        method: "DELETE",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify(ids)
    });
    const json = await res.json();
    if (json.code !== 0) throw new Error(json.msg || "Batch delete failed");
};

const filenameFromContentDisposition = (contentDisposition: string | null, fallback: string) => {
    if (!contentDisposition) return fallback;
    const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
    if (utf8Match?.[1]) {
        return decodeURIComponent(utf8Match[1]);
    }
    const plainMatch = contentDisposition.match(/filename="?([^";]+)"?/i);
    return plainMatch?.[1] ? plainMatch[1] : fallback;
};

export const downloadDoc = async (doc: Doc) => {
    const res = await authFetch(apiUrl(`/api/v1/docs/${doc.id}/download`));
    if (!res.ok) {
        throw new Error(`Download failed: ${res.status}`);
    }
    const blob = await res.blob();
    const fileName = filenameFromContentDisposition(res.headers.get("Content-Disposition"), doc.fileName);
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
};

export const uploadSingle = async (file: File, fileName?: string, overwrite = false, tags: string[] = []) => {
    const formData = new FormData();
    formData.append("file", file);
    if (fileName) formData.append("fileName", fileName);
    formData.append("overwrite", overwrite.toString());
    if (tags && tags.length > 0) {
        tags.forEach(tag => formData.append("tags", tag));
    }

    const res = await authFetch(apiUrl("/api/v1/docs/upload"), {
        method: "POST",
        body: formData
    });
    return await res.json();
};

export const uploadBatch = async (files: File[], overwrite = false, tags: string[] = []) => {
    const formData = new FormData();
    files.forEach(f => formData.append("files", f));
    formData.append("overwrite", overwrite ? "true" : "false");
    if (tags && tags.length > 0) {
        tags.forEach(tag => formData.append("tags", tag));
    }

    const res = await authFetch(apiUrl("/api/v1/upload/batch"), {
        method: "POST",
        body: formData
    });
    return await res.json();
};

export const getAllTags = async (spaceCodes: string[] = []) => {
    const params = new URLSearchParams();
    spaceCodes.forEach(spaceCode => params.append("spaceCodes", spaceCode));
    const query = params.toString();
    const res = await authFetch(apiUrl(`/api/v1/tags${query ? `?${query}` : ""}`));
    const json = await res.json();
    if (json.code === 0) {
        return json.data as string[];
    }
    return [];
};

export const listAccessibleSpaces = async () => {
    const res = await authFetch(apiUrl("/api/v1/spaces"));
    const json = await res.json();
    if (json.code === 0) {
        return json.data as string[];
    }
    return [];
};

export const updateDocPermissions = async (id: string, payload: DocPermissionPayload) => {
    const res = await authFetch(apiUrl(`/api/v1/docs/${id}/permissions`), {
        method: "PATCH",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify(payload)
    });
    const json = await res.json();
    if (json.code !== 0) {
        throw new Error(json.msg || "Update document permissions failed");
    }
};

export const backfillAclMetadata = async () => {
    const res = await authFetch(apiUrl("/api/v1/docs/backfill-acl-metadata"), {
        method: "POST"
    });
    const json = await res.json();
    if (json.code === 0) {
        return json.data as number;
    }
    throw new Error(json.msg || "ACL metadata backfill failed");
};
