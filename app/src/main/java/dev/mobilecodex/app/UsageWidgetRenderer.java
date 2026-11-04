package dev.mobilecodex.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import dev.mobilecodex.app.core.UsageWidgetData;

final class UsageWidgetRenderer {
    static String name(String provider) { return switch (provider) { case "claude" -> "Claude"; case "agy" -> "Gemini"; default -> "Codex"; }; }
    private static boolean korean(Context context) { return AppLanguage.locale(context).getLanguage().equals("ko"); }
    private static String label(Context context, String label) {
        int separator = label.lastIndexOf(" · ");
        if (separator >= 0) return label.substring(0, separator + 3) + label(context, label.substring(separator + 3));
        return switch (label) {
            case "session" -> korean(context) ? "5시간" : "5 hours";
            case "week" -> korean(context) ? "주간" : "Weekly";
            case "primary" -> korean(context) ? "기본" : "Primary";
            case "secondary" -> korean(context) ? "추가" : "Secondary";
            default -> label;
        };
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
        float scale = Math.min(context.getResources().getDisplayMetrics().density, 400f / Math.max(w, h));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.scale(scale, scale);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int ink = dark ? 0xffececec : 0xff0d0d0d;
        int muted = dark ? 0xffb4b4b4 : 0xff5d5d5d;
        int accent = switch (provider) { case "claude" -> Color.rgb(222, 148, 111); case "agy" -> Color.rgb(112, 159, 251); default -> Color.rgb(83, 191, 159); };
        long now = System.currentTimeMillis();
        List<UsageWidgetData.Limit> rows = data.current(now);
        double remaining = rows.isEmpty() ? -1 : rows.get(0).remaining();
        if (remaining >= 0 && remaining <= 20) accent = Color.rgb(225, 103, 110);
        paint.setColor(dark ? 0xff2a2a2a : 0xffffffff);
        canvas.drawRoundRect(1, 1, w - 1, h - 1, Math.min(24, w / 5), Math.min(24, w / 5), paint);
        float pad = Math.max(7, Math.min(16, w / 11));
        boolean detailed = w >= 135 && h >= 190;
        boolean wide = w >= h * 1.6f;
        boolean footer = h >= 125;
        boolean resetLine = h >= 145 && w >= 100;
        float titleSize = Math.min(15, Math.max(10, w / 12));
        text(canvas, paint, name(provider), pad, pad + titleSize, titleSize, ink, w - pad * 2 - (w >= 100 && h >= 100 ? 28 : 0), true);
        float batteryLeft = wide ? w * .56f : pad;
        float batteryTop = pad + titleSize + 12;
        float batteryRight = w - pad - 6;
        float batteryBottom = wide ? h - pad - 14 : detailed ? Math.min(h * .52f, batteryTop + 66) : h - pad - (footer ? (resetLine ? 56 : 40) : 2);
        if (batteryBottom - batteryTop < 19) { batteryTop = Math.min(h - 23, pad + titleSize + 5); batteryBottom = h - pad; }
        float bh = Math.max(12, batteryBottom - batteryTop);
        paint.setColor(dark ? 0xff454545 : 0xffe8e8e8);
        canvas.drawRoundRect(batteryLeft, batteryTop, batteryRight, batteryBottom, 12, 12, paint);
        canvas.drawRoundRect(batteryRight + 2, batteryTop + bh * .32f, batteryRight + 6, batteryTop + bh * .68f, 2, 2, paint);
        if (remaining >= 0) {
            paint.setColor(accent);
            canvas.save(); canvas.clipRect(batteryLeft, batteryTop, batteryLeft + (batteryRight - batteryLeft) * (float) remaining / 100, batteryBottom);
            canvas.drawRoundRect(batteryLeft, batteryTop, batteryRight, batteryBottom, 12, 12, paint); canvas.restore();
        }
        String percent = remaining < 0 ? "—" : Math.round(remaining) + "%";
        float valueSize = Math.min(34, Math.min(bh * .58f, (batteryRight - batteryLeft - 16) * .38f));
        // Keep the percentage readable across every battery fill level and theme.
        paint.setTextSize(valueSize); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setColor(dark ? 0xff2a2a2a : 0xffffffff);
        canvas.drawRoundRect(batteryLeft + 4, batteryTop + bh / 2 - valueSize * .65f,
            batteryLeft + 12 + paint.measureText(percent), batteryTop + bh / 2 + valueSize * .58f, 5, 5, paint);
        text(canvas, paint, percent, batteryLeft + 8, batteryTop + bh / 2 + valueSize * .34f, valueSize, ink, batteryRight - batteryLeft - 16, true);
        if (wide) {
            String caption = rows.isEmpty() ? state(context, data) : label(context, rows.get(0).label());
            text(canvas, paint, caption, pad, h * .62f, 12, muted, w * .48f - pad * 2, false);
        } else if (detailed) {
            float y = batteryBottom + 23;
            for (UsageWidgetData.Limit row : rows.subList(0, Math.min(2, rows.size()))) {
                text(canvas, paint, label(context, row.label()) + "  " + Math.round(row.remaining()) + "%", pad, y, 12, muted, w - pad * 2, false);
                y += 18;
            }
            if (rows.isEmpty()) text(canvas, paint, state(context, data), pad, y, 12, muted, w - pad * 2, false);
            else if (rows.get(0).resetsAt() > now && y < h - pad - 16)
                text(canvas, paint, reset(context, rows.get(0).resetsAt(), now), pad, y, 11, muted, w - pad * 2, false);
        }
        if (!wide && !detailed && footer) text(canvas, paint,
            rows.isEmpty() ? state(context, data) : label(context, rows.get(0).label()),
            pad, batteryBottom + 17, 11, muted, w - pad * 2, false);
        if (!wide && !detailed && resetLine && !rows.isEmpty() && rows.get(0).resetsAt() > now)
            text(canvas, paint, reset(context, rows.get(0).resetsAt(), now), pad, batteryBottom + 33, 10, muted, w - pad * 2, false);
        if (footer) {
            String stamp;
            if (data.updatedAt() == 0) stamp = state(context, data);
            else {
                long age = Math.max(0, (now - data.updatedAt()) / 60000);
                stamp = age < 1 ? (korean(context) ? "방금 확인" : "Just checked")
                    : age < 60 ? age + (korean(context) ? "분 전" : "m ago")
                    : age < 1440 ? age / 60 + (korean(context) ? "시간 전" : "h ago") : age / 1440 + (korean(context) ? "일 전" : "d ago");
            }
            text(canvas, paint, stamp, pad, h - pad, 10, muted, w - pad * 2, false);
        }
        return bitmap;
    }
    private static String reset(Context context, long resetsAt, long now) {
        long minutes = (resetsAt - now + 59999) / 60000;
        if (korean(context)) {
            String duration = minutes >= 60 ? minutes / 60 + "시간 " + minutes % 60 + "분" : minutes + "분";
            return duration + " 후 초기화";
        }
        String duration = minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
        return duration + " to reset";
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
