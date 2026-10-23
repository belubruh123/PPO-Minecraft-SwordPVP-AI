# ed_install_defaults.py: 22 Oct, ONLY when T4 passes the long final check: the profile's config regenerates (version 64)
# with the defaults the checked candidate played with: tactics model, opponent reader, keepWithin 2.3, no chase/hop at a
# ready sword. (tacticOnEvents is already true by default.)
import sys
sys.path.insert(0, r'C:/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad')
from edlib import Ed
e = Ed('D:/swordai/core/src/main/java/dev/swordai/core/config/Config.java')
e.rep('	public static final int CURRENT_VERSION = 63;', '	public static final int CURRENT_VERSION = 64;')
e.rep('	public boolean tacticModel = false;', '	public boolean tacticModel = true;')
e.rep('	public boolean opponentReader = false;', '	public boolean opponentReader = true;')
e.rep('	public float keepWithin = 0f;', '	public float keepWithin = 2.3f;')
e.rep('	public boolean jumpAtReadyOnlyFleeing = false;', '	public boolean jumpAtReadyOnlyFleeing = true;')
e.save()
