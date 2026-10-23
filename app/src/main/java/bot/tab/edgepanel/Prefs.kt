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

    fun of(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun sideRight(c: Context) = of(c).getBoolean(KEY_SIDE_RIGHT, true)
    fun position(c: Context) = of(c).getInt(KEY_POSITION, 38)
    fun startOnBoot(c: Context) = of(c).getBoolean(KEY_BOOT, false)
    fun running(c: Context) = of(c).getBoolean(KEY_RUNNING, false)
}
