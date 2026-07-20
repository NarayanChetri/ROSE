package dev.narayan.rose

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class StartPage { HOME, ALL_FILES }

/**
 * Small wrapper around SharedPreferences so every user-facing setting
 * (theme, AMOLED mode, view mode, sort order, etc.) survives app restarts.
 * This is what was missing before: the old code kept settings only in
 * memory (mutableStateOf), so they silently reset every time the app
 * process was killed.
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = try {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value.name) }

    var amoledMode: Boolean
        get() = prefs.getBoolean(KEY_AMOLED, false)
        set(value) = prefs.edit { putBoolean(KEY_AMOLED, value) }

    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, value) }

    var isGridView: Boolean
        get() = prefs.getBoolean(KEY_GRID_VIEW, false)
        set(value) = prefs.edit { putBoolean(KEY_GRID_VIEW, value) }

    var isCategoryGridView: Boolean
        get() = prefs.getBoolean(KEY_CATEGORY_GRID_VIEW, true)
        set(value) = prefs.edit { putBoolean(KEY_CATEGORY_GRID_VIEW, value) }

    var foldersFirst: Boolean
        get() = prefs.getBoolean(KEY_FOLDERS_FIRST, true)
        set(value) = prefs.edit { putBoolean(KEY_FOLDERS_FIRST, value) }

    var showHiddenFiles: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_HIDDEN, value) }

    var showDetails: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DETAILS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_DETAILS, value) }

    var confirmBeforeDelete: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_DELETE, true)
        set(value) = prefs.edit { putBoolean(KEY_CONFIRM_DELETE, value) }

    var showFileExtensions: Boolean
        get() = prefs.getBoolean(KEY_SHOW_EXTENSIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_EXTENSIONS, value) }

    // Whether to draw thin divider lines between items in file/folder lists
    // (Recent files, Quick access, and the main file browser list). Lets the
    // user make the divider style consistent everywhere, or turn it off
    // entirely for a cleaner, line-free look.
    var showListDividers: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LIST_DIVIDERS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_LIST_DIVIDERS, value) }

    var gridItemSize: String
        get() = prefs.getString(KEY_GRID_ITEM_SIZE, "MEDIUM") ?: "MEDIUM"
        set(value) = prefs.edit { putString(KEY_GRID_ITEM_SIZE, value) }

    var sortBy: String
        get() = prefs.getString(KEY_SORT_BY, "NAME") ?: "NAME"
        set(value) = prefs.edit { putString(KEY_SORT_BY, value) }

    var sortOrder: String
        get() = prefs.getString(KEY_SORT_ORDER, "ASCENDING") ?: "ASCENDING"
        set(value) = prefs.edit { putString(KEY_SORT_ORDER, value) }

    var startPage: StartPage
        get() = try {
            StartPage.valueOf(prefs.getString(KEY_START_PAGE, StartPage.HOME.name) ?: StartPage.HOME.name)
        } catch (e: IllegalArgumentException) {
            StartPage.HOME
        }
        set(value) = prefs.edit { putString(KEY_START_PAGE, value.name) }

    var useShizuku: Boolean
        get() = prefs.getBoolean(KEY_USE_SHIZUKU, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_SHIZUKU, value) }

    // ----- Quick access (Home screen) -----
    // Default quick-access folders (Downloads/Camera/Documents) that the user
    // has long-press-removed. Stored by a stable id, not path, since the
    // underlying path can vary by device/storage layout.
    var quickAccessRemoved: Set<String>
        get() = prefs.getStringSet(KEY_QUICK_ACCESS_REMOVED, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_QUICK_ACCESS_REMOVED, value) }

    // Custom folders the user has added to quick access, stored as absolute
    // paths joined with a delimiter to preserve the order they were added in
    // (SharedPreferences string sets don't guarantee ordering).
    var quickAccessCustomPaths: List<String>
        get() = prefs.getString(KEY_QUICK_ACCESS_CUSTOM, "")
            ?.split(QUICK_ACCESS_DELIMITER)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) = prefs.edit { putString(KEY_QUICK_ACCESS_CUSTOM, value.joinToString(QUICK_ACCESS_DELIMITER)) }

    // External storages (Cloud/SAF trees) added by the user.
    // Stored as "name|uri" pairs.
    var externalStorages: List<String>
        get() = prefs.getString(KEY_EXTERNAL_STORAGES, "")
            ?.split(QUICK_ACCESS_DELIMITER)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) = prefs.edit { putString(KEY_EXTERNAL_STORAGES, value.joinToString(QUICK_ACCESS_DELIMITER)) }

    var offlineFiles: Set<String>
        get() = prefs.getStringSet(KEY_OFFLINE_FILES, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_OFFLINE_FILES, value) }

    var showQuickAccess: Boolean
        get() = prefs.getBoolean(KEY_SHOW_QUICK_ACCESS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_QUICK_ACCESS, value) }

    var showExternalStorage: Boolean
        get() = prefs.getBoolean(KEY_SHOW_EXTERNAL_STORAGE, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_EXTERNAL_STORAGE, value) }

    var quickAccessExpanded: Boolean
        get() = prefs.getBoolean(KEY_QUICK_ACCESS_EXPANDED, true)
        set(value) = prefs.edit { putBoolean(KEY_QUICK_ACCESS_EXPANDED, value) }

    var externalStorageExpanded: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL_STORAGE_EXPANDED, true)
        set(value) = prefs.edit { putBoolean(KEY_EXTERNAL_STORAGE_EXPANDED, value) }

    var useRecycleBin: Boolean
        get() = prefs.getBoolean(KEY_USE_RECYCLE_BIN, true)
        set(value) = prefs.edit { putBoolean(KEY_USE_RECYCLE_BIN, value) }

    // Whether the contextual notification-permission primer (shown the first
    // time a background file operation starts) has already been presented once.
    // We only ever ask this way once - if the user dismisses it, we respect
    // that instead of nagging on every subsequent transfer/download.
    var notificationPrimerShown: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PRIMER_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_PRIMER_SHOWN, value) }

    companion object {
        private const val PREFS_NAME = "rose_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AMOLED = "amoled_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_GRID_VIEW = "grid_view"
        private const val KEY_CATEGORY_GRID_VIEW = "category_grid_view"
        private const val KEY_FOLDERS_FIRST = "folders_first"
        private const val KEY_SHOW_HIDDEN = "show_hidden"
        private const val KEY_SHOW_DETAILS = "show_details"
        private const val KEY_CONFIRM_DELETE = "confirm_delete"
        private const val KEY_SHOW_EXTENSIONS = "show_extensions"
        private const val KEY_SHOW_LIST_DIVIDERS = "show_list_dividers"
        private const val KEY_GRID_ITEM_SIZE = "grid_item_size"
        private const val KEY_SORT_BY = "sort_by"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_START_PAGE = "start_page"
        private const val KEY_USE_SHIZUKU = "use_shizuku"
        private const val KEY_QUICK_ACCESS_REMOVED = "quick_access_removed"
        private const val KEY_QUICK_ACCESS_CUSTOM = "quick_access_custom"
        private const val KEY_EXTERNAL_STORAGES = "external_storages"
        private const val KEY_OFFLINE_FILES = "offline_files"
        private const val KEY_USE_RECYCLE_BIN = "use_recycle_bin"
        private const val KEY_NOTIFICATION_PRIMER_SHOWN = "notification_primer_shown"
        private const val KEY_SHOW_QUICK_ACCESS = "show_quick_access"
        private const val KEY_SHOW_EXTERNAL_STORAGE = "show_external_storage"
        private const val KEY_QUICK_ACCESS_EXPANDED = "quick_access_expanded"
        private const val KEY_EXTERNAL_STORAGE_EXPANDED = "external_storage_expanded"
        private const val QUICK_ACCESS_DELIMITER = "|::|"
    }
}