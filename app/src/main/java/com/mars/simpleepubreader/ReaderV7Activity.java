package com.mars.simpleepubreader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;

public class ReaderV7Activity extends ReaderV6Activity {

    @Override
    LinearLayout topMenu() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xfff5f5f5);
        bar.setPadding(dp(2), 0, dp(2), 0);

        bar.addView(menuItem("Open", v -> {
            save();
            pick();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        bar.addView(menuItem("Settings", v -> {
            save();
            showSettings();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        bar.addView(menuItem("Exit", v -> exitReader()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return bar;
    }

    void showSettings() {
        final String[] items = {"Font", "Font size", "Font weight"};
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) chooseFont();
                    else if (which == 1) chooseFontSize();
                    else chooseWeight();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    void exitReader() {
        save();
        try {
            finishAndRemoveTask();
        } catch (Throwable e) {
            finish();
        }
    }

    @Override
    void quickMenu() {
        final String[] items = {"Open book", "Settings", "Exit"};
        new AlertDialog.Builder(this)
                .setTitle("Reader")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) pick();
                    else if (which == 1) showSettings();
                    else exitReader();
                })
                .show();
    }

    @Override
    void show(int c, int off) {
        if (spine.isEmpty()) {
            home("No readable chapters.");
            return;
        }
        browsing = false;
        chap = Math.max(0, Math.min(c, spine.size() - 1));
        try {
            ensureChapters();
            reader = new ReaderV7(this, chap, off);
            setContentView(shell(reader));
        } catch (Throwable e) {
            home("Could not read book: " + err(e));
        }
    }

    @Override
    ChapterData parseChapter(File file, File bookBase) throws Exception {
        ChapterData out = super.parseChapter(file, bookBase);
        if (isCoverLike(file, out)) removeCoverLabel(out);
        return out;
    }

    boolean isCoverLike(File file, ChapterData ch) {
        try {
            if (file != null && file.getName().toLowerCase(Locale.US).contains("cover")) return true;
            for (File image : ch.images.values()) {
                if (image != null && image.getName().toLowerCase(Locale.US).contains("cover")) return true;
            }
            String visible = ch.text.toString().replace(String.valueOf(IMAGE), "")
                    .replaceAll("\\s+", "").toLowerCase(Locale.US);
            return chapters.isEmpty() && !ch.images.isEmpty() &&
                    (visible.length() == 0 || "cover".equals(visible));
        } catch (Throwable e) {
            return false;
        }
    }

    void removeCoverLabel(ChapterData ch) {
        Pattern p = Pattern.compile("(?i)(?<![A-Za-z])cover(?![A-Za-z])");
        Matcher m = p.matcher(ch.text.toString());
        ArrayList<int[]> ranges = new ArrayList<>();
        while (m.find()) ranges.add(new int[]{m.start(), m.end()});
        for (int i = ranges.size() - 1; i >= 0; i--) {
            deleteChapterRange(ch, ranges.get(i)[0], ranges.get(i)[1]);
        }
        while (ch.text.length() > 0) {
            char x = ch.text.charAt(ch.text.length() - 1);
            if (x != ' ' && x != '\n') break;
            ch.text.setLength(ch.text.length() - 1);
        }
    }

    void deleteChapterRange(ChapterData ch, int start, int end) {
        if (start < 0 || end <= start || end > ch.text.length()) return;
        int delta = end - start;
        ch.text.delete(start, end);

        HashMap<Integer, File> moved = new HashMap<>();
        for (Map.Entry<Integer, File> e : ch.images.entrySet()) {
            int k = e.getKey();
            if (k < start) moved.put(k, e.getValue());
            else if (k >= end) moved.put(k - delta, e.getValue());
        }
        ch.images = moved;

        if (ch instanceof RubyChapterData) {
            RubyChapterData rch = (RubyChapterData) ch;
            ArrayList<RubySpan> keep = new ArrayList<>();
            rch.rubyAt.clear();
            for (RubySpan r : rch.ruby) {
                if (r.end <= start) {
                    keep.add(r);
                } else if (r.start >= end) {
                    r.start -= delta;
                    r.end -= delta;
                    keep.add(r);
                }
            }
            rch.ruby = keep;
            for (RubySpan r : keep) rch.rubyAt.put(r.start, r);
        }
    }

    boolean isCoverImage(Page page, File image) {
        try {
            if (image != null && image.getName().toLowerCase(Locale.US).contains("cover")) return true;
            if (page == null || page.chapter < 0 || page.chapter >= chapters.size()) return false;
            ChapterData ch = chapters.get(page.chapter);
            if (ch.file != null && ch.file.getName().toLowerCase(Locale.US).contains("cover")) return true;
            if (page.chapter == 0 && !ch.images.isEmpty()) {
                String visible = ch.text.toString().replace(String.valueOf(IMAGE), "")
                        .replaceAll("\\s+", "");
                return visible.length() == 0;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    class ReaderV7 extends ReaderV6 {
        ReaderV7(Context c, int chapter, int offset) {
            super(c, chapter, offset);
            rubyPaint.setTextSize(Math.max(15f, fontSize * 0.38f));
            applyWeight(rubyPaint);
        }

        @Override
        void drawRuby(Canvas c, RubySpan ruby, float x, float y) {
            if (ruby == null || ruby.text == null || ruby.text.length() == 0) return;
            Page cur = current();
            if (cur == null) return;
            int baseCells = codePointCount(chapters.get(cur.chapter).text, ruby.start, ruby.end);
            int rubyCells = ruby.text.codePointCount(0, ruby.text.length());
            if (baseCells <= 0 || rubyCells <= 0) return;

            float available = baseCells * vCell;
            float natural = rubyPaint.getTextSize() * 1.10f;
            float step = Math.min(natural, available / rubyCells);
            float used = step * rubyCells;
            float ry = y - (fontSize - rubyPaint.getTextSize()) * 0.18f +
                    Math.max(0, (available - used) / 2f);

            // Keep furigana tight to its base column.  The previous 0.72 offset
            // put long readings too close to the next (right-hand) text column.
            float rx = x + fontSize * 0.61f;

            for (int i = 0; i < ruby.text.length();) {
                int cp = ruby.text.codePointAt(i);
                drawVerticalGlyphV6(c, cp, rx, ry, rubyPaint);
                i += Character.charCount(cp);
                ry += step;
            }
        }

        @Override
        void drawImage(Canvas c, File image) {
            Page page = current();
            if (!isCoverImage(page, image)) {
                super.drawImage(c, image);
                return;
            }

            Bitmap b = loadBitmapForScreen(image);
            if (b == null) {
                c.drawText("Image not supported", getWidth() / 2f, getHeight() / 2f,
                        imageLabelPaint);
                return;
            }

            float maxW = Math.max(1f, getWidth() - 8f);
            float maxH = Math.max(1f, getHeight() - footer - 8f);
            float scale = Math.min(maxW / b.getWidth(), maxH / b.getHeight());
            float dw = b.getWidth() * scale;
            float dh = b.getHeight() * scale;
            float l = (getWidth() - dw) / 2f;
            float t = 4f + (maxH - dh) / 2f;
            c.drawBitmap(b, null, new RectF(l, t, l + dw, t + dh),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
        }
    }
}
