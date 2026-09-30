"""Check that every spoken phrase a scene component looks up (b.at / b.end / tryAt / at in Main.tsx) exists in the
current narration word timings.   ../.venv/bin/python tools/check_beats.py
"""
import re, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from beats import Beats  # noqa: E402

RM = Path(__file__).resolve().parents[1]
index = (RM / 'src/scenes/index.ts').read_text()
comp_to_ids = {}
for sid, comp in re.findall(r"(s\d\d_[a-z_]+):\s*(\w+)", index):
    comp_to_ids.setdefault(comp, []).append(sid)
missing, n = [], 0
for path in sorted((RM / 'src/scenes').glob('*.tsx')):
    comp = path.stem
    ids = comp_to_ids.get(comp, [])
    phrases = re.findall(r"(?:\bb|beats|s\d\d)\.(?:at|end)\('([^']+)'", path.read_text()) + re.findall(r"tryAt\('([^']+)'", path.read_text())
    for sid in ids:
        bt = Beats(sid)
        for ph in phrases:
            n += 1
            try:
                bt.at(ph)
            except Exception:
                missing.append(f'{comp} ({sid}): «{ph}»')
main = (RM / 'src/Main.tsx').read_text()
for var, sid in re.findall(r"const (\w+) = sceneById\('(s\d\d_[a-z_]+)'\)", main):
    for ph in re.findall(rf"beatsFor\({var}\)\.(?:at|end)\('([^']+)'", main):
        n += 1
        try:
            Beats(sid).at(ph)
        except Exception:
            missing.append(f'Main.tsx ({sid}): «{ph}»')
print(f'{n} beat lookups checked, {len(missing)} missing')
print('\n'.join(missing))
sys.exit(1 if missing else 0)
