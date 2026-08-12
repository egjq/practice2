package com.mars.simpleepubreader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.*;
import android.view.*;
import java.io.*;
import java.util.*;

public class ReaderV8Activity extends ReaderV7Activity {
    static final String SETTINGS = "reader_settings";
    volatile int indexGeneration = 0;

    SharedPreferences settings() {
        return getSharedPreferences(SETTINGS, MODE_PRIVATE);
    }

    @Override
    void showFontDialog() {
        final ArrayList<FontChoice> choices = fontChoices();
        String selected = settings().getString("font", FONT_AUTO);
        int checked = 0;
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).label;
            if (choices.get(i).value.equals(selected)) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Reading font")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    FontChoice choice = choices.get(which);
                    settings().edit().putString("font", choice.value).commit();
                    Page here = reader == null ? null : reader.current();
                    dialog.dismiss();
                    if (here != null) show(here.chapter, here.start);
                    else home("Font: " + choice.label);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    String currentFontLabel() {
        String v = settings().getString("font", FONT_AUTO);
        if (FONT_AUTO.equals(v)) return "Auto Mincho";
        if (FONT_SERIF.equals(v)) return "Android serif";
        File f = new File(v);
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    @Override
    Typeface minchoTypeface() {
        String selected = settings().getString("font", FONT_AUTO);
        if (FONT_SERIF.equals(selected)) return Typeface.SERIF;
        if (!FONT_AUTO.equals(selected)) {
            try {
                File f = new File(selected);
                if (f.isFile()) return Typeface.createFromFile(f);
            } catch (Throwable ignored) {}
        }
        return MainActivity.super.minchoTypeface();
    }

    void prepareChapterSlots() {
        synchronized (chapters) {
            if (chapters.size() == spine.size()) return;
            chapters.clear();
            for (int i = 0; i < spine.size(); i++) chapters.add(null);
        }
    }

    ChapterData chapterAt(int i) {
        synchronized (chapters) {
            if (i < 0 || i >= chapters.size()) return null;
            return chapters.get(i);
        }
    }

    ChapterData ensureChapterLoaded(int i) throws Exception {
        prepareChapterSlots();
        ChapterData existing = chapterAt(i);
        if (existing != null) return existing;

        File bookBase = new File(root, "x").getCanonicalFile();
        ChapterData parsed = parseChapter(spine.get(i), bookBase);
        synchronized (chapters) {
            ChapterData again = chapters.get(i);
            if (again != null) return again;
            chapters.set(i, parsed);
            return parsed;
        }
    }

    boolean allChaptersLoaded() {
        synchronized (chapters) {
            if (chapters.size() != spine.size()) return false;
            for (ChapterData ch : chapters) if (ch == null) return false;
            return true;
        }
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
            prepareChapterSlots();
            ensureChapterLoaded(chap);
            ReaderV8 r = new ReaderV8(this, chap, off);
            reader = r;
            setContentView(shell(r));
            startBackgroundIndex(r, chap);
        } catch (Throwable e) {
            home("Could not read book: " + err(e));
        }
    }

    void startBackgroundIndex(final ReaderV8 target, final int around) {
        final int generation = ++indexGeneration;
        new Thread(() -> {
            try {
                for (int distance = 1; distance < spine.size(); distance++) {
                    if (generation != indexGeneration) return;
                    int after = around + distance;
                    if (after < spine.size() && chapterAt(after) == null) ensureChapterLoaded(after);
                    if (generation != indexGeneration) return;
                    int before = around - distance;
                    if (before >= 0 && chapterAt(before) == null) ensureChapterLoaded(before);
                }
                if (generation != indexGeneration) return;
                runOnUiThread(() -> {
                    if (reader == target && target.ready()) {
                        Page p = target.current();
                        if (p != null) target.rebuildAt(p.chapter, p.start);
                    }
                });
            } catch (Throwable ignored) {}
        }).start();
    }

