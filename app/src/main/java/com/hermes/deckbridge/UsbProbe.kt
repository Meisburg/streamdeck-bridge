package com.hermes.deckbridge

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Diagnostic probe: report what the USB stack actually does.
 *
 * Written because controlTransfer calls were timing out at exactly the
 * timeout value with no error in the system log, which is ambiguous between
 * "interface not owned", "stale file descriptor" and "wrong request". This
 * prints the raw return codes so the cause is observed rather than inferred.
 */
object UsbProbe {

    private const val TAG = "UsbProbe"

    fun run(ctx: Context) {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = mgr.deviceList.values.firstOrNull {
            it.vendorId == DeckProtocol.VENDOR_ID &&
                it.productId in DeckProtocol.SUPPORTED_PIDS
        }
        if (dev == null) {
            Log.i(TAG, "no deck in deviceList (size=${mgr.deviceList.size})")
            return
        }

        Log.i(TAG, "device: ${dev.deviceName} id=${dev.deviceId} " +
            "ifaces=${dev.interfaceCount} hasPermission=${mgr.hasPermission(dev)}")

        if (!mgr.hasPermission(dev)) {
            Log.i(TAG, "permission NOT held; control transfers cannot be tested")
            return
        }

        val conn = mgr.openDevice(dev)
        if (conn == null) {
            Log.i(TAG, "openDevice returned null")
            return
        }
        Log.i(TAG, "openDevice ok, fd=${conn.fileDescriptor}")

        // Enumerate interfaces and endpoints.
        for (i in 0 until dev.interfaceCount) {
            val ifc = dev.getInterface(i)
            Log.i(TAG, "iface $i id=${ifc.id} class=${ifc.interfaceClass} " +
                "sub=${ifc.interfaceSubclass} proto=${ifc.interfaceProtocol} " +
                "eps=${ifc.endpointCount}")
            for (e in 0 until ifc.endpointCount) {
                val ep = ifc.getEndpoint(e)
                Log.i(TAG, "  ep addr=0x${"%02X".format(ep.address)} " +
                    "type=${ep.type} dir=${ep.direction} max=${ep.maxPacketSize}")
            }
        }

        val ifc = dev.getInterface(0)

        val claimedForce = conn.claimInterface(ifc, true)
        Log.i(TAG, "claimInterface(force=true) -> $claimedForce")
        val claimed = claimedForce || conn.claimInterface(ifc, false)
        Log.i(TAG, "claimInterface effective -> $claimed")

        // GET_REPORT, input, report id 1 (the key-state channel).
        val buf = ByteArray(64)
        val n = try {
            conn.controlTransfer(0xA1, 0x01, 0x0100, 0, buf, buf.size, 500)
        } catch (t: Throwable) {
            Log.w(TAG, "getReport threw: ${t::class.java.simpleName} ${t.message}")
            -99
        }
        Log.i(TAG, "GET_REPORT(input,id=1) -> $n" +
            if (n > 0) " first=${buf.take(8).joinToString(" ") { "%02X".format(it) }}" else "")

        // SET_REPORT feature: show logo, 32 bytes.
        val feature = DeckProtocol.buildShowLogo()
        val m = try {
            conn.controlTransfer(0x21, 0x09, 0x0300, 0, feature, feature.size, 500)
        } catch (t: Throwable) {
            Log.w(TAG, "setFeature threw: ${t::class.java.simpleName} ${t.message}")
            -99
        }
        Log.i(TAG, "SET_REPORT(feature,showLogo) -> $m")

        // A plain output write on the interrupt endpoint.
        val outEp = (0 until ifc.endpointCount)
            .map { ifc.getEndpoint(it) }
            .firstOrNull { it.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT }
        if (outEp != null) {
            val report = ByteArray(DeckProtocol.OUTPUT_REPORT_SIZE)
            report[0] = 0x02
            val w = try {
                conn.bulkTransfer(outEp, report, report.size, 500)
            } catch (t: Throwable) {
                Log.w(TAG, "bulkTransfer threw: ${t::class.java.simpleName} ${t.message}")
                -99
            }
            Log.i(TAG, "bulkTransfer(out,resetKeyStream) -> $w")
        }

        conn.releaseInterface(ifc)
        conn.close()
        Log.i(TAG, "probe complete")
    }
}
