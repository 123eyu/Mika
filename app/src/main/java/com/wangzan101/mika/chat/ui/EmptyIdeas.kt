package com.wangzan101.mika.chat.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.drawable.toBitmap
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.theme.MikaIcons

/**
 * 一条空对话里的示例:点了把 [task] 的提示词填进输入框;[app] 是手机上对应 App 的图标,
 * 没有时看 [site](提示词里点名的网站自带的标志),再没有用 [fallback]。
 */
internal data class Idea(val task: RecommendedTask, val app: ImageBitmap?, val fallback: ImageVector, val site: Int? = null)

/** 提示词里点名了这些网站、手机上又没装它的 App 时,用网站自己的标志,不用浏览器图标。 */
private val siteMarks = listOf("github.com" to R.drawable.ic_site_github)

private fun siteMark(prompt: String): Int? = siteMarks.firstOrNull { prompt.contains(it.first, ignoreCase = true) }?.second

/** 内置的灵感示例:文字 + 用哪类系统动作找到接它的 App。找不到 App 的不显示。 */
private class Seed(val id: String, val text: Int, val intent: () -> Intent, val fallback: ImageVector)

private val seeds = listOf(
    Seed("maps", R.string.chat_idea_maps, { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=coffee")) }, MikaIcons.Globe),
    Seed("camera", R.string.chat_idea_camera, { Intent(MediaStore.ACTION_IMAGE_CAPTURE) }, MikaIcons.Camera),
    Seed("sms", R.string.chat_idea_sms, { Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) }, MikaIcons.Message),
    Seed("news", R.string.chat_idea_news, { Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")) }, MikaIcons.Search),
    Seed("dark", R.string.chat_idea_dark, { Intent(Settings.ACTION_SETTINGS) }, MikaIcons.Moon),
    Seed("music", R.string.chat_idea_music, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC) }, MikaIcons.Spark),
    Seed("email", R.string.chat_idea_email, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL) }, MikaIcons.Message),
    Seed("screenshot", R.string.chat_idea_screenshot, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY) }, MikaIcons.Photo),
    Seed("meeting", R.string.chat_idea_meeting, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR) }, MikaIcons.Calendar),
)

/** 空对话的内容:上面轮播的几条推广示例,下面两条滚动的示例。 */
internal data class EmptyIdeas(val featured: List<Idea>, val rows: List<List<Idea>>)

/**
 * 按手机上装了的 App 配图标。推荐任务先看提示词里点名了哪个 App(按 App 名字认),
 * 认不出再按类型走系统动作(网页 → 浏览器、闹钟 → 时钟);内置灵感找不到接它的 App 就不列。
 * 读 PackageManager,放在后台线程调用。
 */
internal fun loadEmptyIdeas(context: Context, tasks: List<RecommendedTask>, byName: Boolean = true): EmptyIdeas {
    val pm = context.packageManager
    val apps = if (byName) launchableApps(context) else emptyList()
    fun iconOf(pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() ?: return null
    }
    fun handler(intent: Intent): String? = runCatching {
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.firstOrNull { it != "android" && it != context.packageName }
    }.getOrNull()
    fun named(prompt: String): String? = apps.firstOrNull { mentions(prompt, it.second) }?.first

    val promo = setOf(ChatCopy.ExampleKind.Web, ChatCopy.ExampleKind.Follow, ChatCopy.ExampleKind.GroupChat)
    val featured = mutableListOf<Idea>()
    val rest = mutableListOf<Idea>()
    tasks.forEach { task ->
        val kind = ChatCopy.kindOf(task.prompt) ?: builtInKinds(context)[task.prompt]
        val site = siteMark(task.prompt)
        val pkg = named(task.prompt) ?: if (site != null) null else when (kind) {
            ChatCopy.ExampleKind.Web -> handler(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
            ChatCopy.ExampleKind.Alarm -> handler(Intent(AlarmClock.ACTION_SET_ALARM))
            else -> null
        }
        val idea = Idea(task, pkg?.let(::iconOf), fallbackFor(kind), site)
        if (kind in promo) featured += idea else rest += idea
    }
    seeds.forEach { seed ->
        val pkg = handler(seed.intent()) ?: return@forEach
        val text = tr(seed.text)
        rest += Idea(RecommendedTask("idea-${seed.id}", text, text), iconOf(pkg), seed.fallback)
    }
    val half = (rest.size + 1) / 2
    val rows = listOf(rest.take(half), rest.drop(half)).filter { it.isNotEmpty() }
    return EmptyIdeas(featured, rows)
}

/**
 * 各种界面语言下内置示例的原文 → 类型。推荐任务是按当时的语言存下来的,换了语言后
 * 用当前语言比对就认不出来了(推广卡片会掉进滚动条),所以每种语言都比一遍。
 */
private fun builtInKinds(context: Context): Map<String, ChatCopy.ExampleKind> = kindsByText ?: run {
    val ids = listOf(
        R.string.chat_example_github_star to ChatCopy.ExampleKind.Web,
        R.string.chat_example_follow to ChatCopy.ExampleKind.Follow,
        R.string.chat_example_group_summary to ChatCopy.ExampleKind.GroupChat,
        R.string.chat_example_alarms to ChatCopy.ExampleKind.Alarm,
        R.string.chat_example_schedule to ChatCopy.ExampleKind.Schedule,
    )
    val map = buildMap {
        AppLanguage.entries.mapNotNull { it.tag }.forEach { tag ->
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            val res = context.createConfigurationContext(config).resources
            ids.forEach { (id, kind) -> runCatching { res.getString(id) }.getOrNull()?.let { put(it, kind) } }
        }
    }
    map.also { kindsByText = it }
}

@Volatile private var kindsByText: Map<String, ChatCopy.ExampleKind>? = null

/** 已经画好的 App 图标,按包名;App 换了图标要等进程重启才更新,够用。 */
private val icons = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

/** 包名 → App 名字。读名字要逐个问系统,一百多个 App 要好几秒,所以记下来,只补新装的。 */
private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

/** 手机上能从桌面打开的 App:(包名, 名字),名字长的在前,免得「微信读书」被认成「微信」。 */
private fun launchableApps(context: Context): List<Pair<String, String>> = runCatching {
    val pm = context.packageManager
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { it.activityInfo.packageName to labels.getOrPut(it.activityInfo.packageName) { it.loadLabel(pm).toString().trim() } }
        .filter { it.second.isNotEmpty() }
        .distinctBy { it.first }
        .sortedByDescending { it.second.length }
}.getOrDefault(emptyList())

/**
 * 提示词里是否点名了这个 App。拉丁字母的名字要整词出现(「X」不能匹配到「Xiaomi」里),
 * 中日韩名字直接按子串;只有一个字的中文名太容易误认,不算。
 */
internal fun mentions(prompt: String, label: String): Boolean {
    if (label.isBlank()) return false
    val latin = label.all { it.code < 0x2E80 }
    if (!latin) return label.length >= 2 && prompt.contains(label, ignoreCase = true)
    val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(label) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    return re.containsMatchIn(prompt)
}

private fun fallbackFor(kind: ChatCopy.ExampleKind?): ImageVector = when (kind) {
    ChatCopy.ExampleKind.Web -> MikaIcons.Globe
    ChatCopy.ExampleKind.Follow -> MikaIcons.Person
    ChatCopy.ExampleKind.GroupChat -> MikaIcons.Message
    ChatCopy.ExampleKind.Alarm -> MikaIcons.Alarm
    ChatCopy.ExampleKind.Schedule -> MikaIcons.Calendar
    null -> MikaIcons.Spark
}
