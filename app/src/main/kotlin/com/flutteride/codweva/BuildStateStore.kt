package com.flutteride.codweva

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Locale

/**
 * BuildStateStore - مخزن حالة البناء (Singleton).
 *
 * v1.0 - المسؤوليات:
 *   - تخزين حالة البناء الحالية (idle/building/success/error/cancelled).
 *   - تخزين نص التقدم + النسبة.
 *   - تخزين مسار APK عند النجاح.
 *   - تخزين رسالة الخطأ عند الفشل.
 *   - إشعار MainActivity بأي تغيير (عبر Listeners).
 *
 * ملاحظات:
 *   - يستخدم SharedPreferences (بسيط، يعمل عبر العمليات).
 *   - يحتوي على listeners لإعلام الواجهة بأي تغيير.
 *   - Thread-safe (synchronized على الكتابة).
 *   - Singleton للحالة الواحدة.
 *
 * الاستخدام:
 *   BuildStateStore.init(context)
 *   BuildStateStore.setBuilding("رفع الملفات...", 45)
 *   BuildStateStore.setSuccess(apkFile)
 *   BuildStateStore.setError("فشل رفع الملفات")
 *   BuildStateStore.reset()
 */
object BuildStateStore {

    // ============================================================
    // ثوابت
    // ============================================================
    private const val PREFS_NAME = "klencod_build_state"

    // مفاتيح التخزين
    private const val KEY_STATE = "state"
    private const val KEY_PROGRESS_TEXT = "progress_text"
    private const val KEY_PROGRESS_PERCENT = "progress_percent"
    private const val KEY_PROGRESS_INDETERMINATE = "progress_indeterminate"
    private const val KEY_PROJECT_NAME = "project_name"
    private const val KEY_PROJECT_PATH = "project_path"
    private const val KEY_APK_PATH = "apk_path"
    private const val KEY_ERROR_MESSAGE = "error_message"
    private const val KEY_STARTED_AT_MS = "started_at_ms"
    private const val KEY_FINISHED_AT_MS = "finished_at_ms"
    private const val KEY_GITHUB_OWNER = "github_owner"
    private const val KEY_GITHUB_REPO = "github_repo"
    private const val KEY_GITHUB_RUN_ID = "github_run_id"

    // ============================================================
    // الحالات الممكنة
    // ============================================================
    enum class State(val id: String) {
        IDLE("idle"),
        BUILDING("building"),
        SUCCESS("success"),
        ERROR("error"),
        CANCELLED("cancelled");

        companion object {
            @JvmStatic
            fun fromId(id: String?): State {
                if (id == null) return IDLE
                return values().firstOrNull { it.id == id } ?: IDLE
            }
        }
    }

    // ============================================================
    // الحالة الداخلية
    // ============================================================
    private var prefs: SharedPreferences? = null

    /** Listeners (يُستخدَم من MainActivity للإشعار بأي تغيير). */
    private val listeners = mutableListOf<Listener>()

    private val lock = Any()

    // ============================================================
    // Listener
    // ============================================================
    interface Listener {
        /**
         * يُستدعى عند أي تغيير في الحالة.
         */
        fun onBuildStateChanged(
            state: State,
            progressText: String,
            progressPercent: Int,
            isIndeterminate: Boolean
        )
    }

    // ============================================================
    // التهيئة
    // ============================================================

