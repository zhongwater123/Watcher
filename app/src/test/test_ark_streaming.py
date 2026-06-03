import requests
import time
import os
import json
import base64
import subprocess

API_KEY = "c2fc5c4e-009c-41cf-a893-f75e35647666"
BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
VIDEO_FILE = "f9e334147f394f294f5dff201c9b3f33.mp4"

headers = {"Authorization": f"Bearer {API_KEY}"}

PROMPT = "根据音轨输出前30秒完整字幕，格式[MM:SS] 文本。用中文回答。"


def upload_file(filepath, fps=None, mime="video/mp4"):
    with open(filepath, "rb") as f:
        data = {"purpose": "user_data"}
        if fps is not None:
            data["preprocess_configs[video][fps]"] = str(fps)
        resp = requests.post(
            f"{BASE_URL}/files", headers=headers,
            files={"file": (os.path.basename(filepath), f, mime)},
            data=data,
        )
    return resp.json()


def wait_ready(file_id):
    for i in range(20):
        time.sleep(3)
        resp = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers)
        status = resp.json().get("status")
        if status in ("processed", "ready", "succeeded"):
            return True
        if status == "failed":
            return False
        if status == "active" and i > 3:
            return True
    return False


def stream_request(content_items, label):
    """Send streaming request and measure timing."""
    print(f"\n{'='*50}")
    print(f"  {label}")
    print(f"{'='*50}")

    start_time = time.time()
    first_token_time = None
    full_text = ""
    total_tokens = 0

    resp = requests.post(
        f"{BASE_URL}/responses",
        headers={**headers, "Content-Type": "application/json"},
        json={
            "model": "doubao-seed-2-0-lite-260428",
            "stream": True,
            "input": [{"role": "user", "content": content_items}],
        },
        stream=True,
        timeout=300,
    )

    for line in resp.iter_lines(decode_unicode=True):
        if not line or not line.startswith("data:"):
            continue
        data_str = line[5:].strip()
        if data_str == "[DONE]":
            break
        try:
            event = json.loads(data_str)
        except json.JSONDecodeError:
            continue

        event_type = event.get("type", "")

        # Capture first token timing
        if "output_text.delta" in event_type:
            if first_token_time is None:
                first_token_time = time.time()
            delta = event.get("delta", "")
            full_text += delta
            # Print dots for progress
            if len(full_text) % 50 == 0:
                print(".", end="", flush=True)

        # Capture usage from completed event
        if "response.completed" in event_type:
            response_obj = event.get("response", {})
            usage = response_obj.get("usage", {})
            total_tokens = usage.get("total_tokens", 0)

    end_time = time.time()

    # Results
    total_duration = end_time - start_time
    ttft = (first_token_time - start_time) if first_token_time else None

    print(f"\n\n  --- Results ---")
    print(f"  Total time: {total_duration:.1f}s")
    print(f"  Time to first token (TTFT): {ttft:.1f}s" if ttft else "  TTFT: N/A (no output)")
    print(f"  Output length: {len(full_text)} chars")
    print(f"  Total tokens: {total_tokens}")
    if full_text:
        print(f"\n  --- Output (first 300 chars) ---")
        print(f"  {full_text[:300]}")

    return {
        "label": label,
        "total_time": total_duration,
        "ttft": ttft,
        "output_len": len(full_text),
        "total_tokens": total_tokens,
    }


# =================================================================
print("STREAMING TEST: Comparing different input modes")
print("=================================================================")

# Prepare files
print("\n[Prep] Uploading video (fps=1)...")
upload = upload_file(VIDEO_FILE, fps=1)
video_fid = upload.get("id")
print(f"  video file_id: {video_fid}")
wait_ready(video_fid)

# Extract audio
audio_file = "test_audio_stream.m4a"
subprocess.run(["ffmpeg", "-y", "-i", VIDEO_FILE, "-vn", "-c:a", "copy", audio_file], capture_output=True)

audio_fid = None
audio_b64_url = None

if os.path.exists(audio_file):
    # Upload audio via Files API
    print("\n[Prep] Uploading audio to Files API...")
    upload_a = upload_file(audio_file, fps=None, mime="audio/mp4")
    audio_fid = upload_a.get("id")
    print(f"  audio file_id: {audio_fid}")
    wait_ready(audio_fid)

    # Base64 encode
    with open(audio_file, "rb") as f:
        audio_b64_url = f"data:audio/mp4;base64,{base64.b64encode(f.read()).decode()}"
    print(f"  audio base64 size: {len(audio_b64_url)} chars")

# =================================================================
results = []

# Test A: input_video only (stream)
r = stream_request(
    [
        {"type": "input_video", "file_id": video_fid},
        {"type": "input_text", "text": PROMPT},
    ],
    label="A: input_video only (stream)"
)
results.append(r)

# Test B: input_video + input_audio base64 (stream)
if audio_b64_url:
    r = stream_request(
        [
            {"type": "input_video", "file_id": video_fid},
            {"type": "input_audio", "audio_url": audio_b64_url},
            {"type": "input_text", "text": PROMPT},
        ],
        label="B: input_video + input_audio base64 (stream)"
    )
    results.append(r)

# Test C: input_video + input_audio file_id (stream)
if audio_fid:
    r = stream_request(
        [
            {"type": "input_video", "file_id": video_fid},
            {"type": "input_audio", "file_id": audio_fid},
            {"type": "input_text", "text": PROMPT},
        ],
        label="C: input_video + input_audio file_id (stream)"
    )
    results.append(r)

# Test D: input_audio only (no video, stream) - pure ASR
if audio_fid:
    r = stream_request(
        [
            {"type": "input_audio", "file_id": audio_fid},
            {"type": "input_text", "text": PROMPT},
        ],
        label="D: input_audio only via file_id (pure ASR, stream)"
    )
    results.append(r)

# Test E: input_audio base64 only (no video, stream) - pure ASR
if audio_b64_url:
    r = stream_request(
        [
            {"type": "input_audio", "audio_url": audio_b64_url},
            {"type": "input_text", "text": PROMPT},
        ],
        label="E: input_audio base64 only (pure ASR, stream)"
    )
    results.append(r)

# =================================================================
# Summary table
print("\n\n" + "=" * 70)
print("SUMMARY")
print("=" * 70)
print(f"{'Test':<45} {'TTFT':>6} {'Total':>7} {'Tokens':>7}")
print("-" * 70)
for r in results:
    ttft_str = f"{r['ttft']:.1f}s" if r['ttft'] else "N/A"
    print(f"{r['label']:<45} {ttft_str:>6} {r['total_time']:>6.1f}s {r['total_tokens']:>7}")

# Cleanup
if os.path.exists(audio_file):
    os.remove(audio_file)

print("\n===== ALL STREAMING TESTS COMPLETE =====")
