"""
Test script to diagnose audio outline 400 error.
Tests different combinations of upload method + inference content type
to identify why the audio outline API call fails.
"""
import requests
import time
import os
import sys

API_KEY = "c2fc5c4e-009c-41cf-a893-f75e35647666"
BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
MODEL = "doubao-seed-2-0-lite-260428"

headers = {"Authorization": f"Bearer {API_KEY}"}


def upload_file_as_audio(filepath):
    """Upload file as audio (no preprocess_configs) - matches our app's uploadAudioFile."""
    mime = "audio/mp4" if filepath.endswith(".m4a") else "audio/mpeg"
    with open(filepath, "rb") as f:
        resp = requests.post(
            f"{BASE_URL}/files",
            headers=headers,
            files={"file": (os.path.basename(filepath), f, mime)},
            data={"purpose": "user_data"},
        )
    result = resp.json()
    print(f"[upload_audio] status={resp.status_code} file_id={result.get('id')} file_status={result.get('status')}")
    return result


def upload_file_as_video(filepath, fps=1):
    """Upload file as video (with preprocess_configs) - matches our app's uploadVideoFile."""
    mime = "video/mp4"
    with open(filepath, "rb") as f:
        resp = requests.post(
            f"{BASE_URL}/files",
            headers=headers,
            files={"file": (os.path.basename(filepath), f, mime)},
            data={"purpose": "user_data", "preprocess_configs[video][fps]": str(fps)},
        )
    result = resp.json()
    print(f"[upload_video] status={resp.status_code} file_id={result.get('id')} file_status={result.get('status')}")
    return result


def wait_for_file(file_id, max_attempts=60):
    """Poll until file is ready or failed."""
    for i in range(max_attempts):
        resp = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers)
        info = resp.json()
        status = info.get("status", "unknown")
        if status in ("active", "processed", "ready", "succeeded"):
            print(f"[poll] File {file_id} ready (status={status}) after {i+1} polls")
            return True
        if status == "failed":
            print(f"[poll] File {file_id} FAILED after {i+1} polls")
            return False
        time.sleep(2)
    print(f"[poll] File {file_id} timed out (last status={status})")
    return False


def call_responses_with_input_audio(file_id, text="请识别这段音频的内容，生成结构化大纲。"):
    """Call Responses API using input_audio content type."""
    payload = {
        "model": MODEL,
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": "input_audio", "file_id": file_id},
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
    print(f"[input_audio] status={resp.status_code}")
    if resp.status_code != 200:
        print(f"[input_audio] ERROR BODY: {resp.text}")
    else:
        data = resp.json()
        output_text = ""
        for item in data.get("output", []):
            for content in item.get("content", []):
                if content.get("text"):
                    output_text += content["text"][:200]
        print(f"[input_audio] OK, output preview: {output_text[:200]}")
    return resp


def call_responses_with_input_video(file_id, text="请识别视频中的音频内容，生成结构化大纲。"):
    """Call Responses API using input_video content type (video-embedded audio)."""
    payload = {
        "model": MODEL,
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": "input_video", "file_id": file_id},
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
    print(f"[input_video] status={resp.status_code}")
    if resp.status_code != 200:
        print(f"[input_video] ERROR BODY: {resp.text}")
    else:
        data = resp.json()
        output_text = ""
        for item in data.get("output", []):
            for content in item.get("content", []):
                if content.get("text"):
                    output_text += content["text"][:200]
        print(f"[input_video] OK, output preview: {output_text[:200]}")
    return resp


def test_existing_file_id():
    """Test with the file ID from debug JSON (may be expired after 7 days)."""
    file_id = "file-20260528164240-582jm"
    print(f"\n{'='*60}")
    print(f"TEST 1: Use existing file_id={file_id}")
    print(f"{'='*60}")
    print("Checking file status...")
    resp = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers)
    print(f"File status response: {resp.status_code} {resp.text[:200]}")
    if resp.status_code == 200 and resp.json().get("status") in ("active", "processed"):
        print("\nTrying input_audio:")
        call_responses_with_input_audio(file_id)
        print("\nTrying input_video:")
        call_responses_with_input_video(file_id)


def test_upload_and_infer(audio_file):
    """Upload a local audio file and test both input_audio and input_video inference."""
    print(f"\n{'='*60}")
    print(f"TEST 2: Upload {audio_file} as AUDIO, then test inference")
    print(f"{'='*60}")
    result = upload_file_as_audio(audio_file)
    file_id = result.get("id")
    if not file_id:
        print(f"Upload failed: {result}")
        return
    if not wait_for_file(file_id):
        print("File preprocessing failed!")
        return
    print("\nTrying input_audio:")
    call_responses_with_input_audio(file_id)
    print("\nTrying input_video:")
    call_responses_with_input_video(file_id)

    print(f"\n{'='*60}")
    print(f"TEST 3: Upload {audio_file} as VIDEO (with fps=1), then test inference")
    print(f"{'='*60}")
    result = upload_file_as_video(audio_file, fps=1)
    file_id = result.get("id")
    if not file_id:
        print(f"Upload failed: {result}")
        return
    if not wait_for_file(file_id):
        print("File preprocessing failed!")
        return
    print("\nTrying input_audio:")
    call_responses_with_input_audio(file_id)
    print("\nTrying input_video:")
    call_responses_with_input_video(file_id)


if __name__ == "__main__":
    # First test: check if the existing file ID from the last run still works
    test_existing_file_id()

    # Second test: upload a local audio file if provided
    # Usage: python test_ark_audio_outline.py [path_to_audio.m4a]
    if len(sys.argv) > 1:
        audio_path = sys.argv[1]
        if os.path.exists(audio_path):
            test_upload_and_infer(audio_path)
        else:
            print(f"File not found: {audio_path}")
    else:
        print("\n\nTo test with a local audio file:")
        print("  python test_ark_audio_outline.py path/to/master_audio.m4a")
        print("\nOr pull from device:")
        print("  adb pull /data/user/0/com.example.watcher/files/video_runs/run_32_master_audio_clean.m4a .")
        print("  python test_ark_audio_outline.py run_32_master_audio_clean.m4a")
