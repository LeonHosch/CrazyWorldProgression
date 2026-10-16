package ekuzo.crazyworldprogression.mobforge.client.screen;

import ekuzo.crazyworldprogression.mobforge.networking.EditorPayloads;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplateValidator;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplateYaml;
import ekuzo.crazyworldprogression.client.AdminPanelScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Scrollable, server-authoritative editor over the canonical MobTemplate/YAML schema. */
public final class TemplateEditorScreen extends Screen {
	private static final MobTemplateYaml YAML = new MobTemplateYaml();
	private static final List<String> CATEGORIES = List.of("General", "Attributes", "Equipment", "Properties", "Loot", "AI");
	private static final List<String> ATTRIBUTE_KEYS = List.of("max_health", "movement_speed", "attack_damage", "attack_speed",
			"armor", "armor_toughness", "knockback_resistance", "attack_knockback", "follow_range", "scale");
	private static final List<String> EQUIPMENT_KEYS = List.of("main_hand", "off_hand", "head", "chest", "legs", "feet");
	private static final List<String> AI_MODES = List.of("vanilla", "target_position", "target_player", "nearest_player");
	private static final int SIDEBAR_WIDTH = 116;
	private static final int FORM_TOP = 65;

	private final MobTemplate original;
	private final Screen parent;
	private final boolean existing;
	private final Map<String, String> values = new LinkedHashMap<>();
	private final Map<String, Boolean> toggles = new LinkedHashMap<>();
	private String category = "General";
	private String status = "";
	private boolean dirty;
	private boolean allowClose;
	private String pendingYaml;
	private String pendingId;
	private String saveOriginId;

	public TemplateEditorScreen(Minecraft client, String yaml) {
		this(client, yaml, null, true);
	}

	public TemplateEditorScreen(Minecraft client, String yaml, Screen parent) {
		this(client, yaml, parent, true);
	}

	public TemplateEditorScreen(Minecraft client, String yaml, Screen parent, boolean existing) {
		super(client, client.font, Component.literal("MobForge Template Editor"));
		this.parent = parent;
		this.existing = existing;
		original = YAML.decode(yaml);
		saveOriginId = existing ? original.id() : "";
		load(original);
	}

	private void load(MobTemplate template) {
		values.put("id", template.id()); values.put("entity", template.entity()); values.put("name", nvl(template.name()));
		values.put("tags", String.join(", ", template.tags()));
		ATTRIBUTE_KEYS.forEach(key -> values.put("attr." + key, template.attributes().containsKey(key) ? String.valueOf(template.attributes().get(key)) : ""));
		EQUIPMENT_KEYS.forEach(key -> {
			MobTemplate.EquipmentEntry entry = template.equipment().get(key);
			values.put("equipment." + key, entry == null ? "" : entry.id());
			values.put("equipment." + key + ".count", entry == null ? "1" : String.valueOf(entry.count()));
		});
		MobTemplate.Properties p = template.properties();
		toggles.put("persistent", p.persistent()); toggles.put("burns_in_daylight", p.burnsInDaylight()); toggles.put("can_pick_up_loot", p.canPickUpLoot());
		toggles.put("silent", p.silent()); toggles.put("glowing", p.glowing()); toggles.put("invulnerable", p.invulnerable()); toggles.put("no_gravity", p.noGravity());
		values.put("despawn_behavior", p.despawnBehavior()); toggles.put("drop_vanilla_loot", template.loot().dropVanillaLoot()); values.put("xp", String.valueOf(template.loot().xp()));
		MobTemplate.Ai ai = template.ai(); values.put("ai.mode", ai.mode()); toggles.put("ai.combat", ai.combat());
		values.put("ai.x", ai.target() == null ? "" : String.valueOf(ai.target().x())); values.put("ai.y", ai.target() == null ? "" : String.valueOf(ai.target().y())); values.put("ai.z", ai.target() == null ? "" : String.valueOf(ai.target().z()));
		values.put("ai.arrival", String.valueOf(ai.arrivalRadius())); values.put("ai.player", nvl(ai.player())); values.put("ai.range", String.valueOf(ai.range()));
	}

