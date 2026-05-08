package net.topikachu.rag.auth;

import org.springframework.util.StringUtils;

import java.util.List;

public record SearchScope(
        List<String> requestedSpaceCodes,
        List<String> requestedTags
) {

    public SearchScope {
        requestedSpaceCodes = normalize(requestedSpaceCodes);
        requestedTags = normalize(requestedTags);
    }

    public static SearchScope empty() {
        return new SearchScope(List.of(), List.of());
    }

    public SearchScope withRequestedTags(List<String> tags) {
        return new SearchScope(requestedSpaceCodes, tags);
    }

    public SearchScope mergeRequestedTags(List<String> extraTags) {
        if (extraTags == null || extraTags.isEmpty()) {
            return this;
        }
        return new SearchScope(
                requestedSpaceCodes,
                java.util.stream.Stream.concat(requestedTags.stream(), normalize(extraTags).stream())
                        .distinct()
                        .toList());
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }
}
