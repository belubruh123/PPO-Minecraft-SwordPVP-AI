ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


C = 'src/client/java/dev/swordai/client/Commands.java'
edit(C, [
    ('''				.then(literal("undo").executes(c -> undo(c.getSource())))''', '''				.then(literal("undo").executes(c -> undo(c.getSource())))
				.then(literal("menu").executes(c -> menu(c.getSource())))
				.then(literal("models").executes(c -> models(c.getSource())))
				.then(literal("export")
						.executes(c -> exportModel(c.getSource(), null))
						.then(argument("name", StringArgumentType.word())
								.executes(c -> exportModel(c.getSource(), StringArgumentType.getString(c, "name")))))
				.then(literal("import")
						.executes(c -> models(c.getSource()))
						.then(argument("name", StringArgumentType.word())
								.suggests((c, b) -> {
									ModelPack.list().forEach(b::suggest);
									return b.buildFuture();
								})
								.executes(c -> importModel(c.getSource(), StringArgumentType.getString(c, "name")))))''', 1),
    ('''	private static int help(FabricClientCommandSource source) {
''', '''	private static int help(FabricClientCommandSource source) {
		say(source, "/swordai menu - all of this as buttons (also on Right Shift; change the key in Controls)");
		say(source, "/swordai export [name] | import <name> | models - the trained model as one .zip in swordai/models, to keep or take to another game");
''', 1),
    ('''	private static void say(FabricClientCommandSource source, String message) {''', '''	private static int menu(FabricClientCommandSource source) {
		Minecraft mc = source.getClient();
		// After the chat screen the command was typed in has closed.
		mc.execute(() -> mc.setScreen(new SwordAiScreen()));
		return 1;
	}

	private static int models(FabricClientCommandSource source) {
		java.util.List<String> packs = ModelPack.list();
		if (packs.isEmpty()) {
			say(source, "No models in " + ModelPack.dir() + " yet. /swordai export saves the current one there; a .zip from another game goes there too.");
		} else {
			say(source, "Models in " + ModelPack.dir() + ", newest first: " + String.join(", ", packs) + ". /swordai import <name> makes one the current model.");
		}
		return 1;
	}

	private static int exportModel(FabricClientCommandSource source, String name) {
		try {
			Path out = ModelPack.export(name == null ? ModelPack.defaultName() : name);
			say(source, String.format(java.util.Locale.ROOT, "Saved the current model to %s (%.1f MB). To use it in another game, put that file in its "
					+ "swordai/models folder and /swordai import %s there.", out, Files.size(out) / 1048576.0, ModelPack.clean(out.getFileName().toString())));
			return 1;
		} catch (IOException e) {
			source.sendError(Component.literal("Could not export the model: " + e.getMessage()));
			return 0;
		}
	}

	private static int importModel(FabricClientCommandSource source, String name) {
		if (SwordAiClient.running()) {
			source.sendError(Component.literal("Stop it first (/swordai stop): the model cannot be swapped while it is running."));
			return 0;
		}
		try {
			int files = ModelPack.importPack(name);
			say(source, "Imported " + ModelPack.clean(name) + " (" + files + " files): it is the current model now. What it replaced was saved as "
					+ ModelPack.BEFORE_IMPORT + ": /swordai import " + ModelPack.BEFORE_IMPORT + " takes it back.");
			return 1;
		} catch (IOException e) {
			source.sendError(Component.literal("Could not import " + name + ": " + e.getMessage()));
			return 0;
		}
	}

	private static void say(FabricClientCommandSource source, String message) {''', 1),
])

S = 'src/client/java/dev/swordai/client/SwordAiClient.java'
edit(S, [
    ('''import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
''', '''import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
''', 1),
    ('''	private static volatile float tickMillis;
''', '''	private static volatile float tickMillis;
	/** Opens the SwordAI menu; see {@link SwordAiScreen}. Right Shift unless changed in Controls. */
	private static KeyMapping menuKey;
''', 1),
    ('''		ClientLifecycleEvents.CLIENT_STARTED.register(OptionsGuard::recoverAfterCrash);''', '''		ClientLifecycleEvents.CLIENT_STARTED.register(OptionsGuard::recoverAfterCrash);
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.swordai.menu", InputConstants.Type.KEYSYM,
				org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));''', 1),
    ('''			readInbox(mc);
		});''', '''			readInbox(mc);
			while (menuKey.consumeClick()) {
				if (mc.screen == null && mc.player != null) {
					mc.setScreen(new SwordAiScreen());
				}
			}
		});''', 1),
])
print('menu ok')
