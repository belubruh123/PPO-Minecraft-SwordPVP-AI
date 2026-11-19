package dev.swordai.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compact menu screen (Right Shift or {@code /swordai menu}) triggering the core commands. */
final class SwordAiScreen extends Screen {
	SwordAiScreen() {
		super(Component.literal("SwordAI"));
	}

	@Override
	protected void init() {
		boolean running = SwordAiClient.running();
		int cx = width / 2;
		int y = height / 2 - 50;
		addBtn(cx - 105, y, 100, "Fight nearest", !running, () -> cmd("swordai fight"));
		addBtn(cx + 5, y, 100, "Stop", running, () -> cmd("swordai stop"));
		y += 26;
		addBtn(cx - 105, y, 100, "Train league", !running, () -> cmd("swordai school"));
		addBtn(cx + 5, y, 100, "200-pair check", !running, () -> cmd("swordai school check"));
		y += 26;
		addBtn(cx - 105, y, 100, "Export model", true, () -> cmd("swordai export"));
		addBtn(cx + 5, y, 100, "Close", true, this::onClose);
	}

	private void addBtn(int x, int y, int w, String label, boolean active, Runnable action) {
		Button b = Button.builder(Component.literal(label), btn -> action.run()).bounds(x, y, w, 20).build();
		b.active = active;
		addRenderableWidget(b);
	}

	private void cmd(String command) {
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.connection.sendCommand(command);
			onClose();
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
		super.render(g, mouseX, mouseY, partial);
		g.drawCenteredString(font, "SwordAI " + SwordFiles.modVersion(), width / 2, height / 2 - 72, 0xFFFFC857);
	}
}
