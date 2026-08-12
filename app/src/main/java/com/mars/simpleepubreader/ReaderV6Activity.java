package com.mars.simpleepubreader;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.Typeface;
import android.net.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;

public class ReaderV6Activity extends ReaderV5Activity {
    static final int BROWSE_STORAGE = 10;
    static final int DEFAULT_FONT_SIZE = 46;
    static final int WEIGHT_REGULAR = 0, WEIGHT_MEDIUM = 1, WEIGHT_BOLD = 2;

    boolean pendingBrowser = false;
    boolean browsing = false;
    boolean browserAtLocations = false;
    File browserDir;

    static class BrowserItem {
        String label;
        File file;
        int kind; // 0 file, 1 directory, 2 locations
        BrowserItem(String l, File f, int k) { label = l; file = f; kind = k; }
    }

    static class RubySpan {
        int start, end;
        String text;
        RubySpan(int s, int e, String t) { start = s; end = e; text = t; }
    }

    static class RubyChapterData extends ChapterData {
        ArrayList<RubySpan> ruby = new ArrayList<>();
        HashMap<Integer, RubySpan> rubyAt = new HashMap<>();
        RubyChapterData(File f) { super(f); }
        void addRuby(RubySpan r) {
            ruby.add(r);
            rubyAt.put(r.start, r);
        }
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        if ((getIntent() == null || getIntent().getData() == null) && spine.isEmpty()) {
            String last = getPreferences(0).getString("last_source", "");
            if (last.length() > 0) {
                try {
                    File f = new File(last);
                    if (f.isFile()) openOrRequest(Uri.fromFile(f));
                } catch (Throwable ignored) {}
            }
        }
    }

    int dp(float n) {
        return Math.max(1, Math.round(n * getResources().getDisplayMetrics().density));
    }

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

