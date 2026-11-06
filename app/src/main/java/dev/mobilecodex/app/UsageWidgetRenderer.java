package dev.mobilecodex.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import dev.mobilecodex.app.core.UsageWidgetData;

final class UsageWidgetRenderer {
    /** The bitmap is the only content, so it is drawn near device resolution within a bounded memory budget. */
    private static final float MAX_PIXELS = 1_000_000f;
    static String name(String provider) { return switch (provider) { case "claude" -> "Claude"; case "agy" -> "Gemini"; default -> "Codex"; }; }
    private static boolean korean(Context context) { return AppLanguage.locale(context).getLanguage().equals("ko"); }
    private static String tail(String label) {
        int separator = label.lastIndexOf(" · ");
        return separator >= 0 ? label.substring(separator + 3) : label;
    }
    private static String kind(String tail) {
        String lower = tail.toLowerCase(Locale.ROOT);
        if (lower.equals("session") || lower.contains("five hour") || lower.contains("5 hour")) return "session";
        if (lower.contains("week")) return "week";
        return lower.contains("daily") ? "daily" : tail;
    }
    private static String label(Context context, String label) {
        int separator = label.lastIndexOf(" · ");
        if (separator >= 0) return label.substring(0, separator + 3) + label(context, label.substring(separator + 3));
        return switch (kind(label)) {
            case "session" -> korean(context) ? "5시간" : "5 hours";
            case "week" -> korean(context) ? "주간" : "Weekly";
            case "daily" -> korean(context) ? "일간" : "Daily";
            case "primary" -> korean(context) ? "기본" : "Primary";
            case "secondary" -> korean(context) ? "추가" : "Secondary";
            default -> label;
        };
    }
    static String chipLabel(Context context, String label) { return label(context, tail(label)); }
    /** Gemini quotas are per model, so its chip names a specific model rather than a generic "... models" group. */
    static String modelName(String provider, String label) {
        int separator = label.lastIndexOf(" · ");
        if (!provider.equals("agy") || separator < 0) return "";
        String group = label.substring(0, separator).trim();
        return group.toLowerCase(Locale.ROOT).endsWith("models") ? "" : group.substring(group.lastIndexOf(' ') + 1);
    }
    /** Leads with the limit kind so truncating a long model group never hides it. */
    static String rowLabel(Context context, String label) {
        int separator = label.lastIndexOf(" · ");
        return separator < 0 ? label(context, label) : chipLabel(context, label) + " · " + label.substring(0, separator).replaceFirst("(?i)\\s+models$", "");
    }
    private static String state(Context context, UsageWidgetData data) {
        boolean ko = korean(context);
        return data.status().equals("signed_out") ? (ko ? "로그인 필요" : "Sign in")
            : data.limits().isEmpty() ? (ko ? "확인 필요" : "No reading") : (ko ? "갱신 필요" : "Refresh needed");
    }
    static String description(Context context, String provider, UsageWidgetData data) {
        long now = System.currentTimeMillis();
        StringBuilder text = new StringBuilder(name(provider));
        for (UsageWidgetData.Limit limit : data.current(now)) text.append(", ").append(label(context, limit.label()))
            .append(korean(context) ? " 남은 " : " remaining ").append(Math.round(limit.remaining())).append('%');
        if (data.current(now).isEmpty()) text.append(", ").append(state(context, data));
        if (data.updatedAt() > 0) text.append(", ").append(korean(context) ? "마지막 확인 " : "Last checked ")
            .append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, AppLanguage.locale(context)).format(new Date(data.updatedAt())));
        return text.toString();
    }
    static Bitmap render(Context context, String provider, UsageWidgetData data, int requestedWidth, int requestedHeight) {
        float w = Math.max(48, Math.min(600, requestedWidth)), h = Math.max(48, Math.min(600, requestedHeight));
        float scale = Math.min(context.getResources().getDisplayMetrics().density, (float) Math.sqrt(MAX_PIXELS / (w * h)));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.scale(scale, scale);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int surface = dark ? 0xff1f1f1f : 0xffffffff;
        int track = dark ? 0xff363636 : 0xffe9e9e9;
        int ink = dark ? 0xfff4f4f4 : 0xff0d0d0d;
        int muted = dark ? 0xffb4b4b4 : 0xff5a5a5a;
        int subtle = dark ? 0xff8a8a8a : 0xff868686;
        int chipAlpha = dark ? 0x33 : 0x26;
        int accent = switch (provider) { case "claude" -> Color.rgb(217, 130, 91); case "agy" -> Color.rgb(92, 146, 250); default -> Color.rgb(66, 184, 146); };
        long now = System.currentTimeMillis();
        List<UsageWidgetData.Limit> rows = data.current(now);
        double remaining = rows.isEmpty() ? -1 : rows.get(0).remaining();
        int gauge = gaugeColor(remaining, accent);

        // Accent tint and border highlight stay on the provider color; only the gauge turns red when low.
        float radius = Math.min(24, Math.max(14, Math.min(w, h) * .22f));
        paint.setColor(surface);
        canvas.drawRoundRect(1, 1, w - 1, h - 1, radius, radius, paint);
        paint.setShader(new RadialGradient(w * .15f, 0, Math.max(w, h) * .9f, withAlpha(accent, dark ? 0x2e : 0x1f), withAlpha(accent, 0), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(1, 1, w - 1, h - 1, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1);
        paint.setShader(new LinearGradient(0, 0, 0, h, dark ? 0x2effffff : 0x14000000, dark ? 0x14ffffff : 0x14000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(1, 1, w - 1, h - 1, radius, radius, paint);
        paint.setShader(null); paint.setStyle(Paint.Style.FILL);

        boolean wide = w >= 170 && w >= h * 1.55f && h < 140;
        boolean micro = !wide && (w < 100 || h < 100);
        boolean detailed = !wide && !micro && w >= 130 && h >= 185;
        boolean refresh = w >= 100 && h >= 100;
        // A fixed inset keeps the header centered on the refresh button, which is a separate 44dp view.
        float pad = refresh ? 13 : clamp(Math.min(w, h) * .09f, micro ? 8 : 9, micro ? 11 : 13);

        String percent = remaining < 0 ? "—" : Math.round(remaining) + "%";
        String primaryLabel = rows.isEmpty() ? state(context, data) : chipLabel(context, rows.get(0).label());
        String model = rows.isEmpty() ? "" : modelName(provider, rows.get(0).label());
        String chipFull = model.isEmpty() ? primaryLabel : model + " " + primaryLabel;
        String chipShort = model.isEmpty() ? primaryLabel : model;
        boolean resets = !rows.isEmpty() && rows.get(0).resetsAt() > now;
        String resetText = resets ? reset(context, rows.get(0).resetsAt(), now) : "";
        String resetShort = resets ? resetShort(context, rows.get(0).resetsAt(), now) : "";
        String stamp = stamp(context, data, now);
        if (rows.isEmpty() && stamp.equals(primaryLabel)) stamp = "";

        float headHeight = micro ? 13 : 18;
        float headCenter = pad + headHeight / 2;
        float headBottom = pad + headHeight;
        float badge = micro ? 4 : 9;
        paint.setColor(accent);
        canvas.drawCircle(pad + badge, headCenter, badge, paint);
        if (!micro) {
            paint.setTextSize(10); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            String mark = name(provider).substring(0, 1);
            text(canvas, paint, mark, pad + badge - paint.measureText(mark) / 2, headCenter + 3.6f, 10, 0xffffffff, badge * 2, true);
        }
        float titleSize = micro ? 10.5f : 13f;
        float titleX = pad + badge * 2 + (micro ? 4 : 6);
        text(canvas, paint, name(provider), titleX, headCenter + titleSize * .36f, titleSize, ink, w - titleX - (refresh ? 34 : pad), true);

        if (micro) {
            float numSize = clamp(h * .22f, 13, 16);
            float barHeight = clamp(h * .13f, 6, 9);
            float barTop = h - pad - barHeight;
            float numCenter = (headBottom + barTop) / 2;
            String kind = rows.isEmpty() ? primaryLabel : compactLabel(context, rows.get(0).label());
            hero(canvas, paint, percent, kind, kind, pad, numCenter, numSize, 8, 4, 3, withAlpha(gauge, chipAlpha), ink, w - pad * 2);
            bar(canvas, paint, pad, barTop, w - pad, h - pad, remaining, gauge, track);
            return bitmap;
        }

        boolean footer = h >= 100;
        float footerBaseline = h - pad;
        float contentBottom = footer ? footerBaseline - 13 : h - pad;
        if (footer) footer(canvas, paint, resetText, resetShort, stamp, pad, w - pad, footerBaseline, muted, subtle);

        if (wide) {
            float column = w * .45f;
            float numSize = clamp(h * .22f, 18, 28);
            hero(canvas, paint, percent, chipFull, chipShort, pad, (headBottom + contentBottom) / 2, numSize, 10, 5, 5, withAlpha(gauge, chipAlpha), ink, column - pad - 8);
            boolean second = footer && rows.size() >= 2;
            float gaugeHeight = clamp(h * .12f, 10, 14);
            float blockHeight = gaugeHeight + (second ? 25 : 0);
            float top = ((refresh ? headBottom : pad) + contentBottom - blockHeight) / 2;
            segments(canvas, paint, column, top, w - pad, top + gaugeHeight, remaining, w - pad - column >= 150 ? 10 : 8, gauge, track);
            if (second) limitRow(canvas, paint, context, rows.get(1), column, w - pad, top + gaugeHeight + 8, accent, track, muted, ink);
            return bitmap;
        }

        float numSize = clamp(Math.min(w, h) * .21f, 22, 44);
        float gaugeHeight = clamp(Math.min(w, h) * .1f, 11, 20);
        float capHeight = numSize * .74f;
        int listed = detailed ? Math.min(3, rows.size()) : 0;
        // Spread tall widgets' spare height between the groups instead of leaving it above the footer.
        float spare = detailed ? clamp(contentBottom - 4 - headBottom - 12 - capHeight - 10 - gaugeHeight - 13 - Math.max(0, listed * 24 - 7), 0, 36) : 0;
        float numTop = detailed ? headBottom + 12 + spare * .4f : headBottom + (contentBottom - headBottom - capHeight - 11 - gaugeHeight) / 2;
        float gaugeTop = numTop + capHeight + (detailed ? 10 : 11);
        hero(canvas, paint, percent, chipFull, chipShort, pad, numTop + capHeight / 2, numSize, 10, 7, 6, withAlpha(gauge, chipAlpha), ink, w - pad * 2);
        segments(canvas, paint, pad, gaugeTop, w - pad, gaugeTop + gaugeHeight, remaining, 8, gauge, track);
        if (detailed) {
            float y = gaugeTop + gaugeHeight + 13 + spare * .6f;
            for (UsageWidgetData.Limit row : rows.subList(0, listed)) {
                if (y + 17 > contentBottom - 4) break;
                limitRow(canvas, paint, context, row, pad, w - pad, y, accent, track, muted, ink);
                y += 24;
            }
        }
        return bitmap;
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static int withAlpha(int color, int alpha) { return (color & 0xffffff) | (alpha << 24); }
    private static int gaugeColor(double remaining, int accent) { return remaining >= 0 && remaining <= 20 ? Color.rgb(229, 92, 92) : accent; }
    /** Snaps slivers so a segment never reads as a rendering glitch; the first stays visible above zero. */
    static float segmentFill(double remaining, int count, int index) {
        double raw = Math.max(0, Math.min(1, remaining / 100 * count - index));
        if (raw < .12) return remaining > 0 && index == 0 ? .12f : 0;
        return raw > .88 ? 1 : (float) raw;
    }
    private static void segments(Canvas canvas, Paint paint, float left, float top, float right, float bottom,
            double remaining, int count, int color, int track) {
        float gap = 3;
        float width = (right - left - gap * (count - 1)) / count;
        float r = Math.min(4, (bottom - top) * .3f);
        for (int i = 0; i < count; i++) {
            float x = left + i * (width + gap);
            paint.setColor(track);
            canvas.drawRoundRect(x, top, x + width, bottom, r, r, paint);
            float fill = segmentFill(remaining, count, i);
            if (fill <= 0) continue;
            paint.setColor(color);
            canvas.save();
            canvas.clipRect(x, top, x + width * fill, bottom);
            canvas.drawRoundRect(x, top, x + width, bottom, r, r, paint);
            canvas.restore();
        }
    }
    private static void bar(Canvas canvas, Paint paint, float left, float top, float right, float bottom, double remaining, int color, int track) {
        float r = (bottom - top) / 2;
        paint.setColor(track);
        canvas.drawRoundRect(left, top, right, bottom, r, r, paint);
        if (remaining <= 0) return;
        paint.setColor(color);
        canvas.drawRoundRect(left, top, left + Math.max(r * 2, (right - left) * (float) remaining / 100), bottom, r, r, paint);
    }
    private static void hero(Canvas canvas, Paint paint, String percent, String full, String compact, float x, float center, float numSize,
            float chipSize, float chipPad, float gap, int chipFill, int ink, float width) {
        paint.setTextSize(numSize); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        float numWidth = paint.measureText(percent);
        text(canvas, paint, percent, x, center + numSize * .36f, numSize, ink, width, true);
        float chipWidth = width - numWidth - gap;
        paint.setTextSize(chipSize); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        String kind = paint.measureText(full) + chipPad * 2 <= chipWidth ? full : compact;
        chipWidth = Math.min(chipWidth, paint.measureText(kind) + chipPad * 2);
        if (chipWidth < chipPad * 2 + chipSize) return;
        float half = chipSize * .9f, left = x + numWidth + gap;
        paint.setColor(chipFill);
        canvas.drawRoundRect(left, center - half, left + chipWidth, center + half, half, half, paint);
        text(canvas, paint, kind, left + chipPad, center + chipSize * .36f, chipSize, ink, chipWidth - chipPad * 2 + 1, false);
    }
    private static void limitRow(Canvas canvas, Paint paint, Context context, UsageWidgetData.Limit row, float left, float right, float top,
            int accent, int track, int muted, int ink) {
        String value = Math.round(row.remaining()) + "%";
        paint.setTextSize(10.5f); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        float valueWidth = paint.measureText(value);
        text(canvas, paint, rowLabel(context, row.label()), left, top + 9, 10.5f, muted, right - left - valueWidth - 6, false);
        text(canvas, paint, value, right - valueWidth, top + 9, 10.5f, ink, valueWidth + 1, true);
        bar(canvas, paint, left, top + 13, right, top + 17, row.remaining(), gaugeColor(row.remaining(), accent), track);
    }
    private static void footer(Canvas canvas, Paint paint, String reset, String resetShort, String stamp, float left, float right, float y, int muted, int subtle) {
        float size = 10, width = right - left;
        paint.setTextSize(size); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        float stampWidth = paint.measureText(stamp);
        if (reset.isEmpty()) { text(canvas, paint, stamp, left, y, size, subtle, width, false); return; }
        String chosen = paint.measureText(reset) + stampWidth + 6 <= width ? reset : resetShort;
        boolean fits = stamp.isEmpty() || paint.measureText(chosen) + stampWidth + 6 <= width;
        text(canvas, paint, chosen, left, y, size, muted, width, false);
        if (fits && !stamp.isEmpty()) text(canvas, paint, stamp, right - stampWidth, y, size, subtle, stampWidth + 1, false);
    }
    private static String compactLabel(Context context, String label) {
        String tail = tail(label);
        return switch (kind(tail)) {
            case "session" -> "5h";
            case "week" -> "7d";
            case "daily" -> "1d";
            default -> {
                String localized = label(context, tail);
                yield localized.length() > 3 ? localized.substring(0, 2) : localized;
            }
        };
    }
    private static String stamp(Context context, UsageWidgetData data, long now) {
        if (data.updatedAt() == 0) return state(context, data);
        long age = Math.max(0, (now - data.updatedAt()) / 60000);
        return age < 1 ? (korean(context) ? "방금 확인" : "Just checked")
            : age < 60 ? age + (korean(context) ? "분 전" : "m ago")
            : age < 1440 ? age / 60 + (korean(context) ? "시간 전" : "h ago") : age / 1440 + (korean(context) ? "일 전" : "d ago");
    }
    private static String duration(Context context, long resetsAt, long now) {
        long minutes = (resetsAt - now + 59999) / 60000;
        if (korean(context)) return minutes >= 60 ? minutes / 60 + "시간 " + minutes % 60 + "분" : minutes + "분";
        return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
    }
    private static String reset(Context context, long resetsAt, long now) {
        return duration(context, resetsAt, now) + (korean(context) ? " 후 초기화" : " to reset");
    }
    private static String resetShort(Context context, long resetsAt, long now) {
        return korean(context) ? duration(context, resetsAt, now) + " 후" : "in " + duration(context, resetsAt, now);
    }
    private static void text(Canvas canvas, Paint paint, String value, float x, float y, float size, int color, float width, boolean bold) {
        if (width <= 0) return;
        paint.setColor(color); paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        String visible = value;
        if (paint.measureText(value) > width) {
            int count = paint.breakText(value, true, Math.max(0, width - paint.measureText("…")), null);
            visible = value.substring(0, count) + "…";
        }
        canvas.drawText(visible, x, y, paint);
    }
}
