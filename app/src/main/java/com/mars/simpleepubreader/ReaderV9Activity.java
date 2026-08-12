package com.mars.simpleepubreader;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

public class ReaderV9Activity extends ReaderV8Activity {

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

        bar.addView(menuItem("Chapter", v -> {
            save();
            chooseChapter();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f));

        bar.addView(menuItem("Settings", v -> {
            save();
            showSettings();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f));

        bar.addView(menuItem("Exit", v -> exitReader()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.85f));
        return bar;
    }

    void chooseChapter() {
        if (spine.isEmpty()) {
            home("No book open.");
            return;
        }
        final String[] labels = new String[spine.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = chapterLabel(i);
        final int current = Math.max(0, Math.min(chap, labels.length - 1));

        new AlertDialog.Builder(this)
                .setTitle("Chapter")
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    dialog.dismiss();
                    show(which, 0);
                    save();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    String chapterLabel(int index) {
        String title = "";
        ChapterData ch = chapterAt(index);
        if (ch != null) title = chapterSnippet(ch, 18);
        if (title.length() == 0) {
            try {
                String n = spine.get(index).getName();
                int dot = n.lastIndexOf('.');
                if (dot > 0) n = n.substring(0, dot);
                title = n;
            } catch (Throwable ignored) {}
        }
        if (title.length() == 0) title = "Chapter";
        return (index + 1) + "  " + title;
    }

    String chapterSnippet(ChapterData ch, int maxCp) {
        if (ch == null) return "";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = 0; i < ch.text.length() && count < maxCp;) {
            int cp = Character.codePointAt(ch.text, i);
            i += Character.charCount(cp);
            if (cp == IMAGE || Character.isWhitespace(cp) || cp == 0x3000) continue;
            out.appendCodePoint(cp);
            count++;
        }
        String s = out.toString().trim();
        if ("cover".equalsIgnoreCase(s)) return "Cover";
        return s;
    }

    @Override
    void quickMenu() {
        final String[] items = {"Open book", "Chapter", "Settings", "Exit"};
        new AlertDialog.Builder(this)
                .setTitle("Reader")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) pick();
                    else if (which == 1) chooseChapter();
                    else if (which == 2) showSettings();
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
            prepareChapterSlots();
            ensureChapterLoaded(chap);
            ReaderV9 r = new ReaderV9(this, chap, off);
            reader = r;
            setContentView(shell(r));
            startBackgroundIndex(r, chap);
        } catch (Throwable e) {
            home("Could not read book: " + err(e));
        }
    }

    class ReaderV9 extends ReaderV8 {
        ReaderV9(Context c, int chapter, int offset) {
            super(c, chapter, offset);
        }

        boolean bangQuestion(int cp) {
            return cp == '!' || cp == '?' || cp == 0xFF01 || cp == 0xFF1F;
        }

        boolean tcyPunctuationPairAt(ChapterData ch, int pos) {
            if (ch == null || pos < 0 || pos >= ch.text.length()) return false;
            int a = Character.codePointAt(ch.text, pos);
            if (!bangQuestion(a)) return false;
            int p = pos + Character.charCount(a);
            if (p >= ch.text.length()) return false;
            int b = Character.codePointAt(ch.text, p);
            return bangQuestion(b);
        }

        int unitEnd(ChapterData ch, int pos) {
            int cp = Character.codePointAt(ch.text, pos);
            int end = pos + Character.charCount(cp);
            if (bangQuestion(cp) && tcyPunctuationPairAt(ch, pos)) {
                int b = Character.codePointAt(ch.text, end);
                end += Character.charCount(b);
            }
            return end;
        }

        int unitFirstCp(ChapterData ch, int pos) {
            return Character.codePointAt(ch.text, pos);
        }

        boolean blockImageAt(ChapterData ch, int pos) {
            if (ch == null || pos < 0 || pos >= ch.text.length()) return false;
            if (ch.text.charAt(pos) != IMAGE) return false;
            File f = ch.images.get(pos);
            return f != null && !isInlineImage(f);
        }

