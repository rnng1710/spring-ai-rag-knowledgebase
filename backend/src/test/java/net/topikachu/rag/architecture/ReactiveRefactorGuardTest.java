package net.topikachu.rag.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveRefactorGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    void mainCodeDoesNotUseExplicitBlockCalls() throws IOException {
        List<String> violations = findViolations(List.of(".block(", ".blockFirst(", ".blockLast("));
        assertTrue(violations.isEmpty(), () -> "Found blocking Reactor calls:\n" + String.join("\n", violations));
    }

    @Test
    void mainCodeDoesNotPullMvcTypesBackIn() throws IOException {
        List<String> violations = findViolations(List.of(
                "MultipartFile",
                "SseEmitter",
                "config.annotation.web.builders.HttpSecurity"));
        assertTrue(violations.isEmpty(), () -> "Found MVC-only types in main code:\n" + String.join("\n", violations));
    }

    private List<String> findViolations(List<String> needles) throws IOException {
        try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> scanFile(path, needles).stream())
                    .toList();
        }
    }

    private List<String> scanFile(Path path, List<String> needles) {
        try {
            List<String> lines = Files.readAllLines(path);
            return lines.stream()
                    .map(String::trim)
                    .filter(line -> needles.stream().anyMatch(line::contains))
                    .map(line -> path + ": " + line)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan " + path, e);
        }
    }
}
