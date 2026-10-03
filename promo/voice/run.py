#!/usr/bin/env python3
"""Runs ElevenLabs voice jobs on GitHub Actions, where the API key lives (repository secret ELEVEN_LABS).

The key is read from the environment and never printed. Every result (audio, JSON) is written under the
output folder, which the workflow uploads as an artifact.

    python3 promo/voice/run.py promo/voice/request.json out/

request.json: {"id": "...", "workers": 3, "budget_seconds": 1500, "jobs": [job, ...]}. Job types:
  - {"type": "samples", "text": "...", "filters": {...shared-voices query...}, "count": 8,
     "exclude": ["voice_id", ...], "model_id": "eleven_multilingual_v2",
     "prefix": "en-male-", "fallback_filters": [{...}, ...]}
    Picks the most used matching voices of the public library, saves each one's official preview and a
    sample of `text` read by it, plus <prefix>samples.json with the voices' details. `prefix` keeps several
    samples jobs from overwriting each other's files. `fallback_filters` are tried, in order, only while fewer
    than `count` voices were found (a filter value the library does not know would otherwise return nothing).
  - {"type": "tts", "voice_id": "...", "model_id": "...", "text": "...", "out": "name.mp3",
     "voice_settings": {...}, "timestamps": true, "previous_text": "...", "next_text": "..."}
    With "timestamps" the character alignment is saved next to the audio as name.timing.json.
    previous_text / next_text (also seed, apply_text_normalization) are passed on as they are: they give a
    phrase synthesised on its own the prosody of its neighbours.
  - {"type": "subscription", "out": "subscription.json"}

Consecutive "tts" jobs run on `workers` threads (default 1). After `budget_seconds` (default 1500) no new job is
started: the skipped ones are listed in errors.txt, so the artifact is still uploaded and a follow-up request
can redo only those.
"""
import base64
import json
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

API = os.environ.get("ELEVENLABS_API_URL", "https://api.elevenlabs.io")
KEY = os.environ.get("ELEVENLABS_API_KEY", "").strip()
TTS_EXTRA = ("previous_text", "next_text", "seed", "apply_text_normalization", "previous_request_ids",
             "next_request_ids")
LOCK = threading.Lock()


def log(message):
    with LOCK:
        print(message, flush=True)


def call(method, path, body=None, query=None, raw=False):
    url = API + path + ("?" + urllib.parse.urlencode(query, doseq=True) if query else "")
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(4):
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("xi-api-key", KEY)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                payload = r.read()
                return payload if raw else json.loads(payload)
        except urllib.error.HTTPError as e:
            detail = e.read()[:400].decode("utf-8", "replace")
            if e.code in (429, 500, 502, 503) and attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {detail}") from None
        except (urllib.error.URLError, socket.timeout, ConnectionError) as e:
            if attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            raise RuntimeError(f"{method} {path} -> {type(e).__name__}: {e}") from None


def download(url, dest):
    with urllib.request.urlopen(url, timeout=120) as r, open(dest, "wb") as f:
        f.write(r.read())


def tts(voice_id, text, model_id, dest, voice_settings=None, timestamps=False, extra=None):
    body = {"text": text, "model_id": model_id}
    if voice_settings:
        body["voice_settings"] = voice_settings
    for k in TTS_EXTRA:
        if extra and extra.get(k) not in (None, "", []):
            body[k] = extra[k]
    query = {"output_format": "mp3_44100_192"}
    if timestamps:
        res = call("POST", f"/v1/text-to-speech/{voice_id}/with-timestamps", body, query)
        with open(dest, "wb") as f:
            f.write(base64.b64decode(res["audio_base64"]))
        with open(os.path.splitext(dest)[0] + ".timing.json", "w", encoding="utf-8") as f:
            json.dump({k: v for k, v in res.items() if k != "audio_base64"}, f, ensure_ascii=False)
    else:
        with open(dest, "wb") as f:
            f.write(call("POST", f"/v1/text-to-speech/{voice_id}", body, query, raw=True))


def _candidates(filters, exclude, taken):
    """Library voices matching `filters`, most used first (three sort orders are merged)."""
    seen = {}
    for sort in ("usage_character_count_1y", "cloned_by_count", "trending"):
        q = dict(filters, page_size=50, sort=sort)
        try:
            voices = call("GET", "/v1/shared-voices", query=q).get("voices", [])
        except RuntimeError as e:
            log(f"shared-voices {sort} failed: {e}")
            continue
        for v in voices:
            if v["voice_id"] not in seen and v["voice_id"] not in exclude and v["voice_id"] not in taken:
                seen[v["voice_id"]] = v
    return sorted(seen.values(), key=lambda v: -(v.get("usage_character_count_1y") or 0))


