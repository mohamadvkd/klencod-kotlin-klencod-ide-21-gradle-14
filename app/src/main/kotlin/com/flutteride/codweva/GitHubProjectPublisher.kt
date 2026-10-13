package com.flutteride.codweva

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale

/**
 * GitHubProjectPublisher — ينسّق نشر مشروع وتشغيل GitHub Actions.
 *
 * v4.0 — Git Data API (الحل الجذري لـ 409):
 *   - uploadFiles تستخدم createCommitWithFiles
 *   - blob واحد لكل ملف (متوازي)
 *   - tree واحد + commit واحد + ref واحد
 *   - صفر 409 Conflict
 *
 * v3.1 — الحل الجذري لـ "توقف البناء":
 *   - enableActionsAndContinue: تفعيل Actions
 *   - waitForWorkflowRegistered: انتظار فهرسة الـ workflow
 *   - triggerBuildWithRetry: 10 محاولات
 *   - INITIAL_WAIT_AFTER_UPLOAD_MS: انتظار 3s
 *
 * يدعم:
 *   - Flutter (main.yml + app-release)
 *   - Java    (build-android.yml + klencod-java-debug-apk)
 *   - Kotlin  (build-android.yml + klencod-kotlin-debug-apk)
 *   - React Native (build-android.yml + klencod-reactnative-release-apk)
 */
object GitHubProjectPublisher {

    interface Listener {
        fun onProgress(message: String, progress: Int)
        fun onSuccess(repository: GitHubApi.Repository, run: GitHubApi.Run)
        fun onError(message: String)
    }

    // ============================================================
    // ثوابت الأداء
    // ============================================================

    /** مهلة إجمالية للـ commit (بالدقائق). */
    private const val TOTAL_COMMIT_TIMEOUT_MINUTES = 10L

    /** عدد الـ blobs المرفوعة بالتوازي (يتم التحكم بها من GitHubApi). */
    private const val MAX_PARALLEL_BLOBS = 8

    // ============================================================
    // ثوابت الحل الجذري (مشكلة "توقف البناء")
    // ============================================================

    /** انتظار أولي بعد الـ commit قبل triggerBuild (ms). */
    private const val INITIAL_WAIT_AFTER_COMMIT_MS = 3000L

    /** فاصل زمني بين إعادات triggerBuild (ms). */
    private const val TRIGGER_RETRY_INTERVAL_MS = 3000L

    /** أقصى عدد إعادات triggerBuild. */
    private const val MAX_TRIGGER_ATTEMPTS = 10

    /** أقصى عدد محاولات انتظار فهرسة workflow. */
    private const val MAX_WORKFLOW_CHECK_ATTEMPTS = 15

    /** فاصل بين محاولات التحقق من workflow (ms). */
    private const val WORKFLOW_CHECK_INTERVAL_MS = 2000L

    // ============================================================
    // النسخة القديمة — Flutter افتراضياً
    // ============================================================
    @Deprecated("Use version with projectType")
    @JvmStatic
    fun publish(
        token: String,
        project: File,
        customRepoName: String?,
        isPublicRepo: Boolean,
        listener: Listener
    ) {
        publish(
            token, project, ProjectType.FLUTTER,
            customRepoName, isPublicRepo, "3.47.2", listener
        )
    }

    @Deprecated("Use version with projectType")
    @JvmStatic
    fun publish(
        token: String?,
        project: File?,
        customRepoName: String?,
        isPublicRepo: Boolean,
        flutterVersion: String?,
        listener: Listener
    ) {
        publish(
            token, project, ProjectType.FLUTTER,
            customRepoName, isPublicRepo, flutterVersion, listener
        )
    }

