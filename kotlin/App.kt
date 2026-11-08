package com.example.pdfreader

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val MP = -1
const val WC = -2

// ---------------------------------------------------------------- ডাটা

data class Book(val uri: String, val name: String, val page: Int, val frac: Float, val last: Long)

// type: 1 = হাইলাইট, 2 = আন্ডারলাইন, 3 = নোট (সব অবস্থান পৃষ্ঠার মাপের অনুপাতে 0..1)
data class Ann(
    val id: Long, val page: Int, val type: Int,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val color: Int, val text: String
)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun books(): List<Book> {
        val a = JSONArray(sp.getString("books", "[]"))
        val l = ArrayList<Book>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l.add(
                Book(
                    o.getString("uri"), o.getString("name"), o.optInt("page"),
                    o.optDouble("frac", 0.0).toFloat(), o.optLong("last")
                )
            )
        }
        return l.sortedByDescending { it.last }
    }

    private fun putBooks(l: List<Book>) {
        val a = JSONArray()
        for (b in l) {
            a.put(
                JSONObject().put("uri", b.uri).put("name", b.name).put("page", b.page)
                    .put("frac", b.frac.toDouble()).put("last", b.last)
            )
        }
        sp.edit().putString("books", a.toString()).apply()
    }

    fun get(uri: String): Book? = books().firstOrNull { it.uri == uri }

    fun add(uri: String, name: String) {
        val l = books()
        if (l.any { it.uri == uri }) return
        putBooks(l + Book(uri, name, 0, 0f, System.currentTimeMillis()))
    }

    fun savePos(uri: String, page: Int, frac: Float) {
        val now = System.currentTimeMillis()
        putBooks(books().map { if (it.uri == uri) it.copy(page = page, frac = frac, last = now) else it })
    }

    fun remove(uri: String) {
        putBooks(books().filter { it.uri != uri })
        sp.edit().remove("ann:$uri").apply()
    }

    fun anns(uri: String): MutableList<Ann> {
        val a = JSONArray(sp.getString("ann:$uri", "[]"))
        val l = ArrayList<Ann>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l.add(
                Ann(
                    o.getLong("id"), o.getInt("p"), o.getInt("t"),
                    o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat(),
                    o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat(),
                    o.getInt("c"), o.optString("n", "")
                )
            )
        }
        return l
    }

    fun saveAnns(uri: String, list: List<Ann>) {
        val a = JSONArray()
        for (x in list) {
            a.put(
                JSONObject().put("id", x.id).put("p", x.page).put("t", x.type)
                    .put("x1", x.x1.toDouble()).put("y1", x.y1.toDouble())
                    .put("x2", x.x2.toDouble()).put("y2", x.y2.toDouble())
                    .put("c", x.color).put("n", x.text)
            )
        }
        sp.edit().putString("ann:$uri", a.toString()).apply()
    }

    fun zoom(): Float = sp.getFloat("zoom", 1f)
    fun setZoom(z: Float) {
        sp.edit().putFloat("zoom", z).apply()
    }
}

