package com.mosschief.ricelauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
    ) {
        val labelLower = label.lowercase()
    }

    private val allApps = mutableListOf<AppEntry>()
    private val shownApps = mutableListOf<AppEntry>()

    private lateinit var clockView: TextView
    private lateinit var batteryView: TextView
    private lateinit var searchView: EditText
    private lateinit var listView: ListView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clockView = findViewById(R.id.clock)
        batteryView = findViewById(R.id.battery)
        searchView = findViewById(R.id.search)
        listView = findViewById(R.id.app_list)

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
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(intent, 0)
        allApps.clear()
        resolved.mapNotNullTo(allApps) { info ->
            val activity = info.activityInfo
            if (activity.packageName == packageName) return@mapNotNullTo null
            AppEntry(
                label = info.loadLabel(packageManager).toString(),
                packageName = activity.packageName,
                activityName = activity.name,
            )
        }
        allApps.sortBy { it.labelLower }
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
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // App may have been uninstalled since the list was built.
            loadApps()
        }
    }

    private fun openAppInfo(app: AppEntry) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${app.packageName}"))
        )
    }

    private fun resetSearch() {
        searchView.text.clear()
        searchView.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchView.windowToken, 0)
    }

    private fun updateClock() {
        val fmt = SimpleDateFormat("HH:mm  EEE d MMM", Locale.getDefault())
        clockView.text = fmt.format(Date()).lowercase()
    }
}
