import requests
import time
import os

API_KEY = "c2fc5c4e-009c-41cf-a893-f75e35647666"
BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
VIDEO_FILE = "f9e334147f394f294f5dff201c9b3f33.mp4"

headers = {"Authorization": f"Bearer {API_KEY}"}

print("===== TEST: fps=2 audio preservation =====\n")

# Upload with fps=2
print("[Upload] preprocess_configs[video][fps]=2")
with open(VIDEO_FILE, "rb") as f:
    resp = requests.post(
        f"{BASE_URL}/files",
        headers=headers,
        files={"file": (VIDEO_FILE, f, "video/mp4")},
        data={"purpose": "user_data", "preprocess_configs[video][fps]": "2"},
    )
upload = resp.json()
file_id = upload.get("id")
print(f"  File ID: {file_id}, Status: {upload.get('status')}")

# Wait
for i in range(20):
    time.sleep(3)
    resp = requests.get(f"{BASE_URL}/files/{file_id}", headers=headers)
    status = resp.json().get("status")
    print(f"  Attempt {i+1}: status={status}")
    if status in ("processed", "ready", "succeeded", "failed"):
        break
    if status == "active" and i > 3:
        break

# Call model
print("\n[Model] input_video only, asking for subtitles...")
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
                    {"type": "input_text", "text": "根据音轨输出前20秒字幕，格式[MM:SS] 文本"},
                ],
            }
        ],
    },
    timeout=300,
)

result = resp.json()
if "error" in result:
    print(f"[ERROR] {result['error']}")
else:
    for item in result.get("output", []):
        for c in item.get("content", []):
            if c.get("text"):
                print(c["text"])
    usage = result.get("usage", {})
    print(f"\n--- Usage: input={usage.get('input_tokens')}, output={usage.get('output_tokens')}")
    print(f"--- (fps=1 was 31991 input tokens, fps=2 should be higher due to more frames)")
