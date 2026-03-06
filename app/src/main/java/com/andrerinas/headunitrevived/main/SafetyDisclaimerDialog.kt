package com.andrerinas.headunitrevived.main

import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R

class SafetyDisclaimerDialog : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use custom theme for transparent background (CardView handles background)
        setStyle(STYLE_NO_TITLE, R.style.SafetyDialogTheme)
        isCancelable = false // Prevent back button close
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.dialog_safety_disclaimer, container, false)

        val messageView = view.findViewById<TextView>(R.id.disclaimer_message)
        val btnAccept = view.findViewById<Button>(R.id.btn_accept)

        // Load and format text
        val rawText = getString(R.string.disclaimer_text)
        messageView.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(rawText, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(rawText)
        }

        btnAccept.setOnClickListener {
            // Save setting
            App.provide(requireContext()).settings.hasAcceptedDisclaimer = true
            dismiss()
            (activity as? MainActivity)?.checkSetupFlow()
        }

        return view
    }

    override fun onStart() {
        super.onStart()
        // Make dialog 90% width on phones, less on tablets? 
        // Or just let layout handle it?
        // DialogFragment often defaults to WRAP_CONTENT but limited width.
        // Let's set a min width.
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    companion object {
        const val TAG = "SafetyDisclaimerDialog"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) {
                SafetyDisclaimerDialog().show(manager, TAG)
            }
        }
    }
}