import requests
import time
import os
import base64

API_KEY = "c2fc5c4e-009c-41cf-a893-f75e35647666"
BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
VIDEO_FILE = "f9e334147f394f294f5dff201c9b3f33.mp4"

headers = {"Authorization": f"Bearer {API_KEY}"}


def upload_file(filepath, preprocess_fps=None, mime="video/mp4"):
    """Upload a file to ARK Files API."""
    with open(filepath, "rb") as f:
        data = {"purpose": "user_data"}
        if preprocess_fps is not None:
            data["preprocess_configs[video][fps]"] = str(preprocess_fps)
        resp = requests.post(
            f"{BASE_URL}/files",
            headers=headers,
            files={"file": (os.path.basename(filepath), f, mime)},
            data=data,
        )
    return resp.json()


def wait_for_file(file_id, max_attempts=30):
    """Poll until file is ready."""
    for i in range(max_attempts):
        time.sleep(3)
        resp = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers)
        status = resp.json().get("status")
        print(f"    Attempt {i+1}: status={status}")
        if status in ("processed", "ready", "succeeded"):
            return True
        if status == "failed":
            print("    FAILED!")
            return False
        if status == "active" and i > 3:
            print("    Status 'active', treating as ready.")
            return True
    return False


def call_model(content_items, label=""):
    """Call model and return output text."""
    print(f"\n  [{label}] Calling model...")
    resp = requests.post(
        f"{BASE_URL}/responses",
        headers={**headers, "Content-Type": "application/json"},
        json={
            "model": "doubao-seed-2-0-lite-260428",
            "input": [{"role": "user", "content": content_items}],
        },
        timeout=300,
    )
    result = resp.json()
    if "error" in result:
        print(f"  [ERROR] {result['error'].get('code')}: {result['error'].get('message')}")
        return None
    text = ""
    for item in result.get("output", []):
        for c in item.get("content", []):
            if c.get("text"):
                text += c["text"]
        if item.get("text"):
            text += item["text"]
    usage = result.get("usage", {})
    print(f"  Usage: input={usage.get('input_tokens')}, output={usage.get('output_tokens')}")
    return text


# ============================================================
print("=" * 60)
print("TEST 1: input_video only (preprocess fps=1)")
print("  Already confirmed working - model gets video + audio")
print("=" * 60)

# Upload video
print("\n[Upload] Video with preprocess_configs[video][fps]=1")
upload = upload_file(VIDEO_FILE, preprocess_fps=1)
video_file_id = upload.get("id")
print(f"  File ID: {video_file_id}, Status: {upload.get('status')}")
wait_for_file(video_file_id)

# ============================================================
print("\n" + "=" * 60)
print("TEST 2: input_video + separate input_audio (base64)")
print("  Compare: does adding separate audio change/improve output?")
print("=" * 60)

# Extract audio from video using ffmpeg
audio_file = "test_extracted_audio.m4a"
os.system(f'ffmpeg -y -i {VIDEO_FILE} -vn -acodec copy {audio_file} 2>nul')

if os.path.exists(audio_file):
    audio_size = os.path.getsize(audio_file)
    print(f"\n[Extracted] {audio_file}: {audio_size} bytes")

    # Base64 encode
    with open(audio_file, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode()
    audio_data_url = f"data:audio/mp4;base64,{audio_b64}"
    print(f"  Base64 size: {len(audio_data_url)} chars")
else:
    print(f"\n[SKIP] ffmpeg not available, skipping audio extraction test")
    audio_data_url = None

# ============================================================
print("\n" + "=" * 60)
print("TEST 3: Upload .m4a to Files API (reproduce old bug)")
print("  Verify: does ARK preprocessing fail for extracted m4a?")
print("=" * 60)

if os.path.exists(audio_file):
    print(f"\n[Upload] {audio_file} to Files API (purpose=user_data, no preprocess)")
    upload_audio = upload_file(audio_file, preprocess_fps=None, mime="audio/mp4")
    audio_file_id = upload_audio.get("id")
    print(f"  File ID: {audio_file_id}, Status: {upload_audio.get('status')}")
    if audio_file_id:
        audio_ready = wait_for_file(audio_file_id)
        if audio_ready:
            print("  >>> Audio preprocessing SUCCEEDED! Old bug might be fixed.")
        else:
            print("  >>> Audio preprocessing FAILED! Confirms the bug with extracted m4a.")
else:
    audio_file_id = None
    print("  [SKIP] No audio file to test")

# ============================================================
print("\n" + "=" * 60)
print("COMPARISON: Model outputs")
print("=" * 60)

prompt = "请根据视频音轨，输出前30秒的逐字带时间戳字幕，格式：[MM:SS] 文本"

# Test A: video only
print("\n--- Test A: input_video ONLY ---")
text_a = call_model(
    [
        {"type": "input_video", "file_id": video_file_id},
        {"type": "input_text", "text": prompt},
    ],
    label="video only",
)
if text_a:
    print(text_a[:500])

# Test B: video + audio base64
if audio_data_url:
    print("\n--- Test B: input_video + input_audio (base64) ---")
    text_b = call_model(
        [
            {"type": "input_video", "file_id": video_file_id},
            {"type": "input_audio", "audio_url": audio_data_url},
            {"type": "input_text", "text": prompt},
        ],
        label="video + audio base64",
    )
    if text_b:
        print(text_b[:500])

# Test C: video + audio file_id (if upload succeeded)
if audio_file_id and audio_ready:
    print("\n--- Test C: input_video + input_audio (file_id) ---")
    text_c = call_model(
        [
            {"type": "input_video", "file_id": video_file_id},
            {"type": "input_audio", "file_id": audio_file_id},
            {"type": "input_text", "text": prompt},
        ],
        label="video + audio file_id",
    )
    if text_c:
        print(text_c[:500])

# Cleanup
if os.path.exists(audio_file):
    os.remove(audio_file)

print("\n" + "=" * 60)
print("ALL TESTS COMPLETE")
print("=" * 60)
