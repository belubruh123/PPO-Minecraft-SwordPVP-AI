package dev.swordai.client;

import dev.swordai.core.config.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The SwordAI menu: everything the {@code /swordai} command does that is used day to day, as buttons,
 * and the saved models. Opened with its key (Right Shift unless changed in Controls) or
 * {@code /swordai menu}. A button that starts or stops something runs the same command as typing it
 * would, so what it says comes to the chat as before.
 *
 * <p>Three pages: the main one, exporting the current model under a name, and importing a saved
 * one. A {@code .zip} dropped on the window from anywhere is added to the saved models.
 */
final class SwordAiScreen extends Screen {
	private enum Page { MAIN, EXPORT, IMPORT }

	private static final int COL_W = 100;
	private static final int BUTTON_H = 20;
	private static final int GAP = 4;
	private static final int CONTENT_W = 3 * COL_W + 2 * GAP;
	/** Room for a panel's heading above its buttons. */
	private static final int HEAD = 13;
	/** A panel's margin around what is in it. */
	private static final int PAD = 5;
	private static final int SECTION_GAP = 6;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA8A8B0;
	private static final int DIM = 0xFF787880;
	private static final int GOLD = 0xFFFFC857;
	private static final int GREEN = 0xFF7BE07F;
	private static final int RED = 0xFFFF7070;
	private static final int PANEL = 0xC0101016;
	private static final int EDGE = 0xFF3C3C4C;

	/** A box drawn behind a group of buttons, with its heading. */
	private record Panel(int x, int y, int w, int h, String heading) {
	}

	/** A line of text placed by {@link #init()}, drawn over the panels. */
	private record Label(String text, int x, int y, int color, boolean centred) {
	}

	private final List<Panel> panels = new ArrayList<>();
	private final List<Label> labels = new ArrayList<>();

	private Page page = Page.MAIN;
	private String message = "";
	private int messageColor = WHITE;
	private int importPage;
	/** The name typed for an export, kept while the page is rebuilt. */
	private String exportName;
	private EditBox nameBox;
	private boolean wasRunning;
	/** Where the status lines and the message go; set by {@link #init()}. */
	private int statusX;
	private int statusY;
	private int messageY;
	/** The model line of the status, read when the page is laid out. */
	private String modelText = "";

	SwordAiScreen() {
		super(Component.literal("SwordAI"));
	}

	/** Opened on a page: "export" or "import"; anything else is the main one. */
	SwordAiScreen(String start) {
		this();
		page = "export".equals(start) && Files.exists(SwordFiles.checkpoint()) ? Page.EXPORT : "import".equals(start) ? Page.IMPORT : Page.MAIN;
	}

	// ---- layout ---------------------------------------------------------------------------------

	@Override
	protected void init() {
		panels.clear();
		labels.clear();
		nameBox = null;
		wasRunning = SwordAiClient.running();
		switch (page) {
			case EXPORT -> initExport();
			case IMPORT -> initImport();
			default -> initMain();
		}
	}

