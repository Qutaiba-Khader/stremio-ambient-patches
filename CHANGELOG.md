# Changelog

## 0.2.0 — 2026-11-12

- **HDR light, read correctly.** On Android 14, PixelCopy tone-maps an HDR video for a 1000-nit display and scales the copy
  so that 1000 nits is full white (Readback + libtonemap ToneMapper13): HDR reference white (203 nits) came out at 48 %,
  100 nits at 35 % — the very dim LEDs. The copy is now turned back into the picture's light in nits (the exact inverse of
  that pipeline, including the 650–4000-nit highlight compression), and the light goes to the LEDs through three new
  settings of the HDR set: **white level** (nits shown as full white, default 203), **highlight headroom** (LED range kept
  above white, default 25 %) and **brightest highlight** (nits, about the TV's peak, default 1000).
- **How the HDR picture is read** (HDR set): automatic — a hardware decoder's copy is tone-mapped by Android, a video
  Stremio decodes with FFmpeg holds the PQ/HLG signal itself — or one of them by hand, or "as is" (the old behaviour).
- The old "HDR correction" (which treated every copy as the PQ signal) is gone.
- **New settings page design**: sticky live preview with glowing points and room glow, Points and Colour cards, HDR light
  with a live nits-to-LED graph, connection and playback settings folded under "More settings".

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
