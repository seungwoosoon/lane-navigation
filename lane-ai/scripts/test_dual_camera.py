"""Preview two Windows cameras; this does not record or synchronize frames."""

import argparse

import cv2


def main() -> int:
    parser = argparse.ArgumentParser(description="Preview two cameras side by side in separate windows")
    parser.add_argument("--camera-ids", nargs=2, type=int, default=(1, 2), metavar=("LEFT", "RIGHT"),
                        help="OpenCV device indexes (default: 1 2; the laptop camera may be 0)")
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--fps", type=int, default=30, help="requested camera FPS, not measured FPS")
    parser.add_argument("--rotate-180", nargs="+", type=int, default=(), metavar="ID",
                        help="rotate these camera indexes by 180 degrees for upside-down mounting")
    args = parser.parse_args()

    if len(set(args.camera_ids)) != 2 or any(index < 0 for index in args.camera_ids):
        parser.error("choose two different non-negative camera indexes")
    if args.width < 1 or args.height < 1 or args.fps < 1:
        parser.error("width, height and fps must be positive")
    if not set(args.rotate_180).issubset(args.camera_ids):
        parser.error("--rotate-180 indexes must be included in --camera-ids")

    cameras = []
    try:
        for label, index in zip(("LEFT", "RIGHT"), args.camera_ids):
            camera = cv2.VideoCapture(index, cv2.CAP_DSHOW)
            if not camera.isOpened():
                camera.release()
                raise RuntimeError(f"Cannot open camera {index} ({label}); try other --camera-ids")
            cameras.append((label, index, camera))
            camera.set(cv2.CAP_PROP_FRAME_WIDTH, args.width)
            camera.set(cv2.CAP_PROP_FRAME_HEIGHT, args.height)
            camera.set(cv2.CAP_PROP_FPS, args.fps)
            camera.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))

        for label, index, camera in cameras:
            print(f"{label} camera {index}: driver reports "
                  f"{camera.get(cv2.CAP_PROP_FRAME_WIDTH):.0f}x"
                  f"{camera.get(cv2.CAP_PROP_FRAME_HEIGHT):.0f} "
                  f"at {camera.get(cv2.CAP_PROP_FPS):.1f} FPS; actual capture FPS unverified")
        print("Cover each lens to identify LEFT/RIGHT. Press q in either preview window to quit.")

        while True:
            for label, index, camera in cameras:
                ok, frame = camera.read()
                if not ok or frame is None:
                    raise RuntimeError(f"Cannot read a frame from camera {index} ({label})")
                if index in args.rotate_180:
                    frame = cv2.rotate(frame, cv2.ROTATE_180)
                cv2.imshow(f"{label} - camera {index}", frame)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break
    except (RuntimeError, cv2.error) as exc:
        print(f"Camera test failed: {exc}")
        return 1
    finally:
        for _, _, camera in cameras:
            camera.release()
        cv2.destroyAllWindows()
    return 0


if __name__ == "__main__":

    raise SystemExit(main())
