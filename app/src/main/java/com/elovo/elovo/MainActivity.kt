package com.elovo.elovo

import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.Context
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.elsim.elovo.R
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
    }

    private lateinit var webView: WebView
    private lateinit var splashView: View
    private lateinit var frameLayout: FrameLayout
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var currentLanguage = ""

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values[0] < (proximitySensor?.maximumRange ?: 5f)) {
                if (proximityWakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    proximityWakeLock = pm.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "elovo:proximity"
                    )
                }
                if (proximityWakeLock?.isHeld == false) {
                    proximityWakeLock?.acquire()
                }
            } else {
                if (proximityWakeLock?.isHeld == true) {
                    proximityWakeLock?.release()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            arrayOf(result.data!!.data!!)
        } else null
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        requestPermissions()
        getFCMToken()

        frameLayout = FrameLayout(this)
        setContentView(frameLayout)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )

        topBar = View(this)
        topBar.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            50.dpToPx()
        ).apply { gravity = android.view.Gravity.TOP }
        topBar.setBackgroundColor(Color.BLACK)
        topBar.isClickable = false
        topBar.isFocusable = false

        bottomBar = View(this)
        bottomBar.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            25.dpToPx()
        ).apply { gravity = android.view.Gravity.BOTTOM }
        bottomBar.setBackgroundColor(Color.BLACK)
        bottomBar.isClickable = false
        bottomBar.isFocusable = false

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            topMargin = 50.dpToPx()
            bottomMargin = 25.dpToPx()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.visibility = View.INVISIBLE
        frameLayout.addView(webView)
        frameLayout.addView(topBar)
        frameLayout.addView(bottomBar)

        splashView = layoutInflater.inflate(R.layout.splash, frameLayout, false)
        frameLayout.addView(splashView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            allowFileAccess = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/mp4"))
                }
                fileChooserLauncher.launch(intent)
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript("document.title") { title ->
                    if (title?.contains("Elovo") == true) {
                        runOnUiThread {
                            splashView.visibility = View.GONE
                            webView.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

        currentLanguage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0].toLanguageTag()
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale.toLanguageTag()
        }

        loadWebViewWithLanguage()

        intent?.let { handleCallIntent(it) }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun evaluateJs(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        val newLang = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0].toLanguageTag()
        } else {
            @Suppress("DEPRECATION")
            config.locale.toLanguageTag()
        }
        if (newLang != currentLanguage) {
            currentLanguage = newLang
            loadWebViewWithLanguage()
        }
    }

    fun updateSystemBarColors(topColor: Int, bottomColor: Int) {
        runOnUiThread {
            topBar.setBackgroundColor(topColor)
            bottomBar.setBackgroundColor(bottomColor)
        }
    }

    fun animateBoundaryPulse() {
        val pulseColor = Color.parseColor("#22c55e")
        val duration = 860L

        listOf(topBar, bottomBar).forEach { bar ->
            val originalColor = (bar.background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.BLACK
            val animator = ValueAnimator.ofFloat(0f, 1f, 0f)
            animator.duration = duration
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val blended = Color.argb(
                    255,
                    (Color.red(originalColor) + (Color.red(pulseColor) - Color.red(originalColor)) * fraction).toInt(),
                    (Color.green(originalColor) + (Color.green(pulseColor) - Color.green(originalColor)) * fraction).toInt(),
                    (Color.blue(originalColor) + (Color.blue(pulseColor) - Color.blue(originalColor)) * fraction).toInt()
                )
                bar.setBackgroundColor(blended)
            }
            animator.start()
        }
    }

    private fun loadWebViewWithLanguage() {
        val prefs = getSharedPreferences("elovo_prefs", Context.MODE_PRIVATE)
        val loggedOut = prefs.getBoolean("logged_out", false)

        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale
        }
        val languageTag = locale.toLanguageTag()
        val headers = mapOf("Accept-Language" to languageTag)

        if (loggedOut) {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                prefs.edit().putBoolean("logged_out", false).apply()
                runOnUiThread {
                    webView.loadUrl("https://elovo-app.onrender.com", headers)
                }
            }
        } else {
            webView.loadUrl("https://elovo-app.onrender.com", headers)
        }
    }

    fun startProximitySensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let {
            sensorManager.registerListener(
                proximityListener, it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    fun stopProximitySensor() {
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(proximityListener)
        }
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
        }
        proximityWakeLock = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun getFCMToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Log.d("FCM_TOKEN", token)
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        }
    }

    private fun handleCallIntent(intent: Intent) {
        val action = intent.getStringExtra("call_action") ?: return
        val callerId = intent.getStringExtra("caller_id") ?: return
        val callerName = intent.getStringExtra("caller_name") ?: ""

        if (action == "accept") {
            MyFirebaseMessagingService.stopRingtone()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1)
            webView.postDelayed({
                webView.evaluateJavascript(
                    "window.acceptIncomingCall('$callerId', '$callerName')",
                    null
                )
            }, 1500)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent)
    }

}

fun Int.dpToPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()