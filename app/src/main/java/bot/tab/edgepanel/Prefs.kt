package bot.tab.edgepanel

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "edge_panel"
    const val KEY_SIDE_RIGHT = "side_right"
    const val KEY_POSITION = "position"          // 0..100, vertical position of the handle
    const val KEY_BOOT = "start_on_boot"
    const val KEY_RUNNING = "running"
    const val KEY_MODE = "mode"                  // "", "work", "personal"
    const val KEY_PAGE = "page"
    const val KEY_SAVED_MEDIA = "saved_media"    // media volume before Work mode
    const val KEY_THEME = "theme"                // classic, glass, neon, light
    const val KEY_ACCENT_PREFIX = "accent_"      // + theme id -> colour int, 0 = theme default
    // v3
    const val KEY_SOUND = "sound_on"
    const val KEY_HAPTIC = "haptic_on"
    const val KEY_PAGE_ORDER = "page_order"      // "0,1,2,3,4,5" (PageType ids)
    const val KEY_PAGES_OFF = "pages_off"        // "4,5" = hidden pages
    const val KEY_APP1 = "app1"                  // package name of a user-picked quick app
    const val KEY_APP2 = "app2"

    fun of(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun sideRight(c: Context) = of(c).getBoolean(KEY_SIDE_RIGHT, true)
    fun position(c: Context) = of(c).getInt(KEY_POSITION, 38)
    fun startOnBoot(c: Context) = of(c).getBoolean(KEY_BOOT, false)
    fun running(c: Context) = of(c).getBoolean(KEY_RUNNING, false)
    fun theme(c: Context): String = of(c).getString(KEY_THEME, "classic") ?: "classic"
    fun accent(c: Context, themeId: String) = of(c).getInt(KEY_ACCENT_PREFIX + themeId, 0)
    fun soundOn(c: Context) = of(c).getBoolean(KEY_SOUND, true)
    fun hapticOn(c: Context) = of(c).getBoolean(KEY_HAPTIC, true)
    fun app(c: Context, slot: Int): String? = of(c).getString(if (slot == 0) KEY_APP1 else KEY_APP2, null)?.takeIf { it.isNotEmpty() }

    /** All page types in the user's order (every type exactly once). */
    fun pageOrder(c: Context): IntArray = parseOrder(of(c).getString(KEY_PAGE_ORDER, null))

    fun hiddenPages(c: Context): Set<Int> =
        (of(c).getString(KEY_PAGES_OFF, "") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    /** The pages the panel shows, in order. Never empty. */
    fun pages(c: Context): IntArray {
        val off = hiddenPages(c)
        val list = pageOrder(c).filter { it !in off }
        return if (list.isEmpty()) intArrayOf(PageType.SLIDERS) else list.toIntArray()
    }

    fun parseOrder(s: String?): IntArray {
        val seen = LinkedHashSet<Int>()
        s?.split(',')?.forEach { t -> t.trim().toIntOrNull()?.let { if (it in 0 until PageType.COUNT) seen.add(it) } }
        for (i in 0 until PageType.COUNT) seen.add(i)
        return seen.toIntArray()
    }
}

/** The kinds of page the panel can show. */
object PageType {
    const val SLIDERS = 0
    const val RINGER = 1
    const val MODES = 2
    const val QUICK = 3
    const val MEDIA = 4
    const val APPS = 5
    const val COUNT = 6
    val NAMES = arrayOf(
        "Volume + Brightness",
        "Silent / Vibrate / Ring",
        "DND / Work / Personal",
        "Torch, Wi-Fi, Bluetooth, Screenshot",
        "Music + Battery",
        "Quick apps",
    )
}