        bar.addView(menuItem("Font", v -> {
            save();
            chooseFont();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        bar.addView(menuItem("Size " + currentFontSize(), v -> {
            save();
            chooseFontSize();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f));

        bar.addView(menuItem("Weight " + weightShort(), v -> {
            save();
            chooseWeight();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.3f));

        return bar;
    }

    TextView menuItem(String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Color.BLACK);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        t.setOnClickListener(click);
        return t;
    }

    View shell(View body) {
        LinearLayout all = new LinearLayout(this);
        all.setOrientation(LinearLayout.VERTICAL);
        all.setBackgroundColor(Color.WHITE);
        all.addView(topMenu(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        all.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return all;
    }

    @Override
    void home(String note) {
        save();
        reader = null;
        browsing = false;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding(dp(20), dp(20), dp(20), dp(20));
        body.setBackgroundColor(Color.WHITE);

        TextView t = new TextView(this);
        String last = getPreferences(0).getString("last_name", "");
        StringBuilder s = new StringBuilder("Simple EPUB Reader\nLikebook Mars · Android 6");
        if (last.length() > 0) s.append("\n\nLast book: ").append(last);
        if (note != null && note.length() > 0) s.append("\n\n").append(note);
        s.append("\n\nUse the menu above.");
        t.setText(s.toString());
        t.setTextColor(Color.BLACK);
        t.setTextSize(19);
        t.setGravity(Gravity.CENTER);
        body.addView(t);

        setContentView(shell(body));
    }

    @Override
    void loading(String s) {
        browsing = false;
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(18);
        t.setTextColor(Color.BLACK);
        t.setBackgroundColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        setContentView(shell(t));
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
            reader = new ReaderV6(this, chap, off);
            setContentView(shell(reader));
        } catch (Throwable e) {
            home("Could not read book: " + err(e));
        }
    }

    int currentFontSize() {
        return getPreferences(0).getInt("font_size", DEFAULT_FONT_SIZE);
    }

    int currentWeight() {
        return getPreferences(0).getInt("font_weight", WEIGHT_REGULAR);
    }

    String weightShort() {
        int w = currentWeight();
        if (w == WEIGHT_MEDIUM) return "Med";
        if (w == WEIGHT_BOLD) return "Bold";
        return "Reg";
    }

    void chooseFontSize() {
        final int[] sizes = {38, 42, 46, 50, 54, 58};
        String[] labels = new String[sizes.length];
        int selected = 2;
        int cur = currentFontSize();
        for (int i = 0; i < sizes.length; i++) {
            labels[i] = sizes[i] + " px";
            if (sizes[i] == cur) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Font size")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    Page here = reader == null ? null : reader.current();
                    getPreferences(0).edit().putInt("font_size", sizes[which]).apply();
                    dialog.dismiss();
                    if (here != null) show(here.chapter, here.start);
                    else home("Font size: " + sizes[which]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void chooseWeight() {
        final String[] labels = {"Regular", "Medium", "Bold"};
        int selected = Math.max(0, Math.min(currentWeight(), labels.length - 1));
        new AlertDialog.Builder(this)
                .setTitle("Font weight")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    Page here = reader == null ? null : reader.current();
                    getPreferences(0).edit().putInt("font_weight", which).apply();
                    dialog.dismiss();
                    if (here != null) show(here.chapter, here.start);
                    else home("Font weight: " + labels[which]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void applyWeight(Paint p) {
        p.setFakeBoldText(false);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeWidth(0);
        int w = currentWeight();
        if (w == WEIGHT_MEDIUM) {
            p.setStyle(Paint.Style.FILL_AND_STROKE);
            p.setStrokeWidth(Math.max(0.4f, currentFontSize() / 90f));
        } else if (w == WEIGHT_BOLD) {
            p.setFakeBoldText(true);
        }
    }

    @Override
    void pick() {
        if (!canReadStorage()) {
            pendingBrowser = true;
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    BROWSE_STORAGE);
            return;
        }
        showBookBrowserStart();
    }

    @Override
    void doPick() {
        showBookBrowserStart();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        if (request == BROWSE_STORAGE) {
            pendingBrowser = false;
            if (canReadStorage()) showBookBrowserStart();
            else home("Storage permission is needed to browse books.");
            return;
        }
        super.onRequestPermissionsResult(request, permissions, grants);
    }

    ArrayList<File> storageRoots() {
        ArrayList<File> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        try {
            File internal = Environment.getExternalStorageDirectory().getCanonicalFile();
            if (internal.isDirectory() && seen.add(internal.getPath())) out.add(internal);
        } catch (Throwable ignored) {}

        try {
            File storage = new File("/storage");
            File[] list = storage.listFiles();
            if (list != null) {
                for (File f : list) {
                    try {
                        if (!f.isDirectory() || !f.canRead()) continue;
                        String n = f.getName();
                        if ("emulated".equals(n) || "self".equals(n)) continue;
                        File c = f.getCanonicalFile();
                        if (seen.add(c.getPath())) out.add(c);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    boolean isStorageRoot(File f) {
        if (f == null) return false;
        try {
            String p = f.getCanonicalPath();
            for (File r : storageRoots()) {
                if (p.equals(r.getCanonicalPath())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    void showBookBrowserStart() {
        save();
        reader = null;
        String saved = getPreferences(0).getString("browser_dir", "");
        File start = saved.length() == 0 ? null : new File(saved);
        if (start == null || !start.isDirectory() || !start.canRead()) {
            try {
                File internal = Environment.getExternalStorageDirectory();
                File books = new File(internal, "Books");
                start = books.isDirectory() ? books : internal;
            } catch (Throwable e) {
                start = null;
            }
        }
        if (start != null && start.isDirectory()) showBrowser(start);
        else showLocations();
    }

    void showLocations() {
        save();
        reader = null;
        browsing = true;
        browserAtLocations = true;
        browserDir = null;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Storage locations");
        title.setTextColor(Color.BLACK);
        title.setTextSize(15);
        title.setPadding(dp(12), dp(8), dp(8), dp(8));
        body.addView(title);

        final ArrayList<BrowserItem> items = new ArrayList<>();
        ArrayList<File> roots = storageRoots();
        for (int i = 0; i < roots.size(); i++) {
            File r = roots.get(i);
            String label = i == 0 ? "Internal storage" : "SD card (" + r.getName() + ")";
            items.add(new BrowserItem(label, r, 1));
        }

        ListView list = new ListView(this);
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).label;
        list.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, labels));
        list.setOnItemClickListener((parent, view, pos, id) -> showBrowser(items.get(pos).file));
        body.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(shell(body));
    }

    void showBrowser(File dir) {
        save();
        reader = null;
        browsing = true;
        browserAtLocations = false;
        try { dir = dir.getCanonicalFile(); } catch (Throwable ignored) {}
        browserDir = dir;
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            showLocations();
            return;
        }

        getPreferences(0).edit().putString("browser_dir", dir.getAbsolutePath()).apply();

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Color.WHITE);

        TextView path = new TextView(this);
        path.setText(displayPath(dir));
        path.setTextColor(Color.BLACK);
        path.setTextSize(13);
        path.setSingleLine(true);
        path.setPadding(dp(10), dp(6), dp(8), dp(6));
        body.addView(path);

        final ArrayList<BrowserItem> items = new ArrayList<>();
        if (isStorageRoot(dir)) {
            items.add(new BrowserItem("‹ Storage locations", null, 2));
        } else {
            File parent = dir.getParentFile();
            if (parent != null) items.add(new BrowserItem("‹ ..", parent, 1));
        }

        ArrayList<File> dirs = new ArrayList<>();
        ArrayList<File> books = new ArrayList<>();
        File[] list = dir.listFiles();
        if (list != null) {
            for (File f : list) {
                try {
                    if (f.isDirectory() && f.canRead() && !f.getName().startsWith(".")) {
                        dirs.add(f);
                    } else if (f.isFile() &&
                            f.getName().toLowerCase(Locale.US).endsWith(".epub")) {
                        books.add(f);
                    }
                } catch (Throwable ignored) {}
            }
        }
        Comparator<File> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        Collections.sort(dirs, byName);
        Collections.sort(books, byName);
        for (File f : dirs) items.add(new BrowserItem("[ " + f.getName() + " ]", f, 1));
        for (File f : books) items.add(new BrowserItem(f.getName(), f, 0));

        ListView lv = new ListView(this);
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).label;
        lv.setAdapter(new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, labels));
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            BrowserItem item = items.get(pos);
            if (item.kind == 2) {
                showLocations();
            } else if (item.kind == 1) {
                showBrowser(item.file);
            } else if (item.file != null) {
                openBrowserBook(item.file);
            }
        });
        body.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(shell(body));
    }

    String displayPath(File d) {
        try {
            File internal = Environment.getExternalStorageDirectory().getCanonicalFile();
            String p = d.getCanonicalPath();
            String base = internal.getCanonicalPath();
            if (p.equals(base)) return "Internal storage";
            if (p.startsWith(base + File.separator)) {
                return "Internal storage/" + p.substring(base.length() + 1);
            }
        } catch (Throwable ignored) {}
        return d.getAbsolutePath();
    }

    void openBrowserBook(File f) {
        try {
            save();
            browsing = false;
            getPreferences(0).edit()
                    .putString("last_source", f.getCanonicalPath())
                    .putString("last_name", f.getName())
                    .putString("browser_dir", f.getParentFile().getCanonicalPath())
                    .apply();
            loading("Opening " + f.getName() + "…");
            openAsync(Uri.fromFile(f));
        } catch (Throwable e) {
            home("Could not open EPUB: " + err(e));
        }
    }

    @Override
    void openAsync(final Uri u) {
        try {
            if (u != null && (u.getScheme() == null || "file".equalsIgnoreCase(u.getScheme()))) {
                File f = new File(u.getPath()).getCanonicalFile();
                getPreferences(0).edit()
                        .putString("last_source", f.getPath())
                        .putString("last_name", f.getName())
                        .apply();
            }
        } catch (Throwable ignored) {}
        super.openAsync(u);
    }

    @Override
    ChapterData parseChapter(File file, File bookBase) throws Exception {
        RubyChapterData out = new RubyChapterData(file);
        String src = read(file);
        src = src.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", " ");

        Pattern tags = Pattern.compile("(?is)<[^>]+>");
        Matcher m = tags.matcher(src);
        int last = 0;
        boolean inRuby = false, inRt = false, inRp = false;
        int rubyStart = -1;
        StringBuilder rubyText = new StringBuilder();

        while (m.find()) {
            String text = src.substring(last, m.start());
            if (inRt) appendRubyText(rubyText, text);
            else if (!inRp) appendText(out, text);

            String tag = m.group();
            String name = localTagName(tag);
            boolean closing = isClosingTag(tag);

            if ("ruby".equals(name)) {
                if (!closing) {
                    inRuby = true;
                    rubyStart = out.text.length();
                    rubyText.setLength(0);
                } else {
                    if (inRuby && rubyStart >= 0 && out.text.length() > rubyStart) {
                        String rt = cleanRuby(rubyText.toString());
                        if (rt.length() > 0) {
                            out.addRuby(new RubySpan(rubyStart, out.text.length(), rt));
                        }
                    }
                    inRuby = false;
                    inRt = false;
                    inRp = false;
                    rubyStart = -1;
                    rubyText.setLength(0);
                }
            } else if ("rt".equals(name)) {
                inRt = !closing;
            } else if ("rp".equals(name)) {
                inRp = !closing;
            } else if (!inRt && !inRp && isImageTag(tag)) {
                String path = imageAttr(tag);
                if (path != null) {
                    File imageFile = resolveResource(file.getParentFile(), path, bookBase);
                    if (imageFile != null && imageFile.isFile()) {
                        int pos = out.text.length();
                        out.text.append(IMAGE);
                        out.images.put(pos, imageFile);
                    }
                }
            } else if (!inRt && !inRp && isBreakTag(tag)) {
                appendBreak(out);
            }

            last = m.end();
        }

        if (last < src.length()) {
            String text = src.substring(last);
            if (inRt) appendRubyText(rubyText, text);
            else if (!inRp) appendText(out, text);
        }

        if (inRuby && rubyStart >= 0 && out.text.length() > rubyStart) {
            String rt = cleanRuby(rubyText.toString());
            if (rt.length() > 0) out.addRuby(new RubySpan(rubyStart, out.text.length(), rt));
        }

        trimRubyChapter(out);
        return out;
    }

    String localTagName(String tag) {
        Matcher m = Pattern.compile("(?is)<\\s*/?\\s*([A-Za-z0-9_:-]+)").matcher(tag);
        if (!m.find()) return "";
        String n = m.group(1).toLowerCase(Locale.US);
        int colon = n.indexOf(':');
        return colon < 0 ? n : n.substring(colon + 1);
    }

    boolean isClosingTag(String tag) {
        return tag.matches("(?is)<\\s*/.*");
    }

    void appendRubyText(StringBuilder b, String raw) {
        String s = decodeEntities(raw);
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || cp == 0x3000) continue;
            b.appendCodePoint(cp);
        }
    }

    String cleanRuby(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    void trimRubyChapter(RubyChapterData c) {
        while (c.text.length() > 0 &&
                (c.text.charAt(0) == ' ' || c.text.charAt(0) == '\n')) {
            shiftChapterLeft(c);
            ArrayList<RubySpan> keep = new ArrayList<>();
            c.rubyAt.clear();
            for (RubySpan r : c.ruby) {
                r.start--;
                r.end--;
                if (r.end > 0) {
                    if (r.start < 0) r.start = 0;
                    keep.add(r);
                    c.rubyAt.put(r.start, r);
                }
            }
            c.ruby = keep;
        }

        while (c.text.length() > 0) {
            char x = c.text.charAt(c.text.length() - 1);
            if (x != ' ' && x != '\n') break;
            c.text.setLength(c.text.length() - 1);
        }
        int len = c.text.length();
        ArrayList<RubySpan> keep = new ArrayList<>();
        c.rubyAt.clear();
        for (RubySpan r : c.ruby) {
            if (r.start < len) {
                r.end = Math.min(r.end, len);
                if (r.end > r.start) {
                    keep.add(r);
                    c.rubyAt.put(r.start, r);
                }
            }
        }
        c.ruby = keep;
    }

    String verticalForm(int cp) {
        switch (cp) {
            case 0x3001: return "\uFE11";
            case 0x3002: return "\uFE12";
            case 0xFF0C: return "\uFE10";
            case 0xFF1A: return "\uFE13";
            case 0xFF1B: return "\uFE14";
            case 0xFF01: return "\uFE15";
            case 0xFF1F: return "\uFE16";
            case 0x3016: return "\uFE17";
            case 0x3017: return "\uFE18";
            case 0x2026: return "\uFE19";
            case 0x2025: return "\uFE30";
            case 0x2014:
            case 0x2015: return "\uFE31";
            case 0xFF08: return "\uFE35";
            case 0xFF09: return "\uFE36";
            case 0xFF5B: return "\uFE37";
            case 0xFF5D: return "\uFE38";
            case 0x3014: return "\uFE39";
            case 0x3015: return "\uFE3A";
            case 0x3010: return "\uFE3B";
            case 0x3011: return "\uFE3C";
            case 0x300A: return "\uFE3D";
            case 0x300B: return "\uFE3E";
            case 0x3008: return "\uFE3F";
            case 0x3009: return "\uFE40";
            case 0x300C: return "\uFE41";
            case 0x300D: return "\uFE42";
            case 0x300E: return "\uFE43";
            case 0x300F: return "\uFE44";
            case 0xFF3B: return "\uFE47";
            case 0xFF3D: return "\uFE48";
            default: return null;
        }
    }

    boolean rotateInVertical(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') ||
                (cp >= '0' && cp <= '9') ||
                cp == 0x30FC || cp == 0x301C || cp == 0xFF5E ||
                cp == '-' || cp == 0x2013;
    }

    boolean cornerPunctuation(int cp) {
        return cp == 0x3001 || cp == 0x3002;
    }

    void resumeBook() {
        if (!spine.isEmpty()) {
            int c = getPreferences(0).getInt("chap", 0);
            int o = getPreferences(0).getInt("off", 0);
            show(c, o);
        } else {
            home(null);
        }
    }

    @Override
    public void onBackPressed() {
        if (browsing) {
            if (browserAtLocations) {
                browsing = false;
                resumeBook();
                return;
            }
            if (browserDir != null && !isStorageRoot(browserDir)) {
                File p = browserDir.getParentFile();
                if (p != null) {
                    showBrowser(p);
                    return;
                }
            }
            showLocations();
            return;
        }
        super.onBackPressed();
    }

    void quickMenu() {
        String[] items = {"Open book", "Font", "Font size", "Font weight"};
        new AlertDialog.Builder(this)
                .setTitle("Reader")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) pick();
                    else if (which == 1) chooseFont();
                    else if (which == 2) chooseFontSize();
                    else chooseWeight();
                })
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            save();
            quickMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    @Override
    protected void onStop() {
        save();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        save();
        super.onDestroy();
    }

    class ReaderV6 extends ReaderV5 {
        final float fontSize;
        final float vCell;
        final float vColumn;
        Paint rubyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ReaderV6(Context c, int chapter, int offset) {
            super(c, chapter, offset);
            fontSize = currentFontSize();
            vCell = Math.max(48f, fontSize * 1.26f);
            vColumn = Math.max(64f, fontSize * 1.65f);

            textPaint.setTypeface(minchoTypeface());
            textPaint.setTextSize(fontSize);
            applyWeight(textPaint);

            rubyPaint.setColor(Color.BLACK);
            rubyPaint.setTypeface(minchoTypeface());
            rubyPaint.setTextSize(Math.max(16f, fontSize * 0.48f));
            applyWeight(rubyPaint);
        }

        @Override
        void buildPages(int w, int h) {
            pages.clear();
            currentIndex = -1;
            chapterStarts = new int[chapters.size()];
            chapterCounts = new int[chapters.size()];

            int rows = Math.max(1, (int)((h - top - footer - 12) / vCell));
            int cols = Math.max(1, (int)((w - left - right) / vColumn));

            for (int ci = 0; ci < chapters.size(); ci++) {
                chapterStarts[ci] = pages.size();
                ArrayList<Page> cp = paginate(ci, chapters.get(ci), rows, cols);
                chapterCounts[ci] = cp.size();
                for (int i = 0; i < cp.size(); i++) {
                    Page p = cp.get(i);
                    p.chapterPage = i + 1;
                    p.chapterPages = cp.size();
                    pages.add(p);
                }
            }

            int total = pages.size();
            for (int i = 0; i < total; i++) {
                pages.get(i).bookPage = i + 1;
                pages.get(i).bookPages = total;
            }

            if (pages.isEmpty()) {
                invalidate();
                return;
            }

            int fallback = Math.min(Math.max(savedChapter, 0), chapters.size() - 1);
            currentIndex = chapterStarts[fallback];

            for (int i = 0; i < pages.size(); i++) {
                Page p = pages.get(i);
                if (p.chapter != fallback) continue;
                if (p.image != null) {
                    if (savedOffset == p.start) {
                        currentIndex = i;
                        break;
                    }
                } else if (savedOffset >= p.start &&
                        savedOffset < Math.max(p.end, p.start + 1)) {
                    currentIndex = i;
                    break;
                }
            }
            invalidate();
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
            RubyChapterData rch = ch instanceof RubyChapterData ? (RubyChapterData) ch : null;
            float x = getWidth() - right - fontSize;
            float y = top + fontSize;
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
                    x -= vColumn;
                    y = top + fontSize;
                    continue;
                }
                if (cp == IMAGE) {
                    File image = ch.images.get(i);
                    if (image != null && isInlineImage(image)) drawInlineImageV6(c, image, x, y);
                    i += n;
                    y += vCell;
                    if (y > bottom) {
                        x -= vColumn;
                        y = top + fontSize;
                    }
                    continue;
                }

                if (rch != null) {
                    RubySpan ruby = rch.rubyAt.get(i);
                    if (ruby != null) drawRuby(c, ruby, x, y);
                }

                drawVerticalGlyphV6(c, cp, x, y, textPaint);
                i += n;
                y += vCell;
                if (y > bottom) {
                    x -= vColumn;
                    y = top + fontSize;
                }
            }
        }

        void drawRuby(Canvas c, RubySpan ruby, float x, float y) {
            if (ruby == null || ruby.text == null || ruby.text.length() == 0) return;
            int baseCells = codePointCount(chapters.get(current().chapter).text, ruby.start, ruby.end);
            int rubyCells = ruby.text.codePointCount(0, ruby.text.length());
            if (baseCells <= 0 || rubyCells <= 0) return;

            float available = baseCells * vCell;
            float natural = rubyPaint.getTextSize() * 1.12f;
            float step = Math.min(natural, available / rubyCells);
            float used = step * rubyCells;
            float ry = y - (fontSize - rubyPaint.getTextSize()) * 0.25f +
                    Math.max(0, (available - used) / 2f);
            float rx = x + fontSize * 0.72f;

            for (int i = 0; i < ruby.text.length();) {
                int cp = ruby.text.codePointAt(i);
                drawVerticalGlyphV6(c, cp, rx, ry, rubyPaint);
                i += Character.charCount(cp);
                ry += step;
            }
        }

        int codePointCount(CharSequence s, int start, int end) {
            int count = 0;
            for (int i = start; i < end;) {
                int cp = Character.codePointAt(s, i);
                i += Character.charCount(cp);
                count++;
            }
            return count;
        }

        void drawVerticalGlyphV6(Canvas c, int cp, float x, float y, Paint paint) {
            String v = verticalForm(cp);
            if (v != null) {
                boolean has = false;
                try { has = paint.hasGlyph(v); } catch (Throwable ignored) {}
                if (has) {
                    c.drawText(v, x, y, paint);
                    return;
                }
            }

            String g = new String(Character.toChars(cp));
            if (cornerPunctuation(cp)) {
                c.drawText(g, x + paint.getTextSize() * 0.24f,
                        y - paint.getTextSize() * 0.28f, paint);
                return;
            }

            if (v != null || rotateInVertical(cp)) {
                c.save();
                c.rotate(90, x + paint.getTextSize() * 0.43f,
                        y - paint.getTextSize() * 0.43f);
                c.drawText(g, x, y, paint);
                c.restore();
                return;
            }

            c.drawText(g, x, y, paint);
        }

        void drawInlineImageV6(Canvas c, File image, float x, float baselineY) {
            Bitmap b = inlineBitmap(image);
            if (b == null) return;
            float box = Math.min(vCell - 6, fontSize * 1.12f);
            float scale = Math.min(1f, Math.min(box / b.getWidth(), box / b.getHeight()));
            if (Math.max(b.getWidth(), b.getHeight()) < fontSize * 0.75f) {
                scale = Math.min(box / b.getWidth(), box / b.getHeight());
            }
            float w = b.getWidth() * scale;
            float h = b.getHeight() * scale;
            float l = x + fontSize * 0.48f - w / 2f;
            float t = baselineY - fontSize * 0.9f + (box - h) / 2f;
            c.drawBitmap(b, null, new RectF(l, t, l + w, t + h),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
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
                    if (e.getX() < getWidth() / 2f) ReaderV6Activity.this.next();
                    else ReaderV6Activity.this.prev();
                } else if (dx > dy && dx > 60) {
                    if (e.getX() < downX) ReaderV6Activity.this.next();
                    else ReaderV6Activity.this.prev();
                }
                return true;
            }
            return true;
        }
    }
}
