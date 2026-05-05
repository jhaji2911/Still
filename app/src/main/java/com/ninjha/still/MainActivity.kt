package com.ninjha.still

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.telephony.SmsManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val deterministicEngine = StillEngine()
    private val androidAICore = AndroidAICoreStatePredictor()
    private val inferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sensorFusion: SensorFusion
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var prefs: SharedPreferences

    private var currentScreen = Screen.Presence
    private var onboardingComplete = false
    private var snapshot = SensorSnapshot.Empty
    private var heartRateBpm = 72
    private var ambientAudioOverride: EnviroDecibel? = null
    private var devicePlace = DevicePlace.InHand
    private var peripheral = Peripheral.None
    private var inferencePaused = false
    private var smsChannelEnabled = false
    private var selectedContactName: String? = null
    private var selectedContactPhone: String? = null
    private var lastSentStateLabel: String? = null
    private var lastSmsSentAt = 0L
    private var lastSensorRenderAt = 0L
    private var lastNotificationAt = 0L
    private var lastNotificationLabel: String? = null
    private var lastLoggedLabel: String? = null
    private var lastLoggedAt = 0L
    private var currentState: StillState? = null
    private var currentStateToken: ContextToken? = null
    private var inferenceJob: Job? = null
    private var lastAICorePredictedToken: ContextToken? = null
    private var lastAICoreRequestAt = 0L
    private var sensorsScrollY = 0
    private val screenScrollY = mutableMapOf<Screen, Int>()
    private var renderedScreen: Screen? = null
    private var renderedBottomNavScreen: Screen? = null
    private var sensorsRefs: SensorsRefs? = null

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
                contentHost.post { refreshInference() }
            }
        }
        setContentView(buildShell())
        refreshInference()
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

    override fun onDestroy() {
        inferenceScope.cancel()
        super.onDestroy()
    }

    private fun requestSensorPermissions() {
        val requested = mutableListOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requested += Manifest.permission.POST_NOTIFICATIONS
        }

        val permissions = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 1001)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONTACT_PICK_REQUEST || resultCode != RESULT_OK) return

        val contactUri = data?.data ?: return
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                selectedContactName = cursor.getString(0)
                selectedContactPhone = cursor.getString(1)
                lastSentStateLabel = null
            }
        }
        if (currentScreen == Screen.Channels) renderScreen()
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

        rememberCurrentScroll()

        val state = if (inferencePaused) {
            pausedState()
        } else {
            currentState ?: deterministicEngine.infer(currentToken()).withFallbackReason("Android AICore warming up")
        }

        if (onboardingComplete) {
            maybeLogState(state)
            maybePostStatusNotification(state)
            maybeSendEphemeralStatus(state)
        }

        if (onboardingComplete && currentScreen == Screen.Sensors && renderedScreen == Screen.Sensors) {
            updateSensorsScreen(currentToken(), state)
            return
        }

        contentHost.removeAllViews()
        val nextScreen = if (onboardingComplete) {
            when (currentScreen) {
                Screen.Presence -> presenceScreen(state)
                Screen.Sensors -> sensorsScreen(currentToken(), state)
                Screen.Insights -> insightsScreen()
                Screen.Channels -> channelsScreen(state)
                Screen.Privacy -> privacyScreen()
            }
        } else {
            onboardingScreen()
        }
        contentHost.addView(
            nextScreen,
        )
        renderedScreen = if (onboardingComplete) currentScreen else null
        restoreCurrentScroll()

        if (onboardingComplete) {
            bottomNav.visibility = View.VISIBLE
            if (renderedBottomNavScreen != currentScreen) renderBottomNav()
        } else {
            bottomNav.visibility = View.GONE
            renderedBottomNavScreen = null
        }
    }

    private fun renderBottomNav() {
        bottomNav.removeAllViews()
        Screen.values().forEach { screen ->
            val selected = screen == currentScreen
            bottomNav.addView(navItem(screen, selected), LinearLayout.LayoutParams(0, dp(62), 1f))
        }
        renderedBottomNavScreen = currentScreen
    }

    private fun refreshInference() {
        val token = currentToken()
        currentStateToken = token
        val now = SystemClock.elapsedRealtime()

        if (inferencePaused) {
            currentState = pausedState()
            renderScreen()
            return
        }

        currentState = deterministicEngine
            .infer(token)
            .withFallbackReason(
                if (inferenceJob?.isActive == true) {
                    "Live sensor inference; Android AICore running in background"
                } else {
                    "Live sensor inference"
                },
            )

        val shouldStartAICore =
            onboardingComplete &&
                inferenceJob?.isActive != true &&
                token != lastAICorePredictedToken &&
                now - lastAICoreRequestAt >= AICORE_INFERENCE_INTERVAL_MS

        if (!shouldStartAICore) {
            renderScreen()
            return
        }

        lastAICoreRequestAt = now
        inferenceJob = inferenceScope.launch {
            val prediction = runCatching { withContext(Dispatchers.IO) { androidAICore.predict(token) } }
            val predicted = prediction.getOrElse { error ->
                    deterministicEngine
                        .infer(token)
                        .withFallbackReason("Android AICore retry later: ${error.message ?: error::class.java.simpleName}")
                }
            if (prediction.isSuccess) {
                lastAICorePredictedToken = token
            }

            if (currentStateToken == token && !inferencePaused) {
                val liveState = deterministicEngine.infer(token)
                currentState = if (prediction.isSuccess && predicted.label == liveState.label) {
                    predicted
                } else {
                    liveState.withFallbackReason("Live sensor inference; Android AICore disagreed")
                }
                renderScreen()
            }
        }
    }

    private fun rememberCurrentScroll() {
        val screen = renderedScreen ?: return
        val scrollY = (contentHost.getChildAt(0) as? ScrollView)?.scrollY ?: return
        screenScrollY[screen] = scrollY
        if (screen == Screen.Sensors) sensorsScrollY = scrollY
    }

    private fun restoreCurrentScroll() {
        val targetY = screenScrollY[currentScreen] ?: 0
        val restore = Runnable {
            (contentHost.getChildAt(0) as? ScrollView)?.scrollTo(0, targetY)
        }
        (contentHost.getChildAt(0) as? ScrollView)?.post(restore)
        contentHost.postDelayed(restore, 50L)
        contentHost.postDelayed(restore, 150L)
    }

    private fun pausedState(): StillState = StillState(
        label = "Paused",
        confidence = 1f,
        estimatedReturnMinutes = 60,
        autoReply = "Nishant has paused automatic context inference. He'll reply when ready.",
        reasons = listOf("manual pause enabled"),
    )

    private fun StillState.withFallbackReason(reason: String): StillState =
        copy(reasons = listOf(reason) + reasons)

    private fun navItem(screen: Screen, selected: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (selected) rounded(StillColor.SurfaceContainerHighest, dp(14)) else null
            isClickable = true
            isFocusable = true
            setOnClickListener {
                rememberCurrentScroll()
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
                refreshInference()
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
            refreshInference()
        })
    }

    private fun sensorsScreen(token: ContextToken, state: StillState): View = page(restoreSensorsScroll = true) {
        val refs = SensorsRefs()
        addView(headline("Sensor Tokens", "Manual controls let v1 simulate signals that need wearable or notification integrations later."))
        addView(card {
            addView(label("LIVE TOKEN BUFFER", StillColor.Variant))
            refs.motion = addDynamicRow("Motion")
            refs.heart = addDynamicRow("Heart")
            refs.audio = addDynamicRow("Audio")
            refs.place = addDynamicRow("Place")
            refs.peripheral = addDynamicRow("Peripheral")
            refs.phone = addDynamicRow("Phone")
        })
        addView(computeCard(refs))

        addView(controlGroup("Ambient Audio",
            choice("Auto", ambientAudioOverride == null) { ambientAudioOverride = null },
            choice("Silent", ambientAudioOverride == EnviroDecibel.Silent) { ambientAudioOverride = EnviroDecibel.Silent },
            choice("Rhythmic", ambientAudioOverride == EnviroDecibel.Rhythmic) { ambientAudioOverride = EnviroDecibel.Rhythmic },
            choice("Chaotic", ambientAudioOverride == EnviroDecibel.Chaotic) { ambientAudioOverride = EnviroDecibel.Chaotic },
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
            refs.currentRead = body("", 16f, StillColor.TertiaryText)
            addView(refs.currentRead)
        })
        sensorsRefs = refs
        updateSensorsScreen(token, state)
    }

    private fun LinearLayout.addDynamicRow(title: String): SensorRowRefs {
        val row = dynamicTokenRow(title)
        addView(row.view)
        return row
    }

    private fun updateSensorsScreen(token: ContextToken, state: StillState) {
        val refs = sensorsRefs ?: return
        refs.motion?.update(token.motion.name, "Accel ${format(snapshot.accelerationMagnitude)} m/s2, gyro ${format(snapshot.angularSpeedRadPerSec)} rad/s")
        refs.heart?.update("${heartRateBpm} bpm", token.physioStress.name)
        refs.audio?.update(
            token.ambientAudio.name,
            ambientAudioDetail(token),
        )
        refs.place?.update(token.devicePlace.name, snapshot.sensorDevicePlace?.let { "sensor-derived from gravity/light" } ?: "manual v1 token")
        refs.peripheral?.update(token.peripheral.name, "manual v1 token")
        refs.phone?.update(phoneStateLabel(token), "lock and charging state")
        refs.sensors?.update(sensorAvailability(), "registered hardware feeds")
        refs.steps?.update("${snapshot.sessionSteps}", "pedometer session steps")
        refs.accelerometer?.update(
            "${format(snapshot.accelerationMagnitude)} m/s2",
            "vector ${snapshot.acceleration.formatVector()}, delta g ${format(snapshot.accelerationDeltaFromGravity)}",
        )
        refs.gyroscope?.update(
            "${format(snapshot.angularSpeedRadPerSec)} rad/s",
            "vector ${snapshot.gyroscope.formatVector()}",
        )
        refs.gravity?.update(
            snapshot.gravityOrientation,
            "vector ${snapshot.gravity.formatVector()}, mag ${format(snapshot.gravityMagnitude)}",
        )
        refs.light?.update(
            snapshot.lightLux?.let { "${format(it)} lux" } ?: "unknown",
            "used with gravity to infer pocket/face-down/stand",
        )
        refs.fused?.update(
            token.motion.name,
            "place ${token.devicePlace.name}, stress ${token.physioStress.name}, ${phoneStateLabel(token)}",
        )
        refs.compute?.update(
            state.label,
            state.reasons.joinToString(" + "),
        )
        refs.currentRead?.text = "${state.label}: ${state.reasons.joinToString(", ")}"
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

    private fun channelsScreen(state: StillState): View = page {
        addView(headline("Channels", "Send the current status through local device channels. Contact and message state are kept in memory only."))
        addView(card {
            addView(label("SMS CHANNEL", StillColor.Variant))
            addView(tokenRow("Contact", selectedContactName ?: "None", selectedContactPhone ?: "Pick a contact to enable sending"))
            addView(tokenRow("Mode", if (smsChannelEnabled) "Auto" else "Off", "ephemeral send on state changes"))
            addView(actionWide("Pick Contact").apply {
                setOnClickListener { pickSmsContact() }
            })
            addView(actionWide(if (smsChannelEnabled) "Disable Auto SMS" else "Enable Auto SMS").apply {
                setOnClickListener {
                    smsChannelEnabled = !smsChannelEnabled
                    lastSentStateLabel = null
                    renderScreen()
                }
            })
            addView(actionWide("Send Current Status").apply {
                setOnClickListener {
                    sendStatusSms(state, force = true)
                    renderScreen()
                }
            })
        })
        addView(card(backgroundColor = StillColor.SurfaceContainerLow) {
            addView(label("MESSAGE PREVIEW", StillColor.Variant))
            addView(body(statusMessage(state), 16f, StillColor.Variant))
        })
    }

    private fun pickSmsContact() {
        requestSensorPermissions()
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        startActivityForResult(intent, CONTACT_PICK_REQUEST)
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
        addView(permissionRow("Audio Tokens", "No microphone access. Ambient token is inferred from motion, light, steps, and manual override.", true))
        addView(permissionRow(
            "SMS Channel",
            "Lets Still send ephemeral status messages to a selected contact.",
            hasPermission(Manifest.permission.SEND_SMS),
        ) { requestSensorPermissions() })
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
            enableVibration(true)
            vibrationPattern = STILL_VIBRATION_PATTERN
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
            .setVibrate(STILL_VIBRATION_PATTERN)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        manager.notify(STATUS_NOTIFICATION_ID, notification)
    }

    private fun maybeSendEphemeralStatus(state: StillState) {
        if (!smsChannelEnabled) return
        if (state.label == lastSentStateLabel) return
        if (SystemClock.elapsedRealtime() - lastSmsSentAt < SMS_SEND_INTERVAL_MS) return
        sendStatusSms(state, force = false)
    }

    private fun sendStatusSms(state: StillState, force: Boolean) {
        val phone = selectedContactPhone ?: return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestSensorPermissions()
            return
        }
        if (!force && !smsChannelEnabled) return

        SmsManager.getDefault().sendTextMessage(phone, null, statusMessage(state), null, null)
        lastSentStateLabel = state.label
        lastSmsSentAt = SystemClock.elapsedRealtime()
    }

    private fun statusMessage(state: StillState): String =
        "Still: ${state.autoReply}"

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
        ambientAudio = ambientAudioOverride ?: inferredAmbientAudio(),
        devicePlace = snapshot.sensorDevicePlace ?: devicePlace,
        peripheral = peripheral,
        heartRateBpm = heartRateBpm,
        isDeviceLocked = isDeviceLocked(),
        isCharging = isCharging(),
        sessionSteps = snapshot.sessionSteps,
    )

    private fun stressFromHeartRate(heartRateBpm: Int): PhysioStress = when {
        heartRateBpm >= 155 -> PhysioStress.Anaerobic
        heartRateBpm >= 105 -> PhysioStress.Aerobic
        else -> PhysioStress.Resting
    }

    private fun inferredAmbientAudio(): EnviroDecibel {
        val lightLux = snapshot.lightLux
        return when {
        lightLux == null -> EnviroDecibel.Silent
        lightLux < 2f && snapshot.motion == MotionState.Still -> EnviroDecibel.Silent
        snapshot.sessionSteps >= 20 || snapshot.motion == MotionState.HighMotion -> EnviroDecibel.Chaotic
        snapshot.angularSpeedRadPerSec in 0.15f..0.8f || snapshot.motion == MotionState.MicroVibration -> EnviroDecibel.Rhythmic
        else -> EnviroDecibel.Silent
        }
    }

    private fun page(restoreSensorsScroll: Boolean = false, content: LinearLayout.() -> Unit): View =
        ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(StillColor.Background)
            scrollTo(0, screenScrollY[currentScreen] ?: 0)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                screenScrollY[currentScreen] = scrollY
                if (restoreSensorsScroll) sensorsScrollY = scrollY
            }
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

    private fun dynamicTokenRow(title: String): SensorRowRefs {
        val valueView = statusChip("", StillColor.Variant, StillColor.SurfaceContainer)
        val detailView = body("", 12f, StillColor.Variant)
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 14f, StillColor.Ink, Typeface.BOLD))
                addView(detailView)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
        }
        return SensorRowRefs(view, valueView, detailView)
    }

    private fun SensorRowRefs.update(value: String, detail: String) {
        if (valueView.text.toString() != value) valueView.text = value
        if (detailView.text.toString() != detail) detailView.text = detail
    }

    private fun computeCard(refs: SensorsRefs): View =
        card(backgroundColor = StillColor.SurfaceContainerLow) {
            addView(label("DEV COMPUTE", StillColor.Variant))
            refs.sensors = addDynamicRow("Sensors")
            refs.steps = addDynamicRow("Steps")
            refs.accelerometer = addDynamicRow("Accelerometer")
            refs.gyroscope = addDynamicRow("Gyroscope")
            refs.gravity = addDynamicRow("Gravity")
            refs.light = addDynamicRow("Ambient light")
            refs.fused = addDynamicRow("Fused token")
            refs.compute = addDynamicRow("Score path")
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
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onSelect()
                if (currentScreen == Screen.Sensors) {
                    renderedScreen = null
                    sensorsRefs = null
                }
                refreshInference()
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
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun actionWide(label: String): TextView =
        text(label, 14f, StillColor.Ink, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(StillColor.SurfaceContainer, dp(16), StillColor.OutlineVariant, 1)
            isClickable = true
            isFocusable = true
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
        "Away" -> Tone(StillColor.SurfaceContainerHigh, StillColor.Variant, StillColor.OutlineVariant)
        "Paused" -> Tone(StillColor.SurfaceVariant, StillColor.Variant, StillColor.OutlineVariant)
        else -> Tone(StillColor.TertiaryContainer, StillColor.Tertiary, StillColor.TertiaryDim)
    }

    private fun StillState.symbol(): String = when (label) {
        "Training" -> "Lift"
        "Meditating" -> "Rest"
        "Commuting" -> "Ride"
        "Deep work" -> "Focus"
        "Away" -> "Away"
        "Paused" -> "Hold"
        else -> "Open"
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun VectorSample.formatVector(): String =
        "(${format(x)}, ${format(y)}, ${format(z)})"

    private fun sensorAvailability(): String = listOf(
        "accel" to snapshot.hasAccelerometer,
        "gyro" to snapshot.hasGyroscope,
        "gravity" to snapshot.hasGravitySensor,
        "light" to snapshot.hasLightSensor,
        "steps" to (snapshot.hasStepCounter || snapshot.hasStepDetector),
    ).joinToString(" / ") { (name, available) -> "$name ${if (available) "on" else "off"}" }

    private fun phoneStateLabel(token: ContextToken): String = when {
        token.isDeviceLocked && token.isCharging -> "locked + charging"
        token.isDeviceLocked -> "locked"
        token.isCharging -> "charging"
        else -> "unlocked"
    }

    private fun ambientAudioDetail(token: ContextToken): String =
        ambientAudioOverride?.let { "manual override; no microphone permission used" }
            ?: "inferred without mic from motion ${token.motion.name}, light ${snapshot.lightLux?.let { format(it) } ?: "unknown"} lux, steps ${snapshot.sessionSteps}"

    private fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun isCharging(): Boolean =
        getSystemService(BatteryManager::class.java)?.isCharging == true

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
        Channels("Channels", "Send"),
        Privacy("Privacy", "Lock"),
    }

    private data class SensorRowRefs(
        val view: View,
        val valueView: TextView,
        val detailView: TextView,
    )

    private data class SensorsRefs(
        var motion: SensorRowRefs? = null,
        var heart: SensorRowRefs? = null,
        var audio: SensorRowRefs? = null,
        var place: SensorRowRefs? = null,
        var peripheral: SensorRowRefs? = null,
        var phone: SensorRowRefs? = null,
        var sensors: SensorRowRefs? = null,
        var steps: SensorRowRefs? = null,
        var accelerometer: SensorRowRefs? = null,
        var gyroscope: SensorRowRefs? = null,
        var gravity: SensorRowRefs? = null,
        var light: SensorRowRefs? = null,
        var fused: SensorRowRefs? = null,
        var compute: SensorRowRefs? = null,
        var currentRead: TextView? = null,
    )
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
private const val AICORE_INFERENCE_INTERVAL_MS = 60_000L
private const val NOTIFICATION_UPDATE_INTERVAL_MS = 30_000L
private const val LOG_REPEAT_INTERVAL_MS = 10 * 60_000L
private const val SMS_SEND_INTERVAL_MS = 5 * 60_000L
private const val MAX_LOGS = 20
private const val CONTACT_PICK_REQUEST = 2001
private const val PREFS_NAME = "still_mvp"
private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
private const val KEY_LOGS = "inference_logs"
private const val LOG_ROW_SEPARATOR = "\u001E"
private const val LOG_FIELD_SEPARATOR = "\u001F"
private const val STATUS_CHANNEL_ID = "still_status_signature_v2"
private const val STATUS_NOTIFICATION_ID = 1001
private val STILL_VIBRATION_PATTERN = longArrayOf(0L, 45L, 60L, 120L, 80L, 45L)