    // ============================================================
    // النسخة الجديدة — تقبل projectType
    // ============================================================
    @JvmStatic
    fun publish(
        token: String?,
        project: File?,
        projectType: ProjectType,
        customRepoName: String?,
        isPublicRepo: Boolean,
        flutterVersion: String?,
        listener: Listener
    ) {
        // ============================================================
        // 1) التحقق من المدخلات
        // ============================================================
        if (token.isNullOrBlank()) {
            listener.onError("اربط حساب GitHub أولًا")
            return
        }
        if (project == null || !project.isDirectory) {
            listener.onError("مجلد المشروع غير صالح")
            return
        }

        // ============================================================
        // 2) الإعدادات
        // ============================================================
        val version = if (flutterVersion.isNullOrBlank()) "3.47.2" else flutterVersion.trim()
        val publishStartedAtMs = System.currentTimeMillis()
        val typeLabel = projectType.displayName

        listener.onProgress("التحقق من حساب GitHub لمشروع $typeLabel...", 5)

        // ============================================================
        // 3) التحقق من الحساب
        // ============================================================
        GitHubApi.getUser(token, object : GitHubApi.Callback<String> {
            override fun onSuccess(owner: String) {
                val rawName = if (!customRepoName.isNullOrBlank()) customRepoName else project.name
                val repoName = repositoryName(rawName, projectType)

                // ============================================================
                // 4) البحث عن المستودع أو إنشاؤه
                // ============================================================
                GitHubApi.getRepository(
                    token, owner, repoName,
                    object : GitHubApi.Callback<GitHubApi.Repository> {
                        override fun onSuccess(repository: GitHubApi.Repository) {
                            listener.onProgress(
                                "تم العثور على المستودع، جارٍ تجهيز الملفات...",
                                18
                            )
                            uploadFiles(
                                token, project, repository, projectType,
                                publishStartedAtMs, version, listener
                            )
                        }

                        override fun onError(message: String) {
                            if (message.contains("GitHub HTTP 404")) {
                                createRepository(
                                    token, repoName, project, isPublicRepo, projectType,
                                    publishStartedAtMs, version, listener
                                )
                            } else {
                                listener.onError("فشل الوصول للمستودع:\n$message")
                            }
                        }
                    }
                )
            }

            override fun onError(message: String) {
                listener.onError("فشل التعرف على الحساب:\n$message")
            }
        })
    }

    // ============================================================
    // إنشاء المستودع + تفعيل Actions
    // ============================================================
    private fun createRepository(
        token: String,
        name: String,
        project: File,
        isPublicRepo: Boolean,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        flutterVersion: String,
        listener: Listener
    ) {
        val typeLabel = if (isPublicRepo) "عام" else "خاص"
        listener.onProgress("لم يوجد المستودع، جارٍ إنشاؤه ($typeLabel)...", 10)

        createRepositoryWithRetry(
            token, name, 0, project, isPublicRepo, projectType,
            publishStartedAtMs, flutterVersion, listener
        )
    }

    private fun createRepositoryWithRetry(
        token: String,
        baseName: String,
        suffix: Int,
        project: File,
        isPublicRepo: Boolean,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        flutterVersion: String,
        listener: Listener
    ) {
        if (suffix > 50) {
            listener.onError("تعذر إيجاد اسم مستودع متاح (جرب 50 اسماً)")
            return
        }

        val candidate = if (suffix == 0) baseName else "${baseName}_$suffix"

        if (suffix > 0) {
            listener.onProgress("جارٍ تجربة الاسم: \"$candidate\"...", 12)
        }

        GitHubApi.createRepository(
            token, candidate, !isPublicRepo,
            object : GitHubApi.Callback<GitHubApi.Repository> {
                override fun onSuccess(repository: GitHubApi.Repository) {
                    val typeLabel = if (isPublicRepo) "عام" else "خاص"
                    if (suffix > 0) {
                        listener.onProgress(
                            "الاسم \"$baseName\" كان مستخدماً، تم استخدام \"$candidate\" ($typeLabel)",
                            20
                        )
                    } else {
                        listener.onProgress(
                            "تم إنشاء المستودع \"$candidate\" ($typeLabel) بنجاح",
                            20
                        )
                    }

                    // ⭐ فعّل GitHub Actions تلقائياً
                    enableActionsAndContinue(
                        token, repository, project, projectType,
                        publishStartedAtMs, flutterVersion, listener
                    )
                }

                override fun onError(message: String) {
                    if (isNameConflict(message)) {
                        createRepositoryWithRetry(
                            token, baseName, suffix + 1, project, isPublicRepo, projectType,
                            publishStartedAtMs, flutterVersion, listener
                        )
                    } else {
                        listener.onError("فشل إنشاء المستودع:\n$message")
                    }
                }
            }
        )
    }

