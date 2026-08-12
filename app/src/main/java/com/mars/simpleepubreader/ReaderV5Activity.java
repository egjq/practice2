package com.mars.simpleepubreader;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

public class ReaderV5Activity extends MainActivity {
    static final int FONT_STORAGE = 9;
    static final String FONT_AUTO = "__AUTO__";
    static final String FONT_SERIF = "__SERIF__";
    boolean pendingFontPicker = false;

    static class FontChoice {
        String label;
        String value;
        FontChoice(String l, String v) { label = l; value = v; }
    }

    @Override
    void home(String note) {
        reader = null;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(30, 30, 30, 30);
        l.setBackgroundColor(Color.WHITE);

        TextView t = new TextView(this);
        t.setText("Simple EPUB Reader\nLikebook Mars · Android 6" +
                (note == null ? "" : "\n\n" + note));
        t.setTextColor(Color.BLACK);
        t.setTextSize(21);
        t.setGravity(Gravity.CENTER);
        l.addView(t);

        Button open = new Button(this);
        open.setText("OPEN EPUB");
        open.setTextSize(18);
        open.setOnClickListener(v -> pick());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(300, 72);
        op.setMargins(0, 34, 0, 0);
        l.addView(open, op);

        Button font = new Button(this);
        font.setText("FONT: " + currentFontLabel());
        font.setTextSize(16);
        font.setOnClickListener(v -> chooseFont());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(440, 70);
        fp.setMargins(0, 18, 0, 0);
        l.addView(font, fp);

        TextView hint = new TextView(this);
        hint.setText("Fonts: Internal storage/fonts\nWhile reading: Menu key or center tap = font selector");
        hint.setTextColor(0xff555555);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.setMargins(0, 18, 0, 0);
        l.addView(hint, hp);

        setContentView(l);
    }

    @Override
    void show(int c, int off) {
        if (spine.isEmpty()) {
            home("No readable chapters.");
            return;
        }
        chap = Math.max(0, Math.min(c, spine.size() - 1));
        try {
            ensureChapters();
            reader = new ReaderV5(this, chap, off);
            setContentView(reader);
        } catch (Throwable e) {
            home("Could not read book: " + err(e));
        }
    }

