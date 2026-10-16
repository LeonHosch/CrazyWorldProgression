package ekuzo.crazyworldprogression.mobforge.template;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MobTemplateRegistry {
	private final Path directory;
	private final MobTemplateYaml yaml = new MobTemplateYaml();
	private volatile Map<String, MobTemplate> templates = Map.of();

	public MobTemplateRegistry() { this(FabricLoader.getInstance().getConfigDir().resolve("mobforge/templates")); }
	MobTemplateRegistry(Path directory) { this.directory = directory; }

	public synchronized ReloadResult reload() {
		Map<String, MobTemplate> loaded = new LinkedHashMap<>(); int failures = 0;
		try {
			Files.createDirectories(directory);
			try (var files = Files.list(directory)) {
				for (Path file : files.filter(Files::isRegularFile).filter(MobTemplateRegistry::isYaml).sorted().toList()) {
					try {
						MobTemplate template = yaml.decode(Files.readString(file, StandardCharsets.UTF_8));
						MobTemplateValidator.validate(template);
						if (loaded.putIfAbsent(template.id(), template) != null) throw new IllegalArgumentException("duplicate template ID: " + template.id());
					} catch (Exception exception) {
						failures++; MobForge.LOGGER.warn("Failed to load {}: {}", file.getFileName(), cleanMessage(exception));
					}
				}
			}
		} catch (IOException exception) { failures++; MobForge.LOGGER.error("Could not read template directory: {}", exception.getMessage()); }
		templates = Map.copyOf(loaded);
		MobForge.LOGGER.info("Loaded {} MobForge templates ({} failed).", loaded.size(), failures);
		return new ReloadResult(loaded.size(), failures);
	}

	public synchronized void save(MobTemplate template) throws IOException, TemplateValidationException {
		MobTemplateValidator.validate(template);
		Files.createDirectories(directory);
		Path yamlDestination = directory.resolve(template.id() + ".yaml");
		Path ymlDestination = directory.resolve(template.id() + ".yml");
		Path destination = Files.exists(yamlDestination) && !Files.exists(ymlDestination) ? yamlDestination : ymlDestination;
		destination = destination.normalize();
		if (!destination.getParent().equals(directory.normalize())) throw new IOException("unsafe template path");
		Path temporary = Files.createTempFile(directory, template.id() + "-", ".tmp");
		try {
			Files.writeString(temporary, yaml.encode(template), StandardCharsets.UTF_8);
			MobTemplate roundTrip = yaml.decode(Files.readString(temporary, StandardCharsets.UTF_8));
			MobTemplateValidator.validate(roundTrip);
			try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
		} finally { Files.deleteIfExists(temporary); }
		reload();
	}

	public synchronized boolean delete(String id) throws IOException {
		if (!MobTemplateValidator.SAFE_ID.matcher(id).matches()) return false;
		boolean deleted = Files.deleteIfExists(directory.resolve(id + ".yml"));
		deleted |= Files.deleteIfExists(directory.resolve(id + ".yaml")); reload(); return deleted;
	}

	public Optional<MobTemplate> get(String id) { return Optional.ofNullable(templates.get(id)); }
	public Collection<MobTemplate> all() { return templates.values().stream().sorted(Comparator.comparing(MobTemplate::id)).toList(); }
	public Path directory() { return directory; }
	private static boolean isYaml(Path path) { String name = path.getFileName().toString().toLowerCase(); return name.endsWith(".yml") || name.endsWith(".yaml"); }
	private static String cleanMessage(Exception exception) { Throwable cause = exception; while (cause.getCause() != null) cause = cause.getCause(); return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(); }
	public record ReloadResult(int loaded, int failed) {}
}