    /**
     * يفعّل GitHub Actions ثم يكمل الرفع.
     */
    private fun enableActionsAndContinue(
        token: String,
        repository: GitHubApi.Repository,
        project: File,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        flutterVersion: String,
        listener: Listener
    ) {
        listener.onProgress("تفعيل GitHub Actions في المستودع...", 22)

        GitHubApi.enableActions(
            token, repository.owner, repository.name,
            object : GitHubApi.Callback<Boolean> {
                override fun onSuccess(value: Boolean) {
                    if (value) {
                        listener.onProgress("تم تفعيل Actions بنجاح", 24)
                    }
                    uploadFiles(
                        token, project, repository, projectType,
                        publishStartedAtMs, flutterVersion, listener
                    )
                }

                override fun onError(message: String) {
                    uploadFiles(
                        token, project, repository, projectType,
                        publishStartedAtMs, flutterVersion, listener
                    )
                }
            }
        )
    }

    private fun isNameConflict(message: String?): Boolean {
        if (message == null) return false
        val lower = message.lowercase(Locale.US)
        return lower.contains("http 422") ||
                lower.contains("already exists") ||
                lower.contains("name already") ||
                lower.contains("repository creation failed") ||
                lower.contains("validation failed") ||
                lower.contains("name is not available")
    }

    // ============================================================
    // ⭐ رفع الملفات — Git Data API (صفر 409)
    // ============================================================
    /**
     * يرفع كل الملفات عبر Git Data API:
     *   - blob لكل ملف (متوازي)
     *   - tree واحد
     *   - commit واحد
     *   - ref واحد
     *
     * المزايا:
     *   - صفر 409 Conflict
     *   - commit واحد (تاريخ نظيف)
     *   - blobs متوازية (8 في نفس الوقت)
     */
    private fun uploadFiles(
        token: String,
        project: File,
        repository: GitHubApi.Repository,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        flutterVersion: String,
        listener: Listener
    ) {
        // ============================================================
        // 1) جمع الملفات
        // ============================================================
        val files = mutableListOf<File>()
        collect(project, project, files)

        if (files.isEmpty()) {
            listener.onError("لا توجد ملفات قابلة للرفع داخل المشروع")
            return
        }

        files.sortWith(compareBy { relative(project, it) })

        val total = files.size
        listener.onProgress("تجهيز $total ملف لرفع واحد...", 25)

        // ============================================================
        // 2) تحويل الملفات إلى FileEntry
        // ============================================================
        val fileEntries = mutableListOf<GitHubApi.FileEntry>()

        for (file in files) {
            try {
                val path = relative(project, file)
                val bytes = readBytes(file)

                // كشف إذا كان ثنائياً
                val isBinary = isBinaryFile(file.name, bytes)

                if (isBinary) {
                    fileEntries.add(GitHubApi.FileEntry.fromBytes(path, bytes))
                } else {
                    fileEntries.add(
                        GitHubApi.FileEntry.fromText(
                            path,
                            String(bytes, Charsets.UTF_8)
                        )
                    )
                }
            } catch (e: Exception) {
                listener.onError("تعذر قراءة \"${file.name}\": ${e.message}")
                return
            }
        }

        // ============================================================
        // 3) أنشئ commit واحد (blob tree commit ref)
        // ============================================================
        val startTime = System.currentTimeMillis()

        GitHubApi.createCommitWithFiles(
            token,
            repository.owner,
            repository.name,
            repository.defaultBranch,
            fileEntries,
            "Initial commit: $total files",
            object : GitHubApi.CommitProgressCallback {
                override fun onBlobProgress(current: Int, total: Int) {
                    val progress = 25 + (current * 55 / total)
                    listener.onProgress("__UPLOAD__$current/$total", progress)
                }

                override fun onSuccess(commitSha: String) {
                    val elapsed = System.currentTimeMillis() - startTime

                    listener.onProgress(
                        "✅ تم إنشاء commit واحد ($total ملف) في ${elapsed / 1000} ثانية",
                        82
                    )

                    // انتقل للمرحلة التالية
                    afterUploadSuccess(
                        token, repository, projectType,
                        publishStartedAtMs, flutterVersion, listener
                    )
                }

                override fun onError(message: String) {
                    listener.onError("فشل إنشاء الـ commit:\n$message")
                }
            }
        )
    }