	@Override protected void init() { rebuild(); }

	private void rebuild() {
		clearWidgets();
		for (int i = 0; i < CATEGORIES.size(); i++) {
			String tab = CATEGORIES.get(i);
			Button button = Button.builder(Component.literal((tab.equals(category) ? "▶ " : "") + tab), b -> {
				category = tab; status = ""; rebuild();
			}).bounds(10, 42 + i * 25, SIDEBAR_WIDTH - 20, 21).build();
			button.active = !tab.equals(category);
			addRenderableWidget(button);
		}

		int contentX = SIDEBAR_WIDTH + 10;
		int contentWidth = Math.max(120, width - contentX - 8);
		int formHeight = Math.max(45, height - FORM_TOP - 39);
		FormList form = new FormList(contentWidth, formHeight, FORM_TOP);
		form.setX(contentX);
		form.populate(formBlocks(contentWidth < 210));
		addRenderableWidget(form);

		int footerWidth = Math.min(width - 16, 310); int footerX = (width - footerWidth) / 2; int y = height - 29;
		int footerButtonWidth = (footerWidth - 12) / 4;
		addRenderableWidget(Button.builder(Component.literal("Duplicate"), b -> duplicate()).bounds(footerX, y, footerButtonWidth, 20).build());
		Button delete = Button.builder(Component.literal("Delete"), b -> confirmDelete())
				.bounds(footerX + footerButtonWidth + 4, y, footerButtonWidth, 20).build();
		delete.active = existing && values.getOrDefault("id", "").equals(original.id());
		addRenderableWidget(delete);
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(footerX + (footerButtonWidth + 4) * 2, y, footerButtonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(footerX + (footerButtonWidth + 4) * 3, y, footerButtonWidth, 20).build());
	}

