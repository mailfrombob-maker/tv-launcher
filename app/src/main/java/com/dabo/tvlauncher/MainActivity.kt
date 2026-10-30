package com.dabo.tvlauncher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppItem(
    val pkg: String,
    val label: String,
    val drawable: Drawable,
    val isBanner: Boolean,
    val intent: Intent
) {
    var accent: Int = 0
}

/**
 * Whole UI is built in code on a 960x540 design grid scaled to the real screen height,
 * so it looks the same on 720p, 1080p and 4K boxes regardless of their reported density.
 */
class MainActivity : Activity() {

    private var unit = 1f
    private fun u(v: Float): Float = v * unit
    private fun ui(v: Float): Int = (v * unit).toInt()

    private val ink = 0xFF08080C.toInt()
    private val defaultPins =
        "com.cricfytv.sports,com.phlox.tvwebbrowser,com.netflix.mediaclient," +
            "com.google.android.youtube.tv,com.amazon.amazonvideo.livingroom,com.android.vending"

    // Not real apps: helper services and a leftover drawer shortcut.
    private val hiddenPkgs = setOf(
        "com.google.android.tv.remote.service",
        "com.purefusionatv.appdraweratv"
    )

    // Apps whose banner image is blank or unreadable: use the normal icon instead.
    private val iconOnlyPkgs = setOf("com.phlox.tvwebbrowser")

    private lateinit var bg: GradientDrawable
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var pinnedLabel: TextView
    private lateinit var pinnedRv: RecyclerView
    private lateinit var allRv: RecyclerView
    private lateinit var pinnedAdapter: AppAdapter
    private lateinit var allAdapter: AppAdapter