    /**
     * يهيئ المخزن. يجب استدعاؤه مرة واحدة (في MainActivity أو Application).
     */
    @JvmStatic
    fun init(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext
                    .getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
                    )
            }
        }
    }

    private fun getPrefs(): SharedPreferences? {
        return prefs
    }

    // ============================================================
    // القراءة
    // ============================================================

    /**
     * الحالة الحالية.
     */
    @JvmStatic
    fun getState(): State {
        val p = getPrefs() ?: return State.IDLE
        return State.fromId(p.getString(KEY_STATE, State.IDLE.id))
    }

    /**
     * نص التقدم الحالي.
     */
    @JvmStatic
    fun getProgressText(): String {
        return getPrefs()?.getString(KEY_PROGRESS_TEXT, "") ?: ""
    }

    /**
     * نسبة التقدم الحالية (0-100).
     */
    @JvmStatic
    fun getProgressPercent(): Int {
        return getPrefs()?.getInt(KEY_PROGRESS_PERCENT, 0) ?: 0
    }

    /**
     * هل شريط التقدم غير محدد (spinner)؟
     */
    @JvmStatic
    fun isProgressIndeterminate(): Boolean {
        return getPrefs()?.getBoolean(KEY_PROGRESS_INDETERMINATE, false) ?: false
    }

    /**
     * اسم المشروع الحالي.
     */
    @JvmStatic
    fun getProjectName(): String {
        return getPrefs()?.getString(KEY_PROJECT_NAME, "") ?: ""
    }

    /**
     * مسار المشروع الحالي.
     */
    @JvmStatic
    fun getProjectPath(): String {
        return getPrefs()?.getString(KEY_PROJECT_PATH, "") ?: ""
    }

    /**
     * مسار APK الناتج (فارغ إن لم يكتمل).
     */
    @JvmStatic
    fun getApkPath(): String {
        return getPrefs()?.getString(KEY_APK_PATH, "") ?: ""
    }

    /**
     * مسار APK كـ File (null إن لم يوجد).
     */
    @JvmStatic
    fun getApkFile(): File? {
        val path = getApkPath()
        if (path.isEmpty()) return null
        val f = File(path)
        return if (f.exists() && f.isFile) f else null
    }

    /**
     * رسالة الخطأ (فارغة إن لم يكن هناك خطأ).
     */
    @JvmStatic
    fun getErrorMessage(): String {
        return getPrefs()?.getString(KEY_ERROR_MESSAGE, "") ?: ""
    }

    /**
     * وقت بدء البناء (milliseconds).
     */
    @JvmStatic
    fun getStartedAtMs(): Long {
        return getPrefs()?.getLong(KEY_STARTED_AT_MS, 0L) ?: 0L
    }

    /**
     * وقت انتهاء البناء (milliseconds).
     */
    @JvmStatic
    fun getFinishedAtMs(): Long {
        return getPrefs()?.getLong(KEY_FINISHED_AT_MS, 0L) ?: 0L
    }

    @JvmStatic
    fun saveGitHubRun(owner: String, repo: String, runId: Long) {
        synchronized(lock) {
            getPrefs()?.edit()
                ?.putString(KEY_GITHUB_OWNER, owner)
                ?.putString(KEY_GITHUB_REPO, repo)
                ?.putLong(KEY_GITHUB_RUN_ID, runId)
                ?.commit()
        }
    }

    @JvmStatic
    fun getGitHubOwner(): String = getPrefs()?.getString(KEY_GITHUB_OWNER, "") ?: ""

    @JvmStatic
    fun getGitHubRepo(): String = getPrefs()?.getString(KEY_GITHUB_REPO, "") ?: ""

    @JvmStatic
    fun getGitHubRunId(): Long = getPrefs()?.getLong(KEY_GITHUB_RUN_ID, 0L) ?: 0L

    @JvmStatic
    fun clearGitHubRun() {
        synchronized(lock) {
            getPrefs()?.edit()
                ?.remove(KEY_GITHUB_OWNER)
                ?.remove(KEY_GITHUB_REPO)
                ?.remove(KEY_GITHUB_RUN_ID)
                ?.commit()
        }
    }

    /**
     * هل هناك بناء جارٍ الآن؟
     */
    @JvmStatic
    fun isBuilding(): Boolean {
        return getState() == State.BUILDING
    }

    /**
     * مدة البناء (بالثواني) - تعمل للبناء الجاري أو المنتهي.
     */
    @JvmStatic
    fun getBuildDurationSeconds(): Long {
        val started = getStartedAtMs()
        if (started <= 0L) return 0L
        val finished = getFinishedAtMs()
        val end = if (finished > 0L) finished else System.currentTimeMillis()
        return (end - started) / 1000L
    }

    /**
     * نص ملخص للحالة (للعرض في الواجهة).
     */
    @JvmStatic
    fun getStateLabel(): String {
        return when (getState()) {
            State.IDLE -> "لا يوجد بناء"
            State.BUILDING -> "جارٍ البناء"
            State.SUCCESS -> "اكتمل البناء"
            State.ERROR -> "فشل البناء"
            State.CANCELLED -> "أُلغي البناء"
        }
    }

    // ============================================================
    // الكتابة
    // ============================================================

    /**
     * يبدأ بناءً جديداً.
     *
     * @param projectName اسم المشروع
     * @param projectPath مسار المشروع
     */
    @JvmStatic
    fun startBuilding(projectName: String, projectPath: String) {
        synchronized(lock) {
            val p = getPrefs() ?: return
            p.edit()
                .putString(KEY_STATE, State.BUILDING.id)
                .putString(KEY_PROJECT_NAME, projectName)
                .putString(KEY_PROJECT_PATH, projectPath)
                .putString(KEY_PROGRESS_TEXT, "بدء البناء...")
                .putInt(KEY_PROGRESS_PERCENT, 0)
                .putBoolean(KEY_PROGRESS_INDETERMINATE, true)
                .putString(KEY_APK_PATH, "")
                .putString(KEY_ERROR_MESSAGE, "")
                .putLong(KEY_STARTED_AT_MS, System.currentTimeMillis())
                .putLong(KEY_FINISHED_AT_MS, 0L)
                .commit()
        }
        clearGitHubRun()
        notifyListeners()
    }

    /**
     * يحدّث التقدم.
     *
     * @param message نص التقدم
     * @param percent نسبة (0-100)
     * @param isIndeterminate هل الشريط غير محدد؟
     */
    @JvmStatic
    fun setBuilding(
        message: String,
        percent: Int,
        isIndeterminate: Boolean = false
    ) {
        synchronized(lock) {
            val p = getPrefs() ?: return
            p.edit()
                .putString(KEY_STATE, State.BUILDING.id)
                .putString(KEY_PROGRESS_TEXT, message)
                .putInt(KEY_PROGRESS_PERCENT, percent.coerceIn(0, 100))
                .putBoolean(KEY_PROGRESS_INDETERMINATE, isIndeterminate)
                .commit()
        }
        notifyListeners()
    }

    /**
     * تحديث مختصر: نسبة فقط (يستخدم نص قديم).
     */
    @JvmStatic
    fun setProgress(percent: Int) {
        val currentText = getProgressText()
        setBuilding(currentText, percent, false)
    }

    /**
     * ينهي البناء بنجاح.
     */
    @JvmStatic
    fun setSuccess(apkFile: File) {
        synchronized(lock) {
            val p = getPrefs() ?: return
            p.edit()
                .putString(KEY_STATE, State.SUCCESS.id)
                .putString(KEY_PROGRESS_TEXT, "اكتمل البناء")
                .putInt(KEY_PROGRESS_PERCENT, 100)
                .putBoolean(KEY_PROGRESS_INDETERMINATE, false)
                .putString(KEY_APK_PATH, apkFile.absolutePath)
                .putString(KEY_ERROR_MESSAGE, "")
                .putLong(KEY_FINISHED_AT_MS, System.currentTimeMillis())
                .commit()
        }
        clearGitHubRun()
        notifyListeners()
    }

    /**
     * ينهي البناء بفشل.
     */
    @JvmStatic
    fun setError(errorMessage: String) {
        synchronized(lock) {
            val p = getPrefs() ?: return
            p.edit()
                .putString(KEY_STATE, State.ERROR.id)
                .putString(KEY_PROGRESS_TEXT, "فشل البناء")
                .putBoolean(KEY_PROGRESS_INDETERMINATE, false)
                .putString(KEY_ERROR_MESSAGE, errorMessage)
                .putLong(KEY_FINISHED_AT_MS, System.currentTimeMillis())
                .commit()
        }
        clearGitHubRun()
        notifyListeners()
    }

    /**
     * يلغي البناء.
     */
    @JvmStatic
    fun setCancelled() {
        synchronized(lock) {
            val p = getPrefs() ?: return
            p.edit()
                .putString(KEY_STATE, State.CANCELLED.id)
                .putString(KEY_PROGRESS_TEXT, "تم إلغاء البناء")
                .putBoolean(KEY_PROGRESS_INDETERMINATE, false)
                .putLong(KEY_FINISHED_AT_MS, System.currentTimeMillis())
                .commit()
        }
        clearGitHubRun()
        notifyListeners()
    }

    /**
     * يعيد الحالة إلى IDLE (مسح كل شيء).
     */
    @JvmStatic
    fun reset() {
        synchronized(lock) {
            val p = getPrefs() ?: return
            p.edit()
                .putString(KEY_STATE, State.IDLE.id)
                .putString(KEY_PROGRESS_TEXT, "")
                .putInt(KEY_PROGRESS_PERCENT, 0)
                .putBoolean(KEY_PROGRESS_INDETERMINATE, false)
                .putString(KEY_PROJECT_NAME, "")
                .putString(KEY_PROJECT_PATH, "")
                .putString(KEY_APK_PATH, "")
                .putString(KEY_ERROR_MESSAGE, "")
                .putLong(KEY_STARTED_AT_MS, 0L)
                .putLong(KEY_FINISHED_AT_MS, 0L)
                .commit()
        }
        clearGitHubRun()
        notifyListeners()
    }

    // ============================================================
    // Listeners
    // ============================================================

    /**
     * يضيف listener للإشعار بأي تغيير.
     */
    @JvmStatic
    fun addListener(listener: Listener?) {
        if (listener == null) return
        synchronized(lock) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    /**
     * يزيل listener.
     */
    @JvmStatic
    fun removeListener(listener: Listener?) {
        if (listener == null) return
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    /**
     * يزيل كل listeners (يُستدعى عند إغلاق MainActivity).
     */
    @JvmStatic
    fun clearListeners() {
        synchronized(lock) {
            listeners.clear()
        }
    }

    /**
     * يُشعر كل listeners بالتغيير.
     */
    private fun notifyListeners() {
        val snapshot = synchronized(lock) { listeners.toList() }
        val state = getState()
        val text = getProgressText()
        val percent = getProgressPercent()
        val indeterminate = isProgressIndeterminate()

        for (listener in snapshot) {
            try {
                listener.onBuildStateChanged(state, text, percent, indeterminate)
            } catch (_: Exception) {
            }
        }
    }

    // ============================================================
    // أدوات مساعدة
    // ============================================================

    /**
     * ملخص الحالة الكامل (للتصحيح).
     */
    @JvmStatic
    fun debugSummary(): String {
        return buildString {
            append("BuildStateStore {")
            append("state=").append(getState().id)
            append(", text=").append(getProgressText())
            append(", percent=").append(getProgressPercent())
            append(", indeterminate=").append(isProgressIndeterminate())
            append(", project=").append(getProjectName())
            append(", apk=").append(getApkPath())
            append(", error=").append(
                if (getErrorMessage().isEmpty()) "(none)"
                else getErrorMessage().take(50)
            )
            append("}")
        }
    }

    /**
     * نسخة مختصرة للتقدم (مثل "45%").
     */
    @JvmStatic
    fun progressLabel(): String {
        if (isProgressIndeterminate()) return "..."
        return String.format(Locale.US, "%d%%", getProgressPercent())
    }
}