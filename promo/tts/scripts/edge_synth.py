"""Thin wrapper around the `edge-tts` package (Microsoft Edge "Read aloud" neural voices).

Synthesises one text to an MP3 (24 kHz mono, the service's fixed format) and returns the engine's own
WordBoundary events (offset/duration in seconds from the start of that MP3), which give exact word timings.
TLS: verification stays ON; when the machine sits behind an intercepting proxy, point EDGE_TTS_CA_FILE
(or SSL_CERT_FILE) at the proxy's CA bundle.

  python edge_synth.py --voice ar-SA-HamedNeural --rate=-6% --text "..." --out x.mp3 [--json x.json]
"""
import argparse, asyncio, json, os, ssl, sys
from pathlib import Path

import edge_tts
import edge_tts.communicate as _comm

_ca = os.environ.get('EDGE_TTS_CA_FILE') or os.environ.get('SSL_CERT_FILE')
if _ca and Path(_ca).exists():
    _comm._SSL_CTX = ssl.create_default_context(cafile=_ca)


async def synth(text: str, out: str, voice: str, rate: str = '+0%', pitch: str = '+0Hz', volume: str = '+0%',
                retries: int = 4):
    last = None
    for attempt in range(retries):
        try:
            com = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch, volume=volume, boundary='WordBoundary')
            words, audio = [], bytearray()
            async for chunk in com.stream():
                if chunk['type'] == 'audio':
                    audio.extend(chunk['data'])
                elif chunk['type'] in ('WordBoundary', 'SentenceBoundary'):
                    words.append({'type': chunk['type'], 'text': chunk['text'],
                                  'start': chunk['offset'] / 1e7, 'dur': chunk['duration'] / 1e7})
            if not audio:
                raise RuntimeError('no audio received')
            Path(out).write_bytes(bytes(audio))
            return words
        except Exception as e:  # network hiccups: retry with a short back-off
            last = e
            await asyncio.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f'edge-tts failed after {retries} tries: {last}')


def synth_sync(*a, **k):
    return asyncio.run(synth(*a, **k))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--voice', default='ar-SA-HamedNeural')
    ap.add_argument('--rate', default='+0%')
    ap.add_argument('--pitch', default='+0Hz')
    ap.add_argument('--text', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--json')
    a = ap.parse_args()
    w = synth_sync(a.text, a.out, a.voice, a.rate, a.pitch)
    if a.json:
        Path(a.json).write_text(json.dumps(w, ensure_ascii=False, indent=1))
    print(json.dumps(w, ensure_ascii=False))
