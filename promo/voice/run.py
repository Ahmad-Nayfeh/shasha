#!/usr/bin/env python3
"""Runs ElevenLabs voice jobs on GitHub Actions, where the API key lives (repository secret ELEVEN_LABS).

The key is read from the environment and never printed. Every result (audio, JSON) is written under the
output folder, which the workflow uploads as an artifact.

    python3 promo/voice/run.py promo/voice/request.json out/

request.json: {"id": "...", "jobs": [job, ...]}. Job types:
  - {"type": "samples", "text": "...", "filters": {...shared-voices query...}, "count": 8,
     "exclude": ["voice_id", ...], "model_id": "eleven_multilingual_v2"}
    Picks the most used matching voices of the public library, saves each one's official preview and a
    sample of `text` read by it, plus samples.json with the voices' details.
  - {"type": "tts", "voice_id": "...", "model_id": "...", "text": "...", "out": "name.mp3",
     "voice_settings": {...}, "timestamps": true}
  - {"type": "subscription", "out": "subscription.json"}
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.elevenlabs.io"
KEY = os.environ.get("ELEVENLABS_API_KEY", "").strip()


def call(method, path, body=None, query=None, raw=False):
    url = API + path + ("?" + urllib.parse.urlencode(query, doseq=True) if query else "")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("xi-api-key", KEY)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    for attempt in range(4):
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


def download(url, dest):
    with urllib.request.urlopen(url, timeout=120) as r, open(dest, "wb") as f:
        f.write(r.read())


def tts(voice_id, text, model_id, dest, voice_settings=None, timestamps=False):
    body = {"text": text, "model_id": model_id}
    if voice_settings:
        body["voice_settings"] = voice_settings
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


def samples(job, out):
    seen, order = {}, []
    for sort in ("usage_character_count_1y", "cloned_by_count", "trending"):
        q = dict(job.get("filters", {}), page_size=50, sort=sort)
        for v in call("GET", "/v1/shared-voices", query=q).get("voices", []):
            if v["voice_id"] not in seen and v["voice_id"] not in job.get("exclude", []):
                seen[v["voice_id"]] = v
                order.append(v["voice_id"])
    ranked = sorted(order, key=lambda i: -(seen[i].get("usage_character_count_1y") or 0))
    picked, report = ranked[: job.get("count", 8)], []
    for n, vid in enumerate(picked, 1):
        v = seen[vid]
        entry = {k: v.get(k) for k in ("voice_id", "public_owner_id", "name", "accent", "age", "gender",
                                       "descriptive", "use_case", "category", "cloned_by_count",
                                       "usage_character_count_1y", "description")}
        name = f"{n:02d}"
        try:
            if v.get("preview_url"):
                download(v["preview_url"], os.path.join(out, f"{name}-preview.mp3"))
            try:
                tts(vid, job["text"], job.get("model_id", "eleven_multilingual_v2"),
                    os.path.join(out, f"{name}-sample.mp3"))
            except RuntimeError as e:
                if "not found" not in str(e).lower() and "404" not in str(e):
                    raise
                # Some library voices must be in "My Voices" before the API can use them.
                added = call("POST", f"/v1/voices/add/{v['public_owner_id']}/{vid}",
                             {"new_name": v.get("name", vid)[:40]})
                entry["added_as"] = added.get("voice_id")
                tts(added["voice_id"], job["text"], job.get("model_id", "eleven_multilingual_v2"),
                    os.path.join(out, f"{name}-sample.mp3"))
            entry["file"] = name
        except RuntimeError as e:
            entry["error"] = str(e)
        report.append(entry)
        print(f"{name}: {entry.get('name')} -> {'ok' if 'file' in entry else 'error'}")
    with open(os.path.join(out, "samples.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)


def main():
    if not KEY:
        sys.exit("ELEVENLABS_API_KEY is empty: add the repository secret ELEVEN_LABS.")
    request, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    with open(request, encoding="utf-8") as f:
        req = json.load(f)
    failures = 0
    for job in req["jobs"]:
        try:
            if job["type"] == "samples":
                samples(job, out)
            elif job["type"] == "tts":
                tts(job["voice_id"], job["text"], job["model_id"], os.path.join(out, job["out"]),
                    job.get("voice_settings"), job.get("timestamps", False))
                print(f"tts {job['out']}: ok")
            elif job["type"] == "subscription":
                sub = call("GET", "/v1/user/subscription")
                with open(os.path.join(out, job.get("out", "subscription.json")), "w") as f:
                    json.dump({k: sub.get(k) for k in ("tier", "character_count", "character_limit",
                                                       "next_character_count_reset_unix")}, f)
            else:
                raise RuntimeError(f"unknown job type {job['type']}")
        except RuntimeError as e:
            failures += 1
            print(f"job {job.get('type')} failed: {e}")
            with open(os.path.join(out, "errors.txt"), "a", encoding="utf-8") as f:
                f.write(f"{job.get('type')}: {e}\n")
    print(f"done: {len(req['jobs'])} jobs, {failures} failed")


if __name__ == "__main__":
    main()