def samples(job, out):
    prefix, want = job.get("prefix", ""), job.get("count", 8)
    exclude, picked = set(job.get("exclude", [])), []
    for filters in [job.get("filters", {})] + list(job.get("fallback_filters", [])):
        if len(picked) >= want:
            break
        picked += _candidates(filters, exclude, {v["voice_id"] for v in picked})[: want - len(picked)]
    report = []
    for n, v in enumerate(picked, 1):
        vid = v["voice_id"]
        entry = {k: v.get(k) for k in ("voice_id", "public_owner_id", "name", "accent", "age", "gender",
                                       "descriptive", "use_case", "category", "cloned_by_count",
                                       "usage_character_count_1y", "description")}
        name = f"{prefix}{n:02d}"
        model = job.get("model_id", "eleven_multilingual_v2")
        try:
            if v.get("preview_url"):
                try:
                    download(v["preview_url"], os.path.join(out, f"{name}-preview.mp3"))
                except (OSError, urllib.error.URLError) as e:
                    entry["preview_error"] = str(e)
            try:
                tts(vid, job["text"], model, os.path.join(out, f"{name}-sample.mp3"))
            except RuntimeError as e:
                if "not found" not in str(e).lower() and "404" not in str(e):
                    raise
                # Some library voices must be in "My Voices" before the API can use them.
                added = call("POST", f"/v1/voices/add/{v['public_owner_id']}/{vid}",
                             {"new_name": v.get("name", vid)[:40]})
                entry["added_as"] = added.get("voice_id")
                tts(added["voice_id"], job["text"], model, os.path.join(out, f"{name}-sample.mp3"))
            entry["file"] = name
        except RuntimeError as e:
            entry["error"] = str(e)
        report.append(entry)
        log(f"{name}: {entry.get('name')} -> {'ok' if 'file' in entry else 'error'}")
    if not report:
        raise RuntimeError(f"samples {prefix or 'job'}: the library returned no voice for these filters")
    with open(os.path.join(out, f"{prefix}samples.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)


def run_job(job, out):
    kind = job["type"]
    if kind == "samples":
        samples(job, out)
    elif kind == "tts":
        tts(job["voice_id"], job["text"], job["model_id"], os.path.join(out, job["out"]),
            job.get("voice_settings"), job.get("timestamps", False), job)
        log(f"tts {job['out']}: ok")
    elif kind == "subscription":
        sub = call("GET", "/v1/user/subscription")
        with open(os.path.join(out, job.get("out", "subscription.json")), "w") as f:
            json.dump({k: sub.get(k) for k in ("tier", "character_count", "character_limit",
                                               "next_character_count_reset_unix")}, f)
    else:
        raise RuntimeError(f"unknown job type {kind}")


def main():
    if not KEY:
        sys.exit("ELEVENLABS_API_KEY is empty: add the repository secret ELEVEN_LABS.")
    request, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    with open(request, encoding="utf-8") as f:
        req = json.load(f)
    workers = max(1, int(req.get("workers", 1)))
    budget = float(req.get("budget_seconds", 1500))
    started = time.time()
    failures = [0]

    def guarded(job):
        label = job.get("out") or job.get("prefix") or job["type"]
        try:
            if time.time() - started > budget:
                raise RuntimeError("skipped: the time budget of this run was used up")
            run_job(job, out)
        except Exception as e:  # one failing job never stops the others
            with LOCK:
                failures[0] += 1
                print(f"job {job.get('type')} {label} failed: {e}", flush=True)
                with open(os.path.join(out, "errors.txt"), "a", encoding="utf-8") as f:
                    f.write(f"{job.get('type')} {label}: {e}\n")

    jobs, i = req["jobs"], 0
    while i < len(jobs):
        j = i
        while j < len(jobs) and jobs[j]["type"] == "tts":  # a run of tts jobs goes through the pool
            j += 1
        if j > i:
            with ThreadPoolExecutor(max_workers=workers) as pool:
                list(pool.map(guarded, jobs[i:j]))
            i = j
        else:
            guarded(jobs[i])
            i += 1
    log(f"done: {len(jobs)} jobs, {failures[0]} failed, {time.time() - started:.0f} s")


if __name__ == "__main__":
    main()