// ---------------------------------------------------------------- বইয়ের তালিকা

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private lateinit var list: ListView
    private var books: List<Book> = emptyList()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            store.add(u.toString(), nameOf(u))
        }
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun nameOf(u: Uri): String {
        var n: String? = null
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) n = it.getString(0)
            }
        } catch (_: Exception) {
        }
        return (n ?: "PDF").removeSuffix(".pdf").removeSuffix(".PDF")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0xFF1E88E5.toInt())
        bar.setPadding(dp(16), dp(10), dp(10), dp(10))
        val title = TextView(this)
        title.text = "আমার বই"
        title.setTextColor(Color.WHITE)
        title.textSize = 20f
        bar.addView(title, LinearLayout.LayoutParams(0, WC, 1f))
        val add = Button(this)
        add.text = "+ PDF যোগ করুন"
        add.isAllCaps = false
        add.setOnClickListener { picker.launch(arrayOf("application/pdf")) }
        bar.addView(add)
        root.addView(bar, LinearLayout.LayoutParams(MP, WC))

        val frame = FrameLayout(this)
        list = ListView(this)
        val empty = TextView(this)
        empty.text = "উপরের বাটনে চেপে PDF বই যোগ করুন"
        empty.gravity = Gravity.CENTER
        empty.textSize = 16f
        frame.addView(list, FrameLayout.LayoutParams(MP, MP))
        frame.addView(empty, FrameLayout.LayoutParams(MP, MP))
        list.emptyView = empty
        root.addView(frame, LinearLayout.LayoutParams(MP, 0, 1f))
        setContentView(root)

        list.adapter = object : BaseAdapter() {
            override fun getCount() = books.size
            override fun getItem(position: Int) = books[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val b = books[position]
                v.findViewById<TextView>(android.R.id.text1).text = b.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    if (b.page > 0 || b.frac > 0.01f) "পৃষ্ঠা ${b.page + 1} থেকে পড়া চলবে" else "এখনো পড়া শুরু হয়নি"
                return v
            }
        }
        list.setOnItemClickListener { _, _, p, _ ->
            startActivity(Intent(this, ReaderActivity::class.java).putExtra("uri", books[p].uri))
        }
        list.setOnItemLongClickListener { _, _, p, _ ->
            val b = books[p]
            AlertDialog.Builder(this)
                .setMessage("\"${b.name}\" তালিকা থেকে সরাবেন? (ফাইল মুছবে না, তবে এই বইয়ের মার্ক ও নোট মুছে যাবে)")
                .setPositiveButton("সরান") { _, _ ->
                    store.remove(b.uri)
                    refresh()
                }
                .setNegativeButton("না", null)
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        books = store.books()
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }
}

// ---------------------------------------------------------------- পৃষ্ঠা ভিউ (আঁকা + স্পর্শ)

interface PageHost {
    fun annsOf(page: Int): List<Ann>
    fun mode(): Int
    fun color(): Int
    fun addAnn(a: Ann)
    fun removeAnn(a: Ann)
    fun noteDialog(page: Int, nx: Float, ny: Float, old: Ann?)
}

class PageView(ctx: Context, private val host: PageHost) : View(ctx) {
    @Volatile
    var pageIndex = -1
    var bmp: Bitmap? = null

    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var sx = 0f
    private var sy = 0f
    private var cx = 0f
    private var cy = 0f
    private var drawing = false

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val b = bmp
        if (b != null) {
            dst.set(0, 0, width, height)
            c.drawBitmap(b, null, dst, null)
        } else {
            c.drawColor(Color.WHITE)
        }
        for (a in host.annsOf(pageIndex)) {
            when (a.type) {
                1 -> {
                    paint.style = Paint.Style.FILL
                    paint.color = a.color
                    paint.alpha = 100
                    c.drawRect(a.x1 * w, a.y1 * h, a.x2 * w, a.y2 * h, paint)
                }
                2 -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.5f * d
                    paint.color = a.color
                    c.drawLine(a.x1 * w, a.y2 * h, a.x2 * w, a.y2 * h, paint)
                }
                3 -> drawNote(c, a.x1 * w, a.y1 * h, a.color)
            }
        }
        if (drawing) {
            val l = min(sx, cx)
            val r = max(sx, cx)
            val t = min(sy, cy)
            val bt = max(sy, cy)
            paint.color = host.color()
            if (host.mode() == 1) {
                paint.style = Paint.Style.FILL
                paint.alpha = 100
                c.drawRect(l, t, r, bt, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f * d
                c.drawLine(l, bt, r, bt, paint)
            }
        }
    }

    private fun drawNote(c: Canvas, x: Float, y: Float, col: Int) {
        val s = 13f * d
        paint.style = Paint.Style.FILL
        paint.color = col
        c.drawRoundRect(RectF(x - s, y - s, x + s, y + s), 4f * d, 4f * d, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * d
        for (i in -1..1) {
            val yy = y + i * 6f * d
            c.drawLine(x - 7f * d, yy, x + 7f * d, yy, paint)
        }
    }

    private fun hit(x: Float, y: Float): Ann? {
        val w = width.toFloat()
        val h = height.toFloat()
        val list = host.annsOf(pageIndex).sortedByDescending { it.type == 3 }
        for (a in list) {
            when (a.type) {
                3 -> if (abs(x - a.x1 * w) < 16 * d && abs(y - a.y1 * h) < 16 * d) return a
                1 -> if (x >= a.x1 * w && x <= a.x2 * w && y >= a.y1 * h && y <= a.y2 * h) return a
                2 -> if (x >= a.x1 * w && x <= a.x2 * w && abs(y - a.y2 * h) < 14 * d) return a
            }
        }
        return null
    }

    private fun commit(m: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val l = (min(sx, cx) / w).coerceIn(0f, 1f)
        val r = (max(sx, cx) / w).coerceIn(0f, 1f)
        val t = (min(sy, cy) / h).coerceIn(0f, 1f)
        val b = (max(sy, cy) / h).coerceIn(0f, 1f)
        if (m == 1 && (r - l) * w > 10 * d && (b - t) * h > 6 * d) {
            host.addAnn(Ann(System.nanoTime(), pageIndex, 1, l, t, r, b, host.color(), ""))
        } else if (m == 2 && (r - l) * w > 10 * d) {
            host.addAnn(Ann(System.nanoTime(), pageIndex, 2, l, t, r, b, host.color(), ""))
        }
    }

    private fun tap(m: Int, x: Float, y: Float) {
        val a = hit(x, y)
        when (m) {
            4 -> if (a != null) host.removeAnn(a)
            3 -> {
                if (a != null && a.type == 3) host.noteDialog(pageIndex, a.x1, a.y1, a)
                else host.noteDialog(pageIndex, x / width, y / height, null)
            }
            else -> if (a != null && a.type == 3) host.noteDialog(pageIndex, a.x1, a.y1, a)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = host.mode()
        if (m == 1 || m == 2) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    sx = e.x; sy = e.y; cx = e.x; cy = e.y
                    drawing = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    cx = e.x; cy = e.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    cx = e.x; cy = e.y
                    drawing = false
                    commit(m)
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    drawing = false
                    invalidate()
                }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x; sy = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - sx) < slop && abs(e.y - sy) < slop) tap(m, e.x, e.y)
            }
        }
        return true
    }
}

