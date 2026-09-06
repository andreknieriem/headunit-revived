package com.sesam17.openheadunit.redirect

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sesam17.openheadunit.R

object AppRedirectManager {

    private const val TAG = "AppRedirectManager"
    private const val PREFS_NAME = "openheadunit"

    private const val KEY_REDIRECT_ENABLED = "redirect-enabled"
    private const val KEY_REDIRECT_APPS = "redirect-apps-set"
    private const val KEY_DOUBLE_TAP_HOME = "redirect-double-tap-home"

    private var lastRedirectedPkg: String? = null
    private var lastRedirectTimestamp: Long = 0L
    private const val REDIRECT_DEBOUNCE_MS = 1500L

    private var lastHomeKeyDownTime: Long = 0L

    fun isRedirectEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_REDIRECT_ENABLED, false)
    }

    fun setRedirectEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_REDIRECT_ENABLED, enabled).apply()
        Log.i(TAG, "Redirect enabled set to $enabled")
    }

    fun getSelectedRedirectApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_REDIRECT_APPS, emptySet()) ?: emptySet()
    }

    fun setSelectedRedirectApps(context: Context, apps: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_REDIRECT_APPS, apps).apply()
        Log.i(TAG, "Selected redirect apps set to $apps")
    }

    fun isDoubleTapHomeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DOUBLE_TAP_HOME, false)
    }

    fun setDoubleTapHomeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DOUBLE_TAP_HOME, enabled).apply()
        Log.i(TAG, "Double tap home enabled set to $enabled")
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (RedirectAccessibilityService.isRunning) return true
        val serviceClass = RedirectAccessibilityService::class.java.name
        val enabledServices = AndroidSettings.Secure.getString(
            context.contentResolver,
            AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val accessibilityEnabled = AndroidSettings.Secure.getInt(
            context.contentResolver,
            AndroidSettings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        return accessibilityEnabled && enabledServices.contains(serviceClass)
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open accessibility settings", e)
        }
    }

    fun shouldRedirectApp(context: Context, packageName: String): Boolean {
        if (!isRedirectEnabled(context)) return false
        if (packageName.equals(context.packageName, ignoreCase = true)) return false
        val selectedApps = getSelectedRedirectApps(context)
        return selectedApps.any { it.equals(packageName, ignoreCase = true) }
    }

    fun handleWindowStateChanged(context: Context, packageName: String) {
        if (packageName.equals(context.packageName, ignoreCase = true)) {
            // Reset last redirected pkg when Open Headunit is in foreground
            lastRedirectedPkg = null
            return
        }

        if (!shouldRedirectApp(context, packageName)) return

        val now = SystemClock.uptimeMillis()
        if (packageName.equals(lastRedirectedPkg, ignoreCase = true) && (now - lastRedirectTimestamp < REDIRECT_DEBOUNCE_MS)) {
            Log.d(TAG, "Debouncing redirect for package: $packageName")
            return
        }

        lastRedirectedPkg = packageName
        lastRedirectTimestamp = now

        Log.i(TAG, "Redirect triggered for package: $packageName -> Launching Open Headunit")
        launchOpenHeadUnit(context)
    }

    fun handleHomeKeyEvent(context: Context, event: KeyEvent): Boolean {
        if (!isDoubleTapHomeEnabled(context)) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val now = SystemClock.uptimeMillis()
        val timeout = ViewConfiguration.getDoubleTapTimeout().toLong().coerceAtLeast(300L)

        if (now - lastHomeKeyDownTime < timeout) {
            lastHomeKeyDownTime = 0L
            Log.i(TAG, "Double-tap Home detected -> Launching Open Headunit")
            launchOpenHeadUnit(context)
            return true
        } else {
            lastHomeKeyDownTime = now
            return false
        }
    }

    fun launchOpenHeadUnit(context: Context) {
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.i(TAG, "Successfully launched Open Headunit (${context.packageName})")
            } else {
                Log.e(TAG, "Launch intent for package ${context.packageName} was null!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Open Headunit (${e.message})", e)
        }
    }

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val appsMap = mutableMapOf<String, AppInfo>()

        // 1. Fetch launcher activities
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        for (resolveInfo in resolveInfos) {
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg.equals(selfPkg, ignoreCase = true)) continue
            val name = resolveInfo.loadLabel(pm).toString()
            val icon = try { resolveInfo.loadIcon(pm) } catch (_: Exception) { null }
            appsMap[pkg] = AppInfo(pkg, name, icon)
        }

        // 2. Fetch all installed applications (including system/OEM headunit apps & shortcuts)
        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        for (appInfo in installedApps) {
            val pkg = appInfo.packageName
            if (pkg.equals(selfPkg, ignoreCase = true) || appsMap.containsKey(pkg)) continue
            val name = try { appInfo.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            val icon = try { appInfo.loadIcon(pm) } catch (_: Exception) { null }
            appsMap[pkg] = AppInfo(pkg, name, icon)
        }

        return appsMap.values.sortedBy { it.appName.lowercase() }
    }

    fun getRedirectAppsSummary(context: Context): String {
        if (!isRedirectEnabled(context)) {
            return context.getString(R.string.select_apps_summary_none)
        }
        val selectedApps = getSelectedRedirectApps(context)
        if (selectedApps.isEmpty()) {
            return context.getString(R.string.select_apps_summary_none)
        }
        return context.getString(R.string.select_apps_summary_count, selectedApps.size)
    }

    fun showAppMultiSelectDialog(context: Context, onAppsSelected: ((Set<String>) -> Unit)? = null) {
        val allApps = getInstalledApps(context)
        val currentSelected = getSelectedRedirectApps(context)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_app_multi_select, null)
        val etSearch = dialogView.findViewById<EditText>(R.id.et_search_apps)
        val tvSelectedCount = dialogView.findViewById<TextView>(R.id.tv_selected_count)
        val btnSelectAll = dialogView.findViewById<Button>(R.id.btn_select_all)
        val btnDeselectAll = dialogView.findViewById<Button>(R.id.btn_deselect_all)
        val rvApps = dialogView.findViewById<RecyclerView>(R.id.rv_app_list)

        fun updateCountText(count: Int) {
            tvSelectedCount.text = context.getString(R.string.select_apps_summary_count, count)
        }

        val adapter = AppMultiSelectAdapter(allApps, currentSelected) { count ->
            updateCountText(count)
        }

        rvApps.layoutManager = LinearLayoutManager(context)
        rvApps.adapter = adapter
        updateCountText(currentSelected.size)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSelectAll.setOnClickListener {
            adapter.selectAll()
        }

        btnDeselectAll.setOnClickListener {
            adapter.deselectAll()
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.select_apps_to_redirect)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val newSelected = adapter.getSelectedPackages()
                setSelectedRedirectApps(context, newSelected)
                onAppsSelected?.invoke(newSelected)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
