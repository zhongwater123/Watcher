"""
Test script: Extract audio from test MP4 as .m4a, then test Ark API audio understanding.
Goal: Find a working combination for .m4a files without converting to MP3.

Tests:
1. Extract audio track from MP4 → .m4a (using ffprobe/ffmpeg locally on PC)
2. Upload .m4a with different mime types
3. Try input_audio vs input_video with the uploaded file
4. Compare server's mime_type detection for each upload approach
"""
import subprocess
import requests
import time
import os
import sys
import json

API_KEY = "c2fc5c4e-009c-41cf-a893-f75e35647666"
BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
MODEL = "doubao-seed-2-0-lite-260428"
SOURCE_VIDEO = "f9e334147f394f294f5dff201c9b3f33.mp4"

headers = {"Authorization": f"Bearer {API_KEY}"}


def extract_audio_m4a(input_video, output_m4a):
    """Extract audio track from MP4 as .m4a using ffmpeg (PC-side only for testing)."""
    if os.path.exists(output_m4a):
        print(f"[extract] {output_m4a} already exists, skipping extraction")
        return True
    cmd = [
        "ffmpeg", "-i", input_video,
        "-vn",              # no video
        "-acodec", "copy",  # copy audio stream without re-encoding
        "-y", output_m4a
    ]
    print(f"[extract] Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[extract] ffmpeg failed: {result.stderr[:500]}")
        # Try with re-encoding as fallback
        cmd2 = [
            "ffmpeg", "-i", input_video,
            "-vn", "-acodec", "aac", "-b:a", "128k",
            "-y", output_m4a
        ]
        print(f"[extract] Retry with re-encode: {' '.join(cmd2)}")
        result = subprocess.run(cmd2, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"[extract] ffmpeg re-encode also failed: {result.stderr[:500]}")
            return False
    size = os.path.getsize(output_m4a) if os.path.exists(output_m4a) else 0
    print(f"[extract] Success: {output_m4a} ({size} bytes)")
    return os.path.exists(output_m4a) and size > 0


def upload_file(filepath, mime_type, preprocess_fps=None):
    """Upload file to Ark Files API."""
    data = {"purpose": "user_data"}
    if preprocess_fps is not None:
        data["preprocess_configs[video][fps]"] = str(preprocess_fps)
    with open(filepath, "rb") as f:
        resp = requests.post(
            f"{BASE_URL}/files",
            headers=headers,
            files={"file": (os.path.basename(filepath), f, mime_type)},
            data=data,
        )
    result = resp.json()
    print(f"[upload] mime={mime_type} preprocess_fps={preprocess_fps}")
    print(f"         status={resp.status_code} file_id={result.get('id')}")
    print(f"         server_mime={result.get('mime_type')} server_status={result.get('status')}")
    return result


def wait_for_file(file_id, max_attempts=60):
    """Poll until file is ready or failed."""
    for i in range(max_attempts):
        resp = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers)
        info = resp.json()
        status = info.get("status", "unknown")
        if status in ("active", "processed", "ready", "succeeded"):
            print(f"[poll] File {file_id} ready (status={status}) after {i+1} polls")
            return "active"
        if status == "failed":
            print(f"[poll] File {file_id} FAILED after {i+1} polls")
            return "failed"
        if i % 5 == 0:
            print(f"[poll] File {file_id} status={status}, waiting...")
        time.sleep(2)
    print(f"[poll] File {file_id} timed out")
    return "timeout"


