package com.safeyoutube.brave;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class YouTubeQualityAccessibilityService extends AccessibilityService {

    private static final long COOLDOWN_MS = 18000L;

    private enum State {
        IDLE,
        WAITING_QUALITY_ITEM,
        WAITING_360P_ITEM
    }

    private State state = State.IDLE;
    private long lastCompletedTime = 0L;
    private long stateEnteredTime = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (!pkg.startsWith("com.brave.browser")) return;

        SharedPreferences prefs = getSharedPreferences(VolumeLimiterService.PREFS_NAME, MODE_PRIVATE);
        boolean sessionActive = prefs.getBoolean(VolumeLimiterService.KEY_SESSION_ACTIVE, false);
        boolean auto360 = prefs.getBoolean(VolumeLimiterService.KEY_AUTO_360, true);
        if (!sessionActive || !auto360) return;

        long now = System.currentTimeMillis();
        if (state != State.IDLE && (now - stateEnteredTime > 6000L)) {
            state = State.IDLE;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            if (state == State.WAITING_360P_ITEM) {
                if (clickNodeMatching(root, new String[]{"360p", "360"})) {
                    state = State.IDLE;
                    lastCompletedTime = now;
                }
                return;
            }

            if (state == State.WAITING_QUALITY_ITEM) {
                if (clickNodeMatching(root, new String[]{"Quality", "الجودة"})) {
                    state = State.WAITING_360P_ITEM;
                    stateEnteredTime = now;
                }
                return;
            }

            if (now - lastCompletedTime < COOLDOWN_MS) return;

            // Detect watch page player settings gear button in Brave YouTube mobile UI
            if (hasNodeMatching(root, new String[]{"Play video", "Pause video", "إيقاف الفيديو مؤقتًا", "تشغيل الفيديو", "Enter full screen", "ملء الشاشة"})) {
                if (clickNodeMatching(root, new String[]{"Playback settings", "Settings", "إعدادات التشغيل", "الإعدادات"})) {
                    state = State.WAITING_QUALITY_ITEM;
                    stateEnteredTime = now;
                    handler.postDelayed(() -> {
                        if (state != State.IDLE) {
                            AccessibilityNodeInfo r = getRootInActiveWindow();
                            if (r != null) {
                                if (state == State.WAITING_QUALITY_ITEM && clickNodeMatching(r, new String[]{"Quality", "الجودة"})) {
                                    state = State.WAITING_360P_ITEM;
                                    stateEnteredTime = System.currentTimeMillis();
                                }
                                r.recycle();
                            }
                        }
                    }, 700L);
                }
            }
        } finally {
            root.recycle();
        }
    }

    private boolean hasNodeMatching(AccessibilityNodeInfo root, String[] keywords) {
        return findNodeMatching(root, keywords) != null;
    }

    private boolean clickNodeMatching(AccessibilityNodeInfo root, String[] keywords) {
        AccessibilityNodeInfo node = findNodeMatching(root, keywords);
        if (node == null) return false;
        AccessibilityNodeInfo clickable = node;
        while (clickable != null && !clickable.isClickable()) {
            clickable = clickable.getParent();
        }
        if (clickable != null) {
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private AccessibilityNodeInfo findNodeMatching(AccessibilityNodeInfo root, String[] keywords) {
        if (root == null) return null;
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> byText = root.findAccessibilityNodeInfosByText(kw);
            if (byText != null && !byText.isEmpty()) {
                return byText.get(0);
            }
        }
        return findByContentDescription(root, keywords);
    }

    private AccessibilityNodeInfo findByContentDescription(AccessibilityNodeInfo node, String[] keywords) {
        if (node == null) return null;
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            for (String kw : keywords) {
                if (d.contains(kw.toLowerCase())) {
                    return node;
                }
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findByContentDescription(child, keywords);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Override
    public void onInterrupt() {
        state = State.IDLE;
    }
}
