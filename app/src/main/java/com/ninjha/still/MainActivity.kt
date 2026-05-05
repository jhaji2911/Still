package com.ninjha.still

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ninjha.still.core.ContextToken
import com.ninjha.still.core.DevicePlace
import com.ninjha.still.core.EnviroDecibel
import com.ninjha.still.core.MotionState
import com.ninjha.still.core.Peripheral
import com.ninjha.still.core.PhysioStress
import com.ninjha.still.core.StillEngine
import com.ninjha.still.core.StillState
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val engine = StillEngine()
    private lateinit var sensorFusion: SensorFusion
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var prefs: SharedPreferences

    private var currentScreen = Screen.Presence
    private var onboardingComplete = false
    private var snapshot = SensorSnapshot(0f, MotionState.Still, null)
    private var heartRateBpm = 72
    private var ambientAudio = EnviroDecibel.Silent
    private var devicePlace = DevicePlace.InHand
    private var peripheral = Peripheral.None
    private var inferencePaused = false
    private var lastSensorRenderAt = 0L
    private var lastNotificationAt = 0L
    private var lastNotificationLabel: String? = null
    private var lastLoggedLabel: String? = null
    private var lastLoggedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        onboardingComplete = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        ensureNotificationChannel()
        sensorFusion = SensorFusion(this) {
            val previousMotion = snapshot.motion
            snapshot = it
            val now = SystemClock.elapsedRealtime()
            val shouldRefreshVisibleSensorUi =
                currentScreen == Screen.Presence || currentScreen == Screen.Sensors
            val shouldRender =
                shouldRefreshVisibleSensorUi &&
                    (previousMotion != it.motion || now - lastSensorRenderAt >= SENSOR_RENDER_INTERVAL_MS)

            if (shouldRender) {
                lastSensorRenderAt = now
                contentHost.post { renderScreen() }
            }
        }
        setContentView(buildShell())
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        sensorFusion.start()
    }

    override fun onPause() {
        sensorFusion.stop()
        super.onPause()
    }

    private fun requestSensorPermissions() {
        val requested = mutableListOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BODY_SENSORS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requested += Manifest.permission.POST_NOTIFICATIONS
        }

        val permissions = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 1001)
        }
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StillColor.Background)
        }

        root.addView(topBar())

        contentHost = FrameLayout(this)
        root.addView(
            contentHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(8), dp(12), dp(18))
            background = rounded(
                color = StillColor.PrimaryContainer,
                radius = dp(24),
                strokeColor = StillColor.OutlineVariant,
                strokeWidth = 1,
            )
            elevation = dp(8).toFloat()
        }
        bottomNav.bringToFront()
        root.addView(bottomNav)

        return root
    }

    private fun topBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(14), dp(24), dp(14))
        setBackgroundColor(StillColor.PrimaryContainer)
        elevation = dp(2).toFloat()

        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.still_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(StillColor.SurfaceContainerHighest, dp(16))
            clipToOutline = false
        }, LinearLayout.LayoutParams(dp(34), dp(34)))

        addView(text("Still", 20f, StillColor.Ink, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(text("Settings", 12f, StillColor.Variant, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = pill(StillColor.SurfaceContainer, StillColor.OutlineVariant)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onboardingComplete = true
                prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
                currentScreen = Screen.Privacy
                renderScreen()
            }
        })
    }

    private fun renderScreen() {
        if (!::contentHost.isInitialized) return

        val token = currentToken()
        val state = if (inferencePaused) {
            StillState(
                label = "Paused",
                confidence = 1f,
                estimatedReturnMinutes = 60,
                autoReply = "Nishant has paused automatic context inference. He'll reply when ready.",
                reasons = listOf("manual pause enabled"),
            )
        } else {
            engine.infer(token)
        }

        if (onboardingComplete) {
            maybeLogState(state)
            maybePostStatusNotification(state)
        }

        contentHost.removeAllViews()
        contentHost.addView(
            if (onboardingComplete) {
                when (currentScreen) {
                    Screen.Presence -> presenceScreen(state)
                    Screen.Sensors -> sensorsScreen(token, state)
                    Screen.Insights -> insightsScreen()
                    Screen.Privacy -> privacyScreen()
                }
            } else {
                onboardingScreen()
            },
        )

        if (onboardingComplete) {
            bottomNav.visibility = View.VISIBLE
            renderBottomNav()
        } else {
            bottomNav.visibility = View.GONE
        }
    }

    private fun renderBottomNav() {
        bottomNav.removeAllViews()
        Screen.values().forEach { screen ->
            val selected = screen == currentScreen
            bottomNav.addView(navItem(screen, selected), LinearLayout.LayoutParams(0, dp(62), 1f))
        }
    }

    private fun navItem(screen: Screen, selected: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (selected) rounded(StillColor.SurfaceContainerHighest, dp(14)) else null
            isClickable = true
            isFocusable = true
            setOnClickListener {
                currentScreen = screen
                renderScreen()
            }
            addView(text(screen.symbol, 12f, if (selected) StillColor.Ink else StillColor.Disabled, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                isClickable = false
            })
            addView(text(screen.label.uppercase(Locale.US), 10f, if (selected) StillColor.Ink else StillColor.Disabled, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.1f
                isClickable = false
            }.withMargins(top = dp(3)))
        }

    private fun onboardingScreen(): View = page {
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.still_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val params = LinearLayout.LayoutParams(dp(132), dp(132))
            params.gravity = Gravity.CENTER_HORIZONTAL
            params.setMargins(0, dp(18), 0, dp(18))
            layoutParams = params
        })
        addView(text("Reclaim Your Stillness", 32f, StillColor.Ink, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        addView(body("Still turns coarse on-device signals into a calm availability status. No cloud. No account. No internet permission.", 17f, StillColor.Variant).withMargins(top = dp(12), bottom = dp(24)))
        addView(valueProp("Local-only", "Sensor tokens stay on this phone."))
        addView(valueProp("Fuzzy logic", "Still reads broad patterns, not private raw context."))
        addView(valueProp("Human status", "Replies explain availability without sounding robotic."))
        addView(actionWide("Begin Setup").apply {
            background = rounded(StillColor.Ink, dp(16))
            setTextColor(StillColor.White)
            setOnClickListener {
                onboardingComplete = true
                prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
                requestSensorPermissions()
                currentScreen = Screen.Presence
                renderScreen()
            }
        })
        addView(actionWide("Read Manifesto").apply {
            setOnClickListener {
                onboardingComplete = true
                prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
                currentScreen = Screen.Privacy
                renderScreen()
            }
        })
    }

    private fun valueProp(title: String, detail: String): View =
        card {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("OK", 12f, StillColor.Tertiary, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = pill(StillColor.TertiaryContainer, Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                addView(text(title, 15f, StillColor.Ink, Typeface.BOLD))
                addView(body(detail, 14f, StillColor.Variant))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun presenceScreen(state: StillState): View = page {
        addView(sectionKicker("Current State"))
        addView(statusOrb(state))
        addView(replyCard(state))
        addView(pauseCard())
    }

    private fun statusOrb(state: StillState): View = FrameLayout(this).apply {
        val size = dp(258)
        val tone = stateTone(state.label)
        background = rounded(tone.container, size / 2, tone.border, 1)
        elevation = dp(6).toFloat()

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))

            addView(text(state.symbol(), 38f, tone.foreground, Typeface.NORMAL).apply {
                gravity = Gravity.CENTER
            })
            addView(text(state.label, 32f, StillColor.Ink, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            addView(statusChip("${(state.confidence * 100).roundToInt()}% confidence", tone.foreground, tone.container))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val params = LinearLayout.LayoutParams(size, size)
        params.gravity = Gravity.CENTER_HORIZONTAL
        params.setMargins(0, dp(16), 0, dp(28))
        layoutParams = params
    }

    private fun replyCard(state: StillState): View = card(
        backgroundColor = StillColor.SecondaryContainer,
        strokeColor = StillColor.SecondaryDim,
    ) {
        addView(label("AUTO-REPLY PREVIEW", StillColor.SecondaryText))
        addView(body("\"${state.autoReply}\"", 18f, StillColor.Variant).apply {
            setPadding(dp(10), dp(10), 0, 0)
        })
    }

    private fun pauseCard(): View = card(backgroundColor = StillColor.SurfaceContainerLow) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Pause Inference", 16f, StillColor.Ink, Typeface.BOLD))
            addView(body("Temporarily halt state detection for 1 hour", 14f, StillColor.Variant))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(actionButton(if (inferencePaused) "Resume" else "Pause") {
            inferencePaused = !inferencePaused
            renderScreen()
        })
    }

    private fun sensorsScreen(token: ContextToken, state: StillState): View = page {
        addView(headline("Sensor Tokens", "Manual controls let v1 simulate signals that need wearable or notification integrations later."))
        addView(card {
            addView(label("LIVE TOKEN BUFFER", StillColor.Variant))
            addView(tokenRow("Motion", token.motion.name, "Accel ${format(snapshot.accelerationMagnitude)} m/s2"))
            addView(tokenRow("Heart", "${heartRateBpm} bpm", token.physioStress.name))
            addView(tokenRow("Audio", token.ambientAudio.name, "manual v1 token"))
            addView(tokenRow("Place", token.devicePlace.name, "manual v1 token"))
            addView(tokenRow("Peripheral", token.peripheral.name, "manual v1 token"))
        })

        addView(controlGroup("Ambient Audio",
            choice("Silent", ambientAudio == EnviroDecibel.Silent) { ambientAudio = EnviroDecibel.Silent },
            choice("Rhythmic", ambientAudio == EnviroDecibel.Rhythmic) { ambientAudio = EnviroDecibel.Rhythmic },
            choice("Chaotic", ambientAudio == EnviroDecibel.Chaotic) { ambientAudio = EnviroDecibel.Chaotic },
        ))
        addView(controlGroup("Device Place",
            choice("In hand", devicePlace == DevicePlace.InHand) { devicePlace = DevicePlace.InHand },
            choice("Face down", devicePlace == DevicePlace.FaceDown) { devicePlace = DevicePlace.FaceDown },
            choice("Stand", devicePlace == DevicePlace.ChargingStand) { devicePlace = DevicePlace.ChargingStand },
        ))
        addView(controlGroup("Peripheral",
            choice("Buds", peripheral == Peripheral.Headphones) { peripheral = Peripheral.Headphones },
            choice("Car", peripheral == Peripheral.CarBluetooth) { peripheral = Peripheral.CarBluetooth },
            choice("None", peripheral == Peripheral.None) { peripheral = Peripheral.None },
        ))
        addView(controlGroup("Heart Rate",
            choice("72", heartRateBpm == 72) { heartRateBpm = 72 },
            choice("125", heartRateBpm == 125) { heartRateBpm = 125 },
            choice("165", heartRateBpm == 165) { heartRateBpm = 165 },
        ))
        addView(card(backgroundColor = StillColor.TertiaryContainer) {
            addView(label("CURRENT READ", StillColor.TertiaryText))
            addView(body("${state.label}: ${state.reasons.joinToString(", ")}", 16f, StillColor.TertiaryText))
        })
    }

    private fun insightsScreen(): View = page {
        val logs = loadLogs()
        addView(headline("Daily Rhythm", "Local inference history. Logs are stored on device and can be purged any time."))
        addView(timelineCard())
        addView(text("Recent Activity Logs", 24f, StillColor.Ink, Typeface.BOLD).withMargins(top = dp(8), bottom = dp(8)))
        if (logs.isEmpty()) {
            addView(card {
                addView(text("No logs yet", 16f, StillColor.Ink, Typeface.BOLD))
                addView(body("Still will record coarse inference changes locally after setup.", 14f, StillColor.Variant))
            })
        } else {
            logs.forEachIndexed { index, log ->
                addView(logCard("${log.label} Detected", log.reasons, relativeTime(log.timestamp), index == 0))
            }
        }
    }

    private fun timelineCard(): View = card {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Today", 14f, StillColor.Variant, Typeface.BOLD), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusChip("12 AM - 11:59 PM", StillColor.Variant, StillColor.SurfaceContainer))
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(StillColor.SurfaceContainerHigh, dp(12))
            clipToOutline = false
            addView(segment(20, StillColor.SurfaceVariant))
            addView(segment(15, StillColor.Secondary))
            addView(segment(10, StillColor.SurfaceVariant))
            addView(segment(8, StillColor.Tertiary))
            addView(segment(25, StillColor.SurfaceVariant))
            addView(segment(22, StillColor.Secondary))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).withTop(dp(16)))
        addView(body("Deep work, rest/focus, and available blocks are kept local. History storage next.", 14f, StillColor.Variant).withMargins(top = dp(12)))
    }

    private fun segment(weight: Int, color: Int): View = View(this).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight.toFloat())
    }

    private fun logCard(title: String, detail: String, duration: String, expanded: Boolean): View =
        card(backgroundColor = if (expanded) StillColor.SecondaryContainerLight else StillColor.White) {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(if (expanded) "Focus" else "Log", 12f, StillColor.Variant, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = pill(if (expanded) StillColor.Secondary else StillColor.SurfaceContainerHigh, Color.TRANSPARENT)
                setTextColor(if (expanded) StillColor.White else StillColor.Variant)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
                addView(text(title, 14f, StillColor.Ink, Typeface.BOLD))
                addView(body(detail, 13f, StillColor.Variant))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(duration, 14f, StillColor.Ink, Typeface.BOLD))
        }

    private fun privacyScreen(): View = page {
        addView(headline("Transparency", "Your physical space is translated entirely on-device. Manage how your presence is interpreted."))
        addView(card(backgroundColor = StillColor.TertiaryContainer, strokeColor = StillColor.TertiaryDim) {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("OK", 13f, StillColor.Tertiary, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = pill(StillColor.White, Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                addView(text("Zero-Cloud Active", 24f, StillColor.TertiaryText, Typeface.BOLD))
                addView(body("All telemetry is processed locally. No raw audio or imagery leaves this device.", 15f, StillColor.TertiaryText))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(label("HARDWARE ACCESS", StillColor.Variant).withMargins(top = dp(18), bottom = dp(4)))
        addView(permissionRow(
            "Motion",
            "Detects ambient movement without visual capture.",
            hasPermission(Manifest.permission.ACTIVITY_RECOGNITION),
        ) { requestSensorPermissions() })
        addView(permissionRow(
            "Body Sensors",
            "Accesses connected wearables for physiological state.",
            hasPermission(Manifest.permission.BODY_SENSORS),
        ) { requestSensorPermissions() })
        addView(permissionRow(
            "Lock Screen Status",
            "Shows current inferred state as a public notification.",
            canPostNotifications(),
        ) { requestSensorPermissions() })
        addView(permissionRow("Audio Tokens", "V1 uses manual audio tokens. Raw audio is never recorded.", false))
        addView(actionWide("Purge All Local Logs").apply {
            setOnClickListener {
                clearLogs()
                renderScreen()
            }
        })
        addView(body("Technical manifesto: local-first, coarse tokens, user-visible permissions.", 14f, StillColor.Variant).withMargins(top = dp(14)))
    }

    private fun permissionRow(title: String, detail: String, enabled: Boolean, onClick: (() -> Unit)? = null): View =
        card(backgroundColor = StillColor.White) {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) setOnClickListener { onClick() }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f, if (enabled) StillColor.Ink else StillColor.Variant, Typeface.BOLD))
                addView(body("${if (enabled) "Allowed" else "Tap to allow"} - $detail", 13f, StillColor.Variant))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle(enabled))
        }

    private fun toggle(enabled: Boolean): View = FrameLayout(this).apply {
        background = pill(if (enabled) StillColor.Tertiary else StillColor.SurfaceVariant, Color.TRANSPARENT)
        addView(View(this@MainActivity).apply {
            background = rounded(if (enabled) StillColor.White else StillColor.Variant, dp(10))
        }, FrameLayout.LayoutParams(dp(20), dp(20), if (enabled) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp(2)
            rightMargin = dp(2)
        })
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(26))
    }

    private fun maybeLogState(state: StillState) {
        val now = System.currentTimeMillis()
        val shouldLog = state.label != lastLoggedLabel || now - lastLoggedAt >= LOG_REPEAT_INTERVAL_MS
        if (!shouldLog) return

        lastLoggedLabel = state.label
        lastLoggedAt = now

        val newLog = InferenceLog(
            timestamp = now,
            label = state.label,
            reasons = state.reasons.joinToString(", ").ifBlank { "local context changed" },
        )
        val logs = (listOf(newLog) + loadLogs()).take(MAX_LOGS)
        prefs.edit().putString(KEY_LOGS, logs.joinToString(LOG_ROW_SEPARATOR) { it.serialize() }).apply()
    }

    private fun loadLogs(): List<InferenceLog> =
        prefs.getString(KEY_LOGS, null)
            ?.split(LOG_ROW_SEPARATOR)
            ?.mapNotNull { InferenceLog.deserialize(it) }
            ?: emptyList()

    private fun clearLogs() {
        lastLoggedLabel = null
        lastLoggedAt = 0L
        prefs.edit().remove(KEY_LOGS).apply()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            STATUS_CHANNEL_ID,
            "Still status",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Current Still availability state"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun maybePostStatusNotification(state: StillState) {
        if (!canPostNotifications()) return

        val now = SystemClock.elapsedRealtime()
        val shouldPost = state.label != lastNotificationLabel || now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS
        if (!shouldPost) return

        lastNotificationLabel = state.label
        lastNotificationAt = now
        val manager = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_still_notification)
            .setContentTitle("Still: ${state.label}")
            .setContentText(state.autoReply)
            .setStyle(Notification.BigTextStyle().bigText("${state.autoReply}\n\nReading: ${state.reasons.joinToString(", ")}"))
            .setSubText("${(state.confidence * 100).roundToInt()}% confidence")
            .setOngoing(false)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        manager.notify(STATUS_NOTIFICATION_ID, notification)
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)

    private fun relativeTime(timestamp: Long): String {
        val minutes = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 1 -> "Now"
            minutes < 60 -> "${minutes}m"
            minutes < 1_440 -> "${minutes / 60}h"
            else -> "${minutes / 1_440}d"
        }
    }

    private fun currentToken(): ContextToken = ContextToken(
        motion = snapshot.motion,
        physioStress = stressFromHeartRate(heartRateBpm),
        ambientAudio = ambientAudio,
        devicePlace = devicePlace,
        peripheral = peripheral,
        heartRateBpm = heartRateBpm,
    )

    private fun stressFromHeartRate(heartRateBpm: Int): PhysioStress = when {
        heartRateBpm >= 155 -> PhysioStress.Anaerobic
        heartRateBpm >= 105 -> PhysioStress.Aerobic
        else -> PhysioStress.Resting
    }

    private fun page(content: LinearLayout.() -> Unit): View =
        ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(StillColor.Background)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(30))
                content()
            })
        }

    private fun headline(title: String, subtitle: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 32f, StillColor.Ink, Typeface.BOLD))
            addView(body(subtitle, 16f, StillColor.Variant).withMargins(top = dp(6), bottom = dp(20)))
        }

    private fun sectionKicker(value: String): TextView =
        text(value, 16f, StillColor.Variant, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun tokenRow(title: String, value: String, detail: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 14f, StillColor.Ink, Typeface.BOLD))
                addView(body(detail, 12f, StillColor.Variant))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusChip(value, StillColor.Variant, StillColor.SurfaceContainer))
        }

    private fun controlGroup(title: String, vararg choices: View): View =
        card {
            addView(label(title.uppercase(Locale.US), StillColor.Variant))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                choices.forEach { addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).withMargins(right = dp(6))) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).withTop(dp(12)))
        }

    private fun choice(label: String, selected: Boolean, onSelect: () -> Unit): TextView =
        text(label, 13f, if (selected) StillColor.White else StillColor.Variant, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = pill(if (selected) StillColor.Ink else StillColor.SurfaceContainer, StillColor.OutlineVariant)
            setOnClickListener {
                onSelect()
                renderScreen()
            }
        }

    private fun statusChip(label: String, foreground: Int, backgroundColor: Int): TextView =
        text(label, 12f, foreground, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = pill(backgroundColor, Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).withTop(dp(10))
        }

    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        text(label, 14f, StillColor.White, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(StillColor.Primary, dp(10))
            setOnClickListener { onClick() }
        }

    private fun actionWide(label: String): TextView =
        text(label, 14f, StillColor.Ink, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(StillColor.SurfaceContainer, dp(16), StillColor.OutlineVariant, 1)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).withTop(dp(18))
        }

    private fun label(value: String, color: Int): TextView =
        text(value, 12f, color, Typeface.BOLD).apply {
            letterSpacing = 0.08f
        }

    private fun body(value: String, size: Float, color: Int): TextView =
        text(value, size, color, Typeface.NORMAL).apply {
            setLineSpacing(0f, 1.14f)
        }

    private fun text(value: String, size: Float, color: Int, style: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", style)
            includeFontPadding = true
        }

    private fun card(
        backgroundColor: Int = StillColor.White,
        strokeColor: Int = StillColor.OutlineVariant,
        block: LinearLayout.() -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(backgroundColor, dp(18), strokeColor, 1)
            elevation = dp(2).toFloat()
            block()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).withMargins(bottom = dp(16))
        }

    private fun rounded(color: Int, radius: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
        }

    private fun pill(color: Int, strokeColor: Int): GradientDrawable =
        rounded(color, dp(999), strokeColor, if (strokeColor == Color.TRANSPARENT) 0 else 1)

    private fun stateTone(label: String): Tone = when (label) {
        "Available" -> Tone(StillColor.TertiaryContainer, StillColor.Tertiary, StillColor.TertiaryDim)
        "Training", "Deep work" -> Tone(StillColor.SecondaryContainer, StillColor.Secondary, StillColor.SecondaryDim)
        "Commuting" -> Tone(StillColor.SurfaceContainerHigh, StillColor.Primary, StillColor.OutlineVariant)
        "Paused" -> Tone(StillColor.SurfaceVariant, StillColor.Variant, StillColor.OutlineVariant)
        else -> Tone(StillColor.TertiaryContainer, StillColor.Tertiary, StillColor.TertiaryDim)
    }

    private fun StillState.symbol(): String = when (label) {
        "Training" -> "Lift"
        "Meditating" -> "Rest"
        "Commuting" -> "Ride"
        "Deep work" -> "Focus"
        "Paused" -> "Hold"
        else -> "Open"
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun View.withMargins(
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): View = apply {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )).also { it.setMargins(left, top, right, bottom) }
    }

    private fun LinearLayout.LayoutParams.withMargins(
        left: Int = leftMargin,
        top: Int = topMargin,
        right: Int = rightMargin,
        bottom: Int = bottomMargin,
    ): LinearLayout.LayoutParams = apply {
        setMargins(left, top, right, bottom)
    }

    private fun LinearLayout.LayoutParams.withTop(top: Int): LinearLayout.LayoutParams = apply {
        topMargin = top
    }

    private data class Tone(
        val container: Int,
        val foreground: Int,
        val border: Int,
    )

    private data class InferenceLog(
        val timestamp: Long,
        val label: String,
        val reasons: String,
    ) {
        fun serialize(): String = listOf(
            timestamp.toString(),
            label.escapeLogField(),
            reasons.escapeLogField(),
        ).joinToString(LOG_FIELD_SEPARATOR)

        companion object {
            fun deserialize(value: String): InferenceLog? {
                val parts = value.split(LOG_FIELD_SEPARATOR)
                if (parts.size != 3) return null
                return InferenceLog(
                    timestamp = parts[0].toLongOrNull() ?: return null,
                    label = parts[1].unescapeLogField(),
                    reasons = parts[2].unescapeLogField(),
                )
            }
        }
    }

    private enum class Screen(val label: String, val symbol: String) {
        Presence("Presence", "View"),
        Sensors("Sensors", "Sense"),
        Insights("Insights", "Logs"),
        Privacy("Privacy", "Lock"),
    }
}