def call_model(file_id, content_type, text="请识别这段音频的内容，生成简要大纲。"):
    """Call Responses API with given content type (input_audio or input_video)."""
    payload = {
        "model": MODEL,
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": content_type, "file_id": file_id},
                    {"type": "input_text", "text": text},
                ],
            }
        ],
    }
    resp = requests.post(
        f"{BASE_URL}/responses",
        headers={**headers, "Content-Type": "application/json"},
        json=payload,
    )
    print(f"[model] content_type={content_type} → HTTP {resp.status_code}")
    if resp.status_code != 200:
        error_data = resp.json() if resp.headers.get("content-type", "").startswith("application/json") else resp.text
        print(f"         ERROR: {json.dumps(error_data, ensure_ascii=False)[:500]}")
        return None
    else:
        data = resp.json()
        output_text = ""
        for item in data.get("output", []):
            for content in item.get("content", []):
                if content.get("text"):
                    output_text += content["text"]
        print(f"         OK! Output length: {len(output_text)} chars")
        print(f"         Preview: {output_text[:200]}")
        usage = data.get("usage", {})
        print(f"         Tokens: input={usage.get('input_tokens')} output={usage.get('output_tokens')} audio={usage.get('audio_tokens')}")
        return output_text


def run_test_matrix(filepath):
    """Test all combinations of upload mime + inference content type."""
    print(f"\n{'='*70}")
    print(f"FILE: {filepath} ({os.path.getsize(filepath)} bytes)")
    print(f"{'='*70}")

    results = []

    # Test matrix
    upload_configs = [
        ("audio/mp4", None, "Upload as audio/mp4, no preprocess"),
        ("audio/aac", None, "Upload as audio/aac, no preprocess"),
        ("audio/x-m4a", None, "Upload as audio/x-m4a, no preprocess"),
        ("video/mp4", None, "Upload as video/mp4, no preprocess"),
        ("video/mp4", 1, "Upload as video/mp4, with fps=1 preprocess"),
    ]

    for mime, fps, desc in upload_configs:
        print(f"\n--- {desc} ---")
        upload_result = upload_file(filepath, mime, fps)
        file_id = upload_result.get("id")
        if not file_id:
            results.append((desc, "upload_failed", None, None))
            continue

        file_status = wait_for_file(file_id)
        if file_status != "active":
            results.append((desc, f"preprocess_{file_status}", None, None))
            continue

        # Get server's actual mime detection
        file_info = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers).json()
        server_mime = file_info.get("mime_type", "unknown")
        print(f"[info] Server detected mime: {server_mime}")

        # Try input_audio
        print(f"\n  Trying input_audio:")
        audio_result = call_model(file_id, "input_audio")

        # Try input_video
        print(f"\n  Trying input_video:")
        video_result = call_model(file_id, "input_video")

        results.append((desc, server_mime,
                       "OK" if audio_result else "FAIL",
                       "OK" if video_result else "FAIL"))

    # Summary
    print(f"\n{'='*70}")
    print("RESULTS SUMMARY")
    print(f"{'='*70}")
    print(f"{'Upload Method':<45} {'Server MIME':<15} {'input_audio':<12} {'input_video':<12}")
    print("-" * 84)
    for desc, mime_or_status, audio, video in results:
        if audio is None:
            print(f"{desc:<45} {mime_or_status:<15} {'—':<12} {'—':<12}")
        else:
            print(f"{desc:<45} {mime_or_status:<15} {audio:<12} {video:<12}")


if __name__ == "__main__":
    os.chdir(os.path.dirname(os.path.abspath(__file__)))

    # Step 1: Extract audio from test video
    m4a_file = "test_audio_extracted.m4a"
    if not os.path.exists(SOURCE_VIDEO):
        print(f"ERROR: Source video {SOURCE_VIDEO} not found in {os.getcwd()}")
        print("Make sure you run this script from app/src/test/")
        sys.exit(1)

    if not extract_audio_m4a(SOURCE_VIDEO, m4a_file):
        print("ERROR: Failed to extract audio. Make sure ffmpeg is installed on your PC.")
        print("  Windows: winget install ffmpeg")
        print("  Or download from: https://ffmpeg.org/download.html")
        sys.exit(1)

    # Step 2: Run test matrix
    run_test_matrix(m4a_file)
