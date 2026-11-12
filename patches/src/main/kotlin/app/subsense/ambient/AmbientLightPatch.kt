package app.subsense.ambient

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import org.w3c.dom.Element

private const val EXTENSION = "Lapp/subsense/ambient/Ambient;"
private const val APPLICATION = "Landroid/app/Application;"
private const val STREMIO_SETTINGS = "Lcom/stremio/core/types/profile/Profile\$Settings;"
private const val PROBE = "Lapp/subsense/ambient/FormatProbe;"
private const val MEDIA_CODEC = "Landroid/media/MediaCodec;"
private const val CONFIGURE_PARAMS = "Landroid/media/MediaFormat;Landroid/view/Surface;Landroid/media/MediaCrypto;I"

val STREMIO_TV = Compatibility(
    name = "Stremio",
    packageName = "com.stremio.one",
    apkFileType = ApkFileType.APK,
    appIconColor = 0x5B67F1,
    // Tested on Android TV 1.12.1. Nothing in Stremio's shrunk code is named: the hooks are the app's Application class,
    // Android's own MediaCodec.configure and the one place where Stremio's (unshrunk) profile settings turn tunneled
    // playback on. A release where one is not found fails the patch instead of shipping a broken app.
    targets = listOf(AppTarget(version = null, description = "Stremio for Android TV (tested: 1.12.1)")),
)

private fun refs(classDef: ClassDef) = classDef.methods.mapNotNull { m -> m.implementation?.let { m to it } }

@Suppress("unused")
val ambientLightPatch = bytecodePatch(
    name = "Ambient light",
    description = "Sends the colours along the playing picture's edges to HyperHDR (flatbuffer server), with a settings " +
        "page on the TV (http://<TV address>:8095). Tunneled playback gets its own switch there, off by default, because " +
        "a tunneled picture never reaches the app.",
    default = true,
) {
    compatibleWith(STREMIO_TV)
    extendWith("extensions/extension.mpe")

    execute {
        hookApplication()
        hookTunneling()
        hookCodecConfigure()
        bridgeFormatType()
    }
}

/**
 * Media3's Format, for FormatProbe (videos no MediaCodec decodes): the class with a static method that takes one of
 * its own and builds its log line ("id=", ", mimeType=", ", color="). FormatProbe.formatType() returns it.
 */
private fun BytecodePatchContext.bridgeFormatType() {
    val found = mutableListOf<String>()
    classDefForEach { c ->
        val log = c.methods.any { m ->
            AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "Ljava/lang/String;" &&
                m.parameterTypes.size == 1 && m.parameterTypes[0].toString() == c.type &&
                m.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                    ?.let { s -> "id=" in s && ", mimeType=" in s && ", color=" in s } == true
        }
        if (log) found += c.type
    }
    val format = found.singleOrNull() ?: throw PatchException("Media3 Format: ${found.size} candidates ($found)")
    val probe = mutableClassDefBy(PROBE)
    val stub = probe.methods.first { it.name == "formatType" }
    probe.methods.remove(stub)
    probe.methods.add(
        ImmutableMethod(
            probe.type, stub.name, emptyList(), stub.returnType, stub.accessFlags, null, null, MutableMethodImplementation(1),
        ).toMutable().apply { addInstructions(0, "const-class v0, $format\nreturn-object v0") },
    )
}

/**
 * Start of the Application's onCreate: settings, the page and the activity watcher start. The start, not the end: the
 * app's code reuses p0 for other objects once `this` is no longer needed, so only the first instruction is sure of it.
 */
