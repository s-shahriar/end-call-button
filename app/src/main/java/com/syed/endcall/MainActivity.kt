package com.syed.endcall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Setup checklist and diagnostics. Nothing here runs in the background — the
 * app has no launcher-side work at all once the two services are enabled.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)


    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()

        header(getString(R.string.setup_title))

        row(
            getString(R.string.step_notifications),
            getString(R.string.step_notifications_why),
            notificationAccessGranted()
        ) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        row(
            getString(R.string.step_accessibility),
            getString(R.string.step_accessibility_why),
            accessibilityEnabled()
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        row(
            getString(R.string.step_phone),
            getString(R.string.step_phone_why),
            phonePermissionsGranted()
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS),
                1
            )
        }

        header(getString(R.string.position_title))
        body(getString(R.string.position_why))

        val testing = CallRegistry.active?.key == "test"
        val test = Button(this).apply {
            text = getString(if (testing) R.string.test_button_hide else R.string.test_button)
            setOnClickListener { toggleTestButton() }
        }
        container.addView(test)
    }

    /**
     * Drives the real overlay path with a fake call so the button can be seen,
     * dragged and positioned without needing someone to ring you. Toggles, so
     * there is no countdown to race against.
     */
    private fun toggleTestButton() {
        if (CallRegistry.active?.key == "test") {
            CallRegistry.onCallEnded("test")
        } else {
            CallRegistry.onCallStarted(
                ActiveCall(
                    key = "test",
                    packageName = packageName,
                    source = CallSource.NOTIFICATION,
                    debug = "test button (dry run)",
                    isTest = true
                )
            )
            // Safety net so a forgotten test button cannot sit there forever.
            handler.postDelayed({ if (CallRegistry.active?.key == "test") CallRegistry.onCallEnded("test") }, 300_000)
        }
        render()
    }

    // ---- status checks ----------------------------------------------------

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.contains(packageName)
    }

    private fun accessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return flat.contains("$packageName/${EndCallService::class.java.name}") ||
            flat.contains("$packageName/.EndCallService")
    }

    private fun phonePermissionsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    // ---- tiny view helpers ------------------------------------------------

    private fun header(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        })
    }

    private fun body(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(12))
        })
    }

    private fun row(title: String, why: String, ok: Boolean, onClick: () -> Unit) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = !ok
            if (!ok) setOnClickListener { onClick() }
        }

        block.addView(TextView(this).apply {
            this.text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        block.addView(TextView(this).apply {
            this.text = getString(if (ok) R.string.granted else R.string.not_granted)
            setTextColor(if (ok) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
            textSize = 14f
        })
        block.addView(TextView(this).apply {
            this.text = why
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(4), 0, 0)
        })

        container.addView(block, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
}
