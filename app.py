from fastapi import FastAPI, File, UploadFile, Form
from typing import Optional
import shutil
import os
from transformers import Qwen2VLForConditionalGeneration, AutoProcessor
from qwen_vl_utils import process_vision_info
import torch

# Initialize FastAPI app
app = FastAPI()

# Directory to store uploaded files
UPLOAD_DIR = "uploads"
os.makedirs(UPLOAD_DIR, exist_ok=True)

# Load the Qwen2-VL model and processor
model = Qwen2VLForConditionalGeneration.from_pretrained(
    "Qwen/Qwen2-VL-7B-Instruct-GPTQ-Int4",
    torch_dtype=torch.float16,
    attn_implementation="flash_attention_2",
    device_map="auto",
)
min_pixels = 256 * 28 * 28
max_pixels = 1280 * 28 * 28
processor = AutoProcessor.from_pretrained("Qwen/Qwen2-VL-7B-Instruct-GPTQ-Int4", min_pixels=min_pixels, max_pixels=max_pixels)

@app.post("/upload/")
async def upload_video_text(video: UploadFile = File(...), text: str = Form(...)):
    # Save the uploaded video file
    video_path = os.path.join(UPLOAD_DIR, video.filename)
    with open(video_path, "wb") as buffer:
        shutil.copyfileobj(video.file, buffer)
    
    # Log the file size
    file_size = os.path.getsize(video_path)
    print(f"Received file: {video.filename}, size: {file_size} bytes")
    
    # Prepare the input messages for the model
    messages = [
        {
            "role": "user",
            "content": [
                {
                    "type": "video",
                    "video": f"file:///home/jon/Documents/uploads/recorded_video.mp4",
                    "max_pixels": 360 * 420,
                    "fps": 1.0,  # Default FPS (can be overridden by the backend)
                },
                {"type": "text", "text": text},
            ],
        }
    ]
    
    # Process the input for the model
    text_input = processor.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=True
    )
    image_inputs, video_inputs = process_vision_info(messages)
    inputs = processor(
        text=[text_input],
        images=image_inputs,
        videos=video_inputs,
        padding=True,
        return_tensors="pt",
    )
    inputs = inputs.to("cuda")
    
    # Perform inference
    generated_ids = model.generate(**inputs, max_new_tokens=128)
    generated_ids_trimmed = [
        out_ids[len(in_ids):] for in_ids, out_ids in zip(inputs.input_ids, generated_ids)
    ]
    output_text = processor.batch_decode(
        generated_ids_trimmed, skip_special_tokens=True, clean_up_tokenization_spaces=False
    )
    
    # Return the model's response
    return {output_text[0]}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)