private fun BytecodePatchContext.hookApplication() {
    val apps = mutableListOf<ClassDef>()
    classDefForEach { c ->
        if (c.superclass == APPLICATION && !AccessFlags.ABSTRACT.isSet(c.accessFlags) &&
            c.methods.any { it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V" && it.implementation != null }
        ) apps += c
    }
    val app = apps.singleOrNull() ?: throw PatchException("Application class: ${apps.size} candidates (${apps.joinToString { it.type }})")
    val onCreate = mutableClassDefBy(app).methods.first { it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V" }
    // the range form names any register (a large onCreate puts p0 above v15)
    onCreate.addInstructions(0, "invoke-static/range {p0 .. p0}, $EXTENSION->onAppCreate($APPLICATION)V")
}

/**
 * Stremio sets Media3's tunnelingEnabled straight from its "Audio passthrough" setting:
 *   invoke-virtual {…}, Profile$Settings->getAudioPassthrough()Z / move-result vX / iput-boolean vX, <builder>->…:Z
 * The value goes through Ambient.tunneling (the page's own switch, off by default). Exactly one such place must exist.
 */
private fun BytecodePatchContext.hookTunneling() {
    data class Site(val type: String, val method: String, val index: Int, val register: Int)
    val sites = mutableListOf<Site>()
    classDefForEach { c ->
        for ((m, impl) in refs(c)) {
            val ins = impl.instructions.toList()
            for (i in 0 until ins.size - 2) {
                val call = (ins[i] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (call.definingClass != STREMIO_SETTINGS || call.name != "getAudioPassthrough" || call.returnType != "Z") continue
                if (ins[i + 1].opcode != Opcode.MOVE_RESULT || ins[i + 2].opcode != Opcode.IPUT_BOOLEAN) continue
                val reg = (ins[i + 1] as OneRegisterInstruction).registerA
                val put = ins[i + 2] as TwoRegisterInstruction
                if (put.registerA != reg || ((ins[i + 2] as ReferenceInstruction).reference as FieldReference).type != "Z") continue
                sites += Site(c.type, m.name + m.parameterTypes.joinToString("", "(", ")") + m.returnType, i + 2, reg)
            }
        }
    }
    val site = sites.singleOrNull() ?: throw PatchException("tunneling: ${sites.size} places where audio passthrough sets a flag")
    val method = mutableClassDefBy(site.type).methods.first { it.name + it.parameterTypes.joinToString("", "(", ")") + it.returnType == site.method }
    val r = "v${site.register}"
    if (site.register > 255) throw PatchException("tunneling: register $r out of range")
    val call = if (site.register > 15) "invoke-static/range {$r .. $r}" else "invoke-static {$r}"
    method.addInstructions(site.index, "$call, $EXTENSION->tunneling(Z)Z\nmove-result $r")
}

/** Before every MediaCodec.configure(format, surface, crypto, flags): the format tells SDR or HDR. */
private fun BytecodePatchContext.hookCodecConfigure() {
    data class Site(val type: String, val method: String, val index: Int, val format: Int, val surface: Int, val range: Boolean)
    val sites = mutableListOf<Site>()
    classDefForEach { c ->
        for ((m, impl) in refs(c)) {
            impl.instructions.forEachIndexed { i, insn ->
                val call = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
                if (call.definingClass != MEDIA_CODEC || call.name != "configure" || call.returnType != "V" ||
                    call.parameterTypes.joinToString("") != CONFIGURE_PARAMS
                ) return@forEachIndexed
                val key = m.name + m.parameterTypes.joinToString("", "(", ")") + m.returnType
                when (insn) {
                    is FiveRegisterInstruction -> sites += Site(c.type, key, i, insn.registerD, insn.registerE, false)
                    is RegisterRangeInstruction -> sites += Site(c.type, key, i, insn.startRegister + 1, insn.startRegister + 2, true)
                    else -> throw PatchException("MediaCodec.configure: unexpected call form in ${c.type}")
                }
            }
        }
    }
    if (sites.isEmpty()) throw PatchException("MediaCodec.configure: no call found")
    // later places first, so the earlier indexes stay valid
    for (s in sites.sortedByDescending { it.index }) {
        val method = mutableClassDefBy(s.type).methods.first { it.name + it.parameterTypes.joinToString("", "(", ")") + it.returnType == s.method }
        val call = if (s.range) "invoke-static/range {v${s.format} .. v${s.surface}}" else "invoke-static {v${s.format}, v${s.surface}}"
        method.addInstructions(s.index, "$call, $EXTENSION->onCodecConfigure(Landroid/media/MediaFormat;Landroid/view/Surface;)V")
    }
}

/** The launcher icon and the TV banner share one background: the normal one, lit from its edges like a TV with LEDs behind it. */
private const val GLOW_BACKGROUND = """<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape>
            <gradient android:startColor="#ff000000" android:endColor="#ff1a124f" android:angle="0.0" android:type="linear" />
        </shape>
    </item>
    <item>
        <shape>
            <gradient android:type="linear" android:angle="0.0"
                android:startColor="#b300d8ff" android:centerColor="#00000000" android:endColor="#b3ff3dc8" />
        </shape>
    </item>
    <item>
        <shape>
            <gradient android:type="linear" android:angle="90.0"
                android:startColor="#80ff9d00" android:centerColor="#00000000" android:endColor="#809d7bff" />
        </shape>
    </item>
    <item>
        <shape>
            <gradient android:type="radial" android:gradientRadius="48%p" android:centerX="0.5" android:centerY="0.5"
                android:startColor="#e6000000" android:centerColor="#99000000" android:endColor="#00000000" />
        </shape>
    </item>
</layer-list>
"""

@Suppress("unused")
val ambientBrandingPatch = resourcePatch(
    name = "Ambient app name and icon",
    description = "Names the app \"Stremio Ambient\" and gives its launcher icon and TV banner an LED glow, so it is told " +
        "apart from the original and from the subtitle copy.",
    default = true,
) {
    compatibleWith(STREMIO_TV)

    execute {
        document("res/values/strings.xml").use { document ->
            val children = document.documentElement.childNodes
            var named = false
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue
                if (node.tagName == "string" && node.getAttribute("name") == "app_name") {
                    node.textContent = "Stremio Ambient"
                    named = true
                }
            }
            if (!named) throw PatchException("app_name not found")
        }
        val background = get("res/drawable/ic_background.xml")
        if (!background.exists()) throw PatchException("res/drawable/ic_background.xml not found (the icon's background)")
        background.writeText(GLOW_BACKGROUND)
    }
}
