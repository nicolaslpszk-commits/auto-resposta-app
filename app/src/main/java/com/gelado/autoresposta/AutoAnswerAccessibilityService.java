package com.gelado.autoresposta;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AutoAnswerAccessibilityService extends AccessibilityService {
    public static volatile boolean isRunning = false;
    private long lastClickAt = 0L;
    private String lastClickedText = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        isRunning = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        boolean autoClick = prefs.getBoolean(MainActivity.KEY_AUTO_CLICK, false);
        String profile = prefs.getString(MainActivity.KEY_PROFILE, "");

        if (!autoClick || profile == null || profile.trim().isEmpty()) return;
        if (SystemClock.elapsedRealtime() - lastClickAt < 1800) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<Candidate> candidates = new ArrayList<>();
        collect(root, candidates);

        Candidate best = chooseBest(profile, candidates);
        if (best == null || best.score < 2) return;

        String normalizedBest = normalize(best.text);
        if (normalizedBest.equals(lastClickedText)
                && SystemClock.elapsedRealtime() - lastClickAt < 8000) return;

        if (clickNodeOrParent(best.node)) {
            lastClickAt = SystemClock.elapsedRealtime();
            lastClickedText = normalizedBest;
        }
    }

    private void collect(AccessibilityNodeInfo node, List<Candidate> out) {
        if (node == null) return;

        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();

        String label = text != null ? text.toString().trim() : "";
        if (label.isEmpty() && description != null) label = description.toString().trim();

        if (!label.isEmpty() && isPotentialAnswer(node)) out.add(new Candidate(node, label));

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collect(child, out);
        }
    }

    private boolean isPotentialAnswer(AccessibilityNodeInfo node) {
        if (node.isClickable() || node.isCheckable()) return true;
        AccessibilityNodeInfo parent = node.getParent();
        int hops = 0;
        while (parent != null && hops < 3) {
            if (parent.isClickable() || parent.isCheckable()) return true;
            parent = parent.getParent();
            hops++;
        }
        return false;
    }

    private Candidate chooseBest(String profile, List<Candidate> candidates) {
        Set<String> profileWords = meaningfulWords(profile);
        Candidate best = null;
        int secondBest = Integer.MIN_VALUE;

        for (Candidate c : candidates) {
            Set<String> answerWords = meaningfulWords(c.text);
            int score = 0;

            for (String word : answerWords) {
                if (profileWords.contains(word)) score += 2;
            }

            String p = normalize(profile);
            String a = normalize(c.text);
            if (a.length() >= 4 && p.contains(a)) score += 4;

            c.score = score;

            if (best == null || score > best.score) {
                if (best != null) secondBest = best.score;
                best = c;
            } else if (score > secondBest) {
                secondBest = score;
            }
        }

        if (best == null) return null;
        if (best.score > 0 && best.score == secondBest) return null;
        return best;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int hops = 0;
        while (current != null && hops < 4) {
            if (current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
            hops++;
        }
        return false;
    }

    private Set<String> meaningfulWords(String input) {
        Set<String> result = new HashSet<>();
        String[] parts = normalize(input).split("[^a-z0-9]+");
        for (String part : parts) {
            if (part.length() >= 3 && !isStopWord(part)) result.add(part);
        }
        return result;
    }

    private boolean isStopWord(String word) {
        return word.equals("que") || word.equals("com") || word.equals("para")
                || word.equals("uma") || word.equals("das") || word.equals("dos")
                || word.equals("por") || word.equals("mais") || word.equals("meu")
                || word.equals("minha");
    }

    private String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }

    private static class Candidate {
        final AccessibilityNodeInfo node;
        final String text;
        int score;
        Candidate(AccessibilityNodeInfo node, String text) {
            this.node = node;
            this.text = text;
        }
    }
}
