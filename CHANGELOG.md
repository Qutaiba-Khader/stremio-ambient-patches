# Changelog

## 0.3.1 — 2026-11-25
- Built on the newest Morphe versions (automatic): patcher 2.5.0, smali 5eaff5a2c9, plugin 2.1.2 (Morphe CLI 2.3.0).
  Before: patcher 1.11.0, smali d856bad65f, plugin 1.3.4. No change to the patches themselves.
- Tested before release on the current Stremio apps, patched as morphe-builder does: every patch applied,
  with no warning about an older Morphe Patcher.

## 0.3.0 — 2026-11-12

- **HDR light tunes itself.** Real films are graded far below HDR's 203-nit reference white (edge light measured on
  the owner's TV: mostly 20–60 nits), so a fixed white level can't fit them all. **Automatic white level** (on by
  default): it follows the film's brightness like a camera's exposure — the 80th percentile of the points' light,
  smoothed over about 20 s, dark frames ignored — with a **Lights level** to make it brighter or dimmer. Calibrated so
  the owner's film lands where he set it by hand.
- **Brightest highlight from the TV** (on by default): the peak luminance the TV reports in its EDID (Android's
  Display.HdrCapabilities). A TV that reports none keeps the manual value. The page shows the TV's name, maker, year,
  HDR types and luminance as Android sees them.
- First run and every reset start in this automatic setup.
- **▲▼ arrows** on every slider value: one step per tap, repeating while held.
- New defaults: 11 updates per second; 6 / 6 / 3 / 3 points per edge. Settings that were still at the old defaults
  (20; 8 / 8 / 4 / 4) move to the new ones once.

## 0.2.1 — 2026-11-12

- **Black bars: true black only.** A row or column is a bar only when no pixel in it is brighter than the bar level,
  now 2 by default (a video's own black arrives as 0–1 after the GPU's YUV-to-RGB rounding); a dark picture is never a
  bar. Bars come in pairs (top/bottom, left/right: the smaller side counts, so a dark area on one side stays picture),
  and a new result must hold for 1.5 s before the areas move. The old level (24, up to 100) cut dark parts of the
  picture off and made the box jump. A stored level over the new limit (32) falls back to the default.
- **Resets that say what they reset:** "Reset points" on the Points card, "Reset the SDR/HDR set" on the Colour card,
  and "Reset all settings" out of the folded section.

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
