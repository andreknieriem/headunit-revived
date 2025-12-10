package com.andrerinas.headunitrevived.main


import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.contract.KeyIntent
import com.andrerinas.headunitrevived.utils.IntentFilters
import com.andrerinas.headunitrevived.utils.Settings


/**
 * @author algavris
 * @date 13/06/2017
 */
class KeymapFragment : Fragment(), MainActivity.KeyListener, View.OnClickListener {

    private val idToCode = mapOf(
        R.id.keycode_soft_left to KeyEvent.KEYCODE_SOFT_LEFT,
        R.id.keycode_soft_right to KeyEvent.KEYCODE_SOFT_RIGHT,

        R.id.keycode_dpad_up to KeyEvent.KEYCODE_DPAD_UP,
        R.id.keycode_dpad_down to KeyEvent.KEYCODE_DPAD_DOWN,
        R.id.keycode_dpad_left to KeyEvent.KEYCODE_DPAD_LEFT,
        R.id.keycode_dpad_right to KeyEvent.KEYCODE_DPAD_RIGHT,
        R.id.keycode_dpad_center to KeyEvent.KEYCODE_DPAD_CENTER,

        R.id.keycode_media_play to KeyEvent.KEYCODE_MEDIA_PLAY,
        R.id.keycode_media_pause to KeyEvent.KEYCODE_MEDIA_PAUSE,
        R.id.keycode_media_play_pause to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        R.id.keycode_media_next to KeyEvent.KEYCODE_MEDIA_NEXT,
        R.id.keycode_media_previous to KeyEvent.KEYCODE_MEDIA_PREVIOUS,

        R.id.keycode_search to KeyEvent.KEYCODE_SEARCH,
        R.id.keycode_call to KeyEvent.KEYCODE_CALL,
        R.id.keycode_music to KeyEvent.KEYCODE_MUSIC,
        R.id.keycode_nav to KeyEvent.KEYCODE_GUIDE,
        R.id.keycode_night to KeyEvent.KEYCODE_N)

    private val codeToId = mapOf(
        KeyEvent.KEYCODE_SOFT_LEFT to R.id.keycode_soft_left,
        KeyEvent.KEYCODE_SOFT_RIGHT to R.id.keycode_soft_right,

        KeyEvent.KEYCODE_DPAD_UP to R.id.keycode_dpad_up,
        KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to R.id.keycode_dpad_left,
        KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER to KeyEvent.KEYCODE_DPAD_CENTER,

        KeyEvent.KEYCODE_MEDIA_PLAY to KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE to KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT to KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to KeyEvent.KEYCODE_MEDIA_PREVIOUS,

        KeyEvent.KEYCODE_SEARCH to KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_CALL to R.id.keycode_call,
        KeyEvent.KEYCODE_MUSIC to R.id.keycode_music,
        KeyEvent.KEYCODE_GUIDE to R.id.keycode_nav,
        KeyEvent.KEYCODE_N to R.id.keycode_night)

    private var assignCode = KeyEvent.KEYCODE_UNKNOWN
    private lateinit var settings: Settings
    private var codesMap = mutableMapOf<Int, Int>()
    private lateinit var keypressDebuggerTextView: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): android.view.View {
        val view = inflater.inflate(R.layout.fragment_keymap, container, false)
        keypressDebuggerTextView = view.findViewById(R.id.keypress_debugger_text)
        settings = Settings(requireContext())
        codesMap = settings.keyCodes

        idToCode.forEach {
            (resId, keyCode) ->
            val button = view.findViewById<Button>(resId)
            button.tag = keyCode
            button.setOnClickListener(this)
        }

        view.findViewById<Button>(R.id.reset_codes).setOnClickListener {
            codesMap = mutableMapOf()
            settings.keyCodes = codesMap
        }

        return view
    }

    private val keyCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event: KeyEvent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(KeyIntent.extraEvent, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KeyIntent.extraEvent)
            }
            onKeyEvent(event)
        }
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context?.registerReceiver(keyCodeReceiver, IntentFilters.keyEvent, RECEIVER_NOT_EXPORTED)
        } else {
            context?.registerReceiver(keyCodeReceiver, IntentFilters.keyEvent)
        }
    }

    override fun onPause() {
        super.onPause()
        context?.unregisterReceiver(keyCodeReceiver)
    }

    override fun onClick(v: View?) {
        val button = v as? Button ?: return
        val keyCode = button.tag as Int
        this.assignCode = keyCode
        val name = KeyEvent.keyCodeToString(this.assignCode)
        Toast.makeText(activity, "Press a key to assign to '$name'", Toast.LENGTH_SHORT).show()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        (activity as? MainActivity)?.let {
            it.setDefaultKeyMode(Activity.DEFAULT_KEYS_DISABLE)
            it.keyListener = this
        }
    }

    override fun onDetach() {
        (activity as? MainActivity)?.let {
            it.setDefaultKeyMode(Activity.DEFAULT_KEYS_SHORTCUT)
            it.keyListener = null
        }
        super.onDetach()
    }


    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false // Handle null event

        // debugging keys
        val keyCode = event.keyCode
        val keyName = KeyEvent.keyCodeToString(keyCode)
        keypressDebuggerTextView.text = "Last Key Press: $keyName ($keyCode)"

        // skip assigning for key down and back buttons
        if (event.action == KeyEvent.ACTION_DOWN || keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
        if (this.assignCode != KeyEvent.KEYCODE_UNKNOWN)
        {
            // clear previous values
            (codesMap.entries.find {
                it.value == event.keyCode
            })?.let {
                codesMap.remove(it.key)
            }
            codesMap.put(this.assignCode, event.keyCode)
            settings.keyCodes = codesMap

            val name = KeyEvent.keyCodeToString(this.assignCode)
            Toast.makeText(activity, "'$name' is ${this.assignCode}", Toast.LENGTH_SHORT).show()
            this.assignCode = KeyEvent.KEYCODE_UNKNOWN
        }

        buttonForKeyCode(event.keyCode)?.requestFocus()
        return true
    }

    private fun buttonForKeyCode(keyCode: Int): Button? {
        val mappedCode = (codesMap.entries.find {
            it.value == keyCode
        })?.key ?: keyCode
        val resId = codeToId[mappedCode] ?: return null
        return view?.findViewById(resId)
    }
}