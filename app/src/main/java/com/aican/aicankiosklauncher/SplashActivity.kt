package com.aican.aicankiosklauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView

class SplashActivity : Activity() {

    companion object {
        const val EXTRA_BOOT_LAUNCH = "extra_boot_launch"
    }

    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val bootSteps = listOf(
        "Initializing system..." to 20,
        "Loading kiosk configuration..." to 40,
        "Applying security policies..." to 60,
        "Verifying device owner..." to 80,
        "Launching kiosk..." to 100
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_splash)

        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvSplashStatus)
        tvProgress = findViewById(R.id.tvProgress)

        KioskWatchdogStarter.start(this)

        if (intent.getBooleanExtra(EXTRA_BOOT_LAUNCH, false)) {
            startProgressAnimation()
        } else {
            progressBar.progress = 100
            tvProgress.text = getString(R.string.splash_progress_done)
            tvStatus.text = getString(R.string.splash_status_ready)
            launchMain()
        }
    }

    private fun startProgressAnimation() {
        var stepIndex = 0

        fun runNextStep() {
            if (stepIndex >= bootSteps.size) {
                handler.postDelayed({ launchMain() }, 100)
                return
            }

            val (message, targetProgress) = bootSteps[stepIndex]
            tvStatus.text = message
            animateProgress(progressBar.progress, targetProgress) {
                stepIndex++
                handler.postDelayed({ runNextStep() }, 75)
            }
        }

        runNextStep()
    }

    private fun animateProgress(from: Int, to: Int, onComplete: () -> Unit) {
        val stepDelay = 10L
        var current = from

        fun tick() {
            if (current >= to) {
                onComplete()
                return
            }
            current++
            progressBar.progress = current
            tvProgress.text = getString(R.string.splash_progress_format, current)
            handler.postDelayed({ tick() }, stepDelay)
        }

        tick()
    }

    private fun launchMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Block back during splash.
    }
}
