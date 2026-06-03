"""
Test: Simulate AudioOutlineProcessor's ADTS extraction approach.
Extract raw ADTS AAC from .m4a (bypassing MP4 container), upload to Ark, verify input_audio works.

This validates the exact same approach our Android code will use:
  .m4a → MediaExtractor read frames → add ADTS headers → .aac file → upload → input_audio
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
SOURCE_M4A = "test_audio_extracted.m4a"

headers = {"Authorization": f"Bearer {API_KEY}"}


def extract_adts_with_ffmpeg(input_m4a, output_aac):
    """
    Simulate what our Android code does:
    Read AAC frames from .m4a container → write as raw ADTS .aac
    ffmpeg -i input.m4a -acodec copy -f adts output.aac
    """
    if os.path.exists(output_aac):
        os.remove(output_aac)
    result = subprocess.run(
        ["ffmpeg", "-i", input_m4a, "-acodec", "copy", "-f", "adts", "-y", output_aac],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        print(f"[extract] FAILED: {result.stderr[:300]}")
        return False
    size = os.path.getsize(output_aac) if os.path.exists(output_aac) else 0
    print(f"[extract] {input_m4a} → {output_aac} ({size} bytes)")
    return size > 0


def extract_adts_from_concat(segment_m4as, output_aac):
    """
    Simulate concatenated master audio scenario:
    Multiple .m4a segments → extract ADTS from each → concatenate → single .aac
    This is what AudioOutlineProcessor will do with buildMasterAudioFromFiles output.
    """
    if os.path.exists(output_aac):
        os.remove(output_aac)
    with open(output_aac, "wb") as out:
        for seg in segment_m4as:
            temp = f"_temp_adts_{os.path.basename(seg)}.aac"
            subprocess.run(
                ["ffmpeg", "-i", seg, "-acodec", "copy", "-f", "adts", "-y", temp],
                capture_output=True
            )
            if os.path.exists(temp):
                with open(temp, "rb") as f:
                    out.write(f.read())
                os.remove(temp)
    size = os.path.getsize(output_aac) if os.path.exists(output_aac) else 0
    print(f"[extract_concat] {len(segment_m4as)} segments → {output_aac} ({size} bytes)")
    return size > 0


def upload_and_test(filepath, label):
    """Upload .aac file and test with input_audio."""
    print(f"\n{'='*60}")
    print(f"TEST: {label}")
    print(f"{'='*60}")
    size = os.path.getsize(filepath)
    print(f"File: {filepath} ({size} bytes)")

    # Upload as audio/aac
    with open(filepath, "rb") as f:
        resp = requests.post(
            f"{BASE_URL}/files",
            headers=headers,
            files={"file": (os.path.basename(filepath), f, "audio/aac")},
            data={"purpose": "user_data"},
        )
    result = resp.json()
    file_id = result.get("id")
    server_mime = result.get("mime_type")
    status = result.get("status")
    print(f"Upload: file_id={file_id}, server_mime={server_mime}, status={status}")

    if not file_id:
        print(f"FAILED: {result}")
        return False

    # Wait for preprocessing
    for i in range(30):
        info = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers).json()
        file_status = info.get("status")
        if file_status in ("active", "processed"):
            print(f"Preprocessing: OK ({file_status})")
            break
        if file_status == "failed":
            print(f"Preprocessing: FAILED")
            print(f"  File info: {json.dumps(info, ensure_ascii=False)[:300]}")
            return False
        time.sleep(2)
    else:
        print(f"Preprocessing: TIMEOUT")
        return False

    # Test input_audio
    payload = {
        "model": MODEL,
        "input": [{
            "role": "user",
            "content": [
                {"type": "input_audio", "file_id": file_id},
                {"type": "input_text", "text": "请用一句话概括这段音频的主要内容，然后列出3个关键信息点。"},
            ],
        }],
    }
    resp = requests.post(
        f"{BASE_URL}/responses",
        headers={**headers, "Content-Type": "application/json"},
        json=payload,
    )
    print(f"Model call: HTTP {resp.status_code}")
    if resp.status_code == 200:
        data = resp.json()
        text = ""
        for item in data.get("output", []):
            for c in item.get("content", []):
                if c.get("text"):
                    text += c["text"]
        print(f"Output ({len(text)} chars):")
        print(f"  {text[:300]}")
        usage = data.get("usage", {})
        print(f"Tokens: input={usage.get('input_tokens')} output={usage.get('output_tokens')}")
        return True
    else:
        print(f"ERROR: {resp.text[:300]}")
        return False


if __name__ == "__main__":
    os.chdir(os.path.dirname(os.path.abspath(__file__)))

    if not os.path.exists(SOURCE_M4A):
        print(f"ERROR: {SOURCE_M4A} not found.")
        sys.exit(1)

    # Test 1: Single .m4a → ADTS .aac → upload
    print("\n" + "=" * 60)
    print("SCENARIO 1: Single source .m4a → extract ADTS → upload")
    print("(Simulates: short master audio from single recording)")
    print("=" * 60)
    single_aac = "test_single_adts.aac"
    if extract_adts_with_ffmpeg(SOURCE_M4A, single_aac):
        upload_and_test(single_aac, "Single .m4a → ADTS .aac")

    # Test 2: Split into segments, extract ADTS from each, concatenate
    print("\n" + "=" * 60)
    print("SCENARIO 2: Multiple segments → extract ADTS each → concat → upload")
    print("(Simulates: buildMasterAudioFromFiles + extractAdtsAac)")
    print("=" * 60)
    # Split source into 3 segments
    segments = []
    duration_result = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration", "-of", "csv=p=0", SOURCE_M4A],
        capture_output=True, text=True
    )
    total_duration = float(duration_result.stdout.strip())
    seg_duration = total_duration / 3
    for i in range(3):
        seg_file = f"_test_seg_{i}.m4a"
        subprocess.run([
            "ffmpeg", "-i", SOURCE_M4A,
            "-ss", str(i * seg_duration), "-t", str(seg_duration),
            "-acodec", "copy", "-y", seg_file
        ], capture_output=True)
        segments.append(seg_file)

    concat_aac = "test_concat_adts.aac"
    if extract_adts_from_concat(segments, concat_aac):
        upload_and_test(concat_aac, "3 segments → ADTS concat → upload")

    # Cleanup
    for f in segments:
        if os.path.exists(f):
            os.remove(f)
    if os.path.exists(single_aac):
        os.remove(single_aac)
    if os.path.exists(concat_aac):
        os.remove(concat_aac)

    print("\n" + "=" * 60)
    print("DONE. If both tests show HTTP 200, the ADTS approach is validated.")
    print("=" * 60)
