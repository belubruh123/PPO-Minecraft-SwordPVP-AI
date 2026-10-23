ROOT = 'D:/swordai/'


def edit(path, pairs, root=ROOT):
    full = root + path
    s = open(full, encoding='utf-8').read()
    for a, b, count in pairs:
        assert s.count(a) == count, (path, a[:80], s.count(a))
        s = s.replace(a, b)
    open(full, 'w', encoding='utf-8', newline='').write(s)


S = 'src/client/java/dev/swordai/client/SwordAiClient.java'
edit(S, [
    ('''	/** Opens the SwordAI menu; see {@link SwordAiScreen}. Right Shift unless changed in Controls. */
	private static KeyMapping menuKey;
''', '''	/** Opens the SwordAI menu; see {@link SwordAiScreen}. Right Shift unless changed in Controls. */
	private static KeyMapping menuKey;
	/** Starts {@code /swordai fight} against the nearest player, or stops whatever is running. No key until one is given in Controls. */
	private static KeyMapping fightKey;
''', 1),
    ('''				org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));''', '''				org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT, KeyMapping.Category.MISC));
		fightKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.swordai.fight", InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));''', 1),
    ('''			while (menuKey.consumeClick()) {
				if (mc.screen == null && mc.player != null) {
					mc.setScreen(new SwordAiScreen());
				}
			}''', '''			while (menuKey.consumeClick()) {
				if (mc.screen == null && mc.player != null) {
					mc.setScreen(new SwordAiScreen());
				}
			}
			while (fightKey.consumeClick()) {
				if (mc.screen == null && mc.player != null) {
					mc.player.connection.sendCommand(running() ? "swordai stop" : "swordai fight");
				}
			}''', 1),
])
import json
p = ROOT + 'src/main/resources/assets/swordai/lang/en_us.json'
d = json.load(open(p, encoding='utf-8'))
d['key.swordai.fight'] = 'SwordAI: start / stop fight'
d['key.swordai.menu'] = 'SwordAI: open the menu'
open(p, 'w', encoding='utf-8', newline='\n').write(json.dumps(d, indent=2) + '\n')
print('keys ok')
