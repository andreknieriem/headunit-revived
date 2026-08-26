package com.andrerinas.openheadunit.utils

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.IShizuku
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

class SUExecutor {

    private val all: Collection<SUImplementation> = buildList {
        // libsu requires Lollipop (API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            add(RootImpl())
        } else {
            add(LegacySuImpl())
        }

        // Shizuku requires Marshmallow (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            add(ShizukuImpl())
        }
    }

    private var registered: Boolean = false
    private var active: SUImplementation? = null
    private var checkedPermissionOnBoot = false

    fun register() {
        if (registered)
            return

        registered = true
        all.forEach { it.register() }
    }

    fun unregister() {
        if (!registered)
            return

        registered = false
        all.forEach { it.unregister() }
    }

    // don't keep nagging if multiple services request on boot
    fun checkPermissionOnBoot() {
        if (checkedPermissionOnBoot)
            return

        checkPermission()
        checkedPermissionOnBoot = true
    }

    fun checkPermission(): Boolean {
        testRegistered()

        // check if on main thread (otherwise permission request dialog doesn't appear)
        if (Looper.myLooper() != Looper.getMainLooper())
            throw IllegalStateException("#checkPermission must be called on main thread")

        for (impl in all) {
            if (!impl.checkPermission())
                continue

            // permission granted!
            Log.i("SUExecutor", "SU granted by ${impl.name}")
            active = impl
            return true
        }

        // nobody granted
        Log.i("SUExecutor", "SU not granted")

        active = null
        return false
    }

    fun setProp(key: String, value: String): Boolean {
        testRegistered()

        if (active == null) {
            Log.w("SUExecutor", "#setProp failed: Not active")
            return false
        }

        val exitCode = active?.runShell("setprop $key $value", false) ?: -1
        return exitCode == 0
    }

    fun execShell(cmd: String, asRootUser: Boolean = false): Int {
        testRegistered()

        if (active == null) {
            Log.w("SUExecutor", "#execShell failed: Not active")
            return -1
        }

        return active?.runShell(cmd, asRootUser) ?: -1
    }

    private fun testRegistered() {
        if (!registered)
            throw IllegalStateException("SUExecutor not registered")
    }


    private interface SUImplementation {

        val name: String

        fun register()

        fun unregister()

        fun checkPermission(): Boolean

        fun runShell(cmd: String, asRootUser: Boolean): Int
    }

    private class LegacySuImpl : SUImplementation {
        override val name = "Root (Legacy)"

        override fun register() {}
        override fun unregister() {}

        override fun checkPermission(): Boolean {
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                p.waitFor() == 0
            } catch (_: Exception) {
                false
            }
        }

        override fun runShell(cmd: String, asRootUser: Boolean): Int {
            return try {
                val shellCmd = if (asRootUser) "su" else "sh"
                val process = Runtime.getRuntime().exec(arrayOf(shellCmd, "-c", cmd))
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    val err = process.errorStream.bufferedReader().readText()
                    Log.e("SUExecutor", "Legacy SU #runShell failed: $err")
                }
                exitCode
            } catch (e: Exception) {
                Log.e("SUExecutor", "Legacy SU #runShell failed", e)
                -1
            }
        }
    }

    private class RootImpl : SUImplementation {

        private val rootShell by lazy {
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(5)
                .build()
        }

        override val name = "Root"

        override fun register() {
        }

        override fun unregister() {
        }

        override fun checkPermission(): Boolean {
            if (Shell.isAppGrantedRoot() == true)
                return true

            return Shell.cmd("id").exec().isSuccess // prompt for root permission
        }

        override fun runShell(cmd: String, asRootUser: Boolean): Int {
            try {
                val shell = if (asRootUser) rootShell else Shell.getShell()
                val result = shell
                    .newJob()
                    .add(cmd)
                    .exec()

                if (!result.isSuccess)
                    Log.e("SUExecutor", "#runShell failed: ${result.err}")

                return result.code
            } catch (e: Exception) {
                Log.e("SUExecutor", "#runShell failed", e)
                return -1
            }
        }
    }

    private class ShizukuImpl : SUImplementation, Shizuku.OnRequestPermissionResultListener {

        override val name = "Shizuku"

        var hasPermission: Boolean = false
        val connection = ShizukuServiceConnection()

        private fun isShizukuGranted(): Boolean {
            return try {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                    !Shizuku.isPreV11()
            } catch (_: Throwable) {
                false
            }
        }

        override fun register() {
            try {
                Shizuku.addRequestPermissionResultListener(this)
            } catch (e: Throwable) {
                Log.w("SUExecutor", "Failed to add Shizuku permission listener: ${e.message}")
            }
            if (isShizukuGranted()) {
                this.hasPermission = true
                this.connection.bind()
            }
        }

        override fun unregister() {
            try {
                Shizuku.removeRequestPermissionResultListener(this)
            } catch (_: Throwable) { }
        }

        override fun checkPermission(): Boolean {
            if (isShizukuGranted()) {
                if (!this.hasPermission) {
                    this.hasPermission = true
                    this.connection.bind()
                }
            } else {
                this.hasPermission = false
            }
            return this.hasPermission
        }

        override fun runShell(cmd: String, asRootUser: Boolean): Int {
            return try {
                if (this.connection.service == null) {
                    Log.w("SUExecutor", "#runShell failed: Shizuku service not connected")
                    return -1
                }

                this.connection.service!!.execShell(cmd, asRootUser)
            } catch (e: Exception) {
                Log.e("SUExecutor", "#runShell failed", e)
                -1
            }
        }

        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            this.hasPermission = isShizukuGranted()

            if (this.hasPermission)
                this.connection.bind()
            else
                this.connection.unbind()
        }


        private class PrivilegedService : IShizuku.Stub() {

            override fun execShell(command: String, asRoot: Boolean): Int {
                val cmd = if (asRoot) "su" else "sh"
                val process = Runtime.getRuntime().exec(arrayOf(cmd, "-c", command))
                val errorMsg = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode != 0)
                    Log.e("SUExecutor", "Shizuku execShell failed: $errorMsg")

                return exitCode
            }
        }

        private inner class ShizukuServiceConnection : ServiceConnection {

            private val args by lazy {
                Shizuku.UserServiceArgs(
                    ComponentName(
                        BuildConfig.APPLICATION_ID,
                        PrivilegedService::class.java.name,
                    ),
                )
                    .daemon(false)
                    .processNameSuffix("privileged_service")
            }

            private var isBound: Boolean = false
            var service: IShizuku? = null

            override fun onServiceConnected(p0: ComponentName?, service: IBinder?) {
                this.service = IShizuku.Stub.asInterface(service)
            }

            override fun onServiceDisconnected(p0: ComponentName?) {
                this.service = null
            }

            fun bind() {
                if (isBound || !hasPermission)
                    return

                isBound = true
                Shizuku.bindUserService(args, this)
            }

            fun unbind() {
                if (!isBound)
                    return

                isBound = false
                service = null
                Shizuku.unbindUserService(args, this, true)
            }
        }
    }
}
