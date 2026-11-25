# Stremio Ambient Patches

[Morphe](https://morphe.software) patches for **Stremio for Android TV** (tested: 1.12.1, `com.stremio.one`, the
manual-install APK from [stremio.com/downloads](https://www.stremio.com/downloads)) that send the colours along the
playing picture's edges to [HyperHDR](https://github.com/awawa-dev/HyperHDR), like a TV's own ambient light — with the
hardware decoder left on.

They are made to be applied together with the subtitle patches of
[stremio-subs-patches](https://github.com/Qutaiba-Khader/stremio-subs-patches) and Morphe's "Clone app", so the result
("Stremio Ambient") installs beside the original Stremio.

## Ambient light

Every 1/rate seconds the patch copies the player's video surface with Android's `PixelCopy` into a 96×54 picture (the
decoder's own output, scaled by the GPU), averages one area per point, adjusts the colours and sends HyperHDR a small
border image over its flatbuffer protocol (Register with the chosen priority, then RawImage messages; Clear when the
video leaves, the light is turned off or the address is removed). HyperHDR's LED layout then samples that image.

- **Points**: a count per edge. Each edge is cut into equal shares, one per point; an area covers *width* % of its
  share, starts *distance* % of the picture's height in from the edge and reaches *depth* % of the height into the
  picture. With *skip black bars* on, rows and columns where no pixel reaches the *bar level* are left out first.
- **SDR / HDR**: read from the video (`MediaCodec.configure`'s format, or Media3's `Format` for videos that no
  MediaCodec decodes, such as Stremio's FFmpeg renderer): PQ, HLG and Dolby Vision count as HDR. Each has its own colour
  set — brightness, black level, gamma, saturation, colour temperature. Either set can also be forced.
- **HDR light**: Android 14's PixelCopy tone-maps an HDR video for a 1000-nit display and scales the copy so that 1000 nits
  is full white (frameworks/base `libs/hwui/Readback.cpp`, `Tonemapper.cpp`; frameworks/native `libs/shaders`,
  `libs/tonemap` ToneMapper13: below 650 nits kept, 650–4000 nits compressed onto 650–1000, divided by 1000, gamma 2.2).
  Read as SDR that is far too dim (203-nit white at 48 %). The patch undoes exactly that to get the picture's light in
  nits back — or, for a video decoded by Stremio's FFmpeg renderer (no dataspace on the window), decodes the PQ/HLG signal
  itself — and maps the light to the LEDs with the HDR set's **white level** (nits shown as full white), **highlight
  headroom** (LED range kept above white for highlights) and **brightest highlight** (nits, about the TV's peak).
- **Automatic HDR light**: the white level follows the film (its edge light's 80th percentile, smoothed over about
  20 s, dark frames ignored), and the brightest highlight comes from the TV's EDID (`Display.HdrCapabilities`) when
  the TV reports it. Both are the defaults; either can be set by hand.
- **Tunneled playback**: Stremio turns it on with "Audio passthrough", and a tunneled picture never reaches the app. The
  patch gives it its own switch on the page, **off by default**; audio passthrough keeps working without it.
- **Nothing is built in**: there is no HyperHDR address in the code and no discovery. Until one is typed on the page
  nothing is sent (the first start shows the page's address in a toast).

## The settings page

While Stremio runs, `http://<TV address>:8095/` (port changeable) shows a live preview of the points and their colours,
which colour set is in use, and every setting. A change applies at once and is saved on the TV. The page answers only
clients on private / link-local / loopback addresses, only for a `Host` that is an address or a local name, and a change
must carry the header `X-Ambient` (so another web site cannot change anything).

## Hooks

Nothing in Stremio's shrunk code is named; a release where a hook is not found fails the patch instead of shipping a
broken app:

- the start of the app's `Application.onCreate` (the only `Application` subclass);
- the one place where Stremio's (unshrunk) `Profile$Settings.getAudioPassthrough()` result is stored straight into a
  boolean field (Media3's `tunnelingEnabled`);
- before every `android.media.MediaCodec.configure(MediaFormat, Surface, MediaCrypto, int)`;
- Media3's `Format` class, found by the strings of its log line, for `FormatProbe`.

## Building

```bash
./gradlew :patches:build          # needs GITHUB_ACTOR / GITHUB_TOKEN (read:packages) for the Morphe registry
bash tools/unit-test.sh           # the pure parts on a plain JVM
python3 test/fake-hyperhdr.py     # a stand-in HyperHDR that decodes and logs what arrives (port 19410)
```

Then patch with the Morphe CLI, for example:

```bash
java -jar morphe-desktop-<ver>-all.jar patch \
  -p stremio-subs-patches.mpp -e "Subtitle auto-refresh" -e "Right-to-left subtitle lines" \
  -p patches-<this version>.mpp -e "Ambient light" -e "Ambient app name and icon" \
  -p morphe-patches.mpp -e "Clone app" --exclusive -OpackageName=com.stremio.one.ambient \
  -o stremio-ambient.apk <Stremio for Android TV>.apk
```

A tag `vX.Y.Z` publishes `patches-X.Y.Z.mpp` as a GitHub release.

## Newest Morphe Patcher, automatically

Every day `.github/workflows/patcher-update.yml` asks whether the last release is built on the newest Morphe versions:
the Morphe Patcher the newest Morphe CLI (morphe-desktop) is built with, its smali, and the newest Morphe Gradle plugin
(`tools/morphe_versions.py`). If not, it builds the patches on them, patches the current Stremio apps from Stremio's own
feed exactly as morphe-builder does (`tools/stremio_check.py`, apps listed in `tools/stremio-check.json`), and publishes
the next patch version only when every patch applied and the CLI gave no warning about an older Morphe Patcher.
morphe-builder then builds the apps from that release. If anything fails, nothing is published and the last release
stays in use. "Run workflow" on another branch tests that branch with Stremio without publishing.

## License

GPLv3 — see `LICENSE` and `NOTICE`.
