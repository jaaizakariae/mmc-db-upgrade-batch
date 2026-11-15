// src/main/java/ma/bkam/db/upgrade/service/DbUpgradeRunner.java
package ma.bkam.db.upgrade.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.bkam.db.upgrade.enums.SqlFileProcessResultEnum;
import ma.bkam.db.upgrade.properties.DbUpgradeProperties;
import ma.bkam.db.upgrade.util.DateUtil;
import ma.bkam.db.upgrade.util.FileNaming;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbUpgradeRunner implements CommandLineRunner {

	private final DbUpgradeProperties props;
	private final SqlFileProcessor processor;
	private final SqlExecutionHistoryBootstrapService sqlExecutionHistoryBootstrapService;

	@Override
	public void run(String... args) throws Exception {
		Instant startAll = Instant.now();

		try {
			sqlExecutionHistoryBootstrapService.initialize();
		} catch (Exception exception) {
			log.error("Unable to initialize SQL execution history technical objects.", exception);
			logSummary(0, 0, 1, 0, startAll);
			System.exit(1);
			return;
		}

		Path inputDirectory = Paths.get(props.getInputFolder());

		if (!Files.isDirectory(inputDirectory)) {
			log.error("Input folder does not exist: {}", inputDirectory.toAbsolutePath());
			logSummary(0, 0, 1, 0, startAll);
			System.exit(1);
			return;
		}

		log.info("db-upgrade starting. Input directory: {}", inputDirectory.toAbsolutePath());

		List<Path> candidates;

		try {
			candidates = loadAndValidateScripts(inputDirectory);
		} catch (Exception exception) {
			log.error("Unable to load or validate SQL scripts.", exception);
			logSummary(0, 0, 1, 0, startAll);
			System.exit(1);
			return;
		}

		if (candidates.isEmpty()) {
			log.info("No SQL files found in input folder.");
			logSummary(0, 0, 0, 0, startAll);
			System.exit(0);
			return;
		}

		log.info("{} SQL script(s) validated and ready for execution.", candidates.size());

		AtomicInteger executed = new AtomicInteger();
		AtomicInteger failed = new AtomicInteger();
		AtomicInteger skipped = new AtomicInteger();

		for (Path file : candidates) {
			String filename = file.getFileName().toString();

			log.info("Executing script: {}", filename);

			SqlFileProcessResultEnum result = processor.processSingleFile(file);

			if (result == SqlFileProcessResultEnum.SUCCESS) {
				executed.incrementAndGet();
				continue;
			}

			if (result == SqlFileProcessResultEnum.SKIPPED) {
				skipped.incrementAndGet();
				continue;
			}

			failed.incrementAndGet();

			log.error("Script execution failed: {}", filename);
			logSummary(candidates.size(), executed.get(), failed.get(), skipped.get(), startAll);

			System.exit(1);
			return;
		}

		logSummary(candidates.size(), executed.get(), failed.get(), skipped.get(), startAll);
		log.info("db-upgrade completed successfully.");

		System.exit(0);
	}

	private List<Path> loadAndValidateScripts(Path inputDirectory) throws Exception {
		List<Path> files;

		try (Stream<Path> stream = Files.list(inputDirectory)) {
			files = stream.filter(Files::isRegularFile)
					.filter(file -> file.getFileName().toString().toLowerCase().endsWith(".sql")).toList();
		}

		if (files.isEmpty()) {
			return List.of();
		}

		Map<Path, FileNaming.Parsed> parsedFiles = files.stream()
				.collect(Collectors.toMap(file -> file,
						file -> FileNaming.parse(file.getFileName().toString(), props.getFilenameRegex()).orElseThrow(
								() -> new IllegalArgumentException("Invalid SQL filename: " + file.getFileName()))));

		Map<Integer, List<Path>> filesBySequence = parsedFiles.entrySet().stream()
				.collect(Collectors.groupingBy(entry -> entry.getValue().sequenceNumber(),
						Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

		List<Map.Entry<Integer, List<Path>>> duplicates = filesBySequence.entrySet().stream()
				.filter(entry -> entry.getValue().size() > 1).sorted(Map.Entry.comparingByKey()).toList();

		if (!duplicates.isEmpty()) {
			String duplicateDetails = duplicates.stream()
					.map(entry -> String.format("sequence=%d files=%s", entry.getKey(),
							entry.getValue().stream().map(path -> path.getFileName().toString()).sorted().toList()))
					.collect(Collectors.joining(" | "));

			throw new IllegalStateException("Duplicate SQL sequence numbers are not allowed. " + duplicateDetails);
		}

		return parsedFiles.entrySet().stream()
				.sorted(Comparator
						.comparingInt((Map.Entry<Path, FileNaming.Parsed> entry) -> entry.getValue().sequenceNumber())
						.thenComparing(entry -> entry.getKey().getFileName().toString()))
				.map(Map.Entry::getKey).toList();
	}

	private void logSummary(int total, int executed, int failed, int skipped, Instant startAll) {
		long durationMs = Duration.between(startAll, Instant.now()).toMillis();

		log.info("───────────────────────────────────────────────");
		log.info("db-upgrade summary:");
		log.info("  total scripts : {}", total);
		log.info("  executed OK   : {}", executed);
		log.info("  skipped       : {}", skipped);
		log.info("  failed        : {}", failed);
		log.info("  duration      : {}", DateUtil.formatDuration(durationMs));
		log.info("───────────────────────────────────────────────");
	}
}