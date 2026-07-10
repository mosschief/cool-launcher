package com.mosschief.ricelauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private val FIREFOX_PACKAGES = listOf(
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
        )
    }

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
        val user: UserHandle,
        val key: String,
    ) {
        val labelLower = label.lowercase()
    }

    private val allApps = mutableListOf<AppEntry>()
    private val shownApps = mutableListOf<AppEntry>()

    private lateinit var clockView: TextView
    private lateinit var batteryView: TextView
    private lateinit var searchView: EditText
    private lateinit var listView: ListView
    private lateinit var recents: SharedPreferences
    private lateinit var swipeDetector: GestureDetector
    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                batteryView.text = "${level * 100 / scale}%"
            }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shownApps.size
        override fun getItem(position: Int) = shownApps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            (view as TextView).text = shownApps[position].label
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clockView = findViewById(R.id.clock)
        batteryView = findViewById(R.id.battery)
        searchView = findViewById(R.id.search)
        listView = findViewById(R.id.app_list)
        recents = getSharedPreferences("recents", MODE_PRIVATE)
        launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        userManager = getSystemService(USER_SERVICE) as UserManager

        // On API 30+ (and forced edge-to-edge on 35) pad the root for system bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val root = findViewById<View>(R.id.root)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            launchApp(shownApps[position])
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            openAppInfo(shownApps[position])
            true
        }

        searchView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        searchView.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                shownApps.firstOrNull()?.let { launchApp(it) }
                true
            } else {
                false
            }
        }

        // Fast, steep upward flick anywhere → Firefox. Thresholds are set well
        // above normal list-scroll flings so scrolling is unaffected.
        val density = resources.displayMetrics.density
        val minVelocity = 2500f * density
        val minTravel = resources.displayMetrics.heightPixels * 0.15f
        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val travel = (e1?.y ?: return false) - e2.y
                if (velocityY < -minVelocity &&
                    travel > minTravel &&
                    -velocityY > 2 * kotlin.math.abs(velocityX)
                ) {
                    launchFirefox()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateClock()
        loadApps()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeReceiver)
        unregisterReceiver(batteryReceiver)
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        resetSearch()
    }

    override fun onNewIntent(intent: Intent) {
        // Home pressed while already on the launcher.
        super.onNewIntent(intent)
        resetSearch()
        listView.setSelection(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Launchers don't exit on back; just clear any in-progress search.
        resetSearch()
    }

    private fun loadApps() {
        // LauncherApps sees every profile (personal + work), unlike
        // PackageManager which only queries the profile we run in.
        val myUser = Process.myUserHandle()
        allApps.clear()
        for (profile in userManager.userProfiles) {
            val isWork = profile != myUser
            for (info in launcherApps.getActivityList(null, profile)) {
                val pkg = info.applicationInfo.packageName
                if (pkg == packageName) continue
                val label = info.label.toString()
                // Personal-profile keys keep the old format so existing
                // recency data survives the upgrade.
                val key = if (isWork) {
                    "$pkg/${info.name}:u${userManager.getSerialNumberForUser(profile)}"
                } else {
                    "$pkg/${info.name}"
                }
                allApps.add(AppEntry(
                    label = if (isWork) "$label (Work)" else label,
                    packageName = pkg,
                    activityName = info.name,
                    user = profile,
                    key = key,
                ))
            }
        }
        // Most recently launched first; never-launched apps alphabetical below.
        allApps.sortWith(
            compareByDescending<AppEntry> { recents.getLong(it.key, 0L) }
                .thenBy { it.labelLower }
        )
        pruneRecents()
        applyFilter()
    }

    private fun applyFilter() {
        val query = searchView.text.toString().trim().lowercase()
        shownApps.clear()
        if (query.isEmpty()) {
            shownApps.addAll(allApps)
        } else {
            // Prefix matches first, then substring matches — wmenu-style.
            shownApps.addAll(allApps.filter { it.labelLower.startsWith(query) })
            shownApps.addAll(allApps.filter {
                !it.labelLower.startsWith(query) && it.labelLower.contains(query)
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun launchApp(app: AppEntry) {
        try {
            launcherApps.startMainActivity(
                ComponentName(app.packageName, app.activityName),
                app.user,
                null,
                null,
            )
            recents.edit().putLong(app.key, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            // Uninstalled since the list was built, or the work profile is
            // paused — refresh either way.
            loadApps()
        }
    }

    private fun launchFirefox() {
        val pkg = FIREFOX_PACKAGES.firstOrNull { p ->
            try {
                packageManager.getPackageInfo(p, 0)
                true
            } catch (e: Exception) {
                false
            }
        } ?: return

        // Mimic the Firefox search widget's intent: opens with the address bar
        // focused and the keyboard up (StartSearchIntentProcessor in Fenix).
        val searchIntent = Intent()
            .setClassName(pkg, "org.mozilla.fenix.IntentReceiverActivity")
            .putExtra("open_to_search", "search_widget")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(searchIntent)
        } catch (e: Exception) {
            // Fenix internals changed or activity unavailable: plain launch.
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                try {
                    startActivity(it)
                } catch (e: Exception) {
                    return
                }
            } ?: return
        }
        // Bump Firefox (personal profile) in the recency list too.
        val myUser = Process.myUserHandle()
        allApps.firstOrNull { it.packageName == pkg && it.user == myUser }?.let {
            recents.edit().putLong(it.key, System.currentTimeMillis()).apply()
        }
    }

    private fun openAppInfo(app: AppEntry) {
        try {
            launcherApps.startAppDetailsActivity(
                ComponentName(app.packageName, app.activityName),
                app.user,
                null,
                null,
            )
        } catch (e: Exception) {
            // e.g. work profile paused; nothing sensible to show.
        }
    }

    // wmenu-style: the prompt is always focused with the keyboard up,
    // ready to type into.
    private fun resetSearch() {
        searchView.text.clear()
        searchView.requestFocus()
        searchView.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchView, 0)
        }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.statusBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun updateClock() {
        val fmt = SimpleDateFormat("HH:mm  EEE d MMM", Locale.getDefault())
        clockView.text = fmt.format(Date()).lowercase()
    }

    private fun pruneRecents() {
        val installed = allApps.mapTo(HashSet()) { it.key }
        val editor = recents.edit()
        var dirty = false
        for (key in recents.all.keys) {
            if (key !in installed) {
                editor.remove(key)
                dirty = true
            }
        }
        if (dirty) editor.apply()
    }
}
