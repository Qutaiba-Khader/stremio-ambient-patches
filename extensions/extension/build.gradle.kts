extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.subsense.ambient"
    defaultConfig {
        minSdk = 26 // PixelCopy from a SurfaceView needs Android 7 (24); Stremio for Android TV needs 26 or later anyway
    }
}

// No dependency at all: the extension uses only the Android framework (PixelCopy, MediaFormat, sockets), so nothing in
// Stremio's shrunk code is named.