    void chooseFont() {
        if (!canReadStorage()) {
            pendingFontPicker = true;
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, FONT_STORAGE);
            return;
        }
        showFontDialog();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        if (request == FONT_STORAGE) {
            pendingFontPicker = false;
            showFontDialog();
            return;
        }
        super.onRequestPermissionsResult(request, permissions, grants);
    }

    void showFontDialog() {
        final ArrayList<FontChoice> choices = fontChoices();
        String selected = getPreferences(0).getString("font", FONT_AUTO);
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
                    getPreferences(0).edit().putString("font", choice.value).apply();
                    Page here = reader == null ? null : reader.current();
                    dialog.dismiss();
                    if (here != null) {
                        show(here.chapter, here.start);
                    } else {
                        home("Font: " + choice.label);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    ArrayList<FontChoice> fontChoices() {
        ArrayList<FontChoice> out = new ArrayList<>();
        out.add(new FontChoice("Auto Mincho / Japanese serif", FONT_AUTO));
        out.add(new FontChoice("Android serif", FONT_SERIF));

        ArrayList<File> files = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        ArrayList<File> dirs = new ArrayList<>();
        try { dirs.add(new File(Environment.getExternalStorageDirectory(), "fonts")); } catch (Throwable ignored) {}
        dirs.add(new File("/sdcard/fonts"));
        dirs.add(new File("/storage/emulated/0/fonts"));
        dirs.add(new File("/mnt/sdcard/fonts"));

        for (File dir : dirs) {
            try {
                File[] list = dir.listFiles();
                if (list == null) continue;
                for (File f : list) {
                    if (!f.isFile()) continue;
                    String n = f.getName().toLowerCase(Locale.US);
                    if (!(n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc"))) continue;
                    String path = f.getCanonicalPath();
                    if (seen.add(path)) files.add(f);
                }
            } catch (Throwable ignored) {}
        }

        Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            out.add(new FontChoice(name, f.getAbsolutePath()));
        }
        return out;
    }

    String currentFontLabel() {
        String v = getPreferences(0).getString("font", FONT_AUTO);
        if (FONT_AUTO.equals(v)) return "Auto Mincho";
        if (FONT_SERIF.equals(v)) return "Android serif";
        File f = new File(v);
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    @Override
    Typeface minchoTypeface() {
        String selected = getPreferences(0).getString("font", FONT_AUTO);
        if (FONT_SERIF.equals(selected)) return Typeface.SERIF;
        if (!FONT_AUTO.equals(selected)) {
            try {
                File f = new File(selected);
                if (f.isFile()) return Typeface.createFromFile(f);
            } catch (Throwable ignored) {}
        }
        return super.minchoTypeface();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            chooseFont();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    class ReaderV5 extends Reader {
        HashMap<String, Bitmap> inlineBitmaps = new HashMap<>();

        ReaderV5(Context c, int chapter, int offset) {
            super(c, chapter, offset);
        }

        boolean isInlineImage(File f) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (o.outWidth <= 0 || o.outHeight <= 0) return false;
                int max = Math.max(o.outWidth, o.outHeight);
                long area = (long)o.outWidth * (long)o.outHeight;
                return max <= 200 && area <= 30000;
            } catch (Throwable e) {
                return false;
            }
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
                File leading = ch.images.get(pos);
                if (ch.text.charAt(pos) == IMAGE && leading != null && !isInlineImage(leading)) {
                    Page p = new Page();
                    p.chapter = ci;
                    p.start = pos;
                    p.end = pos + 1;
                    p.image = leading;
                    out.add(p);
                    pos++;
                    continue;
                }

                int start = pos;
                int row = 0;
                int col = 0;

                while (pos < len && col < cols) {
                    File image = ch.images.get(pos);
                    if (ch.text.charAt(pos) == IMAGE && image != null) {
                        if (!isInlineImage(image)) break;
                        pos++;
                        row++;
                        if (row >= rows) {
                            row = 0;
                            col++;
                        }
                        continue;
                    }

                    int cp = Character.codePointAt(ch.text, pos);
                    int n = Character.charCount(cp);
                    if (cp == '\r') {
                        pos += n;
                        continue;
                    }
                    if (cp == '\n') {
                        pos += n;
                        col++;
                        row = 0;
                        continue;
                    }

                    pos += n;
                    row++;
                    if (row >= rows) {
                        row = 0;
                        col++;
                    }
                }

                if (pos == start) {
                    pos++;
                    continue;
                }

                Page p = new Page();
                p.chapter = ci;
                p.start = start;
                p.end = pos;
                out.add(p);
            }

            if (out.isEmpty()) {
                Page p = new Page();
                p.chapter = ci;
                p.start = 0;
                p.end = 0;
                out.add(p);
            }
            return out;
        }

        @Override
        void drawTextPage(Canvas c, Page page) {
            ChapterData ch = chapters.get(page.chapter);
            float x = getWidth() - right - 46;
            float y = top + 46;
            float bottom = getHeight() - footer - 8;

            int i = page.start;
            while (i < page.end && x > left) {
                int cp = Character.codePointAt(ch.text, i);
                int n = Character.charCount(cp);

                if (cp == '\r') {
                    i += n;
                    continue;
                }
                if (cp == '\n') {
                    i += n;
                    x -= column;
                    y = top + 46;
                    continue;
                }
                if (cp == IMAGE) {
                    File image = ch.images.get(i);
                    if (image != null && isInlineImage(image)) drawInlineImage(c, image, x, y);
                    i += n;
                    y += cell;
                    if (y > bottom) {
                        x -= column;
                        y = top + 46;
                    }
                    continue;
                }

                drawVerticalGlyph(c, cp, x, y);
                i += n;
                y += cell;
                if (y > bottom) {
                    x -= column;
                    y = top + 46;
                }
            }
        }

        void drawVerticalGlyph(Canvas c, int cp, float x, float y) {
            String vertical = null;
            if (cp == 0x300C) vertical = "\uFE41";      // 「
            else if (cp == 0x300D) vertical = "\uFE42"; // 」
            else if (cp == 0x300E) vertical = "\uFE43"; // 『
            else if (cp == 0x300F) vertical = "\uFE44"; // 』

            if (vertical != null) {
                boolean has = false;
                try { has = textPaint.hasGlyph(vertical); } catch (Throwable ignored) {}
                if (has) {
                    c.drawText(vertical, x, y, textPaint);
                    return;
                }
                String original = new String(Character.toChars(cp));
                c.save();
                c.rotate(90, x + 20, y - 20);
                c.drawText(original, x, y, textPaint);
                c.restore();
                return;
            }

            String g = new String(Character.toChars(cp));
            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') ||
                    (cp >= '0' && cp <= '9')) {
                c.save();
                c.rotate(90, x + 20, y - 20);
                c.drawText(g, x, y, textPaint);
                c.restore();
            } else {
                c.drawText(g, x, y, textPaint);
            }
        }

        void drawInlineImage(Canvas c, File image, float x, float baselineY) {
            Bitmap b = inlineBitmap(image);
            if (b == null) return;
            float box = Math.min(cell - 6, 52);
            float scale = Math.min(box / b.getWidth(), box / b.getHeight());
            float w = b.getWidth() * scale;
            float h = b.getHeight() * scale;
            float l = x + 22 - w / 2f;
            float t = baselineY - 43 + (box - h) / 2f;
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(b, null, new RectF(l, t, l + w, t + h), p);
        }

        Bitmap inlineBitmap(File f) {
            String path = f.getAbsolutePath();
            Bitmap cached = inlineBitmaps.get(path);
            if (cached != null && !cached.isRecycled()) return cached;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap b = BitmapFactory.decodeFile(path, o);
                if (b != null) inlineBitmaps.put(path, b);
                return b;
            } catch (Throwable e) {
                return null;
            }
        }

        @Override
        void drawImage(Canvas c, File image) {
            Bitmap b = loadBitmapForScreen(image);
            if (b == null) {
                c.drawText("Image not supported", getWidth() / 2f, getHeight() / 2f, imageLabelPaint);
                return;
            }

            float maxW = getWidth() - 32;
            float maxH = getHeight() - footer - 32;
            float fit = Math.min(maxW / b.getWidth(), maxH / b.getHeight());
            float scale = Math.min(1f, fit); // large art shrinks to fit; small art is never enlarged to the page
            float dw = b.getWidth() * scale;
            float dh = b.getHeight() * scale;
            float l = (getWidth() - dw) / 2f;
            float t = 16 + (maxH - dh) / 2f;
            c.drawBitmap(b, null, new RectF(l, t, l + dw, t + dh),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
        }

        void recycleInline() {
            for (Bitmap b : inlineBitmaps.values()) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            inlineBitmaps.clear();
        }

        @Override
        boolean next() {
            recycleInline();
            return super.next();
        }

        @Override
        boolean prev() {
            recycleInline();
            return super.prev();
        }

        @Override
        protected void onDetachedFromWindow() {
            recycleInline();
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float dx = Math.abs(e.getX() - downX);
                float dy = Math.abs(e.getY() - downY);
                if (dx < 45 && dy < 45) {
                    float q = e.getX() / Math.max(1f, getWidth());
                    if (q > 0.38f && q < 0.62f) {
                        ReaderV5Activity.this.chooseFont();
                    } else if (q <= 0.38f) {
                        ReaderV5Activity.this.next();
                    } else {
                        ReaderV5Activity.this.prev();
                    }
                } else if (dx > dy && dx > 60) {
                    if (e.getX() < downX) ReaderV5Activity.this.next();
                    else ReaderV5Activity.this.prev();
                }
                return true;
            }
            return true;
        }
    }
}
