package com.andrerinas.headunitrevived.connection

import android.hardware.usb.UsbDevice
import com.andrerinas.headunitrevived.aap.Utils
import java.util.Locale

/**
 * @author algavris
 * *
 * @date 12/05/2016.
 */
class UsbDeviceCompat(val wrappedDevice: UsbDevice) {

    val deviceName: String
        get() = wrappedDevice.deviceName

    val uniqueName: String
        get() = getUniqueName(wrappedDevice)

    override fun toString(): String {
        return String.format(Locale.US, "%s - %s", uniqueName, wrappedDevice.toString())
    }

    val isInAccessoryMode: Boolean
        get() = isInAccessoryMode(wrappedDevice)

    companion object {
        private const val USB_VID_GOO = 0x18D1   // 6353   Nexus or ACC mode, see PID to distinguish
        private const val USB_VID_HTC = 0x0bb4   // 2996
        private const val USB_VID_SAM = 0x04e8   // 1256
        private const val USB_VID_O1A = 0xfff6   // 65526    Samsung ?
        private const val USB_VID_SON = 0x0fce   // 4046
        private const val USB_VID_LGE = 0x1004   // 65525
        private const val USB_VID_MOT = 0x22b8   // 8888
        private const val USB_VID_ACE = 0x0502
        private const val USB_VID_HUA = 0x12d1
        private const val USB_VID_ZTE = 0x19d2
        private const val USB_VID_XIA = 0x2717
        private const val USB_VID_ASU = 0x0b05
        private const val USB_VID_MEI = 0x2a45
        private const val USB_VID_WIL = 0x4ee7

        private const val USB_PID_ACC = 0x2D00      // Accessory                  100
        private const val USB_PID_ACC_ADB = 0x2D01      // Accessory + ADB            110

        private val VENDOR_NAMES = mapOf(
            USB_VID_GOO to "Google",
            USB_VID_HTC to "HTC",
            USB_VID_SAM to "Samsung",
            USB_VID_SON to "Sony",
            USB_VID_MOT to "Motorola",
            USB_VID_LGE to "LG",
            USB_VID_O1A to "O1A",
            USB_VID_HUA to "Huawei",
            USB_VID_ACE to "Acer",
            USB_VID_ZTE to "ZTE",
            USB_VID_XIA to "Xiaomi",
            USB_VID_ASU to "Asus",
            USB_VID_MEI to "Meizu",
            USB_VID_WIL to "Wileyfox"
        )

        fun getUniqueName(device: UsbDevice): String {
            val vendorId = device.vendorId  // mVendorId=2996               HTC
            val productId = device.productId  // mProductId=1562              OneM8

//            if (App.IS_LOLLIPOP) {                                 // Android 5.0+ only
//                try {
//                    dev_man = usb_man_get(device).toUpperCase(Locale.getDefault())                             // mManufacturerName=HTC
//                    dev_prod = usb_pro_get(device).toUpperCase(Locale.getDefault())                                // mProductName=Android Phone
//                    dev_ser = usb_ser_get(device).toUpperCase(Locale.getDefault())                              // mSerialNumber=FA46RWM22264
//                } catch (e: Throwable) {
//                    AppLog.e(e)
//                }
//            }

            var usb_dev_name = ""
            usb_dev_name += VENDOR_NAMES[vendorId] ?: "$vendorId"
            usb_dev_name += " "
            usb_dev_name += Utils.hex_get(vendorId.toShort())
            usb_dev_name += ":"
            usb_dev_name += Utils.hex_get(productId.toShort())

            return usb_dev_name
        }

        fun isInAccessoryMode(device: UsbDevice): Boolean {
            val dev_vend_id = device.vendorId
            val dev_prod_id = device.productId
            return dev_vend_id == UsbDeviceCompat.USB_VID_GOO &&
                    (dev_prod_id == UsbDeviceCompat.USB_PID_ACC || dev_prod_id == UsbDeviceCompat.USB_PID_ACC_ADB)
        }
    }
}
