"""Beat frames outside the renderer (same lookup as src/lib/timeline.ts beatsFor): used to pick still frames.

  ../.venv/bin/python tools/beats.py s14_devmode 'واضغط الزر' ...     -> absolute + scene-relative frames
  from beats import Beats; b = Beats('s14_devmode'); b.at('واضغط الزر')   (scene-relative frame)
"""
import json, re, sys, unicodedata
from pathlib import Path

RM = Path(__file__).resolve().parents[1]
TL = json.loads((RM / 'src/data/timeline.json').read_text())
WORDS = json.loads((RM / 'src/data/words.json').read_text())
FPS = 30
DIAC = re.compile(r'[ؐ-ًؚ-ٰٟۖ-ۭـ]')


def norm(w: str) -> str:
    w = unicodedata.normalize('NFKC', w)
    w = DIAC.sub('', w)
    w = re.sub('[إأآٱ]', 'ا', w).replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
    return re.sub(r'[^\w]', '', w).lower()


class Beats:
    def __init__(self, sid):
        self.scene = next(s for s in TL['scenes'] if s['id'] == sid)
        self.words = WORDS[sid]['words']
        self.nw = [norm(w['w']) for w in self.words]
        self.start = self.scene['start_frame']
        self.duration = self.scene['duration_frames']

    def _find(self, phrase, occ=0):
        toks = [norm(t) for t in phrase.split() if norm(t)]
        c = 0
        for i in range(len(self.nw) - len(toks) + 1):
            if self.nw[i:i + len(toks)] == toks:
                if c == occ:
                    return i, len(toks)
                c += 1
        raise KeyError(f'{phrase} not in {self.scene["id"]}')

    def at(self, phrase, occ=0):
        i, _ = self._find(phrase, occ)
        return round(self.words[i]['s'] * FPS)

    def end(self, phrase, occ=0):
        i, n = self._find(phrase, occ)
        return round(self.words[i + n - 1]['e'] * FPS)


if __name__ == '__main__':
    b = Beats(sys.argv[1])
    for p in sys.argv[2:]:
        r = b.at(p)
        print(f'{p}: rel {r}  abs {b.start + r}')
    print('scene start', b.start, 'duration', b.duration)