    private var allApps: List<AppItem> = emptyList()
    private var currentAccent = 0xFF1B2440.toInt()
    private var accentAnim: ValueAnimator? = null
    private var didInitialFocus = false

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        unit = resources.displayMetrics.heightPixels / 540f
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(buildUi())
        reload()

        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addDataScheme("package")
        registerReceiver(pkgReceiver, f)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pkgReceiver)
        } catch (e: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !didInitialFocus) {
            didInitialFocus = true
            focusFirstRow()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home button pressed while already on the launcher: jump back to the start.
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            pinnedRv.scrollToPosition(0)
            allRv.scrollToPosition(0)
            focusFirstRow()
        }
    }

    // A launcher has nowhere to go back to.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
    }

    private fun focusFirstRow() {
        val target = if (pinnedRv.visibility == View.VISIBLE) pinnedRv else allRv
        target.requestFocus()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(ink)
        root.clipChildren = false

        bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(currentAccent, ink))
        val bgView = View(this)
        bgView.background = bg
        root.addView(bgView, FrameLayout.LayoutParams(-1, -1))

        // Darkens the lower half so the rows stay readable on bright accent colours.
        val shade = View(this)
        shade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0xE6000000.toInt())
        )
        root.addView(shade, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.clipChildren = false
        col.clipToPadding = false
        col.setPadding(0, ui(22f), 0, ui(14f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Clock + date
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.BOTTOM
        top.setPadding(ui(48f), 0, ui(48f), 0)

        val time = TextClock(this)
        time.format12Hour = "h:mm"
        time.format24Hour = "H:mm"
        time.setTextColor(Color.WHITE)
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(24f))
        time.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

        val date = TextClock(this)
        date.format12Hour = "EEEE, d MMMM"
        date.format24Hour = "EEEE, d MMMM"
        date.setTextColor(0x99FFFFFF.toInt())
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12f))
        date.setPadding(ui(12f), 0, 0, ui(3f))

        top.addView(time)
        top.addView(date)
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        // Hero: name of the focused app
        val hero = LinearLayout(this)
        hero.orientation = LinearLayout.VERTICAL
        hero.gravity = Gravity.BOTTOM
        hero.setPadding(ui(48f), 0, ui(48f), ui(16f))

        heroTitle = TextView(this)
        heroTitle.setTextColor(Color.WHITE)
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(44f))
        heroTitle.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        heroTitle.maxLines = 1
        heroTitle.ellipsize = android.text.TextUtils.TruncateAt.END

        heroSub = TextView(this)
        heroSub.setTextColor(0xB3FFFFFF.toInt())
        heroSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(13f))

        hero.addView(heroTitle)
        hero.addView(heroSub)
        col.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f))

        // Rows
        pinnedLabel = rowLabel("Pinned")
        pinnedAdapter = AppAdapter(emptyList(), true)
        pinnedRv = makeRow(pinnedAdapter)
        col.addView(pinnedLabel, LinearLayout.LayoutParams(-1, -2))
        col.addView(pinnedRv, LinearLayout.LayoutParams(-1, -2))

        val allLabel = rowLabel("All apps")
        allAdapter = AppAdapter(emptyList(), false)
        allRv = makeRow(allAdapter)
        col.addView(allLabel, LinearLayout.LayoutParams(-1, -2))
        col.addView(allRv, LinearLayout.LayoutParams(-1, -2))

        return root
    }

    private fun rowLabel(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0x99FFFFFF.toInt())
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12f))
        t.setPadding(ui(48f), ui(14f), 0, ui(2f))
        return t
    }

    private fun makeRow(adapter: AppAdapter): RecyclerView {
        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipChildren = false
        rv.clipToPadding = false
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        rv.setPadding(ui(41f), ui(10f), ui(41f), ui(6f))
        return rv
    }

    private fun tileBackground(focused: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = u(10f)
        d.setColor(0xFF1E1E26.toInt())
        if (focused) d.setStroke(ui(3f), Color.WHITE)
        return d
    }

    // ---------------------------------------------------------------- data

    private fun prefs() = getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private fun getPins(): List<String> =
        (prefs().getString("pins", defaultPins) ?: "").split(",").filter { it.isNotBlank() }

    private fun reload() {
        allApps = loadApps()
        allAdapter.items = allApps
        allAdapter.notifyDataSetChanged()
        refreshPins()
    }

    private fun refreshPins() {
        val pins = getPins()
        val items = pins.mapNotNull { p -> allApps.firstOrNull { it.pkg == p } }
        pinnedAdapter.items = items
        pinnedAdapter.notifyDataSetChanged()
        val vis = if (items.isEmpty()) View.GONE else View.VISIBLE
        pinnedLabel.visibility = vis
        pinnedRv.visibility = vis
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppItem>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || pkg in hiddenPkgs || !seen.add(pkg)) continue
                val banner = if (pkg in iconOnlyPkgs) null else ri.activityInfo.loadBanner(pm)
                val drawable = banner ?: ri.loadIcon(pm)
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                out.add(AppItem(pkg, ri.loadLabel(pm).toString(), drawable, banner != null, launch))
            }
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    private fun launch(item: AppItem) {
        try {
            startActivity(item.intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open " + item.label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePin(item: AppItem, fromPinnedRow: Boolean) {
        val pins = getPins().toMutableList()
        val wasPinned = pins.remove(item.pkg)
        if (!wasPinned) pins.add(item.pkg)
        prefs().edit().putString("pins", pins.joinToString(",")).apply()
        refreshPins()
        Toast.makeText(
            this,
            (if (wasPinned) "Unpinned " else "Pinned ") + item.label,
            Toast.LENGTH_SHORT
        ).show()
        onItemFocused(item)
        if (fromPinnedRow && wasPinned) {
            val target = if (pinnedAdapter.items.isEmpty()) allRv else pinnedRv
            target.post { target.requestFocus() }
        }
    }

    // ---------------------------------------------------------------- dynamic colour

    private fun onItemFocused(item: AppItem) {
        heroTitle.text = item.label
        heroSub.text = if (getPins().contains(item.pkg)) {
            "Pinned. Hold OK to unpin."
        } else {
            "Hold OK to pin to the top row."
        }
        animateAccent(accentFor(item))
    }

    private fun accentFor(item: AppItem): Int {
        if (item.accent != 0) return item.accent
        var c = 0xFF2A3350.toInt()
        try {
            val d = item.drawable.constantState?.newDrawable()?.mutate() ?: item.drawable
            val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 24, 24)
            d.draw(Canvas(b))
            val one = Bitmap.createScaledBitmap(b, 1, 1, true)
            val px = one.getPixel(0, 0)
            val hsv = FloatArray(3)
            Color.colorToHSV(px, hsv)
            if (Color.alpha(px) > 60 && hsv[1] > 0.12f) {
                hsv[1] = Math.min(hsv[1], 0.75f)
                hsv[2] = 0.42f
                c = Color.HSVToColor(hsv)
            }
        } catch (e: Exception) {
        }
        item.accent = c
        return c
    }

    private fun animateAccent(target: Int) {
        if (target == currentAccent) return
        accentAnim?.cancel()
        val a = ValueAnimator.ofObject(ArgbEvaluator(), currentAccent, target)
        a.duration = 500
        a.addUpdateListener {
            currentAccent = it.animatedValue as Int
            bg.setColors(intArrayOf(currentAccent, ink))
        }
        a.start()
        accentAnim = a
    }

    // ---------------------------------------------------------------- adapter

    inner class AppAdapter(var items: List<AppItem>, private val pinnedRow: Boolean) :
        RecyclerView.Adapter<AppAdapter.VH>() {

        inner class VH(
            val tile: FrameLayout,
            val img: ImageView,
            val label: TextView,
            root: View
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(this@MainActivity)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(ui(7f), ui(4f), ui(7f), ui(2f))
            root.isFocusable = true
            root.isClickable = true

            val tile = FrameLayout(this@MainActivity)
            tile.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, u(10f))
                }
            }
            tile.clipToOutline = true
            val img = ImageView(this@MainActivity)
            tile.addView(img, FrameLayout.LayoutParams(-1, -1))
            root.addView(tile, LinearLayout.LayoutParams(ui(150f), ui(84f)))

            val label = TextView(this@MainActivity)
            label.setTextColor(Color.WHITE)
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(11f))
            label.maxLines = 1
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            label.gravity = Gravity.CENTER_HORIZONTAL
            label.setPadding(0, ui(5f), 0, 0)
            root.addView(label, LinearLayout.LayoutParams(ui(150f), -2))

            return VH(tile, img, label, root)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            h.img.setImageDrawable(item.drawable)
            if (item.isBanner) {
                h.img.scaleType = ImageView.ScaleType.CENTER_CROP
                h.img.setPadding(0, 0, 0, 0)
            } else {
                h.img.scaleType = ImageView.ScaleType.FIT_CENTER
                h.img.setPadding(ui(42f), ui(9f), ui(42f), ui(9f))
            }
            h.label.text = item.label
            applyFocus(h, h.itemView.isFocused)

            h.itemView.setOnFocusChangeListener { _, focused ->
                applyFocus(h, focused)
                if (focused) onItemFocused(item)
            }
            h.itemView.setOnClickListener { launch(item) }
            h.itemView.setOnLongClickListener {
                togglePin(item, pinnedRow)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        private fun applyFocus(h: VH, focused: Boolean) {
            val v = h.itemView
            val s = if (focused) 1.1f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(130).start()
            v.translationZ = if (focused) u(24f) else 0f
            h.label.alpha = if (focused) 1f else 0.55f
            h.tile.background = tileBackground(focused)
        }
    }
}
