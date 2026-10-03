"""Validate narration/scenes.json before synthesis.

Checks per scene:
  1. display / tts / tts_diac have the same phrase skeleton (same pause marks in the same order);
  2. tts and tts_diac have the same words (tts_diac with its diacritics removed == tts, after normalisation);
  3. waqf: the last word of every tts_diac phrase carries no short vowel and no tanween;
  4. coverage: Arabic consonants in tts_diac without any mark are listed (review list; long vowels, the
     silent alif of hamzat al-wasl / al-, and the lam of al- before a sun letter are expected to be bare);
  5. no digits and no Latin letters in tts / tts_diac (numbers and names written as spoken), except the TV's English
     label «Android TV OS build», which is sent in Latin so the voice reads it in English (A/B-tested).
Prints a word count and exits 1 on any hard error.
"""
import json, re, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from narr import split_phrases, words, strip_diac, pausal_ok, DIAC, ARABIC_LETTER
from arnorm import normalize, latin_to_arabic

V = Path(__file__).resolve().parents[2]
# python check_text.py [narration/scenes-mobile.json]   (default: the TV script)
scenes = json.loads((V / (sys.argv[1] if len(sys.argv) > 1 else 'narration/scenes.json')).read_text())
SUN = set('تثدذرزسشصضطظلن')
LONG = set('اوىيآ')
errors, review, total_words = [], [], 0


def bare_consonants(word: str):
    """Letters of a diacritised word that carry no mark and are not expected to be bare."""
    out = []
    letters = [(i, ch) for i, ch in enumerate(word) if ARABIC_LETTER.match(ch)]
    for k, (i, ch) in enumerate(letters):
        nxt = word[i + 1] if i + 1 < len(word) else ''
        marked = bool(nxt and DIAC.match(nxt))
        if marked or ch in 'اآىءة' or ch == 'ؤ' and marked:
            continue
        if ch in 'وي':
            prev_marks = word[letters[k - 1][0] + 1:i] if k > 0 else ''
            if any(m in prev_marks for m in ('ُ', 'ِ')) or (k > 0 and letters[k - 1][1] in 'ا'):
                continue  # long vowel (uu/ii) or diphthong glide written plainly
        if ch == 'ل' and k > 0 and letters[k - 1][1] == 'ا' and k + 1 < len(letters) and letters[k + 1][1] in SUN:
            continue  # al- before a sun letter
        if ch == 'ل' and k == 1 and letters[0][1] in 'ا':
            continue
        if ch == 'ل' and k == 1 and letters[0][1] == 'ل' and k + 1 < len(letters) and letters[k + 1][1] in SUN:
            continue  # lil- before a sun letter (لِلشَّاشَةِ)
        out.append(ch)
    return out


for sc in scenes:
    sid = sc['id']
    P = {k: split_phrases(sc[k]) for k in ('display', 'tts', 'tts_diac')}
    marks = {k: [p['mark'] for p in v] for k, v in P.items()}
    if not (marks['display'] == marks['tts'] == marks['tts_diac']):
        errors.append(f'{sid}: phrase skeleton differs\n  display {marks["display"]}\n  tts     {marks["tts"]}\n  diac    {marks["tts_diac"]}')
    for a, b in zip(P['tts'], P['tts_diac']):
        wa, wb = words(a['text']), words(b['text'])
        wbx = words(latin_to_arabic(b['text']))  # English UI labels sent in Latin (read in English by the voice)
        if len(wa) != len(wbx):
            errors.append(f'{sid}: word count {len(wa)} != {len(wb)}: «{a["text"]}» vs «{b["text"]}»')
        if normalize(' '.join(wa)) != normalize(strip_diac(' '.join(wbx))):
            errors.append(f'{sid}: words differ:\n  tts  {normalize(" ".join(wa))}\n  diac {normalize(strip_diac(" ".join(wbx)))}')
        if wb and not pausal_ok(wb[-1]):
            errors.append(f'{sid}: not in waqf form before a pause: «{wb[-1]}» in «{b["text"]}»')
        for w in wb:
            bc = bare_consonants(w)
            if bc:
                review.append(f'{sid}: {w}  bare: {"".join(bc)}')
    for k in ('tts', 'tts_diac'):
        latin_ok = re.sub(r'Android TV OS build', '', sc[k]) if k == 'tts_diac' else sc[k]  # the one English label read in English
        if re.search(r'[0-9A-Za-z٠-٩]', latin_ok):
            errors.append(f'{sid}: digits or Latin letters in {k}')
    total_words += len(words(sc['tts']))

print(f'{len(scenes)} scenes, {total_words} spoken words')
if review:
    print('\nreview (letters without a mark):')
    print('\n'.join('  ' + r for r in review))
if errors:
    print('\nERRORS:')
    print('\n'.join('  ' + e for e in errors))
    sys.exit(1)
print('\nOK: skeletons match, words match, every pause is preceded by a waqf form')
