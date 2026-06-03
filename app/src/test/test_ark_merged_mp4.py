import requests
import time
import os
import subprocess

API_KEY = "c2fc5c4e-009c-41cf-a893-f75e35647666"
BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
VIDEO_FILE = "f9e334147f394f294f5dff201c9b3f33.mp4"

headers = {"Authorization": f"Bearer {API_KEY}"}

print("=" * 60)
print("TEST: Merged MP4 (separate audio + video combined)")
print("  Simulates: independent audio recording + video recording -> merge")
print("=" * 60)

# Step 1: Extract video-only (no audio) and audio-only from original
video_only = "test_video_only.mp4"
audio_only = "test_audio_only.m4a"
merged_file = "test_merged.mp4"

print("\n[Step 1] Splitting original into video-only + audio-only...")
# Extract video without audio
subprocess.run(
    ["ffmpeg", "-y", "-i", VIDEO_FILE, "-an", "-c:v", "copy", video_only],
    capture_output=True,
)
# Extract audio without video
subprocess.run(
    ["ffmpeg", "-y", "-i", VIDEO_FILE, "-vn", "-c:a", "copy", audio_only],
    capture_output=True,
)

if not os.path.exists(video_only) or not os.path.exists(audio_only):
    print("  ERROR: ffmpeg extraction failed")
    exit(1)

print(f"  Video-only: {os.path.getsize(video_only)} bytes")
print(f"  Audio-only: {os.path.getsize(audio_only)} bytes")

# Step 2: Merge them back into a new MP4 (simulating the real scenario)
print("\n[Step 2] Merging video + audio into new MP4...")
subprocess.run(
    ["ffmpeg", "-y", "-i", video_only, "-i", audio_only, "-c:v", "copy", "-c:a", "copy", "-shortest", merged_file],
    capture_output=True,
)

if not os.path.exists(merged_file):
    print("  ERROR: ffmpeg merge failed")
    exit(1)

merged_size = os.path.getsize(merged_file)
original_size = os.path.getsize(VIDEO_FILE)
print(f"  Merged file: {merged_size} bytes")
print(f"  Original file: {original_size} bytes")
print(f"  Size diff: {merged_size - original_size} bytes")

# Step 3: Upload merged file with preprocess_configs[video][fps]=1
print("\n[Step 3] Uploading MERGED MP4 with preprocess_configs[video][fps]=1...")
with open(merged_file, "rb") as f:
    resp = requests.post(
        f"{BASE_URL}/files",
        headers=headers,
        files={"file": (merged_file, f, "video/mp4")},
        data={"purpose": "user_data", "preprocess_configs[video][fps]": "1"},
    )
upload = resp.json()
file_id = upload.get("id")
print(f"  File ID: {file_id}")
print(f"  Status: {upload.get('status')}")

if not file_id:
    print(f"  Upload failed: {upload}")
    exit(1)

# Step 4: Wait for preprocessing
print("\n[Step 4] Waiting for preprocessing...")
for i in range(30):
    time.sleep(3)
    resp = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers)
    status = resp.json().get("status")
    print(f"  Attempt {i+1}: status={status}")
    if status in ("processed", "ready", "succeeded"):
        print("  Preprocessing complete!")
        break
    if status == "failed":
        print("  >>> PREPROCESSING FAILED! Merged MP4 not accepted.")
        exit(1)
    if status == "active" and i > 3:
        print("  Status 'active', treating as ready.")
        break

# Step 5: Call model
print("\n[Step 5] Calling model with merged file (input_video only)...")
resp = requests.post(
    f"{BASE_URL}/responses",
    headers={**headers, "Content-Type": "application/json"},
    json={
        "model": "doubao-seed-2-0-lite-260428",
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": "input_video", "file_id": file_id},
                    {
                        "type": "input_text",
                        "text": "请根据视频音轨，输出前30秒的带时间戳字幕，格式：[MM:SS] 文本内容。请用中文回答。",
                    },
                ],
            }
        ],
    },
    timeout=300,
)

result = resp.json()
if "error" in result:
    print(f"\n[ERROR] {result['error'].get('code')}: {result['error'].get('message')}")
else:
    print("\n===== MODEL OUTPUT (from MERGED MP4) =====\n")
    for item in result.get("output", []):
        for c in item.get("content", []):
            if c.get("text"):
                print(c["text"])
        if item.get("text"):
            print(item["text"])
    usage = result.get("usage", {})
    print(f"\n--- Usage: input={usage.get('input_tokens')}, output={usage.get('output_tokens')}")

# Cleanup
for f in [video_only, audio_only, merged_file]:
    if os.path.exists(f):
        os.remove(f)

print("\n" + "=" * 60)
print("TEST COMPLETE")
print("  If subtitles appeared -> merged MP4 works correctly!")
print("  If no audio content -> merging breaks audio track")
print("=" * 60)
