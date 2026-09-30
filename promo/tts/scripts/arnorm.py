"""Arabic normalisation + CER used for every ASR check (same rules for all engines)."""
import re
import unicodedata

DIAC = re.compile(r'[ؐ-ًؚ-ٰٟۖ-ۭـ]')  # harakat, shadda, sukun, dagger alef, tatweel
PUNCT = re.compile(r'[^\w\s]|_', re.UNICODE)
LATIN_DIGIT = re.compile(r'[A-Za-z0-9]')
SPELLING_VARIANTS = [('جوجل', 'غوغل'), ('قوقل', 'غوغل'), ('جوغل', 'غوغل'), ('غوجل', 'غوغل'),
                     ('تيفي', 'تي في'), ('يوسبي', 'يو اس بي'), ('ايبي', 'اي بي'), ('جيت', 'غيت'), ('كيوار', 'كيو ار')]


def normalize(t: str) -> str:
    t = unicodedata.normalize('NFKC', t)
    t = DIAC.sub('', t)
    t = re.sub('[إأآٱ]', 'ا', t)
    t = t.replace('ى', 'ي').replace('ة', 'ه')
    t = t.replace('ؤ', 'و').replace('ئ', 'ي')
    t = PUNCT.sub(' ', t)
    t = re.sub(r'\s+', ' ', t).strip()
    # spelling variants of loan-words that ASR writes differently (not pronunciation errors)
    for a, b in SPELLING_VARIANTS:
        t = re.sub(rf'(?<!\S){a}(?!\S)', b, t)
    return t


# ASR writes spoken numbers as digits; map the few numbers used in our scripts back to words
NUM_WORDS = {'1080': 'الف وثمانين', '60': 'ستين', '15': 'خمس عشره', '10': 'عشر', '9': 'تسع', '8': 'ثماني', '7': 'سبع',
             '6': 'ست', '5': 'خمس', '4': 'اربع', '3': 'ثلاث', '2': 'اثنين', '1': 'واحد'}


# Latin words/acronyms the ASR sometimes writes in Latin script although they were spoken in Arabic
# (the script deliberately pronounces them: "آر إس إس", "كيو آر" ...). Mapped back before scoring.
LATIN_WORDS = {
    'rss': 'ار اس اس', 'qr': 'كيو ار', 'usb': 'يو اس بي', 'ip': 'اي بي', 'adb': 'ايه دي بي', 'tcl': 'تي سي ال',
    'tv': 'تي في', 'google': 'غوغل', 'github': 'غيت هب', 'git': 'غيت', 'hub': 'هب', 'youtube': 'يوتيوب',
    'sponsorblock': 'سبونسر بلوك', 'sponsor': 'سبونسر', 'block': 'بلوك', 'takeout': 'تيك اوت', 'take': 'تيك',
    'out': 'اوت', 'android': 'اندرويد', 'setup': 'ست اب', 'set': 'ست', 'up': 'اب', 'windows': 'ويندوز',
    'pin': 'بن', 'os': 'او اس', 'build': 'بيلد', 'mac': 'ماك', 'enter': 'انتر', 'ok': 'اوكي', 'shasha': 'شاشا',
    'ethernet': 'ايثرنت', 'wifi': 'واي فاي', 'wi': 'واي', 'fi': 'فاي', 'address': 'ادرس',
}


def latin_to_arabic(t: str) -> str:
    return re.sub(r'[A-Za-z]+', lambda m: LATIN_WORDS.get(m.group(0).lower(), m.group(0)), t)


def spell_numbers(t: str) -> str:
    t = latin_to_arabic(t)
    t = t.translate(str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789'))
    return re.sub(r'\d+', lambda m: NUM_WORDS.get(m.group(0), m.group(0)), t)


def cer(ref: str, hyp: str) -> float:
    import jiwer
    r, h = normalize(ref), normalize(spell_numbers(hyp))
    if not r:
        return 0.0
    return jiwer.cer(r, h)


def wer(ref: str, hyp: str) -> float:
    import jiwer
    return jiwer.wer(normalize(ref), normalize(spell_numbers(hyp)))