    /**
     * بعد نجاح الرفع: انتظار + فهرسة + trigger.
     */
    private fun afterUploadSuccess(
        token: String,
        repository: GitHubApi.Repository,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        flutterVersion: String,
        listener: Listener
    ) {
        Thread {
            try {
                // ============================================================
                // 1) انتظار GitHub ليفهرس الـ workflow
                // ============================================================
                listener.onProgress(
                    "انتظار GitHub لفهرسة الـ workflow (3 ثواني)...",
                    83
                )

                Thread.sleep(INITIAL_WAIT_AFTER_COMMIT_MS)

                val workflowFile = GitHubConfig.workflowFileFor(projectType)

                // ============================================================
                // 2) انتظر حتى يُسجّل الـ workflow
                // ============================================================
                waitForWorkflowRegistered(
                    token, repository, workflowFile,
                    0, listener
                ) { workflowReady ->
                    if (!workflowReady) {
                        listener.onError(
                            "الـ workflow \"$workflowFile\" لم يُسجّل في GitHub Actions.\n\n" +
                            "الأسباب المحتملة:\n" +
                            "• الملف .github/workflows/$workflowFile غير موجود في المشروع\n" +
                            "• GitHub Actions معطّل في المستودع\n" +
                            "• التوكن يفتقر لصلاحية workflow\n\n" +
                            "تحقق من أن المشروع يحتوي على .github/workflows/$workflowFile"
                        )
                        return@waitForWorkflowRegistered
                    }

                    // ============================================================
                    // 3) الآن شغّل البناء مع retry
                    // ============================================================
                    triggerBuildWithRetry(
                        token, repository, projectType,
                        workflowFile, flutterVersion,
                        publishStartedAtMs, listener, 0
                    )
                }

            } catch (e: Exception) {
                listener.onError("خطأ أثناء انتظار الفهرسة: ${e.message}")
            }
        }.start()
    }

    // ============================================================
    // الحل الجذري: انتظار فهرسة الـ workflow
    // ============================================================
    private fun waitForWorkflowRegistered(
        token: String,
        repository: GitHubApi.Repository,
        workflowFile: String,
        attempt: Int,
        listener: Listener,
        onReady: (Boolean) -> Unit
    ) {
        if (attempt >= MAX_WORKFLOW_CHECK_ATTEMPTS) {
            onReady(false)
            return
        }

        GitHubApi.checkWorkflowExists(
            token, repository.owner, repository.name, workflowFile,
            object : GitHubApi.Callback<Boolean> {
                override fun onSuccess(value: Boolean) {
                    if (value) {
                        listener.onProgress("✅ الـ workflow جاهز", 84)
                        onReady(true)
                    } else {
                        listener.onProgress(
                            "انتظار فهرسة الـ workflow (${attempt + 1}/$MAX_WORKFLOW_CHECK_ATTEMPTS)...",
                            83
                        )
                        Thread {
                            try {
                                Thread.sleep(WORKFLOW_CHECK_INTERVAL_MS)
                            } catch (_: InterruptedException) {
                            }
                            waitForWorkflowRegistered(
                                token, repository, workflowFile,
                                attempt + 1, listener, onReady
                            )
                        }.start()
                    }
                }

                override fun onError(message: String) {
                    Thread {
                        try {
                            Thread.sleep(WORKFLOW_CHECK_INTERVAL_MS)
                        } catch (_: InterruptedException) {
                        }
                        waitForWorkflowRegistered(
                            token, repository, workflowFile,
                            attempt + 1, listener, onReady
                        )
                    }.start()
                }
            }
        )
    }