        boolean cannotStartColumn(int cp) {
            String s = "、。，．・：；？！!?‼⁇⁈⁉…‥ー―‐‑–—〜～"
                    + "）〕］｝〉》」』】〙〗〟’”｠»"
                    + "ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ"
                    + "々〻ゝゞヽヾ";
            return s.indexOf(cp) >= 0;
        }

        boolean cannotEndColumn(int cp) {
            String s = "（〔［｛〈《「『【〘〖〝‘“｟«";
            return s.indexOf(cp) >= 0;
        }

        int columnEnd(ChapterData ch, int start, int rows) {
            int len = ch.text.length();
            int pos = start;
            ArrayList<Integer> units = new ArrayList<>();
            boolean explicitBreak = false;

            while (pos < len && units.size() < rows) {
                if (blockImageAt(ch, pos)) break;
                int cp = Character.codePointAt(ch.text, pos);
                if (cp == '\r') {
                    pos += Character.charCount(cp);
                    continue;
                }
                if (cp == '\n') {
                    pos += Character.charCount(cp);
                    explicitBreak = true;
                    break;
                }
                units.add(pos);
                pos = unitEnd(ch, pos);
            }

            if (units.isEmpty()) return pos;
            if (explicitBreak || units.size() < rows || pos >= len || blockImageAt(ch, pos)) {
                return pos;
            }

            int end = pos;

            // Do not leave an opening bracket at the bottom of a vertical column.
            while (units.size() > 1) {
                int last = units.get(units.size() - 1);
                if (!cannotEndColumn(unitFirstCp(ch, last))) break;
                end = last;
                units.remove(units.size() - 1);
            }

            // If punctuation would become the first item in the next column,
            // carry the preceding character with it. This is simple kinsoku shori.
            if (units.size() > 1 && end < len && !blockImageAt(ch, end)) {
                int next = Character.codePointAt(ch.text, end);
                if (next != '\n' && next != '\r' && cannotStartColumn(next)) {
                    int moved = units.get(units.size() - 1);
                    end = moved;
                    units.remove(units.size() - 1);
                    while (units.size() > 1) {
                        int last = units.get(units.size() - 1);
                        if (!cannotEndColumn(unitFirstCp(ch, last))) break;
                        end = last;
                        units.remove(units.size() - 1);
                    }
                }
            }
            return end;
        }

        @Override
        ArrayList<Page> paginate(int ci, ChapterData ch, int rows, int cols) {
            ArrayList<Page> out = new ArrayList<>();
            int pos = 0;
            int len = ch.text.length();

            if (len == 0) {
                Page p = new Page();
                p.chapter = ci;
                p.start = 0;
                p.end = 0;
                out.add(p);
                return out;
            }

            while (pos < len) {
                if (blockImageAt(ch, pos)) {
                    Page p = new Page();
                    p.chapter = ci;
                    p.start = pos;
                    p.end = pos + 1;
                    p.image = ch.images.get(pos);
                    out.add(p);
                    pos++;
                    continue;
                }

                int start = pos;
                int usedCols = 0;
                while (pos < len && usedCols < cols) {
                    if (blockImageAt(ch, pos)) break;
                    int end = columnEnd(ch, pos, rows);
                    if (end <= pos) break;
                    pos = end;
                    usedCols++;
                }

                if (pos == start) {
                    pos = Math.min(len, unitEnd(ch, pos));
                }

                Page p = new Page();
                p.chapter = ci;
                p.start = start;
                p.end = pos;
                out.add(p);
            }
            return out;
        }

