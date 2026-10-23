p = 'D:/swordai/core/src/main/java/dev/swordai/core/config/Config.java'
s = open(p, encoding='utf-8').read()
a = '''	 * hit, which it has no swing to answer; see {@code JumpReset}. 0: only ever in a combo.
	 */
	public float jumpResetCounterChance = 1f;'''
assert s.count(a) == 1
s = s.replace(a, '''	 * hit, which it has no swing to answer; see {@code JumpReset}. 0: only ever in a combo.
	 *
	 * <p>Off. Measured with the network not learning: in a fast exchange nearly every hit it takes
	 * comes within those thirteen ticks, so it jumped with nine hits in ten (64 jumps a minute against
	 * the opponent that follows its hits with a running jump, 84 against the one that answers every
	 * hit), which is far more of this than was asked for; it then landed 69 hits to 73 taken against
	 * the first, where it is 55 to 68 without, and no better than before against the second, and
	 * its sweeps went from one hit in twelve to one in six.
	 */
	public float jumpResetCounterChance = 0f;''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('default off')
