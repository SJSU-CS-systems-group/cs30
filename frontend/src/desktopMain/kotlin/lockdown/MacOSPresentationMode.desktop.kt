package lockdown

import com.sun.jna.NativeLibrary
import com.sun.jna.Platform

// NSApplicationPresentationOptions flags from macOS AppKit
private const val NS_PRESENTATION_DEFAULT: Long              = 0L
private const val NS_PRESENTATION_HIDE_DOCK: Long            = 1L shl 1   // 2
private const val NS_PRESENTATION_HIDE_MENU_BAR: Long        = 1L shl 3   // 8
private const val NS_PRESENTATION_DISABLE_PROCESS_SWITCHING: Long = 1L shl 5 // 32

// Lockdown options: hide Dock + menu bar + block Cmd+Tab
// (DisableProcessSwitching requires HideDock to be set)
private const val LOCKDOWN_PRESENTATION: Long =
    NS_PRESENTATION_HIDE_DOCK or
    NS_PRESENTATION_HIDE_MENU_BAR or
    NS_PRESENTATION_DISABLE_PROCESS_SWITCHING

fun applyMacOSLockdownPresentation() {
    if (!Platform.isMac()) {
        println("[MacOSPresentation] Skipping (not macOS)")
        return
    }
    try {
        println("[MacOSPresentation] 📱 Applying presentation options: 0x${LOCKDOWN_PRESENTATION.toString(16)} (HideDock=${LOCKDOWN_PRESENTATION and NS_PRESENTATION_HIDE_DOCK != 0L}, HideMenuBar=${LOCKDOWN_PRESENTATION and NS_PRESENTATION_HIDE_MENU_BAR != 0L}, DisableProcessSwitching=${LOCKDOWN_PRESENTATION and NS_PRESENTATION_DISABLE_PROCESS_SWITCHING != 0L})")
        setNSPresentationOptions(LOCKDOWN_PRESENTATION)
        println("[MacOSPresentation] ✅ Lockdown options applied (hide Dock + menu bar + disable Cmd+Tab)")
    } catch (e: Exception) {
        println("[MacOSPresentation] ❌ Failed to set lockdown options: ${e.message}")
        e.printStackTrace()
    }
}

fun restoreMacOSPresentation() {
    if (!Platform.isMac()) {
        println("[MacOSPresentation] Skipping restore (not macOS)")
        return
    }
    try {
        println("[MacOSPresentation] 📱 Restoring presentation options to 0x${NS_PRESENTATION_DEFAULT.toString(16)}")
        setNSPresentationOptions(NS_PRESENTATION_DEFAULT)
        println("[MacOSPresentation] ✅ Presentation options restored to default (Dock + menu bar visible)")
    } catch (e: Exception) {
        println("[MacOSPresentation] ❌ Failed to restore options: ${e.message}")
        e.printStackTrace()
    }
}

private fun setNSPresentationOptions(options: Long) {
    val objc = NativeLibrary.getInstance("objc")
    val getClass = objc.getFunction("objc_getClass")
    val msgSend = objc.getFunction("objc_msgSend")
    val selRegister = objc.getFunction("sel_registerName")

    val nsAppClass = getClass.invokePointer(arrayOf("NSApplication"))
    val sharedAppSel = selRegister.invokePointer(arrayOf("sharedApplication"))
    val nsApp = msgSend.invokePointer(arrayOf(nsAppClass, sharedAppSel))
    val setPresentationSel = selRegister.invokePointer(arrayOf("setPresentationOptions:"))
    msgSend.invoke(Long::class.java, arrayOf(nsApp, setPresentationSel, options))
}
