"""Inference-only adaptation of official UFLD v2 CULane ResNet18.

Source: cfzd/Ultra-Fast-Lane-Detection-v2 @ c903880678454dfd9b55a63022368db05c00bc6d
model/model_culane.py and model/backbone.py. MIT; see third_party/ufldv2-LICENSE.
No training helpers, DALI, auxiliary segmentation, TTA, or automatic downloads.
Parameter names match the official pretrained checkpoint exactly.
"""

import torch
from torchvision.models import resnet18


class Backbone(torch.nn.Module):
    def __init__(self):
        super().__init__()
        base = resnet18(weights=None)
        for name in ("conv1", "bn1", "relu", "maxpool", "layer1", "layer2", "layer3", "layer4"):
            setattr(self, name, getattr(base, name))

    def forward(self, x):
        for name in ("conv1", "bn1", "relu", "maxpool", "layer1", "layer2", "layer3", "layer4"):
            x = getattr(self, name)(x)
        return x


class UfldCulaneRes18(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.model = Backbone()
        self.input_dim = 320 // 32 * (1600 // 32) * 8
        self.dims = (200 * 72 * 4, 100 * 81 * 4, 2 * 72 * 4, 2 * 81 * 4)
        self.cls = torch.nn.Sequential(
            torch.nn.LayerNorm(self.input_dim),
            torch.nn.Linear(self.input_dim, 2048),
            torch.nn.ReLU(),
            torch.nn.Linear(2048, sum(self.dims)),
        )
        self.pool = torch.nn.Conv2d(512, 8, 1)

    def forward(self, x):
        out = self.cls(self.pool(self.model(x)).reshape(-1, self.input_dim))
        row, col, exist_row, exist_col = out.split(self.dims, dim=1)
        return {
            "loc_row": row.reshape(-1, 200, 72, 4),
            "loc_col": col.reshape(-1, 100, 81, 4),
            "exist_row": exist_row.reshape(-1, 2, 72, 4),
            "exist_col": exist_col.reshape(-1, 2, 81, 4),
        }
