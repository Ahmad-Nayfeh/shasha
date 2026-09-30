"""Narration text model shared by the validator, the synthesiser and the word aligner.

narration/scenes.json holds, per scene, three parallel texts with the SAME punctuation skeleton:
  display   what the captions show (Latin names stay Latin: GitHub, QR, USB, OK ...)
  tts       what is spoken, undiacritised (technical names written as pronounced: غيت هب، كيو آر ...)
  tts_diac  `tts` fully diacritised: case endings inside phrases, waqf (sukun) on the last word before every
            pause, hamzat al-wasl as a bare alif, shadda, tanween, numbers as words.

Pauses (inserted as real silence between separately synthesised phrases):
  ، ,  -> PAUSE_COMMA      : ؛ -> PAUSE_COLON      . ؟ ! -> PAUSE_SENTENCE      blank line -> PAUSE_BEAT
"""
import re

PAUSE_COMMA = 0.25
PAUSE_COLON = 0.3
PAUSE_SENTENCE = 0.5
PAUSE_BEAT = 0.9

PUNCT_PAUSE = {'،': PAUSE_COMMA, ',': PAUSE_COMMA, ':': PAUSE_COLON, '؛': PAUSE_COLON,
               '.': PAUSE_SENTENCE, '؟': PAUSE_SENTENCE, '?': PAUSE_SENTENCE, '!': PAUSE_SENTENCE}
CUE_END = set('.؟?!:')           # a caption/timeline cue ends at a sentence end or a colon
CLOSERS = '»)"\''                 # may follow the punctuation mark ("«…»." or "…».")

DIAC = re.compile(r'[ؐ-ًؚ-ٰٟۖ-ۭ]')
ARABIC_LETTER = re.compile(r'[ء-ي]')

# A phrase ends at a pause mark that is followed by whitespace or the end of the text (so "1080p" or
# "Shasha-Setup.zip" never split). Closing quotes/brackets may sit on either side of the mark.
_SPLIT = re.compile(r'([»)"\']*[،,:؛.؟?!][»)"\']*)(?=\s|$)')


def split_phrases(text: str):
    """[(phrase_without_final_mark, mark, pause_s, cue_end)] in order."""
    out = []
    paragraphs = text.split('\n\n')
    for pi, para in enumerate(paragraphs):
        pos = 0
        para = para.strip()
        for m in _SPLIT.finditer(para):
            mark = next(c for c in m.group(1) if c in PUNCT_PAUSE)
            phrase = (para[pos:m.start()] + m.group(1)).strip()
            out.append({'text': phrase, 'mark': mark, 'pause': PUNCT_PAUSE[mark], 'cue_end': mark in CUE_END})
            pos = m.end()
        rest = para[pos:].strip()
        if rest:
            out.append({'text': rest, 'mark': '', 'pause': PAUSE_SENTENCE, 'cue_end': True})
        if pi < len(paragraphs) - 1 and out:
            out[-1]['pause'] = PAUSE_BEAT
            out[-1]['cue_end'] = True
    return out


def words(text: str):
    """Whitespace tokens that contain a letter/digit, with punctuation and quotes stripped."""
    toks = []
    for t in text.split():
        t = re.sub(r'^[«»()"\'،,:؛.؟?!]+|[«»()"\'،,:؛.؟?!]+$', '', t)
        if re.search(r'\w', t):
            toks.append(t)
    return toks


def strip_diac(t: str) -> str:
    return DIAC.sub('', t)


def engine_text(phrase: str) -> str:
    """Exactly what is sent to the voice for one phrase.
    - guillemets/brackets removed (they are for the eye);
    - pausal ta marbuta: the voice (ar-SA-HamedNeural) reads «ةْ» as a released /t/ (measured: identical to «تْ»),
      so the waqf form is written as «هْ», which it reads as the correct soft /h/;
    - shadda is moved in front of the vowel/tanween it carries (letter + ّ + vowel, the conventional typing
      order): with the vowel first the voice stretches the preceding vowel instead of doubling the consonant
      (measured on «مِلَفٌّ»: vowel-first 265 ms vowel + 65 ms /f/ ("milāfun"); shadda-first 170 ms + ~170 ms /ff/);
    - the final punctuation mark is kept: it tells the voice whether the phrase continues (، :) or ends (. ؟)."""
    t = re.sub(r'[«»()"]', '', phrase)
    t = re.sub('([ً-ِ])ّ', 'ّ\\1', t)
    t = t.replace('ةْ', 'هْ')
    return re.sub(r'\s+', ' ', t).strip()


SUKUN, SHADDA = 'ْ', 'ّ'
SHORT = set('َُِ')          # fatha damma kasra
TANWEEN = set('ًٌٍ')


def pausal_ok(word: str) -> bool:
    """True if a phrase-final word is in waqf form: no short vowel or tanween on its last letter."""
    w = word.rstrip('»)"\'')
    if not w:
        return True
    tail = []
    i = len(w) - 1
    while i >= 0 and DIAC.match(w[i]):
        tail.append(w[i])
        i -= 1
    if not ARABIC_LETTER.match(w[i] if i >= 0 else ''):
        return True  # Latin or digits
    if any(c in TANWEEN for c in tail):
        return False
    if any(c in SHORT for c in tail):
        return False
    return True
