package net.topikachu.rag.service.etl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.*;
import java.util.stream.Stream;

@Slf4j
@Component
public class DocReader {

	@Value("${input.directory}")
	private String inputDir;

	@Value("${input.filename.glob:*.{txt,pdf,html}}")
	private String pattern;

	public Flux<Path> scanDirectory() {
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

		return Flux.using(
				() -> Files.walk(Paths.get(inputDir)),
				stream ->
						Flux.fromStream(
								stream
										.filter(Files::isRegularFile)
										.filter(path -> matcher.matches(path.getFileName()))
						),
				Stream::close
		).subscribeOn(Schedulers.boundedElastic());
	}
}