    // ============================================================
    // triggerBuild مع إعادة محاولة
    // ============================================================
    private fun triggerBuildWithRetry(
        token: String,
        repository: GitHubApi.Repository,
        projectType: ProjectType,
        workflowFile: String,
        flutterVersion: String,
        publishStartedAtMs: Long,
        listener: Listener,
        attempt: Int
    ) {
        if (attempt >= MAX_TRIGGER_ATTEMPTS) {
            listener.onError(
                "فشل تشغيل GitHub Actions بعد $MAX_TRIGGER_ATTEMPTS محاولات.\n\n" +
                "المستودع أُنشئ ورفع بنجاح، لكن البناء لم يبدأ.\n\n" +
                "الحل:\n" +
                "1. افتح المستودع: github.com/${repository.owner}/${repository.name}\n" +
                "2. اذهب إلى تبويب Actions\n" +
                "3. تأكد من تفعيل Workflows\n" +
                "4. شغّل البناء يدوياً من هناك\n\n" +
                "أو اضغط 'بناء APK' مرة أخرى من التطبيق."
            )
            return
        }

        if (attempt > 0) {
            listener.onProgress(
                "إعادة محاولة trigger (${attempt + 1}/$MAX_TRIGGER_ATTEMPTS)...",
                84
            )
        }

        GitHubApi.triggerBuild(
            token,
            repository.owner,
            repository.name,
            repository.defaultBranch,
            workflowFile,
            flutterVersion,
            object : GitHubApi.Callback<Boolean> {
                override fun onSuccess(value: Boolean) {
                    listener.onProgress(
                        "✅ تم تشغيل GitHub Actions، جارٍ انتظار البناء...",
                        85
                    )
                    findStartedRun(
                        token, repository, projectType,
                        publishStartedAtMs, listener, 0
                    )
                }

                override fun onError(message: String) {
                    val is404 = message.contains("HTTP 404") ||
                            message.contains("Not Found")
                    val is422 = message.contains("HTTP 422")

                    if (is404 || is422) {
                        listener.onProgress(
                            "GitHub لم يستجب بعد، إعادة المحاولة...",
                            84
                        )

                        Thread {
                            try {
                                Thread.sleep(TRIGGER_RETRY_INTERVAL_MS)
                            } catch (_: InterruptedException) {
                            }
                            triggerBuildWithRetry(
                                token, repository, projectType,
                                workflowFile, flutterVersion,
                                publishStartedAtMs, listener, attempt + 1
                            )
                        }.start()
                    } else {
                        listener.onError(
                            "فشل تشغيل GitHub Actions:\n$message\n\n" +
                            "تحقق من:\n" +
                            "• تفعيل Actions في المستودع\n" +
                            "• صلاحيات التوكن (scope: repo + workflow)\n" +
                            "• وجود ملف .github/workflows/$workflowFile"
                        )
                    }
                }
            }
        )
    }

    // ============================================================
    // انتظار بدء الـ Run
    // ============================================================
    private fun findStartedRun(
        token: String,
        repository: GitHubApi.Repository,
        projectType: ProjectType,
        publishStartedAtMs: Long,
        listener: Listener,
        attempt: Int
    ) {
        val workflowFile = GitHubConfig.workflowFileFor(projectType)

        GitHubApi.latestRun(
            token, repository.owner, repository.name, publishStartedAtMs,
            workflowFile,
            object : GitHubApi.Callback<GitHubApi.Run> {
                override fun onSuccess(run: GitHubApi.Run) {
                    listener.onSuccess(repository, run)
                }

                override fun onError(message: String) {
                    if (attempt >= 10) {
                        listener.onError(
                            "تم تشغيل البناء، لكن تعذر الحصول على رقم العملية:\n$message\n\n" +
                            "افتح المستودع لمتابعة البناء يدوياً."
                        )
                        return
                    }
                    Thread {
                        try {
                            Thread.sleep(2500L)
                        } catch (_: InterruptedException) {
                        }
                        findStartedRun(
                            token, repository, projectType,
                            publishStartedAtMs, listener, attempt + 1
                        )
                    }.start()
                }
            }
        )
    }

