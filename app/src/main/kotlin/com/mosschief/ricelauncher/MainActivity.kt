package com.mosschief.ricelauncher

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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

class MainActivity : Activity() {

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
    ) {
        val labelLower = label.lowercase()
        val key = "$packageName/$activityName"
    }

    private val allApps = mutableListOf<AppEntry>()
    private val shownApps = mutableListOf<AppEntry>()

    private lateinit var searchView: EditText
    private lateinit var listView: ListView
    private lateinit var recents: SharedPreferences

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

        searchView = findViewById(R.id.search)
        listView = findViewById(R.id.app_list)
        recents = getSharedPreferences("recents", MODE_PRIVATE)

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
        loadApps()
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
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
            recents.edit().putLong(app.key, System.currentTimeMillis()).apply()
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