	private List<FormBlock> formBlocks(boolean compact) {
		List<FormBlock> blocks = new ArrayList<>();
		switch (category) {
			case "General" -> {
				section(blocks, "Identity", "The file-safe ID and registered vanilla entity type.");
				row(blocks, compact, text("id", "Template ID", "Lowercase letters, numbers, _ and - only."),
						new FormItem("entity", "Entity type", "Search the registered entity list.", FieldType.ENTITY, List.of()));
				section(blocks, "Presentation", "Player-facing naming and selector organization.");
				row(blocks, compact, text("name", "Display name", "Optional name shown above the mob."),
						text("tags", "Selector tags", "Comma-separated tags such as raid, wave_1."));
			}
			case "Attributes" -> {
				section(blocks, "Vital statistics", "Health, damage, and attack timing.");
				row(blocks, compact, attr("max_health"), attr("attack_damage"));
				row(blocks, compact, attr("attack_speed"), attr("attack_knockback"));
				section(blocks, "Defense", "Native armor and knockback mitigation.");
				row(blocks, compact, attr("armor"), attr("armor_toughness"));
				row(blocks, compact, attr("knockback_resistance"));
				section(blocks, "Movement and awareness", "Navigation speed, target range, and physical scale.");
				row(blocks, compact, attr("movement_speed"), attr("follow_range"));
				row(blocks, compact, attr("scale"));
			}
			case "Equipment" -> {
				section(blocks, "Hands", "Items held in the main and off hand.");
				equipmentRow(blocks, compact, "main_hand"); equipmentRow(blocks, compact, "off_hand");
				section(blocks, "Armor", "Items equipped in each armor slot.");
				for (String slot : List.of("head", "chest", "legs", "feet")) equipmentRow(blocks, compact, slot);
			}
			case "Properties" -> {
				section(blocks, "Lifecycle", "Persistence and normal distance-based despawning.");
				row(blocks, compact, choice("despawn_behavior", "Despawn behavior", "Vanilla rules or never despawn.", List.of("vanilla", "never")),
						bool("persistent", "Persistent", "Prevent normal distance despawning."));
				section(blocks, "World behavior", "How the mob interacts with its surroundings.");
				row(blocks, compact, bool("burns_in_daylight", "Burns in daylight", "Use daylight sensitivity when supported."),
						bool("can_pick_up_loot", "Can pick up loot", "Allow compatible ground-item pickup."));
				section(blocks, "Rendering and physics", "Sound, appearance, damage, and gravity switches.");
				row(blocks, compact, bool("silent", "Silent", "Suppress entity sounds."), bool("glowing", "Glowing", "Show the vanilla glowing outline."));
				row(blocks, compact, bool("invulnerable", "Invulnerable", "Prevent normal damage."), bool("no_gravity", "No gravity", "Disable gravity for the entity."));
			}
			case "Loot" -> {
				section(blocks, "Death rewards", "Control the vanilla loot table and experience reward.");
				row(blocks, compact, bool("drop_vanilla_loot", "Drop vanilla loot", "Run the entity's normal loot table."),
						text("xp", "Experience", "Use -1 for the vanilla amount."));
			}
			case "AI" -> {
				section(blocks, "Controller", "Choose an objective and whether combat may interrupt it.");
				row(blocks, compact, choice("ai.mode", "AI mode", "Select the objective controller.", AI_MODES),
						bool("ai.combat", "Combat enabled", "Fight threats, then resume the objective."));
				switch (values.getOrDefault("ai.mode", "vanilla")) {
					case "target_position" -> {
						section(blocks, "Position objective", "Destination coordinates and completion distance.");
						row(blocks, compact, text("ai.x", "Target X", "Destination X coordinate."), text("ai.y", "Target Y", "Destination Y coordinate."), text("ai.z", "Target Z", "Destination Z coordinate."));
						row(blocks, compact, text("ai.arrival", "Arrival radius", "Stop within this many blocks."));
					}
					case "target_player" -> {
						section(blocks, "Player objective", "The named player the mob should pursue.");
						row(blocks, compact, text("ai.player", "Player", "Player name; commands use UUIDs when possible."));
					}
					case "nearest_player" -> {
						section(blocks, "Player search", "Limits for periodic nearest-player selection.");
						row(blocks, compact, text("ai.range", "Search range", "Maximum player search radius."));
					}
					default -> { }
				}
			}
		}
		return blocks;
	}

	private void equipmentRow(List<FormBlock> blocks, boolean compact, String slot) {
		row(blocks, compact, new FormItem("equipment." + slot, pretty(slot), "Choose an item or clear this slot.", FieldType.ITEM, List.of()),
				text("equipment." + slot + ".count", "Count", "Equipment stack count from 1 to 99."));
	}

	private void row(List<FormBlock> blocks, boolean compact, FormItem... fields) {
		if (compact && fields.length > 1) for (FormItem field : fields) blocks.add(new FieldBlock(List.of(field)));
		else blocks.add(new FieldBlock(List.of(fields)));
	}
	private static void section(List<FormBlock> blocks, String title, String description) { blocks.add(new SectionBlock(title, description)); }

