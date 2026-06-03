"""
Test: Simulate master audio concatenation (like Android MediaMuxer would do)
and check if the result is detected as audio/x-m4a or video/mp4 by Ark API.

Steps:
1. Split test_audio_extracted.m4a into 3 segments
2. Concatenate them back (simulating buildMasterAudioFromFiles)
3. Upload and test with input_audio
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


def split_audio(source, num_segments=3):
    """Split audio into N equal segments."""
    # Get duration
    result = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
         "-of", "csv=p=0", source],
        capture_output=True, text=True
    )
    duration = float(result.stdout.strip())
    segment_duration = duration / num_segments
    print(f"[split] Total duration: {duration:.1f}s, segment: {segment_duration:.1f}s")

    segment_files = []
    for i in range(num_segments):
        start = i * segment_duration
        out_file = f"test_segment_{i+1}.m4a"
        subprocess.run([
            "ffmpeg", "-i", source,
            "-ss", str(start), "-t", str(segment_duration),
            "-acodec", "copy", "-y", out_file
        ], capture_output=True)
        size = os.path.getsize(out_file) if os.path.exists(out_file) else 0
        print(f"[split] Segment {i+1}: {out_file} ({size} bytes)")
        segment_files.append(out_file)
    return segment_files


def concat_with_ffmpeg_demuxer(segment_files, output):
    """Concatenate using ffmpeg concat demuxer (stream copy, no re-encode)."""
    # Create concat list file
    list_file = "concat_list.txt"
    with open(list_file, "w") as f:
        for seg in segment_files:
            f.write(f"file '{seg}'\n")
    subprocess.run([
        "ffmpeg", "-f", "concat", "-safe", "0",
        "-i", list_file, "-acodec", "copy", "-y", output
    ], capture_output=True)
    os.remove(list_file)
    size = os.path.getsize(output) if os.path.exists(output) else 0
    print(f"[concat_demuxer] {output} ({size} bytes)")
    return os.path.exists(output) and size > 0


def concat_with_ffmpeg_filter(segment_files, output):
    """Concatenate using ffmpeg filter_complex (re-encodes audio)."""
    inputs = []
    for seg in segment_files:
        inputs.extend(["-i", seg])
    filter_str = f"concat=n={len(segment_files)}:v=0:a=1[out]"
    subprocess.run([
        "ffmpeg", *inputs,
        "-filter_complex", filter_str,
        "-map", "[out]",
        "-acodec", "aac", "-b:a", "128k",
        "-y", output
    ], capture_output=True)
    size = os.path.getsize(output) if os.path.exists(output) else 0
    print(f"[concat_filter] {output} ({size} bytes)")
    return os.path.exists(output) and size > 0


def concat_with_raw_aac(segment_files, output):
    """
    Simulate Android MediaMuxer behavior:
    Extract raw AAC from each segment, concatenate, re-wrap in MP4.
    This mimics what MediaMuxer does (packet-level copy with PTS offset).
    """
    raw_file = "temp_raw.aac"
    # Extract raw AAC stream from each segment and concatenate
    with open(raw_file, "wb") as outf:
        for seg in segment_files:
            # Extract raw ADTS AAC
            subprocess.run([
                "ffmpeg", "-i", seg, "-acodec", "copy",
                "-f", "adts", "-y", "temp_part.aac"
            ], capture_output=True)
            if os.path.exists("temp_part.aac"):
                with open("temp_part.aac", "rb") as inf:
                    outf.write(inf.read())
                os.remove("temp_part.aac")
    # Wrap concatenated AAC back into MP4 container
    subprocess.run([
        "ffmpeg", "-i", raw_file,
        "-acodec", "copy", "-y", output
    ], capture_output=True)
    os.remove(raw_file)
    size = os.path.getsize(output) if os.path.exists(output) else 0
    print(f"[concat_raw_aac] {output} ({size} bytes)")
    return os.path.exists(output) and size > 0


def upload_and_test(filepath, label):
    """Upload file and test with input_audio."""
    print(f"\n--- Testing: {label} ---")
    if not os.path.exists(filepath):
        print(f"  File not found: {filepath}")
        return

    size = os.path.getsize(filepath)
    print(f"  File: {filepath} ({size} bytes)")

    with open(filepath, "rb") as f:
        resp = requests.post(
            f"{BASE_URL}/files",
            headers=headers,
            files={"file": (os.path.basename(filepath), f, "audio/mp4")},
            data={"purpose": "user_data"},
        )
    result = resp.json()
    file_id = result.get("id")
    server_mime = result.get("mime_type")
    print(f"  Upload: file_id={file_id}, server_mime={server_mime}")

    if not file_id:
        print(f"  Upload failed: {result}")
        return

    # Wait for preprocessing
    for i in range(30):
        info = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers).json()
        status = info.get("status")
        if status in ("active", "processed"):
            print(f"  Preprocessing: OK (status={status})")
            break
        if status == "failed":
            print(f"  Preprocessing: FAILED")
            return
        time.sleep(2)
    else:
        print(f"  Preprocessing: TIMEOUT")
        return

    # Final server mime after preprocessing
    final_mime = info.get("mime_type", "unknown")
    print(f"  Final server_mime: {final_mime}")

    # Test input_audio
    payload = {
        "model": MODEL,
        "input": [{
            "role": "user",
            "content": [
                {"type": "input_audio", "file_id": file_id},
                {"type": "input_text", "text": "请用一句话概括这段音频的内容。"},
            ],
        }],
    }
    resp = requests.post(
        f"{BASE_URL}/responses",
        headers={**headers, "Content-Type": "application/json"},
        json=payload,
    )
    print(f"  input_audio: HTTP {resp.status_code}")
    if resp.status_code == 200:
        data = resp.json()
        text = ""
        for item in data.get("output", []):
            for c in item.get("content", []):
                if c.get("text"):
                    text += c["text"]
        print(f"  Result: {text[:150]}")
    else:
        print(f"  Error: {resp.text[:300]}")


if __name__ == "__main__":
    os.chdir(os.path.dirname(os.path.abspath(__file__)))

    if not os.path.exists(SOURCE_M4A):
        print(f"ERROR: {SOURCE_M4A} not found. Run test_ark_audio_m4a.py first.")
        sys.exit(1)

    # Split into segments
    print("=" * 60)
    print("STEP 1: Split audio into segments")
    print("=" * 60)
    segments = split_audio(SOURCE_M4A, 3)

    # Test different concatenation methods
    print("\n" + "=" * 60)
    print("STEP 2: Concatenate with different methods")
    print("=" * 60)

    concat_demuxer_file = "test_concat_demuxer.m4a"
    concat_filter_file = "test_concat_filter.m4a"
    concat_raw_file = "test_concat_raw.m4a"

    concat_with_ffmpeg_demuxer(segments, concat_demuxer_file)
    concat_with_ffmpeg_filter(segments, concat_filter_file)
    concat_with_raw_aac(segments, concat_raw_file)

    # Upload and test each
    print("\n" + "=" * 60)
    print("STEP 3: Upload and test each file with Ark API")
    print("=" * 60)

    upload_and_test(SOURCE_M4A, "Original (single source, not concatenated)")
    upload_and_test(concat_demuxer_file, "Concat via demuxer (stream copy, like MediaMuxer)")
    upload_and_test(concat_filter_file, "Concat via filter (re-encoded AAC)")
    upload_and_test(concat_raw_file, "Concat via raw AAC (ADTS concat + re-wrap)")

    # Cleanup
    for f in segments:
        if os.path.exists(f):
            os.remove(f)