private fun String.escapeLogField(): String =
    replace("\\", "\\\\")
        .replace(LOG_FIELD_SEPARATOR, "\\f")
        .replace(LOG_ROW_SEPARATOR, "\\r")

private fun String.unescapeLogField(): String =
    replace("\\r", LOG_ROW_SEPARATOR)
        .replace("\\f", LOG_FIELD_SEPARATOR)
        .replace("\\\\", "\\")

private object StillColor {
    const val Background = 0xFFFCF8F7.toInt()
    const val White = 0xFFFFFFFF.toInt()
    const val Ink = 0xFF1C1B1B.toInt()
    const val Variant = 0xFF464742.toInt()
    const val Disabled = 0xFF8E8C86.toInt()
    const val Primary = 0xFF5E5E5B.toInt()
    const val PrimaryContainer = 0xFFF9F7F2.toInt()
    const val SurfaceContainer = 0xFFF1EDEC.toInt()
    const val SurfaceContainerLow = 0xFFF7F3F1.toInt()
    const val SurfaceContainerHigh = 0xFFEBE7E6.toInt()
    const val SurfaceContainerHighest = 0xFFE5E2E0.toInt()
    const val SurfaceVariant = 0xFFE5E2E0.toInt()
    const val OutlineVariant = 0xFFC7C7BF.toInt()
    const val Secondary = 0xFF685C58.toInt()
    const val SecondaryContainer = 0xFFF0DFDA.toInt()
    const val SecondaryContainerLight = 0x33F0DFDA
    const val SecondaryDim = 0xFFD3C3BE.toInt()
    const val SecondaryText = 0xFF6E625E.toInt()
    const val Tertiary = 0xFF5C614D.toInt()
    const val TertiaryContainer = 0xFFF5FAE1.toInt()
    const val TertiaryDim = 0xFFC4C9B1.toInt()
    const val TertiaryText = 0xFF444937.toInt()
}

private const val SENSOR_RENDER_INTERVAL_MS = 2_000L
private const val NOTIFICATION_UPDATE_INTERVAL_MS = 30_000L
private const val LOG_REPEAT_INTERVAL_MS = 10 * 60_000L
private const val MAX_LOGS = 20
private const val PREFS_NAME = "still_mvp"
private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
private const val KEY_LOGS = "inference_logs"
private const val LOG_ROW_SEPARATOR = "\u001E"
private const val LOG_FIELD_SEPARATOR = "\u001F"
private const val STATUS_CHANNEL_ID = "still_status"
private const val STATUS_NOTIFICATION_ID = 1001
