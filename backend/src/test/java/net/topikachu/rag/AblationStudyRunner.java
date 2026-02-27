package net.topikachu.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.evaluation.EvaluationConfig;
import net.topikachu.rag.evaluation.EvaluationResultItem;
import net.topikachu.rag.service.chat.ChatService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

@SpringBootTest
@Slf4j
// @Disabled("Manual trigger only. Requires running Milvus, TEI, and Ollama.")
public class AblationStudyRunner {

    @Autowired
    private ChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("classpath:prompts/baseline_rag.st")
    private Resource baselinePrompt;

    @Value("classpath:prompts/optimized_rag.st")
    private Resource optimizedPrompt;

    // Optional: Load golden dataset from classpath
    @Value("classpath:evaluation/golden_questions.json")
    private Resource goldenQuestionsResource;

    @Test
    public void runAblationStudy() throws Exception {
        // 1. Load Golden Dataset
        List<Map<String, String>> dataset;
        try (InputStream is = goldenQuestionsResource.getInputStream()) {
            dataset = objectMapper.readValue(is, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (Exception e) {
            log.warn(
                    "Could not load golden dataset. Ensure src/test/resources/evaluation/golden_questions.json exists.");
            return;
        }

        // 2. Define Ablation Versions
        Map<String, EvaluationConfig> versions = Map.of(
                "v0_baseline", new EvaluationConfig(false, false, false), // Dense only, no Rerank, Baseline Prompt
                "v1_hybrid", new EvaluationConfig(true, false, false), // Hybrid, no Rerank, Baseline Prompt
                "v2_hybrid_rerank", new EvaluationConfig(true, true, false), // Hybrid + Rerank, Baseline Prompt
                "v3_optimized", new EvaluationConfig(true, true, true) // Hybrid + Rerank + Optimized Prompt
        );

        // 3. Run evaluation for each version
        for (Map.Entry<String, EvaluationConfig> entry : versions.entrySet()) {
            String versionName = entry.getKey();
            EvaluationConfig config = entry.getValue();
            log.info("Starting evaluation for version: {}", versionName);

            Path outputPath = Paths.get("evaluation_results", "result_" + versionName + ".jsonl");
            Files.createDirectories(outputPath.getParent());
            // Clear previous results for this version if they exist
            Files.deleteIfExists(outputPath);
            Files.createFile(outputPath);

            // 4. Iterate over queries
            for (Map<String, String> item : dataset) {
                String question = item.get("question");
                String groundTruth = item.get("ground_truth");

                try {
                    log.info("Evaluating [{}]: {}", versionName, question);
                    EvaluationResultItem result = chatService.evaluateQuery(
                            question, groundTruth, config, baselinePrompt, optimizedPrompt);

                    // 5. Partial Save (JSONL append)
                    String jsonlLine = objectMapper.writeValueAsString(result) + "\n";
                    Files.writeString(outputPath, jsonlLine, StandardOpenOption.APPEND);

                    // 6. Mandatory Cooldown (Prevent local LLM crash/OOM)
                    log.info("Sleeping for 2 seconds to cool down local LLM...");
                    Thread.sleep(2000);

                } catch (Exception e) {
                    log.error("Failed to evaluate question: {}", question, e);
                    // Continue to next question, don't break the entire batch
                }
            }
            log.info("Finished evaluation for version: {}", versionName);
        }
    }
}
