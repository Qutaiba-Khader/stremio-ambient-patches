# Changelog

## 0.1.0 — 2026-11-12

First release.

- **Ambient light**: the playing picture's edges go to HyperHDR (flatbuffer server, default port 19400) while the hardware
  decoder stays on. PixelCopy of the player's video surface at 96×54, one average per point, black bars skipped.
- Points: counts per edge (top, bottom, left, right), distance from the edge, area depth and width, black-bar level.
- SDR and HDR are told apart from the video itself (MediaCodec's format, or Media3's format for videos Stremio's FFmpeg
  renderer decodes): PQ, HLG and Dolby Vision are HDR. Each has its own colour set (brightness, black level, gamma,
  saturation, colour temperature); the HDR set adds an HDR correction (PQ to SDR).
- Settings page on the TV, `http://<TV address>:8095`: live preview, every change applied at once and saved on the TV.
  Home network only. No HyperHDR address is built in: nothing is sent until it is typed on the page.
- Tunneled playback has its own switch on the page, off by default (Stremio ties it to "Audio passthrough"; a tunneled
  picture never reaches the app). Audio passthrough keeps working without it.
- **Ambient app name and icon**: "Stremio Ambient", with an edge-lit launcher icon and TV banner.
