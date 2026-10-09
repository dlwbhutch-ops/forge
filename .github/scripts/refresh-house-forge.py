#!/usr/bin/env python3
"""Refresh matching Forge mechanics and resources from the reviewed, pinned revision.

The UI update checker is read-only. Advance this pin and run the HOUSE regression
checks before shipping another app; do not mix arbitrary new scripts with an old engine.
"""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
METADATA = ROOT / 'forge-gui-android/assets/house-card-data.json'
pin = json.loads(METADATA.read_text())['sourceCommit']
if len(pin) != 40 or any(c not in '0123456789abcdef' for c in pin):
    raise ValueError('Invalid pinned Forge commit')
modules = ['forge-core', 'forge-game', 'forge-ai']
directories = [m + '/src/main/java' for m in modules]
directories += ['forge-gui/res/' + d for d in [
    'cardsfolder', 'tokenscripts', 'editions', 'lists', 'ai', 'blockdata',
    'setlookup', 'formats', 'languages']]
with tempfile.TemporaryDirectory(prefix='house-forge-source-') as temporary:
    checkout = Path(temporary)
    def git(*args):
        subprocess.run(['git', '-C', str(checkout), *args], check=True)
    git('init', '-q')
    git('remote', 'add', 'origin', 'https://github.com/Card-Forge/forge.git')
    git('config', 'remote.origin.promisor', 'true')
    git('config', 'remote.origin.partialclonefilter', 'blob:none')
    git('fetch', '--depth=1', '--filter=blob:none', 'origin', pin)
    git('sparse-checkout', 'init', '--cone')
    git('sparse-checkout', 'set', *directories)
    git('checkout', '--detach', pin)
    for name in directories:
        destination = ROOT / name
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(checkout / name, destination)

# Keep binary compatibility with HOUSE clients built against earlier Forge.
game = ROOT / 'forge-game/src/main/java/forge/game/Game.java'
text = game.read_text()
anchor = '    public int AI_TIMEOUT = 5;'
assert text.count(anchor) == 1
text = text.replace(anchor, anchor + '\n    public boolean AI_CAN_USE_TIMEOUT = true;\n'
                    '    public boolean canUseTimeout() { return AI_CAN_USE_TIMEOUT; }')
game.write_text(text)

# Preserve HOUSE's single-game-thread forced attack evaluation and cancellation.
attack = ROOT / 'forge-ai/src/main/java/forge/ai/AiAttackController.java'
text = attack.read_text()
start = text.index('            ExecutorService executor = Executors.newFixedThreadPool(',
                   text.index('// Attackers that don\'t really have a choice'))
end = text.index('            List<Callable<Integer>> tasks = new ArrayList<>();', start)
text = text[:start] + '            // HOUSE: mutate live combat only on the game thread.\n' + text[end:]
start = text.index('            try {\n                executor.invokeAll(tasks,')
end = text.index('\n            if (attackersLeft.isEmpty())', start)
text = text[:start] + '''            for (Callable<Integer> task : tasks) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Attack selection cancelled");
                try { task.call(); }
                catch (RuntimeException error) { throw error; }
                catch (Exception error) { throw new IllegalStateException("Attack requirement evaluation failed", error); }
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Attack selection cancelled");
            }
''' + text[end:]
attack.write_text(text)

empower = ROOT / 'forge-game/src/main/java/forge/game/ability/effects/EmpowerEffect.java'
text = empower.read_text()
assert text.count('"+1/+1 counter"') == 1
empower.write_text(text.replace('"+1/+1 counter"', '"loyalty counter"'))

# Existing platform renderers still call these binary interfaces.
card = ROOT / 'forge-game/src/main/java/forge/game/card/Card.java'
text = card.read_text()
anchor = '    // shield = regeneration'
assert text.count(anchor) == 1
text = text.replace(anchor, '''    public final FCollectionView<SpellAbility> getBasicSpells() {
        return getBasicSpells(currentState);
    }
    public final FCollectionView<SpellAbility> getBasicSpells(CardState state) {
        final FCollection<SpellAbility> result = new FCollection<>();
        for (final SpellAbility ability : state.getNonManaAbilities()) {
            if (ability.isSpell() && ability.isBasicSpell()) result.add(ability);
        }
        return result;
    }
''' + anchor)
card.write_text(text)
view = ROOT / 'forge-game/src/main/java/forge/game/card/CardView.java'
text = view.read_text()
anchor = '    public int getDamage() {'
assert text.count(anchor) == 1
text = text.replace(anchor, '''    public int getCrackOverlayInt() {
        int damage = getDamage();
        return damage <= 2 ? 0 : damage <= 4 ? 1 : damage <= 6 ? 2 : 3;
    }
    public boolean hasBackSide() {
        return hasAlternateState() && getAlternateState().getState() == CardStateName.Backside;
    }
''' + anchor)
view.write_text(text)
# HOUSE: static continuous effects must enumerate live players without asking for
# turn direction. getPlayersInTurnOrder() invokes TurnReversed abilities, which
# calls Card.getStaticAbilities(), reentering this exact continuous calculation.
continuous = ROOT / 'forge-game/src/main/java/forge/game/staticability/StaticAbilityContinuous.java'
text = continuous.read_text()
old = '        for (Player p : controller.getGame().getPlayersInTurnOrder()) {'
assert text.count(old) == 1, 'Unexpected Forge continuous affected-player selector'
text = text.replace(old, '''        // The affected set does not depend on turn direction. Avoid recursion
        // through TurnReversed -> Card.getStaticAbilities -> continuous layers.
        for (Player p : controller.getGame().getPlayers()) {''')
continuous.write_text(text)

print('HOUSE_FORGE_REFRESH_PASS ' + pin)