        @Override
        void drawTextPage(Canvas c, Page page) {
            ChapterData ch = chapterAt(page.chapter);
            if (ch == null) return;
            RubyChapterData rch = ch instanceof RubyChapterData ? (RubyChapterData) ch : null;
            int rows = Math.max(1, (int)((getHeight() - top - footer - 12) / vCell));
            float x = getWidth() - right - fontSize;
            int pos = page.start;

            while (pos < page.end && x > left) {
                int end = Math.min(page.end, columnEnd(ch, pos, rows));
                if (end <= pos) break;
                float y = top + fontSize;
                int i = pos;

                while (i < end) {
                    int cp = Character.codePointAt(ch.text, i);
                    int n = Character.charCount(cp);
                    if (cp == '\r') {
                        i += n;
                        continue;
                    }
                    if (cp == '\n') {
                        i += n;
                        break;
                    }
                    if (cp == IMAGE) {
                        File image = ch.images.get(i);
                        if (image != null && isInlineImage(image)) drawInlineImageV6(c, image, x, y);
                        i += n;
                        y += vCell;
                        continue;
                    }

                    if (rch != null) {
                        RubySpan ruby = rch.rubyAt.get(i);
                        if (ruby != null) drawRuby(c, ruby, x, y);
                    }

                    if (tcyPunctuationPairAt(ch, i)) {
                        int secondPos = i + n;
                        int second = Character.codePointAt(ch.text, secondPos);
                        drawTcyPair(c, cp, second, x, y, textPaint);
                        i = secondPos + Character.charCount(second);
                    } else {
                        drawVerticalGlyphV6(c, cp, x, y, textPaint);
                        i += n;
                    }
                    y += vCell;
                }

                pos = end;
                x -= vColumn;
            }
        }

        void drawTcyPair(Canvas c, int a, int b, float x, float y, Paint base) {
            char ca = (a == 0xFF01 || a == '!') ? '!' : '?';
            char cb = (b == 0xFF01 || b == '!') ? '!' : '?';
            String pair = new String(new char[]{ca, cb});
            Paint p = new Paint(base);
            p.setTextSize(base.getTextSize() * 0.62f);
            p.setFakeBoldText(base.isFakeBoldText());
            float box = base.getTextSize();
            float w = p.measureText(pair);
            Paint.FontMetrics fm = p.getFontMetrics();
            float centerY = y - box * 0.50f;
            float baseline = centerY - (fm.ascent + fm.descent) * 0.5f;
            float px = x + (box - w) * 0.5f;
            c.drawText(pair, px, baseline, p);
        }

        boolean horizontalStroke(int cp) {
            return cp == 0x2010 || cp == 0x2011 || cp == 0x2012 || cp == 0x2013 ||
                    cp == 0x2014 || cp == 0x2015 || cp == 0x2212 || cp == 0xFF0D ||
                    cp == '-' || cp == 0x30FC || cp == 0x301C || cp == 0xFF5E ||
                    cp == 0x2500 || cp == 0x2501;
        }

        void drawVerticalDots(Canvas c, int count, float x, float y, Paint paint) {
            Paint dot = new Paint(paint);
            dot.setStyle(Paint.Style.FILL);
            float size = paint.getTextSize();
            float cx = x + size * 0.50f;
            float radius = Math.max(1.5f, size * 0.055f);
            if (count == 2) {
                c.drawCircle(cx, y - size * 0.64f, radius, dot);
                c.drawCircle(cx, y - size * 0.34f, radius, dot);
            } else {
                c.drawCircle(cx, y - size * 0.72f, radius, dot);
                c.drawCircle(cx, y - size * 0.50f, radius, dot);
                c.drawCircle(cx, y - size * 0.28f, radius, dot);
            }
        }

