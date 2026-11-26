"""Read the project's EPSG:5179 A2/B2 Shapefile handoff for offline replay."""

from collections import defaultdict
from dataclasses import dataclass
from math import cos, radians, sin, sqrt, tan
from pathlib import Path
import struct

import numpy as np


def project_gps(latitude: float, longitude: float) -> tuple[float, float]:
    """WGS84 latitude/longitude to the handoff's GRS80 transverse Mercator grid."""
    phi, lam = radians(latitude), radians(longitude - 127.5)
    radius = 6378137.0
    flattening = 1 / 298.257222101
    e2 = flattening * (2 - flattening)

    def meridian(p: float) -> float:
        return radius * (
            (1 - e2 / 4 - 3 * e2**2 / 64 - 5 * e2**3 / 256) * p
            - (3 * e2 / 8 + 3 * e2**2 / 32 + 45 * e2**3 / 1024) * sin(2 * p)
            + (15 * e2**2 / 256 + 45 * e2**3 / 1024) * sin(4 * p)
            - 35 * e2**3 / 3072 * sin(6 * p)
        )

    n = radius / sqrt(1 - e2 * sin(phi)**2)
    ep2 = e2 / (1 - e2)
    t, c, h = tan(phi)**2, ep2 * cos(phi)**2, cos(phi) * lam
    east = n * (h + (1 - t + c) * h**3 / 6 + (5 - 18 * t + t*t + 72*c - 58*ep2) * h**5 / 120)
    north = meridian(phi) - meridian(radians(38)) + n * tan(phi) * (
        h*h / 2 + (5 - t + 9*c + 4*c*c) * h**4 / 24
        + (61 - 58*t + t*t + 600*c - 330*ep2) * h**6 / 720
    )
    return 1e6 + .9996 * east, 2e6 + .9996 * north


def _records(base: Path):
    dbf = base.with_suffix(".dbf").read_bytes()
    count, header, size = struct.unpack_from("<IHH", dbf, 4)
    fields = [
        (dbf[i:i + 11].split(b"\0")[0].decode("ascii").lower(), dbf[i + 16])
        for i in range(32, header - 1, 32)
    ]
    for index in range(count):
        row = dbf[header + index * size:header + (index + 1) * size]
        if len(row) != size:
            raise ValueError(f"Incomplete DBF row in {base}")
        if row[0:1] == b"*":
            yield None
            continue
        values, offset = {}, 1
        for name, width in fields:
            values[name] = row[offset:offset + width].decode("utf-8", errors="replace").strip()
            offset += width
        yield values


def _parts(base: Path):
    shape = base.with_suffix(".shp").read_bytes()
    offset = 100
    while offset < len(shape):
        size = 2 * struct.unpack_from(">I", shape, offset + 4)[0]
        record = memoryview(shape)[offset + 8:offset + 8 + size]
        offset += 8 + size
        kind = struct.unpack_from("<I", record)[0]
        if kind not in (3, 13, 23):
            raise ValueError(f"Expected a polyline in A2, got shape type {kind}")
        parts, points = struct.unpack_from("<II", record, 36)
        splits = struct.unpack_from(f"<{parts}I", record, 44)
        xy = np.frombuffer(record, dtype="<f8", count=2 * points, offset=44 + 4 * parts).reshape(-1, 2)
        yield tuple(xy[first:last] for first, last in zip(splits, (*splits[1:], points)))


@dataclass(frozen=True)
class LaneLink:
    number: str
    left: str
    right: str
    start_node: str
    end_node: str
    parts: tuple[np.ndarray, ...]


class ApproachMap:
    def __init__(self, directory: Path):
        a2 = directory / "a2_link"
        b2 = directory / "b2_surfacelinemark"
        for layer in (a2, b2):
            prj = layer.with_suffix(".prj").read_text(encoding="utf-8-sig")
            if not all(token in prj for token in (
                "KGD2002_Unified_Coordinate_System", "Transverse_Mercator", "127.5", "0.9996"
            )):
                raise ValueError(f"{layer.name} must use the EPSG:5179 handoff")
        self.links = {}
        for row, parts in zip(_records(a2), _parts(a2), strict=True):
            if row is not None:
                self.links[row["id"]] = LaneLink(
                    row["laneno"], row["l_linkid"], row["r_linkid"],
                    row["fromnodeid"], row["tonodeid"], parts,
                )
        if not self.links:
            raise ValueError("No A2 lane links")
        self.right_edges = frozenset(
            row["l_linkid"] for row in _records(b2)
            if row is not None and row["kind"] == "515" and not row["r_linkid"]
        )
        starts = defaultdict(list)
        for key, lane in self.links.items():
            if lane.start_node:
                starts[lane.start_node].append(key)
        self.successors = {key: tuple(starts.get(lane.end_node, ())) for key, lane in self.links.items()}
        self.numbers = {key: lane.number for key, lane in self.links.items()}
        self._segment_ids = []
        starts_xy, ends_xy = [], []
        for key, lane in self.links.items():
            for part in lane.parts:
                if len(part) < 2:
                    continue
                starts_xy.extend(part[:-1])
                ends_xy.extend(part[1:])
                self._segment_ids.extend([key] * (len(part) - 1))
        if not starts_xy:
            raise ValueError("A2 has no usable segments")
        self._starts = np.array(starts_xy)
        self._vectors = np.array(ends_xy) - self._starts
        lengths = np.sum(self._vectors ** 2, axis=1)
        self._lengths = np.where(lengths > 0, lengths, 1.0)

    def nearest(self, xy: tuple[float, float], radius_m: float = 20) -> str | None:
        delta = np.array(xy) - self._starts
        positions = np.clip(np.sum(delta * self._vectors, axis=1) / self._lengths, 0, 1)
        residual = delta - self._vectors * positions[:, None]
        distances = np.sum(residual ** 2, axis=1)
        nearest = int(np.argmin(distances))
        return self._segment_ids[nearest] if distances[nearest] <= radius_m**2 else None

    def lane_order(self, seed: str) -> tuple[str, ...]:
        visited = {seed}
        while self.links[seed].left in self.links:
            neighbor = self.links[seed].left
            if neighbor in visited or self.links[neighbor].right != seed:
                return ()
            seed = neighbor
            visited.add(seed)
        ordered = [seed]
        while self.links[ordered[-1]].right in self.links:
            neighbor = self.links[ordered[-1]].right
            if neighbor in ordered or self.links[neighbor].left != ordered[-1]:
                return ()
            ordered.append(neighbor)
        return tuple(ordered)

    def route_targets(self, connector: str | None) -> frozenset[str]:
        """Follow each direct approach predecessor backward along its numbered lane."""
        if connector is None:
            return frozenset()
        if connector not in self.links:
            raise ValueError(f"Unknown route link {connector}")
        predecessors = defaultdict(list)
        for key, successors in self.successors.items():
            for successor in successors:
                predecessors[successor].append(key)
        pending = list(predecessors[connector])
        matches = set()
        while pending:
            key = pending.pop()
            if key in matches or key == connector:
                continue
            matches.add(key)
            pending.extend(previous for previous in predecessors[key] if self.numbers[previous] == self.numbers[key])
        return frozenset(matches)
