# Perspective control module

`LPCMatrixCalculator` builds a pinhole-camera homography from pitch, roll, focal length in pixels,
and the image principal point. It maps image points into a leveled plane; an image renderer must
apply the transform and decide how to crop or fill the expanded corners. `LpcOrientationTracker`
uses Android's fused rotation-vector sensor when present and must be started/stopped with the
camera preview.

This is a geometric prototype, not a calibrated lens correction profile. It does not account for
lens distortion, device-specific sensor alignment, optical stabilization, display-axis remapping,
or camera intrinsics beyond the values supplied by the caller. The sensor is optional; callers
should disable live correction when it is unavailable.