    @Override
    void openBrowserBook(File f) {
        try {
            File selected = f.getCanonicalFile();
            String last = getPreferences(0).getString("last_source", "");
            File list = new File(root, "spine.txt");
            if (last.length() > 0 && selected.getPath().equals(new File(last).getCanonicalPath()) &&
                    list.isFile()) {
                save();
                browsing = false;
                if (spine.isEmpty()) loadList();
                int c = getPreferences(0).getInt("chap", 0);
                int o = getPreferences(0).getInt("off", 0);
                show(c, o);
                return;
            }
        } catch (Throwable ignored) {}
        super.openBrowserBook(f);
    }

    @Override
    boolean isCoverLike(File file, ChapterData ch) {
        if (super.isCoverLike(file, ch)) return true;
        try {
            int i = spine.indexOf(file);
            if (i == 0 && !ch.images.isEmpty()) {
                String visible = ch.text.toString().replace(String.valueOf(IMAGE), "")
                        .replaceAll("\\s+", "").toLowerCase(Locale.US);
                return visible.length() == 0 || "cover".equals(visible);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    class ReaderV8 extends ReaderV7 {
        int resumeChapter;
        int resumeOffset;

        ReaderV8(Context c, int chapter, int offset) {
            super(c, chapter, offset);
            resumeChapter = chapter;
            resumeOffset = offset;
            rubyPaint.setTextSize(Math.max(15f, fontSize * 0.40f));
            applyWeight(rubyPaint);
        }

        void rebuildAt(int chapter, int offset) {
            resumeChapter = chapter;
            resumeOffset = offset;
            buildPages(getWidth(), getHeight());
        }

        @Override
        void buildPages(int w, int h) {
            if (w <= 0 || h <= 0) return;
            pages.clear();
            currentIndex = -1;
            chapterStarts = new int[spine.size()];
            chapterCounts = new int[spine.size()];
            Arrays.fill(chapterStarts, -1);

            int rows = Math.max(1, (int)((h - top - footer - 12) / vCell));
            int cols = Math.max(1, (int)((w - left - right) / vColumn));

            for (int ci = 0; ci < spine.size(); ci++) {
                ChapterData ch = chapterAt(ci);
                if (ch == null) continue;
                chapterStarts[ci] = pages.size();
                ArrayList<Page> cp = paginate(ci, ch, rows, cols);
                chapterCounts[ci] = cp.size();
                for (int i = 0; i < cp.size(); i++) {
                    Page p = cp.get(i);
                    p.chapterPage = i + 1;
                    p.chapterPages = cp.size();
                    pages.add(p);
                }
            }

            boolean exact = allChaptersLoaded();
            int total = pages.size();
            for (int i = 0; i < total; i++) {
                Page p = pages.get(i);
                p.bookPage = exact ? i + 1 : 0;
                p.bookPages = exact ? total : 0;
            }

            if (pages.isEmpty()) {
                invalidate();
                return;
            }

            int fallback = Math.max(0, Math.min(resumeChapter, spine.size() - 1));
            int first = chapterStarts[fallback];
            if (first >= 0) currentIndex = first;
            else currentIndex = 0;

            for (int i = 0; i < pages.size(); i++) {
                Page p = pages.get(i);
                if (p.chapter != fallback) continue;
                if (p.image != null) {
                    if (resumeOffset == p.start) {
                        currentIndex = i;
                        break;
                    }
                } else if (resumeOffset >= p.start &&
                        resumeOffset < Math.max(p.end, p.start + 1)) {
                    currentIndex = i;
                    break;
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!ready()) {
                c.drawText("Loading…", 30, 50, footerPaint);
                return;
            }
            Page page = current();
            if (page.image != null) drawImage(c, page.image);
            else drawTextPage(c, page);

            String footerText = "Ch " + (page.chapter + 1) + "/" + spine.size() +
                    "  " + page.chapterPage + "/" + page.chapterPages;
            if (allChaptersLoaded()) {
                footerText += "   Book " + page.bookPage + "/" + page.bookPages;
            } else {
                footerText += "   Book …";
            }
            c.drawText(footerText, 10, getHeight() - 8, footerPaint);
        }

        @Override
        void drawRuby(Canvas c, RubySpan ruby, float x, float y) {
            if (ruby == null || ruby.text == null || ruby.text.length() == 0) return;
            Page cur = current();
            if (cur == null) return;
            ChapterData ch = chapterAt(cur.chapter);
            if (ch == null) return;
            int baseCells = codePointCount(ch.text, ruby.start, ruby.end);
            int rubyCells = ruby.text.codePointCount(0, ruby.text.length());
            if (baseCells <= 0 || rubyCells <= 0) return;

            float available = baseCells * vCell;
            float natural = rubyPaint.getTextSize() * 1.10f;
            float step = Math.min(natural, available / rubyCells);
            float used = step * rubyCells;
            float ry = y - (fontSize - rubyPaint.getTextSize()) * 0.18f +
                    Math.max(0, (available - used) / 2f);

            // Vertical Japanese ruby belongs on the outer/right side of the base text.
            // v7 moved it inward; v8 deliberately moves it farther outward instead.
            float rx = x + fontSize * 0.92f;

            for (int i = 0; i < ruby.text.length();) {
                int cp = ruby.text.codePointAt(i);
                drawVerticalGlyphV6(c, cp, rx, ry, rubyPaint);
                i += Character.charCount(cp);
                ry += step;
            }
        }

        int firstPageOf(int chapter) {
            for (int i = 0; i < pages.size(); i++) if (pages.get(i).chapter == chapter) return i;
            return -1;
        }

        int lastPageOf(int chapter) {
            for (int i = pages.size() - 1; i >= 0; i--) if (pages.get(i).chapter == chapter) return i;
            return -1;
        }

        @Override
        boolean next() {
            if (!ready()) return false;
            Page cur = current();
            if (currentIndex + 1 < pages.size()) {
                Page n = pages.get(currentIndex + 1);
                if (n.chapter == cur.chapter || n.chapter == cur.chapter + 1) {
                    currentIndex++;
                    recycleBitmap();
                    chap = n.chapter;
                    resumeChapter = n.chapter;
                    resumeOffset = n.start;
                    invalidate();
                    return true;
                }
            }
            int nextChapter = cur.chapter + 1;
            if (nextChapter >= spine.size()) return false;
            try {
                ensureChapterLoaded(nextChapter);
                rebuildAt(nextChapter, 0);
                int p = firstPageOf(nextChapter);
                if (p >= 0) currentIndex = p;
                Page n = current();
                if (n != null) {
                    chap = n.chapter;
                    resumeChapter = n.chapter;
                    resumeOffset = n.start;
                }
                invalidate();
                return p >= 0;
            } catch (Throwable e) {
                return false;
            }
        }

        @Override
        boolean prev() {
            if (!ready()) return false;
            Page cur = current();
            if (currentIndex > 0) {
                Page p = pages.get(currentIndex - 1);
                if (p.chapter == cur.chapter || p.chapter == cur.chapter - 1) {
                    currentIndex--;
                    recycleBitmap();
                    chap = p.chapter;
                    resumeChapter = p.chapter;
                    resumeOffset = p.start;
                    invalidate();
                    return true;
                }
            }
            int prevChapter = cur.chapter - 1;
            if (prevChapter < 0) return false;
            try {
                ensureChapterLoaded(prevChapter);
                rebuildAt(prevChapter, 0);
                int p = lastPageOf(prevChapter);
                if (p >= 0) currentIndex = p;
                Page n = current();
                if (n != null) {
                    chap = n.chapter;
                    resumeChapter = n.chapter;
                    resumeOffset = n.start;
                }
                invalidate();
                return p >= 0;
            } catch (Throwable e) {
                return false;
            }
        }
    }
}
