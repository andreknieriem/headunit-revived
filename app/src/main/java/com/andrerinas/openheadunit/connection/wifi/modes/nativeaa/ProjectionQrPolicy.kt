package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * What the Native AA setup screen knows when the user asks for a QR code. Null fields are things
 * this head unit has not resolved yet rather than things it does not have.
 */
data class ProjectionQrSnapshot(
    val strategy: NativeStrategy,
    val ssid: String?,
    val passkey: String?,
    val bssid: String?,
    val ip: String?,
    val listeningPort: Int?,
    val bluetoothMac: String?,
    val bluetoothName: String?,
)

/**
 * Whether a setup QR can be shown, and why not when it cannot.
 *
 * Scanning the QR writes this head unit's network and TCP endpoint into the phone's known-car
 * record, which outlives the session and is only cleared by forgetting the head unit. So it is
 * offered under the same two conditions [WppEndpointPolicy] puts on advertising the endpoint over
 * Bluetooth: our own access point, whose name and address survive the record, and a server that is
 * actually listening on the port the record will name.
 *
 * The refusals are separate values because each one names a different thing for the user to do.
 */
object ProjectionQrPolicy {

    enum class Refusal {
        /** Native AA is not running, so nothing has resolved a network or a port yet. */
        NOT_RUNNING,

        /** WiFi Direct: the group is renamed and re-keyed on every create, so the record goes stale. */
        NOT_HOTSPOT,

        /** No WPP TCP server is listening, so the record would name a port nothing answers. */
        NOT_LISTENING,

        NO_CREDENTIALS,
        NO_BSSID,
        NO_ADDRESS,

        /** No readable Bluetooth address to identify this head unit by. */
        NO_BLUETOOTH_IDENTITY,

        /** This unit's Bluetooth name reads as a dongle, which sends the phone down another path. */
        DONGLE_IDENTITY,
    }

    sealed class Result {
        data class Show(val url: String) : Result()
        data class Refuse(val refusal: Refusal) : Result()
    }

    fun decide(snapshot: ProjectionQrSnapshot?): Result {
        if (snapshot == null) return Result.Refuse(Refusal.NOT_RUNNING)
        if (snapshot.strategy != NativeStrategy.HOTSPOT) return Result.Refuse(Refusal.NOT_HOTSPOT)
        val port = snapshot.listeningPort ?: return Result.Refuse(Refusal.NOT_LISTENING)
        if (ProjectionDeepLink.looksLikeDongle(snapshot.bluetoothName)) {
            return Result.Refuse(Refusal.DONGLE_IDENTITY)
        }
        // Ahead of the link builder, which only checks the shape: Android hands a non-privileged
        // app 02:00:00:00:00:00 for its own adapter, and that is MAC-shaped. A QR naming it would
        // scan cleanly and then match no device the phone is ever connected to.
        if (!SoftApBssidPolicy.isUsable(snapshot.bluetoothMac)) {
            return Result.Refuse(Refusal.NO_BLUETOOTH_IDENTITY)
        }

        val built = ProjectionDeepLink.build(
            ssid = snapshot.ssid.orEmpty(),
            passkey = snapshot.passkey.orEmpty(),
            bssid = snapshot.bssid.orEmpty(),
            wppTcpIp = snapshot.ip.orEmpty(),
            wppTcpPort = port,
            bluetoothMac = snapshot.bluetoothMac.orEmpty(),
        )
        return when (built) {
            is ProjectionDeepLink.Result.Ok -> Result.Show(built.url)
            is ProjectionDeepLink.Result.Failed -> Result.Refuse(
                when (built.invalid) {
                    ProjectionDeepLink.Invalid.NoSsid,
                    ProjectionDeepLink.Invalid.NoPasskey -> Refusal.NO_CREDENTIALS
                    ProjectionDeepLink.Invalid.NoBssid -> Refusal.NO_BSSID
                    ProjectionDeepLink.Invalid.NoAddress -> Refusal.NO_ADDRESS
                    ProjectionDeepLink.Invalid.NoBluetoothDevice -> Refusal.NO_BLUETOOTH_IDENTITY
                }
            )
        }
    }
}
