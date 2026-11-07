package top.onlyquant.tingxie.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Ported from frontend/src/lib/dates.ts so both clients label time alike.

private val months = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")

private fun local(i: Instant) = LocalDateTime.ofInstant(i, ZoneId.systemDefault())

private fun dayDiff(a: LocalDateTime, b: LocalDateTime) = ChronoUnit.DAYS.between(a.toLocalDate(), b.toLocalDate())

private fun pad(n: Int) = n.toString().padStart(2, '0')

private fun clock(d: LocalDateTime) = "${pad(d.hour)}:${pad(d.minute)}"

/** The default title of a new document: its local creation time. */
fun defaultTitle(now: LocalDateTime = LocalDateTime.now()) =
    "${now.year}/${pad(now.monthValue)}/${pad(now.dayOfMonth)} ${clock(now)}"

/** The list group a document edited at [at] falls into. Weeks start on Monday. */
fun groupLabel(at: Instant, nowI: Instant = Instant.now()): String {
    val d = local(at)
    val now = local(nowI)
    val days = dayDiff(d, now)
    if (days <= 0) return "今天"
    if (days == 1L) return "昨天"
    val weekday = now.dayOfWeek.value - DayOfWeek.MONDAY.value
    if (days <= weekday) return "本周早些时候"
    if (days <= weekday + 7) return "上周"
    if (d.year == now.year) return months[d.monthValue - 1]
    return "${d.year}年${d.monthValue}月"
}

/** A short edit time for the document meta line. */
fun shortTime(at: Instant, nowI: Instant = Instant.now()): String {
    val d = local(at)
    val now = local(nowI)
    val days = dayDiff(d, now)
    if (days <= 0) return "今天 ${clock(d)}"
    if (days == 1L) return "昨天 ${clock(d)}"
    if (d.year == now.year) return "${d.monthValue}月${d.dayOfMonth}日 ${clock(d)}"
    return "${d.year}/${d.monthValue}/${d.dayOfMonth}"
}

/** A clock time for send records: today 14:32, otherwise with the date. */
fun sentTime(at: Instant, nowI: Instant = Instant.now()): String {
    val d = local(at)
    return if (dayDiff(d, local(nowI)) <= 0) clock(d) else shortTime(at, nowI)
}

/** When a device was last used, e.g. 「3 分钟前使用」. */
fun usedAgo(at: Instant, nowI: Instant = Instant.now()): String {
    val minutes = ChronoUnit.MINUTES.between(at, nowI)
    if (minutes < 1) return "刚刚使用"
    if (minutes < 60) return "$minutes 分钟前使用"
    if (minutes < 24 * 60) return "${minutes / 60} 小时前使用"
    val days = dayDiff(local(at), local(nowI))
    if (days <= 30) return "$days 天前使用"
    return "${monthDay(at, nowI)}使用"
}

/** When an online Mac last saw the user, e.g. 「3 分钟前有操作」. */
fun activeAgo(at: Instant, nowI: Instant = Instant.now()): String {
    val minutes = ChronoUnit.MINUTES.between(at, nowI)
    if (minutes < 1) return "刚刚有操作"
    if (minutes < 60) return "$minutes 分钟前有操作"
    if (minutes < 24 * 60) return "${minutes / 60} 小时前有操作"
    return "${dayDiff(local(at), local(nowI))} 天前有操作"
}

/** The day a device signed in, e.g. 「10 月 20 日登录」. */
fun signedInOn(at: Instant, nowI: Instant = Instant.now()) = "${monthDay(at, nowI)}登录"

private fun monthDay(at: Instant, nowI: Instant): String {
    val d = local(at)
    val year = if (d.year == local(nowI).year) "" else "${d.year} 年 "
    return "$year${d.monthValue} 月 ${d.dayOfMonth} 日"
}
