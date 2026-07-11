package net.topikachu.rag.service.etl.fileParseStrategy;

import net.topikachu.rag.config.OcrProperties;
import net.topikachu.rag.service.etl.OcrPdfDocumentReader;
import net.topikachu.rag.service.etl.PdfScanDetector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FileParseStrategyFactoryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void usesLegacyParserStrategiesByDefault() {
        contextRunner.run(context -> {
            FileParseStrategyFactory factory = context.getBean(FileParseStrategyFactory.class);

            assertThat(factory.getFileParseStrategy("pdf"))
                    .isInstanceOf(PdfParseStrategy.class);
            assertThat(factory.getFileParseStrategy("md"))
                    .isInstanceOf(MarkdownParseStrategy.class);
        });
    }

    @Test
    void usesPocParserStrategiesWhenConfigured() {
        contextRunner
                .withPropertyValues("rag.parser.provider=spring-ai-alibaba-poc")
                .run(context -> {
                    FileParseStrategyFactory factory = context.getBean(FileParseStrategyFactory.class);

                    assertThat(factory.getFileParseStrategy("pdf"))
                            .isInstanceOf(PocPdfParseStrategy.class);
                    assertThat(factory.getFileParseStrategy("md"))
                            .isInstanceOf(PocMarkdownParseStrategy.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            FileParseStrategyFactory.class,
            PdfParseStrategy.class,
            MarkdownParseStrategy.class,
            PocPdfParseStrategy.class,
            PocMarkdownParseStrategy.class
    })
    static class TestConfiguration {

        @Bean
        TextSplitter textSplitter() {
            return mock(TextSplitter.class);
        }

        @Bean
        PdfScanDetector pdfScanDetector() {
            return mock(PdfScanDetector.class);
        }

        @Bean
        OcrProperties ocrProperties() {
            return mock(OcrProperties.class);
        }

        @Bean
        OcrPdfDocumentReader ocrPdfDocumentReader() {
            return mock(OcrPdfDocumentReader.class);
        }
    }
}