        @Override
        void drawVerticalGlyphV6(Canvas c, int cp, float x, float y, Paint paint) {
            if (cp == 0x2026) {
                String v = verticalForm(cp);
                try {
                    if (v != null && paint.hasGlyph(v)) {
                        c.drawText(v, x, y, paint);
                        return;
                    }
                } catch (Throwable ignored) {}
                drawVerticalDots(c, 3, x, y, paint);
                return;
            }
            if (cp == 0x2025) {
                String v = verticalForm(cp);
                try {
                    if (v != null && paint.hasGlyph(v)) {
                        c.drawText(v, x, y, paint);
                        return;
                    }
                } catch (Throwable ignored) {}
                drawVerticalDots(c, 2, x, y, paint);
                return;
            }

            if (bangQuestion(cp)) {
                int full = (cp == '?' || cp == 0xFF1F) ? 0xFF1F : 0xFF01;
                String v = verticalForm(full);
                try {
                    if (v != null && paint.hasGlyph(v)) {
                        c.drawText(v, x, y, paint);
                        return;
                    }
                } catch (Throwable ignored) {}
                // Single ! and ? remain upright in Japanese vertical composition.
                String g = new String(Character.toChars(full));
                c.drawText(g, x, y, paint);
                return;
            }

            String v = verticalForm(cp);
            if (v != null) {
                try {
                    if (paint.hasGlyph(v)) {
                        c.drawText(v, x, y, paint);
                        return;
                    }
                } catch (Throwable ignored) {}
            }

            String g = new String(Character.toChars(cp));
            if (cornerPunctuation(cp)) {
                c.drawText(g, x + paint.getTextSize() * 0.24f,
                        y - paint.getTextSize() * 0.28f, paint);
                return;
            }

            if (horizontalStroke(cp) || v != null || rotateInVertical(cp)) {
                c.save();
                float size = paint.getTextSize();
                c.rotate(90, x + size * 0.43f, y - size * 0.43f);
                c.drawText(g, x, y, paint);
                c.restore();
                return;
            }
            c.drawText(g, x, y, paint);
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

            float step = rubyPaint.getTextSize() * 1.10f;
            float baseSpan = Math.max(fontSize, (baseCells - 1) * vCell + fontSize);
            float maxForBase = rubyCells <= 1 ? step :
                    Math.max(rubyPaint.getTextSize() * 0.76f,
                            (baseSpan - rubyPaint.getTextSize()) / (rubyCells - 1));
            step = Math.min(step, maxForBase);

            Paint.FontMetrics bfm = textPaint.getFontMetrics();
            Paint.FontMetrics rfm = rubyPaint.getFontMetrics();
            float firstBaseCenter = y + (bfm.ascent + bfm.descent) * 0.5f;
            float baseCenter = firstBaseCenter + (baseCells - 1) * vCell * 0.5f;
            float rubyCenterOffset = (rfm.ascent + rfm.descent) * 0.5f;
            float ry = baseCenter - rubyCenterOffset - (rubyCells - 1) * step * 0.5f;

            // Clamp the complete ruby run to the visible reading area.
            float minTop = top + 2f;
            float maxBottom = getHeight() - footer - 8f;
            if (rubyCells > 1) {
                float available = maxBottom - minTop - (rfm.descent - rfm.ascent);
                if (available > 0) step = Math.min(step, available / (rubyCells - 1));
                ry = baseCenter - rubyCenterOffset - (rubyCells - 1) * step * 0.5f;
            }
            float runTop = ry + rfm.ascent;
            float runBottom = ry + (rubyCells - 1) * step + rfm.descent;
            if (runTop < minTop) ry += minTop - runTop;
            runBottom = ry + (rubyCells - 1) * step + rfm.descent;
            if (runBottom > maxBottom) ry -= runBottom - maxBottom;

            // Keep ruby on the conventional outer/right side, but never clip it off-screen.
            float rx = x + fontSize * 0.92f;
            float widest = 0f;
            for (int i = 0; i < ruby.text.length();) {
                int cp = ruby.text.codePointAt(i);
                String g = new String(Character.toChars(cp));
                widest = Math.max(widest, rubyPaint.measureText(g));
                i += Character.charCount(cp);
            }
            float maxX = getWidth() - 2f - Math.max(widest, rubyPaint.getTextSize() * 0.65f);
            rx = Math.min(rx, maxX);
            rx = Math.max(x + fontSize * 0.58f, rx);

            for (int i = 0; i < ruby.text.length();) {
                int cp = ruby.text.codePointAt(i);
                drawVerticalGlyphV6(c, cp, rx, ry, rubyPaint);
                i += Character.charCount(cp);
                ry += step;
            }
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

            String footerText = "Ch " + (page.chapter + 1) +
                    "  " + page.chapterPage + "/" + page.chapterPages;
            if (allChaptersLoaded()) {
                footerText += "   Book " + page.bookPage + "/" + page.bookPages;
            } else {
                footerText += "   Book …";
            }
            c.drawText(footerText, 10, getHeight() - 8, footerPaint);
        }
    }
}