	private AbstractWidget createWidget(FormItem item) {
		return switch (item.type()) {
			case TEXT -> {
				EditBox box = new EditBox(font, 0, 0, 100, 20, Component.literal(item.label()));
				box.setMaxLength(256); box.setValue(values.getOrDefault(item.key(), ""));
				box.setResponder(value -> { values.put(item.key(), value); dirty = true; status = ""; });
				yield box;
			}
			case ENTITY -> Button.builder(displayValue(item.key(), "Choose entity…"), b -> minecraft.setScreenAndShow(
					new RegistryPickerScreen(minecraft, this, "Select entity type", BuiltInRegistries.ENTITY_TYPE.keySet().stream()
							.map(Object::toString).filter(id -> !id.equals("minecraft:player")).sorted().toList(), values.get(item.key()), value -> {
						values.put(item.key(), value); dirty = true; status = ""; rebuild();
					}))).bounds(0, 0, 100, 20).build();
			case ITEM -> Button.builder(displayValue(item.key(), "Empty"), b -> minecraft.setScreenAndShow(
					new RegistryPickerScreen(minecraft, this, "Select item", BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).sorted().toList(),
							values.get(item.key()), value -> { values.put(item.key(), value); dirty = true; status = ""; rebuild(); })))
					.bounds(0, 0, 100, 20).build();
			case BOOLEAN -> CycleButton.onOffBuilder(toggle(item.key())).displayOnlyValue().create(0, 0, 100, 20,
					Component.literal(item.label()), (button, value) -> { toggles.put(item.key(), value); dirty = true; status = ""; });
			case CHOICE -> CycleButton.builder(Component::literal, values.getOrDefault(item.key(), item.options().getFirst())).withValues(item.options()).displayOnlyValue()
					.create(0, 0, 100, 20, Component.literal(item.label()), (button, value) -> {
						values.put(item.key(), value); dirty = true; status = "";
						if (item.key().equals("ai.mode")) rebuild();
					});
		};
	}

	private final class FormList extends ContainerObjectSelectionList<FormEntry> {
		FormList(int width, int height, int y) { super(TemplateEditorScreen.this.minecraft, width, height, y, 52); centerListVertically = false; }
		void populate(List<FormBlock> blocks) {
			for (FormBlock block : blocks) {
				if (block instanceof SectionBlock section) addEntry(new SectionEntry(section), 22);
				else if (block instanceof FieldBlock fields) addEntry(new FieldEntry(fields.fields()), 52);
			}
		}
		@Override protected boolean entriesCanBeSelected() { return false; }
		@Override public int getRowWidth() { return Math.min(Math.max(120, getWidth() - 20), 640); }
	}

	private abstract static class FormEntry extends ContainerObjectSelectionList.Entry<FormEntry> { }

	private final class SectionEntry extends FormEntry {
		private final SectionBlock section;
		SectionEntry(SectionBlock section) { this.section = section; }
		@Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovering, float partialTick) {
			graphics.fill(getContentX(), getContentY() + 17, getContentRight(), getContentY() + 18, 0x6652606D);
			graphics.text(font, section.title(), getContentX() + 3, getContentY() + 3, 0xFFFFFFFF);
		}
		@Override public List<? extends NarratableEntry> narratables() { return List.of(); }
		@Override public List<? extends GuiEventListener> children() { return List.of(); }
	}

	private final class FieldEntry extends FormEntry {
		private final List<FormItem> fields;
		private final List<AbstractWidget> widgets;
		FieldEntry(List<FormItem> fields) { this.fields = fields; this.widgets = fields.stream().map(TemplateEditorScreen.this::createWidget).toList(); }
		@Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovering, float partialTick) {
			int gap = 12;
			int groupWidth = Math.min(getContentWidth(), 620);
			int widthCap = fields.size() == 1 ? 150 : 300;
			int cellWidth = Math.min(widthCap, (groupWidth - gap * (fields.size() - 1)) / fields.size());
			for (int i = 0; i < fields.size(); i++) {
				int x = getContentX() + i * (cellWidth + gap); int y = getContentY();
				FormItem field = fields.get(i); AbstractWidget widget = widgets.get(i);
				graphics.text(font, field.label(), x, y + 1, 0xFFFFFFFF);
				graphics.textWithWordWrap(font, Component.literal(field.description()), x, y + 11, cellWidth, 0xFF929AA3);
				widget.setX(x); widget.setY(y + 30); widget.setWidth(cellWidth);
				widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
			}
		}
		@Override public List<? extends NarratableEntry> narratables() { return widgets; }
		@Override public List<? extends GuiEventListener> children() { return widgets; }
		@Override public void visitWidgets(Consumer<AbstractWidget> consumer) { widgets.forEach(consumer); }
	}

	private void duplicate() { values.put("id", uniqueCopyId(values.getOrDefault("id", "template"))); dirty = true; category = "General"; status = "Duplicate ready—save to create it."; rebuild(); }
	private static String uniqueCopyId(String id) { return id.endsWith("_copy") ? id + "_2" : id + "_copy"; }

	private void save() {
		if (pendingYaml != null) { status = "Waiting for server validation…"; return; }
		try {
			MobTemplate template = buildTemplate(); MobTemplateValidator.validate(template);
			pendingYaml = YAML.encode(template);
			pendingId = template.id();
			ClientPlayNetworking.send(new EditorPayloads.SaveTemplate(pendingYaml,
					saveOriginId));
			status = "Waiting for server validation…";
		} catch (Exception exception) { status = "Invalid: " + exception.getMessage(); }
	}

	public void saveResult(boolean success, String message) {
		status = success ? message : "Invalid: " + message;
		if (success) {
			if (pendingId != null) saveOriginId = pendingId;
			try { if (pendingYaml != null && pendingYaml.equals(YAML.encode(buildTemplate()))) dirty = false; }
			catch (RuntimeException ignored) { dirty = true; }
		} else dirty = true;
		pendingYaml = null;
		pendingId = null;
	}

	private MobTemplate buildTemplate() {
		Map<String, Double> attributes = new LinkedHashMap<>();
		ATTRIBUTE_KEYS.forEach(key -> { if (!blank("attr." + key)) attributes.put(key, number("attr." + key)); });
		Map<String, MobTemplate.EquipmentEntry> equipment = new LinkedHashMap<>();
		EQUIPMENT_KEYS.forEach(slot -> {
			String id = values.getOrDefault("equipment." + slot, "");
			if (!id.isBlank()) {
				MobTemplate.EquipmentEntry old = original.equipment().get(slot);
				equipment.put(slot, new MobTemplate.EquipmentEntry(id, integer("equipment." + slot + ".count"), old == null ? Map.of() : old.components()));
			}
		});
		MobTemplate.Properties properties = new MobTemplate.Properties(toggle("persistent"), toggle("burns_in_daylight"), toggle("can_pick_up_loot"),
				toggle("silent"), toggle("glowing"), toggle("invulnerable"), toggle("no_gravity"), values.get("despawn_behavior"));
		boolean positionMode = "target_position".equals(values.get("ai.mode"));
		MobTemplate.Position target = !positionMode || (blank("ai.x") && blank("ai.y") && blank("ai.z")) ? null : new MobTemplate.Position(number("ai.x"), number("ai.y"), number("ai.z"));
		MobTemplate.Ai ai = new MobTemplate.Ai(values.get("ai.mode"), toggle("ai.combat"), target, number("ai.arrival"),
				!"target_player".equals(values.get("ai.mode")) || blank("ai.player") ? null : values.get("ai.player").trim(), number("ai.range"), original.ai().settings());
		List<String> tags = blank("tags") ? List.of() : Arrays.stream(values.get("tags").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
		return new MobTemplate(values.get("id").trim(), values.get("entity"), blank("name") ? null : values.get("name"), attributes, equipment, properties,
				new MobTemplate.Loot(toggle("drop_vanilla_loot"), integer("xp")), tags, ai);
	}

	private void confirmDelete() {
		if (!existing || !values.getOrDefault("id", "").equals(original.id())) {
			status = "Only the original saved template can be deleted.";
			return;
		}
		minecraft.setScreenAndShow(new ConfirmScreen(result -> {
			if (result) { ClientPlayNetworking.send(new EditorPayloads.DeleteTemplate(original.id())); allowClose = true; finish(); }
			else minecraft.setScreenAndShow(this);
		}, Component.literal("Delete template?"), Component.literal("This permanently deletes " + original.id() + ".")));
	}

	@Override public void onClose() {
		if (!dirty || allowClose) { finish(); return; }
		minecraft.setScreenAndShow(new ConfirmScreen(result -> {
			if (result) { allowClose = true; finish(); }
			else minecraft.setScreenAndShow(this);
		}, Component.literal("Discard unsaved changes?"), Component.literal("Your MobForge template changes have not been saved.")));
	}

	private void finish() {
		minecraft.setScreenAndShow(parent);
		if (parent instanceof AdminPanelScreen screen) screen.refreshFromServer();
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, 34, 0xCC101820);
		graphics.fill(0, 34, SIDEBAR_WIDTH, height - 36, 0x99101820);
		graphics.fill(SIDEBAR_WIDTH, 34, SIDEBAR_WIDTH + 1, height - 36, 0xFF52606D);
		graphics.fill(0, height - 36, width, height, 0xCC101820);
		graphics.text(font, title, 12, 12, 0xFFFFFFFF);
		if (!status.isBlank()) graphics.text(font, truncate(status, Math.max(20, width - 220)), 210, 12, status.startsWith("Invalid") ? 0xFFFF7777 : 0xFF77FF9A);
		graphics.text(font, Component.literal(category), SIDEBAR_WIDTH + 14, 42, 0xFFFFFFFF);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private Component displayValue(String key, String fallback) { String value = values.getOrDefault(key, ""); return Component.literal(value.isBlank() ? fallback : value); }
	private boolean toggle(String key) { return toggles.getOrDefault(key, false); }
	private boolean blank(String key) { return values.getOrDefault(key, "").isBlank(); }
	private double number(String key) { return Double.parseDouble(values.get(key)); }
	private int integer(String key) { double value = number(key); if (value != Math.rint(value)) throw new IllegalArgumentException(pretty(key) + " must be an integer"); return (int) value; }
	private String truncate(String value, int width) { if (font.width(value) <= width) return value; return font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…"; }
	private static String nvl(String value) { return value == null ? "" : value; }
	private static String pretty(String key) { String value = key.substring(key.lastIndexOf('.') + 1).replace('_', ' '); return Character.toUpperCase(value.charAt(0)) + value.substring(1); }
	private static String attributeDescription(String key) {
		return switch (key) {
			case "max_health" -> "Maximum hit points; greater than zero.";
			case "movement_speed" -> "Absolute vanilla speed; usually 0.20–0.35, maximum 0.70.";
			case "attack_damage" -> "Base melee damage where supported.";
			case "attack_speed" -> "Native attack cooldown speed.";
			case "armor" -> "Base armor before equipment.";
			case "armor_toughness" -> "Mitigation against strong attacks.";
			case "knockback_resistance" -> "Knockback resistance from 0 to 1.";
			case "attack_knockback" -> "Extra knockback dealt by attacks.";
			case "follow_range" -> "Target-selection awareness distance.";
			case "scale" -> "Native entity scale.";
			default -> "Optional native attribute value.";
		};
	}
	private static FormItem attr(String key) { return text("attr." + key, pretty(key), attributeDescription(key)); }
	private static FormItem text(String key, String label, String description) { return new FormItem(key, label, description, FieldType.TEXT, List.of()); }
	private static FormItem bool(String key, String label, String description) { return new FormItem(key, label, description, FieldType.BOOLEAN, List.of()); }
	private static FormItem choice(String key, String label, String description, List<String> options) { return new FormItem(key, label, description, FieldType.CHOICE, options); }
	private enum FieldType { TEXT, BOOLEAN, CHOICE, ENTITY, ITEM }
	private interface FormBlock { }
	private record SectionBlock(String title, String description) implements FormBlock { }
	private record FieldBlock(List<FormItem> fields) implements FormBlock { }
	private record FormItem(String key, String label, String description, FieldType type, List<String> options) { }
}
