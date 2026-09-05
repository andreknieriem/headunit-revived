package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.PermissionRowBinder
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemOptimizer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Intelligent, device-aware first-run wizard.
 *
 * Steps: Permissions / Connection / Vehicle / Ready.
 */
class OnboardingActivity : BaseActivity() {

    private val settings by lazy { App.provide(this).settings }
    private lateinit var flipper: ViewFlipper
    private lateinit var backBtn: MaterialButton
    private lateinit var nextBtn: MaterialButton
    private lateinit var skipBtn: MaterialButton
    private lateinit var stepper: LinearLayout

    private var step = 0
    private var isBinding = false

    private var selectedSize = SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    private var selectedPortrait = false

    // Permissions step (registered here, before the activity is RESUMED).
    private var permissionBinder: PermissionRowBinder? = null
    private val permNormalLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionBinder?.rebind() }
    private val permSpecialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionBinder?.rebind() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force Extreme Dark / Pure Black theme and Force Night mode
        settings.appTheme = Settings.AppTheme.EXTREME_DARK
        settings.useExtremeDarkMode = true
        settings.nightMode = Settings.NightMode.NIGHT
        settings.hasAcceptedDisclaimer = true

        // Defaults per specifications:
        // - Auto-connect last session ON by default
        // - Connected Phone's GPS by default (useGpsForNavigation = false)
        // - Default vehicle name: "AA"
        settings.autoConnectLastSession = true
        settings.useGpsForNavigation = false
        if (settings.vehicleDisplayName.isBlank()) {
            settings.vehicleDisplayName = DEFAULT_VEHICLE_NAME
            settings.vehicleMake = DEFAULT_VEHICLE_MAKE
            settings.headUnitMake = DEFAULT_VEHICLE_MAKE
        }

        theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)

        setContentView(R.layout.activity_onboarding)

        step = savedInstanceState?.getInt(KEY_STEP, 0) ?: 0

        flipper = findViewById(R.id.onb_flipper)
        backBtn = findViewById(R.id.onb_back)
        nextBtn = findViewById(R.id.onb_next)
        skipBtn = findViewById(R.id.onb_skip)
        stepper = findViewById(R.id.onb_stepper)

        selectedPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        selectedSize = estimateSizePreset()

        // Apply recommended display settings in background
        applyDefaultDisplaySettings()

        buildStepperDots()
        bindSteps()
        bindPermissionsStep()

        // Ready step: optional-extras card. Each row finishes the wizard and jumps straight into
        // that Settings sub-screen (things the wizard does not already walk you through).
        findViewById<View>(R.id.onb_extra_loading).setOnClickListener {
            finishOnboardingInto(R.id.loadingScreenFragment)
        }
        findViewById<View>(R.id.onb_extra_keymap).setOnClickListener {
            finishOnboardingInto(R.id.keymapFragment)
        }
        findViewById<View>(R.id.onb_extra_mic).setOnClickListener {
            finishOnboardingInto(R.id.micSettingsFragment)
        }

        backBtn.setOnClickListener { if (step > 0) goBack() }
        nextBtn.setOnClickListener { onNext() }
        skipBtn.setOnClickListener { onDoItLater() }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    override fun onResume() {
        super.onResume()
        permissionBinder?.rebind()
    }

    private fun isStepHidden(s: Int): Boolean = false

    private fun visibleSteps(): List<Int> = (0 until STEP_COUNT).filter { !isStepHidden(it) }

    private fun goForward() {
        var s = step + 1
        while (s < STEP_COUNT && isStepHidden(s)) s++
        if (s >= STEP_COUNT) { finishOnboarding(); return }
        step = s
        render()
    }

    private fun goBack() {
        var s = step - 1
        while (s > 0 && isStepHidden(s)) s--
        step = s.coerceAtLeast(0)
        render()
    }

    private fun buildStepperDots() {
        stepper.removeAllViews()
        repeat(visibleSteps().size) {
            val dot = View(this)
            val h = (5 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(h, h)
            MarginLayoutParamsCompat.setMarginEnd(
                lp,
                (6 * resources.displayMetrics.density).toInt()
            )
            dot.layoutParams = lp
            stepper.addView(dot)
        }
    }

    private fun updateStepperDots() {
        val steps = visibleSteps()
        if (stepper.childCount != steps.size) buildStepperDots()
        val density = resources.displayMetrics.density
        val active = resolveAttrColor(com.google.android.material.R.attr.colorPrimary)
        val inactive = 0x33808080
        val currentPos = steps.indexOf(step).coerceAtLeast(0)
        for (i in steps.indices) {
            val dot = stepper.getChildAt(i) ?: continue
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            lp.width = ((if (i == currentPos) 26 else 8) * density).toInt()
            dot.layoutParams = lp
            val bg = GradientDrawable()
            bg.cornerRadius = 5 * density
            bg.setColor(if (i <= currentPos) active else inactive)
            dot.background = bg
        }
    }

    private fun bindSteps() {
        // --- Connection: multi-select of USB / WiFi ---
        val connGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_conn_group)
        isBinding = true
        val modes = settings.connectionModes
        if (Settings.ConnectionMode.USB in modes) connGroup.check(R.id.onb_conn_usb)
        if (Settings.ConnectionMode.WIFI in modes) connGroup.check(R.id.onb_conn_wifi)
        isBinding = false
        updateConnectionDetail()
        connGroup.addOnButtonCheckedListener { group, _, _ ->
            if (isBinding) return@addOnButtonCheckedListener
            val checked = group.checkedButtonIds
            val selected = buildSet {
                if (R.id.onb_conn_usb in checked) add(Settings.ConnectionMode.USB)
                if (R.id.onb_conn_wifi in checked) add(Settings.ConnectionMode.WIFI)
            }
            settings.connectionModes = selected
            updateConnectionDetail()
            if (step == STEP_CONNECTION) nextBtn.isEnabled = selected.isNotEmpty()
        }

        // --- Car brand ---
        bindVehicleStep()
    }

    private fun bindVehicleStep() {
        val input = findViewById<TextInputEditText>(R.id.onb_vehicle_input)
        val screen = findViewById<TextView>(R.id.onb_vehicle_screen_name)
        val chips = findViewById<ChipGroup>(R.id.onb_vehicle_chips)

        val dashboard = findViewById<ImageView>(R.id.onb_vehicle_dashboard)
        val rhdSwitch = findViewById<SwitchMaterial>(R.id.onb_vehicle_rhd_switch)
        fun applyWheelSide(rhd: Boolean) { dashboard.scaleX = if (rhd) -1f else 1f }
        rhdSwitch.isChecked = settings.rightHandDrive
        applyWheelSide(settings.rightHandDrive)
        rhdSwitch.setOnCheckedChangeListener { _, checked ->
            settings.rightHandDrive = checked
            applyWheelSide(checked)
        }

        val current = settings.vehicleDisplayName.trim().ifEmpty { DEFAULT_VEHICLE_NAME }
        val brands = resources.getStringArray(R.array.vehicle_brands).toMutableList()

        // Ensure "GAC" is at the beginning of the brand list
        brands.removeAll { it.equals("GAC", ignoreCase = true) }
        brands.add(0, "GAC")

        if (current.isNotEmpty() && !current.equals("GAC", ignoreCase = true)) {
            brands.removeAll { it.equals(current, ignoreCase = true) }
            brands.add(0, current)
        }
        chips.removeAllViews()
        brands.forEach { brand ->
            val chip = layoutInflater.inflate(R.layout.item_vehicle_chip, chips, false) as Chip
            chip.text = brand
            chip.id = ViewCompat.generateViewId()
            chips.addView(chip)
        }

        isBinding = true
        input.setText(current)
        screen.text = current
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as Chip
            if (chip.text.toString().equals(current, ignoreCase = true)) {
                chip.isChecked = true
                break
            }
        }
        isBinding = false

        chips.setOnCheckedStateChangeListener { group, checkedIds ->
            if (isBinding) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id) ?: return@setOnCheckedStateChangeListener
            isBinding = true
            input.setText(chip.text)
            input.setSelection(input.text?.length ?: 0)
            screen.text = chip.text
            isBinding = false
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                screen.text = text
                if (isBinding) return
                val checkedId = chips.checkedChipId
                if (checkedId != View.NO_ID) {
                    val chip = chips.findViewById<Chip>(checkedId)
                    if (chip != null && !chip.text.toString().equals(text.trim(), ignoreCase = true)) {
                        isBinding = true
                        chips.clearCheck()
                        isBinding = false
                    }
                }
            }
        })
    }

    private fun render() {
        flipper.displayedChild = step
        backBtn.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        skipBtn.visibility = if (step == STEP_COUNT - 1) View.GONE else View.VISIBLE
        nextBtn.text = getString(if (step == STEP_COUNT - 1) R.string.onb_ready_finish else R.string.onb_next)
        nextBtn.isEnabled = when (step) {
            STEP_CONNECTION -> settings.connectionModes.isNotEmpty()
            else -> true
        }
        if (step == STEP_READY) findViewById<TextView>(R.id.onb_ready_summary).text = summaryText()
        if (step == STEP_PERMISSIONS) permissionBinder?.rebind()
        updateStepperDots()
    }

    private fun bindPermissionsStep() {
        val container = findViewById<LinearLayout>(R.id.onb_perms_container)
        permissionBinder = PermissionRowBinder(this, container, permNormalLauncher, permSpecialLauncher)
        findViewById<MaterialButton>(R.id.onb_perms_enable_all).setOnClickListener {
            permissionBinder?.requestAllMissing()
        }
        permissionBinder?.rebind()
    }

    private fun onNext() {
        if (step == STEP_VEHICLE) {
            val brand = findViewById<TextInputEditText>(R.id.onb_vehicle_input)
                .text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_VEHICLE_NAME }
            settings.vehicleDisplayName = brand
            val make = if (brand.lowercase() in NOT_A_MANUFACTURER) DHU_MAKE else brand
            settings.vehicleMake = make
            settings.headUnitMake = make
        }
        if (step == STEP_COUNT - 1) finishOnboarding() else goForward()
    }

    private fun onDoItLater() {
        settings.hasAcceptedDisclaimer = true
        settings.appTheme = Settings.AppTheme.EXTREME_DARK
        settings.useExtremeDarkMode = true
        settings.nightMode = Settings.NightMode.NIGHT
        settings.autoConnectLastSession = true
        settings.useGpsForNavigation = false
        deferredThisSession = true
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (step > 0) goBack() else onDoItLater()
    }

    private fun finishOnboarding() {
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.appTheme = Settings.AppTheme.EXTREME_DARK
        settings.useExtremeDarkMode = true
        settings.nightMode = Settings.NightMode.NIGHT
        settings.autoConnectLastSession = true
        settings.useGpsForNavigation = false
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        finish()
    }

    private fun finishOnboardingInto(destinationId: Int) {
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.appTheme = Settings.AppTheme.EXTREME_DARK
        settings.useExtremeDarkMode = true
        settings.nightMode = Settings.NightMode.NIGHT
        settings.autoConnectLastSession = true
        settings.useGpsForNavigation = false
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_DESTINATION, destinationId)
        )
        finish()
    }

    private fun updateConnectionDetail() {
        val detail = findViewById<TextView>(R.id.onb_conn_detail)
        detail.text = if (settings.connectionModes.isEmpty()) "" else connectionModesLabel()
    }

    private fun connectionModesLabel(): String {
        val modes = settings.connectionModes
        if (modes.isEmpty()) return getString(R.string.connection_kind_unset)
        val parts = mutableListOf<String>()
        if (Settings.ConnectionMode.USB in modes) parts.add(getString(R.string.connection_kind_usb))
        if (Settings.ConnectionMode.WIFI in modes) parts.add(getString(R.string.connection_kind_wifi))
        return parts.joinToString(", ")
    }

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> display?.getRealMetrics(metrics)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ->
                (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            else ->
                (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    private fun estimateSizePreset(): SystemOptimizer.DisplaySizePreset {
        val m = realMetrics()
        val xdpi = if (m.xdpi > 40f) m.xdpi else m.densityDpi.toFloat()
        val ydpi = if (m.ydpi > 40f) m.ydpi else m.densityDpi.toFloat()
        val wIn = m.widthPixels / xdpi
        val hIn = m.heightPixels / ydpi
        val diagonal = sqrt(wIn * wIn + hIn * hIn)
        @Suppress("DEPRECATION")
        return SystemOptimizer.DisplaySizePreset.values().minByOrNull {
            abs(it.diagonalInch - diagonal)
        } ?: SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    }

    private fun isUpgrader(): Boolean =
        settings.hasCompletedSetupWizard ||
            settings.onboardingVersion in 1 until CURRENT_ONBOARDING_VERSION

    private fun applyDefaultDisplaySettings() {
        val result = SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait)
        val panel = realMetrics()
        if (!isUpgrader()) {
            val previousViewMode = settings.viewMode
            settings.resolutionId = SystemOptimizer.recommendedResolution(panel.widthPixels, panel.heightPixels).id
            settings.videoCodec = result.recommendedVideoCodec
            settings.viewMode = result.recommendedViewMode
            settings.screenOrientation = result.suggestedOrientation
            if (settings.viewMode != previousViewMode) settings.pendingRendererConfirm = true
        }
        settings.dpiPixelDensity = result.recommendedDpi
        settings.commit()
    }

    private fun summaryText(): String {
        val conn = connectionModesLabel()
        val res = getString(
            R.string.resolution_recommended_format,
            Settings.Resolution.fromId(settings.resolutionId)?.resName ?: getString(R.string.auto)
        )
        val base = getString(R.string.onb_ready_summary, conn, res, settings.videoCodec)
        val perms = getString(
            R.string.onb_summary_permissions_format,
            AppPermissions.grantedCount(this), AppPermissions.visible().size
        )
        val vehicle = settings.vehicleDisplayName.trim()
        val head = if (vehicle.isNotEmpty())
            getString(R.string.onb_summary_vehicle_format, vehicle) + "\n" + base
        else base
        return head + "\n" + perms
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {
        const val CURRENT_ONBOARDING_VERSION = 4

        @Volatile
        var deferredThisSession = false
        private const val KEY_STEP = "onb_step"
        private const val STEP_COUNT = 4
        private const val STEP_PERMISSIONS = 0
        private const val STEP_CONNECTION = 1
        private const val STEP_VEHICLE = 2
        private const val STEP_READY = 3

        private const val DEFAULT_VEHICLE_NAME = "AA"
        private const val DEFAULT_VEHICLE_MAKE = "GAC"
        private const val DHU_MAKE = "Google"
        private val NOT_A_MANUFACTURER = setOf("google", "open headunit", "emzoom aa")
    }
}