// ---------------------------------------------------------------- রিডার

class ReaderActivity : ComponentActivity(), PageHost {
    private lateinit var store: Store
    private lateinit var uriStr: String
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var ratios = FloatArray(0)

    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var hsv: HorizontalScrollView
    private lateinit var pageLabel: TextView
    private lateinit var colorBtn: Button

    private var zoom = 1f
    private var curMode = 0
    private var colorIdx = 0
    private val colors = intArrayOf(0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(), 0xFFFF4081.toInt(), 0xFF2196F3.toInt())
    private val anns = ArrayList<Ann>()
    private val modeBtns = ArrayList<Button>()
    private var screenW = 0
    private val pageW: Int get() = (screenW * zoom).toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tb(text: String, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 13f
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(10), 0, dp(10), 0)
        b.setBackgroundColor(0xFFE0E0E0.toInt())
        b.setTextColor(Color.BLACK)
        val lp = LinearLayout.LayoutParams(WC, dp(40))
        lp.setMargins(dp(3), dp(4), dp(3), dp(4))
        b.layoutParams = lp
        b.setOnClickListener { click() }
        return b
    }

    private fun setMode(m: Int) {
        curMode = m
        modeBtns.forEachIndexed { i, b ->
            b.setBackgroundColor(if (i == m) 0xFF90CAF9.toInt() else 0xFFE0E0E0.toInt())
        }
        when (m) {
            1 -> Toast.makeText(this, "লেখার উপর আঙুল টেনে হাইলাইট করুন", Toast.LENGTH_SHORT).show()
            2 -> Toast.makeText(this, "লাইনের নিচ দিয়ে আঙুল টেনে আন্ডারলাইন করুন", Toast.LENGTH_SHORT).show()
            3 -> Toast.makeText(this, "যেখানে নোট রাখতে চান সেখানে ট্যাপ করুন", Toast.LENGTH_SHORT).show()
            4 -> Toast.makeText(this, "মুছতে মার্কের উপর ট্যাপ করুন", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val u = intent.getStringExtra("uri")
        if (u == null) {
            finish()
            return
        }
        uriStr = u
        anns.addAll(store.anns(uriStr))
        zoom = store.zoom().coerceIn(1f, 2f)
        screenW = resources.displayMetrics.widthPixels

        rv = RecyclerView(this)
        lm = LinearLayoutManager(this)
        rv.layoutManager = lm
        rv.setBackgroundColor(0xFFBDBDBD.toInt())
        rv.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom = dp(6)
            }
        })
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateLabel()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) savePos()
            }
        })
        hsv = HorizontalScrollView(this)
        hsv.isFillViewport = true
        hsv.addView(rv, FrameLayout.LayoutParams(pageW, MP))

        // টুলবার
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        pageLabel = TextView(this)
        pageLabel.setPadding(dp(10), 0, dp(10), 0)
        pageLabel.textSize = 14f
        row.addView(pageLabel)
        val names = arrayOf("পড়ুন", "হাইলাইট", "আন্ডারলাইন", "নোট", "মুছুন")
        for (i in names.indices) {
            val b = tb(names[i]) { setMode(i) }
            modeBtns.add(b)
            row.addView(b)
        }
        colorBtn = tb("রং") {
            colorIdx = (colorIdx + 1) % colors.size
            colorBtn.setBackgroundColor(colors[colorIdx])
        }
        colorBtn.setBackgroundColor(colors[0])
        row.addView(colorBtn)
        row.addView(tb("−") { changeZoom(-0.25f) })
        row.addView(tb("+") { changeZoom(0.25f) })
        row.addView(tb("পৃষ্ঠায় যান") { goDialog() })
        row.addView(tb("মার্ক তালিকা") { annListDialog() })
        val tbScroll = HorizontalScrollView(this)
        tbScroll.setBackgroundColor(0xFFF5F5F5.toInt())
        tbScroll.isHorizontalScrollBarEnabled = false
        tbScroll.addView(row)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(tbScroll, LinearLayout.LayoutParams(MP, WC))
        root.addView(hsv, LinearLayout.LayoutParams(MP, 0, 1f))
        setContentView(root)
        setMode(0)
        pageLabel.text = "লোড হচ্ছে…"

        exec.execute {
            try {
                val fd = contentResolver.openFileDescriptor(Uri.parse(uriStr), "r")
                    ?: throw IllegalStateException("open failed")
                pfd = fd
                val r = PdfRenderer(fd)
                renderer = r
                val arr = FloatArray(r.pageCount)
                for (i in arr.indices) {
                    val p = r.openPage(i)
                    arr[i] = p.height.toFloat() / p.width.toFloat()
                    p.close()
                }
                ratios = arr
                runOnUiThread { show() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "PDF খোলা যায়নি (ফাইল সরানো বা পাসওয়ার্ড দেওয়া হতে পারে)", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun show() {
        if (ratios.isEmpty()) {
            Toast.makeText(this, "এই PDF-এ কোনো পৃষ্ঠা নেই", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        rv.adapter = PageAdapter()
        val b = store.get(uriStr)
        restorePos(b?.page ?: 0, b?.frac ?: 0f)
        updateLabel()
    }

    private fun restorePos(page: Int, frac: Float) {
        val pg = page.coerceIn(0, ratios.size - 1)
        val top = -(frac * pageW * ratios[pg]).toInt()
        lm.scrollToPositionWithOffset(pg, top)
    }

    private fun savePos() {
        if (ratios.isEmpty() || rv.adapter == null) return
        val p = lm.findFirstVisibleItemPosition()
        if (p < 0) return
        val v = lm.findViewByPosition(p)
        val frac = if (v != null && v.height > 0) (-v.top).toFloat() / v.height else 0f
        store.savePos(uriStr, p, frac.coerceIn(0f, 1f))
    }

    private fun updateLabel() {
        if (ratios.isEmpty()) return
        val p = lm.findFirstVisibleItemPosition()
        if (p >= 0) pageLabel.text = "${p + 1} / ${ratios.size}"
    }

    private fun changeZoom(delta: Float) {
        if (ratios.isEmpty()) return
        val nz = (zoom + delta).coerceIn(1f, 2f)
        if (nz == zoom) return
        savePos()
        zoom = nz
        store.setZoom(zoom)
        rv.layoutParams = FrameLayout.LayoutParams(pageW, MP)
        rv.adapter?.notifyDataSetChanged()
        val b = store.get(uriStr)
        restorePos(b?.page ?: 0, b?.frac ?: 0f)
    }

    private fun goTo(page: Int) {
        if (ratios.isEmpty()) return
        lm.scrollToPositionWithOffset(page.coerceIn(0, ratios.size - 1), 0)
    }

    private fun goDialog() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "১ থেকে ${ratios.size}"
        AlertDialog.Builder(this).setTitle("কোন পৃষ্ঠায় যাবেন?").setView(et)
            .setPositiveButton("যান") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n != null) goTo(n - 1)
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun annListDialog() {
        val items = anns.sortedWith(compareBy({ it.page }, { it.y1 }))
        if (items.isEmpty()) {
            Toast.makeText(this, "এখনো কোনো মার্ক বা নোট নেই", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map {
            "পৃষ্ঠা ${it.page + 1} • " + when (it.type) {
                1 -> "হাইলাইট"
                2 -> "আন্ডারলাইন"
                else -> "নোট: " + it.text.take(60)
            }
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("সব মার্ক ও নোট")
            .setItems(labels) { _, i -> goTo(items[i].page) }
            .setNegativeButton("বন্ধ", null).show()
    }

    // ---- PageHost
    override fun annsOf(page: Int): List<Ann> = anns.filter { it.page == page }
    override fun mode(): Int = curMode
    override fun color(): Int = colors[colorIdx]

    override fun addAnn(a: Ann) {
        anns.add(a)
        store.saveAnns(uriStr, anns)
        refreshPages()
    }

    override fun removeAnn(a: Ann) {
        anns.removeAll { it.id == a.id }
        store.saveAnns(uriStr, anns)
        refreshPages()
    }

    override fun noteDialog(page: Int, nx: Float, ny: Float, old: Ann?) {
        val et = EditText(this)
        et.setText(old?.text ?: "")
        et.hint = "নোট লিখুন"
        et.minLines = 3
        et.gravity = Gravity.TOP
        val b = AlertDialog.Builder(this).setTitle("পৃষ্ঠা ${page + 1} — নোট").setView(et)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                val t = et.text.toString().trim()
                if (old != null) {
                    val i = anns.indexOfFirst { it.id == old.id }
                    if (i >= 0) anns[i] = old.copy(text = t)
                    store.saveAnns(uriStr, anns)
                    refreshPages()
                } else if (t.isNotEmpty()) {
                    addAnn(Ann(System.nanoTime(), page, 3, nx, ny, nx, ny, color(), t))
                }
            }
            .setNegativeButton("বাতিল", null)
        if (old != null) b.setNeutralButton("মুছুন") { _, _ -> removeAnn(old) }
        b.show()
    }

    private fun refreshPages() {
        for (i in 0 until rv.childCount) rv.getChildAt(i).invalidate()
    }

    override fun onPause() {
        super.onPause()
        savePos()
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.execute {
            try {
                renderer?.close()
                pfd?.close()
            } catch (_: Exception) {
            }
        }
        exec.shutdown()
    }

    class VH(val pv: PageView) : RecyclerView.ViewHolder(pv)

    inner class PageAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = ratios.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = PageView(this@ReaderActivity, this@ReaderActivity)
            v.layoutParams = RecyclerView.LayoutParams(MP, 100)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = holder.pv
            val pos = position
            v.pageIndex = pos
            v.bmp = null
            val w = max(1, pageW)
            val ht = max(1, (w * ratios[pos]).toInt())
            v.layoutParams = RecyclerView.LayoutParams(MP, ht)
            v.invalidate()
            exec.execute {
                if (v.pageIndex != pos) return@execute
                val r = renderer ?: return@execute
                try {
                    val page = r.openPage(pos)
                    val bm = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888)
                    bm.eraseColor(Color.WHITE)
                    page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    runOnUiThread {
                        if (v.pageIndex == pos) {
                            v.bmp = bm
                            v.invalidate()
                        }
                    }
                } catch (t: Throwable) {
                }
            }
        }

        override fun onViewRecycled(holder: VH) {
            holder.pv.bmp = null
        }
    }
}