    // ============================================================
    // الأدوات
    // ============================================================
    private fun collect(root: File, directory: File, result: MutableList<File>) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            val path = relative(root, child)
            if (isExcluded(path)) continue
            if (child.isDirectory) {
                collect(root, child, result)
            } else if (child.isFile && child.length() <= 10L * 1024L * 1024L) {
                result.add(child)
            }
        }
    }

    private fun isExcluded(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val lower = normalized.lowercase(Locale.US)
        return lower == "local.properties" ||
                lower.startsWith(".git/") ||
                lower.startsWith(".klencod_backup/") ||
                lower.startsWith(".klencod/") ||
                lower.startsWith("build/") ||
                lower.contains("/build/") ||
                lower.contains(".gradle/") ||
                lower.contains("keystore") ||
                lower.endsWith(".jks") ||
                lower.endsWith(".keystore") ||
                lower.endsWith(".pem") ||
                lower.endsWith(".apk") ||
                lower.endsWith(".aab")
    }

    @Throws(IOException::class)
    private fun readBytes(file: File): ByteArray {
        FileInputStream(file).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        }
    }

    /**
     * كشف إذا كان الملف ثنائياً.
     */
    private fun isBinaryFile(fileName: String, bytes: ByteArray): Boolean {
        // 1) بالامتداد
        val lower = fileName.lowercase(Locale.US)
        val binaryExtensions = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico",
            ".ttf", ".otf", ".woff", ".woff2",
            ".mp3", ".mp4", ".wav", ".ogg",
            ".so", ".dylib", ".dll", ".a",
            ".jar", ".class", ".dex",
            ".zip", ".rar", ".7z", ".gz", ".tar"
        )
        for (ext in binaryExtensions) {
            if (lower.endsWith(ext)) return true
        }

        // 2) بفحص المحتوى (أول 512 بايت)
        val sampleSize = minOf(512, bytes.size)
        var nonTextCount = 0
        for (i in 0 until sampleSize) {
            val b = bytes[i].toInt() and 0xFF
            // نطاق النص المسموح: 0x09, 0x0A, 0x0D, 0x20 - 0x7E
            val isText = b == 0x09 || b == 0x0A || b == 0x0D ||
                    (b in 0x20..0x7E) ||
                    b >= 0x80  // UTF-8 multi-byte
            if (!isText) nonTextCount++
        }

        // إذا > 30% non-text → binary
        return nonTextCount * 100 / sampleSize > 30
    }

    private fun relative(root: File, file: File): String {
        return try {
            val base = root.canonicalPath
            val value = file.canonicalPath
            if (value.startsWith("$base${File.separator}")) {
                value.substring(base.length + 1).replace(File.separatorChar, '/')
            } else {
                file.name
            }
        } catch (_: IOException) {
            file.name
        }
    }

    // ============================================================
    // اسم المستودع
    // ============================================================
    private fun repositoryName(raw: String?, projectType: ProjectType): String {
        var name = raw?.trim() ?: "app"
        if (name.isEmpty()) name = "app"

        val sb = StringBuilder()
        for (c in name) {
            val isArabic = (c in '\u0600'..'\u06FF') ||
                    (c in '\u0750'..'\u077F') ||
                    (c in '\uFB50'..'\uFDFF') ||
                    (c in '\uFE70'..'\uFEFF')
            val isLatinDigit = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9')
            val isSafe = c == '-' || c == '_' || c == '.'

            when {
                isArabic || isLatinDigit || isSafe -> sb.append(c)
                c == ' ' -> sb.append('-')
            }
        }

        name = sb.toString()
        while (name.startsWith("-") || name.startsWith(".")) name = name.substring(1)
        while (name.endsWith("-") || name.endsWith(".")) {
            name = name.substring(0, name.length - 1)
        }
        if (name.isEmpty()) name = "app"

        val prefix = GitHubConfig.repoPrefixFor(projectType)

        val finalName = if (name.startsWith(prefix)) {
            name
        } else {
            "$prefix$name"
        }

        return if (finalName.length > 90) finalName.substring(0, 90) else finalName
    }
}