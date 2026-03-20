package net.topikachu.rag.service.etl;

import java.util.Locale;

public final class TextSanitizer {

    private static final int LOW_QUALITY_REMOVED_THRESHOLD = 8;
    private static final double LOW_QUALITY_REMOVED_RATIO = 0.02d;
    private static final int MIN_MEANINGFUL_CODEPOINTS = 1;

    private TextSanitizer() {
    }

    public static SanitizationResult sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return new SanitizationResult("", 0, 0, 0, 0);
        }

        StringBuilder sanitized = new StringBuilder(text.length());
        int removedChars = 0;
        int normalizedWhitespace = 0;
        int meaningfulCodePoints = 0;
        int index = 0;

        while (index < text.length()) {
            char current = text.charAt(index);
            int codePoint;

            if (Character.isHighSurrogate(current)) {
                if (index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                    codePoint = Character.toCodePoint(current, text.charAt(index + 1));
                    index += 2;
                } else {
                    removedChars++;
                    index++;
                    continue;
                }
            } else if (Character.isLowSurrogate(current)) {
                removedChars++;
                index++;
                continue;
            } else {
                codePoint = current;
                index++;
            }

            if (isDisallowedCodePoint(codePoint)) {
                removedChars++;
                continue;
            }

            if (codePoint == '\r' || codePoint == '\n' || codePoint == '\f' || codePoint == 0x2028 || codePoint == 0x2029) {
                normalizedWhitespace += appendNewline(sanitized);
                continue;
            }

            if (codePoint == '\t') {
                normalizedWhitespace += appendTab(sanitized);
                continue;
            }

            if (Character.isWhitespace(codePoint) || codePoint == 0x00A0 || codePoint == 0x3000) {
                normalizedWhitespace += appendSpace(sanitized);
                continue;
            }

            sanitized.appendCodePoint(codePoint);
            if (isMeaningfulCodePoint(codePoint)) {
                meaningfulCodePoints++;
            }
        }

        while (sanitized.length() > 0) {
            char last = sanitized.charAt(sanitized.length() - 1);
            if (last == ' ' || last == '\n' || last == '\t') {
                sanitized.deleteCharAt(sanitized.length() - 1);
                normalizedWhitespace++;
                continue;
            }
            break;
        }

        return new SanitizationResult(
                sanitized.toString(),
                text.length(),
                removedChars,
                normalizedWhitespace,
                meaningfulCodePoints);
    }

    public static boolean containsIllegalCodePoints(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                    int codePoint = Character.toCodePoint(current, text.charAt(index + 1));
                    if (isDisallowedCodePoint(codePoint)) {
                        return true;
                    }
                    index++;
                    continue;
                }
                return true;
            }
            if (Character.isLowSurrogate(current)) {
                return true;
            }
            if (isDisallowedCodePoint(current)) {
                return true;
            }
        }
        return false;
    }

    public static String preview(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = text
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    private static boolean isDisallowedCodePoint(int codePoint) {
        if (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t') {
            return true;
        }
        if (codePoint >= 0xFDD0 && codePoint <= 0xFDEF) {
            return true;
        }
        return (codePoint & 0xFFFF) == 0xFFFE || (codePoint & 0xFFFF) == 0xFFFF;
    }

    private static boolean isMeaningfulCodePoint(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static int appendNewline(StringBuilder sanitized) {
        if (sanitized.length() == 0) {
            return 1;
        }
        char last = sanitized.charAt(sanitized.length() - 1);
        if (last == ' ') {
            sanitized.setCharAt(sanitized.length() - 1, '\n');
            return 1;
        }
        if (last == '\n') {
            return 1;
        }
        sanitized.append('\n');
        return 0;
    }

    private static int appendTab(StringBuilder sanitized) {
        if (sanitized.length() == 0) {
            return 1;
        }
        char last = sanitized.charAt(sanitized.length() - 1);
        if (last == '\n' || last == '\t') {
            return 1;
        }
        sanitized.append('\t');
        return 0;
    }

    private static int appendSpace(StringBuilder sanitized) {
        if (sanitized.length() == 0) {
            return 1;
        }
        char last = sanitized.charAt(sanitized.length() - 1);
        if (last == ' ' || last == '\n' || last == '\t') {
            return 1;
        }
        sanitized.append(' ');
        return 0;
    }

    public record SanitizationResult(
            String text,
            int originalLength,
            int removedChars,
            int normalizedWhitespace,
            int meaningfulCodePoints) {

        public boolean wasModified() {
            return removedChars > 0 || normalizedWhitespace > 0 || text.length() != originalLength;
        }

        public boolean isEffectivelyEmpty() {
            return text.isBlank() || meaningfulCodePoints < MIN_MEANINGFUL_CODEPOINTS;
        }

        public boolean isLowQualityExtraction() {
            if (originalLength == 0) {
                return false;
            }
            return (removedChars >= LOW_QUALITY_REMOVED_THRESHOLD && removedRatio() >= LOW_QUALITY_REMOVED_RATIO)
                    || (text.length() < 24 && meaningfulCodePoints < 8);
        }

        public double removedRatio() {
            if (originalLength == 0) {
                return 0d;
            }
            return (double) removedChars / (double) originalLength;
        }

        public String removedRatioPercent() {
            return String.format(Locale.ROOT, "%.2f%%", removedRatio() * 100d);
        }
    }
}
