"""Real pretrained UFLD v2 inference and original-image pixel decoding.

Preprocessing/anchor decoding adapted from the pinned upstream demo.py,
data/dataset.py and utils/common.py. See third_party/ufldv2-LICENSE.
This module never estimates the vehicle's absolute lane number.
"""

from pathlib import Path

import numpy as np
from PIL import Image
import torch
from torchvision import transforms

from .contracts import CameraObservation, PixelBoundary
from .ufld_model import UfldCulaneRes18


UPSTREAM_COMMIT = "c903880678454dfd9b55a63022368db05c00bc6d"
PROFILE = "ufldv2_culane_res18"
QUALITY_METHOD = "mean_existence_softmax_on_accepted_anchors; uncalibrated, not lane confidence"
EXPECTED_SHAPES = {
    "loc_row": (1, 200, 72, 4), "loc_col": (1, 100, 81, 4),
    "exist_row": (1, 2, 72, 4), "exist_col": (1, 2, 81, 4),
}


def preprocess(frame_bgr: np.ndarray) -> torch.Tensor:
    if frame_bgr.ndim != 3 or frame_bgr.shape[2] != 3 or frame_bgr.dtype != np.uint8:
        raise ValueError("expected an HxWx3 uint8 BGR frame")
    image = Image.fromarray(frame_bgr[:, :, ::-1].copy())
    # Match official PIL Resize -> ToTensor -> ImageNet Normalize -> bottom crop.
    transform = transforms.Compose([
        transforms.Resize((int(320 / 0.6), 1600)),
        transforms.ToTensor(),
        transforms.Normalize((0.485, 0.456, 0.406), (0.229, 0.224, 0.225)),
    ])
    return transform(image)[:, -320:, :].unsqueeze(0)


def decode_predictions(prediction: dict, width: int, height: int) -> tuple[PixelBoundary, ...]:
    if width < 1 or height < 1:
        raise ValueError("image dimensions must be positive")
    for name, shape in EXPECTED_SHAPES.items():
        if name not in prediction or tuple(prediction[name].shape) != shape:
            raise ValueError(f"{name}: expected CULane ResNet18 output shape {shape}")
        if not torch.isfinite(prediction[name]).all():
            raise ValueError(f"{name}: non-finite model output")
    # Model slots are frame-local boundary slots, NOT absolute road lane IDs.
    boundaries = []
    for slot in range(4):
        axis = "row" if slot in (1, 2) else "col"
        logits = prediction[f"loc_{axis}"].detach().cpu()[0, :, :, slot]
        existence = prediction[f"exist_{axis}"].detach().cpu()[0, :, :, slot]
        accepted = existence.argmax(0).bool()
        anchors = np.linspace(0.42, 1, 72) if axis == "row" else np.linspace(0, 1, 81)
        threshold = len(anchors) / (2 if axis == "row" else 4)
        points = []
        quality = None
        if int(accepted.sum()) > threshold:
            quality = float(existence.softmax(0)[1, accepted].mean())
            for anchor_index in torch.nonzero(accepted).flatten().tolist():
                maximum = int(logits[:, anchor_index].argmax())
                indices = torch.arange(max(0, maximum - 1), min(logits.shape[0] - 1, maximum + 1) + 1)
                position = float((logits[indices, anchor_index].softmax(0) * indices).sum() + 0.5)
                position /= logits.shape[0] - 1
                if axis == "row":
                    x, y = position * width, anchors[anchor_index] * height
                else:
                    x, y = anchors[anchor_index] * width, position * height
                # Upstream uses W/H as endpoints. Bound the exported pixel index.
                points.append((float(np.clip(x, 0, width - 1)), float(np.clip(y, 0, height - 1))))
        boundaries.append(PixelBoundary(f"slot_{slot}", tuple(points), len(points) >= 2, quality))
    return tuple(boundaries)


class UfldDetector:
    def __init__(self, weights: Path, device: str = "cpu", threads: int = 4):
        if not weights.is_file():
            raise FileNotFoundError(f"pretrained weights not found: {weights}")
        if device not in ("cpu", "cuda"):
            raise ValueError("device must be cpu or cuda")
        if device == "cuda" and not torch.cuda.is_available():
            raise ValueError("CUDA requested but unavailable; use --device cpu")
        if threads < 1:
            raise ValueError("threads must be positive")
        torch.set_num_threads(threads)
        self.device = torch.device(device)
        # Do not execute arbitrary checkpoint pickle objects or allow missing keys.
        checkpoint = torch.load(weights, map_location="cpu", weights_only=True)
        if not isinstance(checkpoint, dict) or not isinstance(checkpoint.get("model"), dict):
            raise ValueError("expected official checkpoint containing a 'model' state dict")
        state = {name.removeprefix("module."): value for name, value in checkpoint["model"].items()}
        with torch.device("meta"):
            self.model = UfldCulaneRes18()
        self.model.load_state_dict(state, strict=True, assign=True)
        self.model.to(self.device).eval()

    def detect(self, frame_bgr: np.ndarray, camera_id: str, frame_id: int,
               timestamp_ms: int | None = None, video_time_ms: float | None = None,
               timestamp_source: str = "unavailable") -> CameraObservation:
        height, width = frame_bgr.shape[:2]
        with torch.inference_mode():
            predictions = self.model(preprocess(frame_bgr).to(self.device))
        return CameraObservation(camera_id, frame_id, timestamp_ms, width, height,
            decode_predictions(predictions, width, height), video_time_ms, timestamp_source)
