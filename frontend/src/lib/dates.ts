const MONTHS = ["一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月"];

const pad = (n: number) => String(n).padStart(2, "0");

const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate());

/** Days between the calendar dates of a and b (b later). */
const dayDiff = (a: Date, b: Date) => Math.round((startOfDay(b).getTime() - startOfDay(a).getTime()) / 86_400_000);

const clock = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;

/** The default title of a new document: its local creation time. */
export function defaultTitle(d = new Date()) {
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${clock(d)}`;
}

/** The sidebar group a document edited at d falls into. Weeks start on Monday. */
export function groupLabel(d: Date, now = new Date()) {
  const days = dayDiff(d, now);
  if (days <= 0) return "今天";
  if (days === 1) return "昨天";
  const weekday = (now.getDay() + 6) % 7; // days since Monday
  if (days <= weekday) return "本周早些时候";
  if (days <= weekday + 7) return "上周";
  if (d.getFullYear() === now.getFullYear()) return MONTHS[d.getMonth()]!;
  return `${d.getFullYear()}年${d.getMonth() + 1}月`;
}

/** A short edit time for the document meta line. */
export function shortTime(d: Date, now = new Date()) {
  const days = dayDiff(d, now);
  if (days <= 0) return `今天 ${clock(d)}`;
  if (days === 1) return `昨天 ${clock(d)}`;
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日 ${clock(d)}`;
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`;
}

/** When a device was last used, e.g. 「3 分钟前使用」. */
export function usedAgo(d: Date, now = new Date()) {
  const minutes = Math.floor((now.getTime() - d.getTime()) / 60_000);
  if (minutes < 1) return "刚刚使用";
  if (minutes < 60) return `${minutes} 分钟前使用`;
  if (minutes < 24 * 60) return `${Math.floor(minutes / 60)} 小时前使用`;
  const days = dayDiff(d, now);
  if (days <= 30) return `${days} 天前使用`;
  return `${monthDay(d, now)}使用`;
}

/** The day a device signed in, e.g. 「10 月 20 日登录」. */
export function signedInOn(d: Date, now = new Date()) {
  return `${monthDay(d, now)}登录`;
}

const monthDay = (d: Date, now: Date) =>
  `${d.getFullYear() === now.getFullYear() ? "" : `${d.getFullYear()} 年 `}${d.getMonth() + 1} 月 ${d.getDate()} 日`;
