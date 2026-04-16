import { apiUrl, authFetch } from "./client";

export interface Doc {
    id: string;
    docUuid: string;
    fileName: string;
    fileHash: string;
    status: string;
    createDate: string;
    updateDate: string;
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

export const getAllTags = async () => {
    const res = await authFetch(apiUrl("/api/v1/tags"));
    const json = await res.json();
    if (json.code === 0) {
        return json.data as string[];
    }
    return [];
};
