package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.*;
import static dev.mobilecodex.app.core.Json.*;

/** Short-lived node references, never persisted. Coordinates and nodes share one observation. */
final class PhoneScreen implements AutoCloseable {
    final String id = UUID.randomUUID().toString();
    final long revision, capturedAt = SystemClock.uptimeMillis();
    final int width, height, windowId;
    final String packageName;
    final Map<String, AccessibilityNodeInfo> nodes = new LinkedHashMap<>();
    final Map<String, Rect> bounds = new HashMap<>();
    final JSONObject data;
    boolean passwordVisible;
    PhoneScreen(AccessibilityNodeInfo root, long revision, int width, int height) throws Exception {
        this.revision = revision; this.width = width; this.height = height;
        windowId = root.getWindowId(); packageName = value(root.getPackageName());
        JSONArray items = new JSONArray();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        boolean truncated = false;
        try {
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 600) {
                AccessibilityNodeInfo node = queue.removeFirst();
                String key = "n" + nodes.size(); nodes.put(key, node);
                Rect box = new Rect(); node.getBoundsInScreen(box); bounds.put(key, box);
                boolean visible = node.isVisibleToUser(), password = node.isPassword();
                passwordVisible |= password && visible;
                if (visible) items.put(obj("id", key, "text", password ? "[password]" : value(node.getText()),
                    "description", password ? "[password]" : value(node.getContentDescription()),
                    "class", value(node.getClassName()), "viewId", node.getViewIdResourceName(),
                    "bounds", array(box.left, box.top, box.right, box.bottom), "clickable", node.isClickable(),
                    "longClickable", node.isLongClickable(), "editable", node.isEditable(), "scrollable", node.isScrollable(),
                    "enabled", node.isEnabled(), "focused", node.isFocused(), "checked", node.isChecked(), "password", password));
                for (int i = 0; i < node.getChildCount(); i++) {
                    if (nodes.size() + queue.size() >= 600) { truncated = true; break; }
                    AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.addLast(child);
                }
            }
            truncated |= !queue.isEmpty();
        } finally { while (!queue.isEmpty()) queue.removeFirst().recycle(); }
        data = obj("snapshotId", id, "package", packageName, "windowId", windowId, "width", width, "height", height,
            "coordinateSpace", "physical_screen_pixels", "nodes", items, "truncated", truncated);
    }
    void requireFresh(String requested, long currentRevision, int currentWindow, String currentPackage, int currentWidth, int currentHeight) throws IOException {
        if (!id.equals(requested) || revision != currentRevision || SystemClock.uptimeMillis() - capturedAt > 30000 ||
            windowId != currentWindow || !packageName.equals(currentPackage) || width != currentWidth || height != currentHeight)
            throw new IOException(t("화면이 바뀌었거나 오래된 좌표입니다. mobile_phone_screen으로 다시 확인해 주세요."));
    }
    AccessibilityNodeInfo node(String id) throws IOException {
        AccessibilityNodeInfo node = nodes.get(id);
        if (node == null || !node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) throw new IOException(t("화면 요소가 더 이상 유효하지 않습니다. 화면을 다시 읽어 주세요."));
        Rect now = new Rect(); node.getBoundsInScreen(now);
        if (!now.equals(bounds.get(id))) throw new IOException(t("화면 요소의 위치가 바뀌었습니다. 화면을 다시 읽어 주세요."));
        return node;
    }
    static String value(CharSequence text) { if (text == null) return ""; String s = text.toString(); return s.substring(0, Math.min(s.length(), 1500)); }
    static float coordinate(JSONObject args, String name, int limit) throws Exception {
        double value = args.getDouble(name);
        if (!Double.isFinite(value) || value < 0 || value >= limit) throw new IOException(t("화면 밖 좌표입니다: ") + name);
        return (float)value;
    }
    @Override public void close() { for (AccessibilityNodeInfo node : nodes.values()) node.recycle(); nodes.clear(); bounds.clear(); }
}