	private void initMain() {
		boolean running = wasRunning;
		Config cfg = SwordFiles.loadConfig();
		boolean model = Files.exists(SwordFiles.checkpoint());
		int x = (width - CONTENT_W) / 2;
		int total = (HEAD + 2 * 11 + 2 * PAD + SECTION_GAP) + 3 * (HEAD + BUTTON_H + 2 * PAD + SECTION_GAP) + BUTTON_H + 14;
		int y = Math.max(PAD + 2, (height - total) / 2);

		// What is going on, and which model it is.
		int statusH = HEAD + 2 * 11;
		panels.add(new Panel(x - PAD, y - PAD, CONTENT_W + 2 * PAD, statusH + 2 * PAD, null));
		labels.add(new Label("SwordAI", x, y, GOLD, false));
		labels.add(new Label(SwordFiles.modVersion(), x + font.width("SwordAI") + 5, y, DIM, false));
		statusX = x;
		statusY = y + HEAD;
		modelText = modelLine(cfg);
		y += statusH + 2 * PAD + SECTION_GAP;

		y = section(x, y, "Fight");
		col(x, 0, y, "Fight nearest", "Your character fights the nearest player, played by the AI. Stop (or the fight key) gives it back to you.",
				!running, () -> run("swordai fight"));
		col(x, 1, y, "Spar: you vs it", "You fight the AI here, in this world.", !running, () -> run("swordai spar"));
		Button stop = col(x, 2, y, "Stop", running ? "Stops what is running and gives you the controls back." : "Nothing is running.",
				running, () -> run("swordai stop"));
		if (running) {
			stop.setMessage(Component.literal("Stop").withStyle(ChatFormatting.RED));
		}
		y += BUTTON_H + 2 * PAD + SECTION_GAP;

		y = section(x, y, "Train");
		col(x, 0, y, "Train on screen", "Trains in this world, on the screen, the way it fights: first person.", !running, () -> run("swordai train"));
		col(x, 1, y, "Train headless", "Trains without drawing the fights, which is faster. Leave the game open.", !running,
				() -> run("swordai train headless"));
		speedControl(x + 2 * (COL_W + GAP), y, cfg.trainSpeed, !running);
		y += BUTTON_H + 2 * PAD + SECTION_GAP;

		y = section(x, y, "Model");
		col(x, 0, y, "Export...", model ? "Saves the current model as one .zip, to keep or to take to another PC."
				: "There is no trained model yet.", model, () -> show(Page.EXPORT));
		col(x, 1, y, "Import...", "Loads a saved model. The current one is saved first, so it can be undone.", true, () -> {
			importPage = 0;
			show(Page.IMPORT);
		});
		col(x, 2, y, "Open folder", "Opens swordai/models. Put .zip files from another PC there, or drop them on this menu.", true,
				this::openFolder);
		y += BUTTON_H + 2 * PAD + SECTION_GAP;

		int quarter = (CONTENT_W - 3 * GAP) / 4;
		boolean skin = cfg.showOpponentSkin;
		addRenderableWidget(Button.builder(Component.literal("Report"), b -> run("swordai report"))
				.tooltip(Tooltip.create(Component.literal("Copies a report of the training to the clipboard (and swordai/report.txt).")))
				.bounds(x, y, quarter, BUTTON_H).build());
		addRenderableWidget(Button.builder(Component.literal(skin ? "Skins: real" : "Skins: keyed"), b -> {
			if (minecraft.player != null) {
				minecraft.player.connection.sendCommand(skin ? "swordai skin off" : "swordai skin on");
			}
			rebuildWidgets();
		}).tooltip(Tooltip.create(Component.literal("Real: you see the opponent's own skin. The AI sees its keyed picture either way.")))
				.bounds(x + quarter + GAP, y, quarter, BUTTON_H).build());
		addRenderableWidget(Button.builder(Component.literal("Keybinds..."), b -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options)))
				.tooltip(Tooltip.create(Component.literal("Set the menu key and a key that starts and stops a fight (under Miscellaneous).")))
				.bounds(x + 2 * (quarter + GAP), y, quarter, BUTTON_H).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(x + CONTENT_W - quarter, y, quarter, BUTTON_H).build());
		messageY = y + BUTTON_H + 6;
	}

	private void initExport() {
		int x = (width - CONTENT_W) / 2;
		List<String> about = wrap("Saves the network with its settings and its training as one .zip (a few MB) in swordai/models. "
				+ "Copy that file to another PC and use Import there.");
		int h = HEAD + about.size() * 10 + 4 + 36 + BUTTON_H;
		int y = Math.max(PAD + 2, (height - h - 20) / 2);
		panels.add(new Panel(x - PAD, y - PAD, CONTENT_W + 2 * PAD, h + 2 * PAD, null));
		labels.add(new Label("Export model", x, y, GOLD, false));
		y += HEAD;
		for (String line : about) {
			labels.add(new Label(line, x, y, GREY, false));
			y += 10;
		}
		y += 4;
		if (exportName == null) {
			exportName = ModelPack.defaultName();
		}
		nameBox = new EditBox(font, x, y, CONTENT_W, 20, Component.literal("Name"));
		nameBox.setMaxLength(48);
		nameBox.setFilter(s -> s.matches("[A-Za-z0-9._-]*"));
		nameBox.setValue(exportName);
		nameBox.setHint(Component.literal("name of the model").withStyle(ChatFormatting.DARK_GRAY));
		nameBox.setResponder(s -> exportName = s);
		addRenderableWidget(nameBox);
		setInitialFocus(nameBox);
		statusX = x;
		statusY = y + 24;
		y += 36;
		col(x, 0, y, "Export", "Saves it now.", true, this::export);
		col(x, 1, y, "Open folder", "Opens swordai/models.", true, this::openFolder);
		col(x, 2, y, "Back", null, true, () -> show(Page.MAIN));
		messageY = y + BUTTON_H + PAD + 8;
	}

	private void initImport() {
		boolean running = wasRunning;
		List<String> packs = ModelPack.list();
		// The model from before the last import goes first, as the way back.
		if (packs.remove(ModelPack.BEFORE_IMPORT)) {
			packs.add(0, ModelPack.BEFORE_IMPORT);
		}
		int x = (width - CONTENT_W) / 2;
		List<String> hint = wrap(running ? "Stop SwordAI first: the model can't be swapped while it runs."
				: "Click a model to load it. The one it replaces is saved first, so it can be undone.");
		hint.addAll(wrap("Or drop a .zip from anywhere onto this window."));
		int hintH = hint.size() * 10 + 6;
		int fixed = HEAD + hintH + BUTTON_H + PAD + SECTION_GAP + BUTTON_H + 30;
		int rows = Math.max(2, Math.min(6, (height - fixed - 2 * PAD) / (BUTTON_H + 2)));
		int pages = Math.max(1, (packs.size() + rows - 1) / rows);
		importPage = Math.max(0, Math.min(importPage, pages - 1));
		int listH = Math.max(1, Math.min(rows, packs.size())) * (BUTTON_H + 2);
		int h = HEAD + hintH + listH + (pages > 1 ? BUTTON_H + 2 : 0);
		int y = Math.max(PAD + 2, (height - h - BUTTON_H - 30) / 2);
		panels.add(new Panel(x - PAD, y - PAD, CONTENT_W + 2 * PAD, h + 2 * PAD, null));
		labels.add(new Label("Import model", x, y, GOLD, false));
		y += HEAD;
		for (int i = 0; i < hint.size(); i++) {
			boolean drop = i == hint.size() - 1;
			labels.add(new Label(hint.get(i), x, y, running && !drop ? RED : drop ? DIM : GREY, false));
			y += 10;
		}
		y += 6;
		statusX = x;
		statusY = -1;
		if (packs.isEmpty()) {
			labels.add(new Label("No saved models yet. Export one, or drop a .zip here.", x + CONTENT_W / 2, y + 6, WHITE, true));
		}
		for (int i = importPage * rows; i < Math.min(packs.size(), (importPage + 1) * rows); i++) {
			String name = packs.get(i);
			boolean undo = name.equals(ModelPack.BEFORE_IMPORT);
			Component label = Component.literal(undo ? "Undo the last import" : name)
					.append(Component.literal("   " + ModelPack.details(ModelPack.file(name))).withStyle(ChatFormatting.GRAY));
			Button row = addRenderableWidget(Button.builder(label, b -> confirmImport(name))
					.tooltip(Tooltip.create(Component.literal(running ? "Stop SwordAI first."
							: undo ? "Puts back the model that the last import replaced (" + name + ".zip)."
							: "Loads " + name + ".zip as the model (it asks first).")))
					.bounds(x, y, CONTENT_W, BUTTON_H).build());
			row.active = !running;
			y += BUTTON_H + 2;
		}
		if (packs.isEmpty()) {
			y += BUTTON_H + 2;
		}
		if (pages > 1) {
			int third = (CONTENT_W - 2 * GAP) / 3;
			Button prev = addRenderableWidget(Button.builder(Component.literal("< Newer"), b -> {
				importPage--;
				rebuildWidgets();
			}).bounds(x, y, third, BUTTON_H).build());
			prev.active = importPage > 0;
			labels.add(new Label((importPage + 1) + " / " + pages, x + CONTENT_W / 2, y + 6, GREY, true));
			Button next = addRenderableWidget(Button.builder(Component.literal("Older >"), b -> {
				importPage++;
				rebuildWidgets();
			}).bounds(x + CONTENT_W - third, y, third, BUTTON_H).build());
			next.active = importPage < pages - 1;
			y += BUTTON_H + 2;
		}
		y += PAD + SECTION_GAP;
		int half = (CONTENT_W - GAP) / 2;
		addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> openFolder())
				.tooltip(Tooltip.create(Component.literal("Opens swordai/models.")))
				.bounds(x, y, half, BUTTON_H).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> show(Page.MAIN))
				.bounds(x + half + GAP, y, half, BUTTON_H).build());
		messageY = y + BUTTON_H + 8;
	}

	/** A panel with a heading; returns where its buttons go. */
	private int section(int x, int y, String heading) {
		panels.add(new Panel(x - PAD, y - PAD, CONTENT_W + 2 * PAD, HEAD + BUTTON_H + 2 * PAD, heading));
		return y + HEAD;
	}

	/** A button in column {@code col} (0 to 2) of a section. */
	private Button col(int x, int col, int y, String text, String tip, boolean active, Runnable action) {
		Button.Builder b = Button.builder(Component.literal(text), button -> action.run())
				.bounds(x + col * (COL_W + GAP), y, COL_W, BUTTON_H);
		if (tip != null) {
			b.tooltip(Tooltip.create(Component.literal(tip)));
		}
		Button button = addRenderableWidget(b.build());
		button.active = active;
		return button;
	}

	/** "-  x2  +": how fast training on the screen runs the game. */
	private void speedControl(int x, int y, float speed, boolean active) {
		Tooltip tip = Tooltip.create(Component.literal("How fast training on the screen runs the game, x1 to x8. Headless training goes as fast as it can."));
		Button minus = addRenderableWidget(Button.builder(Component.literal("-"), b -> changeSpeed(-1f)).tooltip(tip)
				.bounds(x, y, BUTTON_H, BUTTON_H).build());
		Button plus = addRenderableWidget(Button.builder(Component.literal("+"), b -> changeSpeed(1f)).tooltip(tip)
				.bounds(x + COL_W - BUTTON_H, y, BUTTON_H, BUTTON_H).build());
		minus.active = active && speed > 1f;
		plus.active = active && speed < 8f;
		labels.add(new Label(String.format(Locale.ROOT, "Speed x%.0f", speed), x + COL_W / 2, y + 6, active ? WHITE : DIM, true));
	}

	private List<String> wrap(String text) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && font.width(line + " " + word) > CONTENT_W) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0) {
				line.append(' ');
			}
			line.append(word);
		}
		if (line.length() > 0) {
			lines.add(line.toString());
		}
		return lines;
	}

	// ---- drawing --------------------------------------------------------------------------------

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(g, mouseX, mouseY, partialTick);
		for (Panel p : panels) {
			g.fill(p.x(), p.y(), p.x() + p.w(), p.y() + p.h(), PANEL);
			g.renderOutline(p.x(), p.y(), p.w(), p.h(), EDGE);
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		for (Panel p : panels) {
			if (p.heading() != null) {
				g.drawString(font, p.heading(), p.x() + PAD, p.y() + PAD, GOLD);
			}
		}
		for (Label l : labels) {
			if (l.centred()) {
				g.drawCenteredString(font, l.text(), l.x(), l.y(), l.color());
			} else {
				g.drawString(font, l.text(), l.x(), l.y(), l.color());
			}
		}
		if (page == Page.MAIN) {
			drawStatus(g);
		} else if (page == Page.EXPORT && nameBox != null) {
			boolean taken = Files.exists(ModelPack.file(nameBox.getValue()));
			g.drawString(font, taken ? "A model by this name is there already: it will be replaced."
					: "Saved as swordai/models/" + ModelPack.clean(nameBox.getValue()) + ".zip", statusX, statusY, taken ? GOLD : DIM);
		}
		if (!message.isEmpty()) {
			int y = Math.min(messageY, height - 12);
			g.drawCenteredString(font, fit(message, width - 16), width / 2, y, messageColor);
		}
	}

	/** The two lines under the title: what is running, and the model. */
	private void drawStatus(GuiGraphics g) {
		AgentSession s = SwordAiClient.session();
		boolean running = SwordAiClient.running() && s != null;
		String state;
		if (running) {
			state = "Running: " + s.describe();
		} else {
			state = "Idle.  Menu: " + keyName(SwordAiClient.menuKey()) + "   Fight on/off: " + keyName(SwordAiClient.fightKey());
		}
		g.drawString(font, fit(state, CONTENT_W), statusX, statusY, running ? GREEN : GREY);
		g.drawString(font, fit(modelText, CONTENT_W), statusX, statusY + 11, WHITE);
	}

	private static String keyName(KeyMapping key) {
		return key == null || key.isUnbound() ? "no key (Keybinds...)" : key.getTranslatedKeyMessage().getString();
	}

	/** "Model: trained 18 Oct 16:49, stage 10 (Crits), 2.1 MB". */
	private static String modelLine(Config cfg) {
		Path ckpt = SwordFiles.checkpoint();
		if (!Files.exists(ckpt)) {
			return "Model: none yet. Train one, or import one.";
		}
		StringBuilder line = new StringBuilder("Model: trained ").append(ModelPack.details(ckpt));
		try (InputStream in = Files.newInputStream(SwordFiles.curriculum())) {
			Properties p = new Properties();
			p.load(in);
			int stage = Integer.parseInt(p.getProperty("stage", "-1"));
			if (stage >= 0) {
				line.append(", stage ").append(stage + 1);
				if (cfg.stages != null && stage < cfg.stages.size()) {
					line.append(" (").append(cfg.stages.get(stage).name).append(')');
				}
			}
		} catch (IOException | RuntimeException e) {
			// No curriculum file, or not one that can be read: the stage is left out.
		}
		return line.toString();
	}

	private String fit(String text, int w) {
		return font.width(text) <= w ? text : font.plainSubstrByWidth(text, w - font.width("...")) + "...";
	}

	@Override
	public void tick() {
		super.tick();
		// Something started or stopped (by a key, a command, or by itself): the buttons follow.
		if (page != Page.EXPORT && SwordAiClient.running() != wasRunning) {
			rebuildWidgets();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---- actions --------------------------------------------------------------------------------

	private void show(Page next) {
		page = next;
		rebuildWidgets();
	}

	private void say(String text, int color) {
		message = text;
		messageColor = color;
	}

	/** Runs a {@code /swordai} command as if typed, with the menu closed so that the game has the keys again. */
	private void run(String command) {
		Minecraft mc = minecraft;
		onClose();
		if (mc.player != null) {
			mc.player.connection.sendCommand(command);
		}
	}

	private void changeSpeed(float by) {
		float next = Math.max(1f, Math.min(8f, Math.round(SwordFiles.loadConfig().trainSpeed) + by));
		if (minecraft.player != null) {
			minecraft.player.connection.sendCommand(String.format(Locale.ROOT, "swordai speed %.0f", next));
		}
		rebuildWidgets();
	}

	private void export() {
		String name = ModelPack.clean(nameBox == null ? ModelPack.defaultName() : nameBox.getValue());
		try {
			Path out = ModelPack.export(name);
			say(String.format(Locale.ROOT, "Exported %s (%.1f MB) to swordai/models.", out.getFileName(), Files.size(out) / 1048576.0), GREEN);
			exportName = null;
			show(Page.MAIN);
		} catch (IOException e) {
			say("Could not export: " + e.getMessage(), RED);
		}
	}

	private void confirmImport(String name) {
		boolean undo = name.equals(ModelPack.BEFORE_IMPORT);
		Component title = Component.literal(undo ? "Undo the last import?" : "Import " + name + "?");
		Component body = Component.literal(undo ? "This puts back the model the last import replaced."
				: "This replaces the current model. The current one is saved first, and \"Undo the last import\" brings it back.");
		minecraft.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				importPack(name);
			}
			minecraft.setScreen(this);
		}, title, body));
	}

	private void importPack(String name) {
		if (SwordAiClient.running()) {
			say("Stop SwordAI first: the model can't be swapped while it is running.", RED);
			return;
		}
		try {
			int n = ModelPack.importPack(name);
			say("Imported " + name + " (" + n + " files). It is the model now.", GREEN);
			page = Page.MAIN;
		} catch (IOException e) {
			say("Could not import: " + e.getMessage(), RED);
		}
	}

	/** Zip files dropped on the window from anywhere are added to the saved models, to be imported from the list. */
	@Override
	public void onFilesDrop(List<Path> files) {
		List<String> added = new ArrayList<>();
		String problem = null;
		for (Path f : files) {
			try {
				added.add(ModelPack.add(f));
			} catch (IOException | RuntimeException e) {
				problem = e.getMessage();
			}
		}
		if (!added.isEmpty()) {
			say("Added " + String.join(", ", added) + ". Click it to import." + (problem == null ? "" : " (" + problem + ")"), GREEN);
			importPage = 0;
			show(Page.IMPORT);
		} else if (problem != null) {
			say(problem, RED);
		}
	}

	private void openFolder() {
		try {
			Files.createDirectories(ModelPack.dir());
			net.minecraft.util.Util.getPlatform().openPath(ModelPack.dir());
		} catch (IOException e) {
			say("Could not open the folder: " + e.getMessage(), RED);
		}
	}
}
