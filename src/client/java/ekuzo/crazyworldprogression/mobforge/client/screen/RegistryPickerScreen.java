package ekuzo.crazyworldprogression.mobforge.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable, paged selector used for registered entity and item identifiers. */
final class RegistryPickerScreen extends Screen {
	private final Screen parent;
	private final List<String> choices;
	private final Consumer<String> selection;
	private final String initialValue;
	private String query = "";
	private int page;
	private int pageCount = 1;
	private EditBox searchBox;

	RegistryPickerScreen(Minecraft client, Screen parent, String title, List<String> choices, String initialValue, Consumer<String> selection) {
		super(client, client.font, Component.literal(title));
		this.parent = parent;
		this.choices = choices;
		this.initialValue = initialValue == null ? "" : initialValue;
		this.selection = selection;
	}

	@Override protected void init() { rebuild(false); }

	private void rebuild(boolean focusSearch) {
		clearWidgets();
		int panelWidth = Math.min(420, width - 32);
		int x = (width - panelWidth) / 2;
		searchBox = new EditBox(font, x, 38, panelWidth, 20, Component.literal("Search registered identifiers"));
		searchBox.setMaxLength(128); searchBox.setValue(query);
		searchBox.setResponder(value -> {
			if (value.equals(query)) return;
			query = value; page = 0; rebuild(true);
		});
		addRenderableWidget(searchBox);

		List<String> filtered = filtered();
		int rows = Math.max(2, (height - 116) / 23);
		pageCount = Math.max(1, (filtered.size() + rows - 1) / rows);
		page = Math.min(page, pageCount - 1);
		int first = page * rows;
		for (int i = first; i < Math.min(filtered.size(), first + rows); i++) {
			String value = filtered.get(i);
			String marker = value.equals(initialValue) ? "✓ " : "";
			addRenderableWidget(Button.builder(Component.literal(marker + value), b -> choose(value)).bounds(x, 67 + (i - first) * 23, panelWidth, 20).build());
		}

		int controlsY = height - 44;
		Button previous = Button.builder(Component.literal("‹ Previous"), b -> { page--; rebuild(false); }).bounds(x, controlsY, 86, 20).build(); previous.active = page > 0;
		Button next = Button.builder(Component.literal("Next ›"), b -> { page++; rebuild(false); }).bounds(x + panelWidth - 72, controlsY, 72, 20).build(); next.active = page + 1 < pageCount;
		addRenderableWidget(previous); addRenderableWidget(next);
		if (title.getString().toLowerCase(Locale.ROOT).contains("item")) addRenderableWidget(Button.builder(Component.literal("Clear slot"), b -> choose("")).bounds(x + panelWidth / 2 - 46, controlsY, 92, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 - 38, height - 21, 76, 20).build());
		if (focusSearch) { setInitialFocus(searchBox); searchBox.moveCursorToEnd(false); }
	}

	private List<String> filtered() {
		String needle = query.trim().toLowerCase(Locale.ROOT);
		return needle.isEmpty() ? choices : choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).contains(needle)).toList();
	}

	private void choose(String value) { selection.accept(value); minecraft.setScreenAndShow(parent); }
	@Override public void onClose() { minecraft.setScreenAndShow(parent); }

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, 0xDD0B1118);
		graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		graphics.text(font, "Search", (width - Math.min(420, width - 32)) / 2, 28, 0xFFAAB2BA);
		graphics.centeredText(font, "Page " + (page + 1) + " / " + pageCount + " • " + filtered().size() + " matches", width / 2, height - 35, 0xFFAAB2BA);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
