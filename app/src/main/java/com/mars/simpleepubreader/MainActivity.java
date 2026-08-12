package com.mars.simpleepubreader;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.xmlpull.v1.*;

public class MainActivity extends Activity {
    static final int PICK = 7, STORAGE = 8;
    static final char IMAGE = '\uFFFC';

    File root;
    ArrayList<File> spine = new ArrayList<>();
    ArrayList<ChapterData> chapters = new ArrayList<>();
    Reader reader;
    int chap = 0;
    Uri pendingUri;
    boolean pendingPick = false;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        root = new File(getFilesDir(), "book");
        Uri u = getIntent() == null ? null : getIntent().getData();
        if (u != null) {
            openOrRequest(u);
        } else if (loadList()) {
            chap = getPreferences(0).getInt("chap", 0);
            show(chap, getPreferences(0).getInt("off", 0));
        } else {
            home(null);
        }
    }

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

        Button b = new Button(this);
        b.setText("OPEN EPUB");
        b.setTextSize(18);
        b.setOnClickListener(v -> pick());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(260, 70);
        p.setMargins(0, 35, 0, 0);
        l.addView(b, p);
        setContentView(l);
    }

    void loading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(20);
        t.setTextColor(Color.BLACK);
        t.setBackgroundColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        setContentView(t);
    }

    boolean canReadStorage() {
        return Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    void pick() {
        if (!canReadStorage()) {
            pendingPick = true;
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE);
            return;
        }
        doPick();
    }

    void doPick() {
        pendingPick = false;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/epub+zip");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, PICK);
        } catch (Throwable e) {
            try {
                i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, PICK);
            } catch (Throwable x) {
                home("No file picker found. Open an EPUB from the file manager.");
            }
        }
    }

    protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (r == PICK && c == RESULT_OK && d != null && d.getData() != null) {
            Uri u = d.getData();
            try {
                if ("content".equals(u.getScheme())) {
                    getContentResolver().takePersistableUriPermission(
                            u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Throwable ignored) {}
            openOrRequest(u);
        }
    }

    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r != STORAGE) return;
        Uri u = pendingUri;
        pendingUri = null;
        if (u != null) {
            loading("Opening EPUB…");
            openAsync(u);
            return;
        }
        if (pendingPick) {
            pendingPick = false;
            doPick();
        }
    }

    void openOrRequest(Uri u) {
        if (u == null) {
            home("No file selected.");
            return;
        }
        if (!canReadStorage()) {
            pendingUri = u;
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE);
            return;
        }
        loading("Opening EPUB…");
        openAsync(u);
    }

    InputStream openInput(Uri u) throws Exception {
        String s = u.getScheme();
        if (s == null || "file".equalsIgnoreCase(s)) {
            String p = u.getPath();
            if (p == null) throw new FileNotFoundException("Missing file path");
            return new FileInputStream(new File(p));
        }
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) throw new FileNotFoundException("Cannot read selected file");
        return in;
    }

    void openAsync(final Uri u) {
        new Thread(() -> {
            try {
                del(root);
                root.mkdirs();
                chapters.clear();
                File z = new File(root, "book.epub");
                File dir = new File(root, "x");
                dir.mkdirs();
                copy(openInput(u), new FileOutputStream(z));
                unzip(z, dir);
                parse(dir);
                saveList();
                chap = 0;
                getPreferences(0).edit().putInt("chap", 0).putInt("off", 0).apply();
                runOnUiThread(() -> show(0, 0));
            } catch (final Throwable e) {
                runOnUiThread(() -> home("Could not open EPUB: " + err(e)));
            }
        }).start();
    }

    void show(int c, int off) {
        if (spine.isEmpty()) {
            home("No readable chapters.");
            return;
        }
        chap = Math.max(0, Math.min(c, spine.size() - 1));
        try {
            ensureChapters();
            reader = new Reader(this, chap, off);
            setContentView(reader);
        } catch (Throwable e) {
            home("Could not read book: " + err(e));
        }
    }

    void ensureChapters() throws Exception {
        if (chapters.size() == spine.size()) return;
        chapters.clear();
        File bookBase = new File(root, "x").getCanonicalFile();
        for (int i = 0; i < spine.size(); i++) {
            chapters.add(parseChapter(spine.get(i), bookBase));
        }
    }

    ChapterData parseChapter(File file, File bookBase) throws Exception {
        ChapterData out = new ChapterData(file);
        String src = read(file);
        src = src.replaceAll("(?is)<(script|style|rt)\\b[^>]*>.*?</\\1\\s*>", " ");

        Pattern tagPattern = Pattern.compile("(?is)<[^>]+>");
        Matcher m = tagPattern.matcher(src);
        int last = 0;
        while (m.find()) {
            appendText(out, src.substring(last, m.start()));
            String tag = m.group();

            if (isImageTag(tag)) {
                String path = imageAttr(tag);
                if (path != null) {
                    File imageFile = resolveResource(file.getParentFile(), path, bookBase);
                    if (imageFile != null && imageFile.isFile()) {
                        int pos = out.text.length();
                        out.text.append(IMAGE);
                        out.images.put(pos, imageFile);
                    }
                }
            } else if (isBreakTag(tag)) {
                appendBreak(out);
            }
            last = m.end();
        }
        if (last < src.length()) appendText(out, src.substring(last));

        trimChapter(out);
        return out;
    }

    boolean isImageTag(String tag) {
        String x = tag.toLowerCase(Locale.US);
        return x.matches("(?is)<\\s*(img|image)\\b.*");
    }

    boolean isBreakTag(String tag) {
        String x = tag.toLowerCase(Locale.US).replaceAll("\\s+", " ");
        if (x.matches("(?is)<\\s*br\\b.*")) return true;
        return x.matches("(?is)<\\s*/\\s*(p|div|h[1-6]|li|blockquote|section|article|tr|table|figure|figcaption)\\b.*");
    }

    String imageAttr(String tag) {
        Pattern a = Pattern.compile("(?is)(?:src|xlink:href|href)\\s*=\\s*(['\"])(.*?)\\1");
        Matcher m = a.matcher(tag);
        if (m.find()) return decodeEntities(m.group(2)).trim();

        a = Pattern.compile("(?is)(?:src|xlink:href|href)\\s*=\\s*([^\\s>]+)");
        m = a.matcher(tag);
        return m.find() ? decodeEntities(m.group(1)).trim() : null;
    }

    File resolveResource(File parent, String ref, File bookBase) {
        try {
            if (ref == null || ref.length() == 0 || ref.startsWith("data:") ||
                    ref.startsWith("http:") || ref.startsWith("https:")) return null;
            int q = ref.indexOf('?');
            if (q >= 0) ref = ref.substring(0, q);
            int h = ref.indexOf('#');
            if (h >= 0) ref = ref.substring(0, h);
            File f = new File(parent, Uri.decode(ref)).getCanonicalFile();
            String bp = bookBase.getCanonicalPath() + File.separator;
            if (!f.getPath().startsWith(bp)) return null;
            return f;
        } catch (Throwable e) {
            return null;
        }
    }

    void appendBreak(ChapterData c) {
        int n = c.text.length();
        if (n == 0) return;
        char last = c.text.charAt(n - 1);
        if (last != '\n') c.text.append('\n');
    }

    void appendText(ChapterData c, String raw) {
        String s = decodeEntities(raw);
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);

            if (cp == '\r') continue;
            if (cp == '\t' || cp == '\f' || cp == 0x0b) cp = ' ';

            int n = c.text.length();
            char last = n == 0 ? 0 : c.text.charAt(n - 1);

            if (cp == '\n') {
                if (last != '\n') c.text.append('\n');
            } else if (cp == ' ') {
                if (last != ' ' && last != '\n') c.text.append(' ');
            } else {
                c.text.appendCodePoint(cp);
            }
        }
    }

    void trimChapter(ChapterData c) {
        while (c.text.length() > 0 &&
                (c.text.charAt(0) == ' ' || c.text.charAt(0) == '\n')) {
            shiftChapterLeft(c);
        }
        while (c.text.length() > 0) {
            char x = c.text.charAt(c.text.length() - 1);
            if (x != ' ' && x != '\n') break;
            c.text.setLength(c.text.length() - 1);
        }
    }

    void shiftChapterLeft(ChapterData c) {
        c.text.deleteCharAt(0);
        HashMap<Integer, File> moved = new HashMap<>();
        for (Map.Entry<Integer, File> e : c.images.entrySet()) {
            if (e.getKey() > 0) moved.put(e.getKey() - 1, e.getValue());
        }
        c.images = moved;
    }

    String decodeEntities(String s) {
        s = s.replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");

        Pattern p = Pattern.compile("&#(x?[0-9A-Fa-f]+);");
        Matcher m = p.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String v = m.group(1);
            try {
                int cp = (v.startsWith("x") || v.startsWith("X"))
                        ? Integer.parseInt(v.substring(1), 16)
                        : Integer.parseInt(v, 10);
                m.appendReplacement(b, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Throwable e) {
                m.appendReplacement(b, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(b);
        return b.toString();
    }

    void next() {
        if (reader != null) {
            reader.next();
            save();
        }
    }

    void prev() {
        if (reader != null) {
            reader.prev();
            save();
        }
    }

    void save() {
        if (reader != null && reader.ready()) {
            Page p = reader.current();
            if (p != null) {
                chap = p.chapter;
                getPreferences(0).edit()
                        .putInt("chap", chap)
                        .putInt("off", p.start)
                        .apply();
            }
        }
    }

    public boolean onKeyDown(int k, KeyEvent e) {
        if (reader != null &&
                (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN ||
                        k == KeyEvent.KEYCODE_DPAD_LEFT)) {
            next();
            return true;
        }
        if (reader != null &&
                (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_PAGE_UP ||
                        k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            prev();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            save();
            home("Choose another book");
            return true;
        }
        return super.onKeyDown(k, e);
    }

    public void onBackPressed() {
        if (reader != null) {
            save();
            home("Position saved");
        } else {
            super.onBackPressed();
        }
    }

    void parse(File base) throws Exception {
        spine.clear();
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(false);
        XmlPullParser p = f.newPullParser();
        File c = new File(base, "META-INF/container.xml");
        p.setInput(new FileInputStream(c), null);
        String op = null;
        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.START_TAG && "rootfile".equals(local(p.getName()))) {
                op = attr(p, "full-path");
                break;
            }
        }
        if (op == null) throw new Exception("No OPF");

        File of = child(base, Uri.decode(op));
        HashMap<String, String> manifest = new HashMap<>();
        ArrayList<String> order = new ArrayList<>();

        p = f.newPullParser();
        p.setInput(new FileInputStream(of), null);
        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if ("item".equals(n)) {
                    String id = attr(p, "id");
                    String h = attr(p, "href");
                    if (id != null && h != null) manifest.put(id, h);
                }
                if ("itemref".equals(n)) {
                    String id = attr(p, "idref");
                    if (id != null) order.add(id);
                }
            }
        }

        for (String id : order) {
            String h = manifest.get(id);
            if (h == null) continue;
            int q = h.indexOf('#');
            if (q >= 0) h = h.substring(0, q);
            File x = new File(of.getParentFile(), Uri.decode(h)).getCanonicalFile();
            String bp = base.getCanonicalPath() + File.separator;
            if (x.isFile() && x.getPath().startsWith(bp)) spine.add(x);
        }
        if (spine.isEmpty()) throw new Exception("No readable spine");
    }

    static String local(String s) {
        int i = s == null ? -1 : s.indexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }

    static String attr(XmlPullParser p, String n) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (n.equals(local(p.getAttributeName(i)))) return p.getAttributeValue(i);
        }
        return null;
    }

    static File child(File b, String r) throws Exception {
        File f = new File(b, r).getCanonicalFile();
        String p = b.getCanonicalPath() + File.separator;
        if (!f.getPath().startsWith(p)) throw new Exception("Unsafe EPUB path");
        return f;
    }

    void unzip(File z, File out) throws Exception {
        String base = out.getCanonicalPath() + File.separator;
        ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(z)));
        ZipEntry e;
        byte[] b = new byte[16384];
        while ((e = in.getNextEntry()) != null) {
            File f = new File(out, e.getName());
            if (!f.getCanonicalPath().startsWith(base)) throw new Exception("Unsafe EPUB path");
            if (e.isDirectory()) {
                f.mkdirs();
            } else {
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                FileOutputStream o = new FileOutputStream(f);
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                o.close();
            }
            in.closeEntry();
        }
        in.close();
    }

    void saveList() throws Exception {
        FileWriter w = new FileWriter(new File(root, "spine.txt"));
        File b = new File(root, "x");
        String p = b.getCanonicalPath() + File.separator;
        for (File f : spine) {
            w.write(f.getCanonicalPath().substring(p.length()) + "\n");
        }
        w.close();
    }

    boolean loadList() {
        try {
            spine.clear();
            chapters.clear();
            File b = new File(root, "x");
            File l = new File(root, "spine.txt");
            if (!l.isFile()) return false;
            BufferedReader r = new BufferedReader(new FileReader(l));
            String s;
            while ((s = r.readLine()) != null) {
                File f = child(b, s);
                if (f.isFile()) spine.add(f);
            }
            r.close();
            return !spine.isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    static void copy(InputStream i, OutputStream o) throws Exception {
        byte[] b = new byte[16384];
        int n;
        while ((n = i.read(b)) > 0) o.write(b, 0, n);
        i.close();
        o.close();
    }

    static String read(File f) throws Exception {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder b = new StringBuilder();
        char[] c = new char[8192];
        int n;
        while ((n = r.read(c)) > 0) b.append(c, 0, n);
        r.close();
        return b.toString();
    }

    static void del(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] a = f.listFiles();
            if (a != null) for (File x : a) del(x);
        }
        f.delete();
    }

    static String err(Throwable e) {
        String s = e.getMessage();
        return s == null ? e.getClass().getSimpleName() : s;
    }

    Typeface minchoTypeface() {
        String[] dirs = {"/system/fonts", "/vendor/fonts", "/product/fonts"};
        String[] needles = {
                "mincho", "notoserif", "serifcjk", "serifjp",
                "hiragino", "yumincho", "ipamin", "ipaexm",
                "kozmin", "songti", "ming"
        };

        for (String d : dirs) {
            try {
                File dir = new File(d);
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (String needle : needles) {
                    for (File f : files) {
                        String n = f.getName().toLowerCase(Locale.US);
                        if (n.contains(needle) &&
                                (n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc"))) {
                            try {
                                return Typeface.createFromFile(f);
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        try {
            Typeface t = Typeface.create("serif", Typeface.NORMAL);
            if (t != null) return t;
        } catch (Throwable ignored) {}
        return Typeface.SERIF;
    }

    static class ChapterData {
        File file;
        StringBuilder text = new StringBuilder();
        HashMap<Integer, File> images = new HashMap<>();
        ChapterData(File f) { file = f; }
    }

    static class Page {
        int chapter;
        int start;
        int end;
        File image;
        int chapterPage;
        int chapterPages;
        int bookPage;
        int bookPages;
    }

    class Reader extends View {
        final int savedChapter;
        final int savedOffset;
        ArrayList<Page> pages = new ArrayList<>();
        int currentIndex = -1;
        int[] chapterStarts;
        int[] chapterCounts;

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint imageLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float downX, downY;

        final float top = 22;
        final float left = 20;
        final float right = 24;
        final float footer = 34;
        final float cell = 58;
        final float column = 76;

        Bitmap shownBitmap;
        String shownBitmapPath;

        Reader(Context c, int chapter, int offset) {
            super(c);
            savedChapter = chapter;
            savedOffset = offset;
            setBackgroundColor(Color.WHITE);

            textPaint.setColor(Color.BLACK);
            textPaint.setTypeface(minchoTypeface());
            textPaint.setTextSize(46);

            footerPaint.setColor(0xff444444);
            footerPaint.setTextSize(18);

            imageLabelPaint.setColor(0xff555555);
            imageLabelPaint.setTextSize(20);
            imageLabelPaint.setTextAlign(Paint.Align.CENTER);
        }

        boolean ready() {
            return currentIndex >= 0 && currentIndex < pages.size();
        }

        Page current() {
            return ready() ? pages.get(currentIndex) : null;
        }

        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            buildPages(w, h);
        }

        void buildPages(int w, int h) {
            pages.clear();
            currentIndex = -1;
            chapterStarts = new int[chapters.size()];
            chapterCounts = new int[chapters.size()];

            int rows = Math.max(1, (int)((h - top - footer - 12) / cell));
            int cols = Math.max(1, (int)((w - left - right) / column));

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
                } else if (savedOffset >= p.start && savedOffset < Math.max(p.end, p.start + 1)) {
                    currentIndex = i;
                    break;
                }
            }
            invalidate();
        }

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
                File img = ch.images.get(pos);
                if (ch.text.charAt(pos) == IMAGE && img != null) {
                    Page p = new Page();
                    p.chapter = ci;
                    p.start = pos;
                    p.end = pos + 1;
                    p.image = img;
                    out.add(p);
                    pos++;
                    continue;
                }

                int start = pos;
                int row = 0;
                int col = 0;

                while (pos < len && col < cols) {
                    File image = ch.images.get(pos);
                    if (ch.text.charAt(pos) == IMAGE && image != null) break;

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

        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (!ready()) {
                c.drawText("No pages", 30, 50, footerPaint);
                return;
            }

            Page page = pages.get(currentIndex);
            if (page.image != null) {
                drawImage(c, page.image);
            } else {
                drawTextPage(c, page);
            }

            String footerText = "Ch " + (page.chapter + 1) + "/" + chapters.size() +
                    "  " + page.chapterPage + "/" + page.chapterPages +
                    "   Book " + page.bookPage + "/" + page.bookPages;
            c.drawText(footerText, 10, getHeight() - 8, footerPaint);
        }

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
                    i += n;
                    continue;
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

                i += n;
                y += cell;
                if (y > bottom) {
                    x -= column;
                    y = top + 46;
                }
            }
        }

        void drawImage(Canvas c, File image) {
            Bitmap b = loadBitmapForScreen(image);
            if (b == null) {
                c.drawText("Image not supported", getWidth() / 2f, getHeight() / 2f, imageLabelPaint);
                return;
            }

            float maxW = getWidth() - 32;
            float maxH = getHeight() - footer - 32;
            float scale = Math.min(maxW / b.getWidth(), maxH / b.getHeight());
            float dw = b.getWidth() * scale;
            float dh = b.getHeight() * scale;
            float l = (getWidth() - dw) / 2f;
            float t = 16 + (maxH - dh) / 2f;
            RectF dst = new RectF(l, t, l + dw, t + dh);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(b, null, dst, p);
        }

        Bitmap loadBitmapForScreen(File f) {
            String path = f.getAbsolutePath();
            if (shownBitmap != null && path.equals(shownBitmapPath) && !shownBitmap.isRecycled()) {
                return shownBitmap;
            }
            recycleBitmap();

            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

                int reqW = Math.max(1, getWidth() - 32);
                int reqH = Math.max(1, getHeight() - (int)footer - 32);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= reqW &&
                        bounds.outHeight / (sample * 2) >= reqH) {
                    sample *= 2;
                }

                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = sample;
                opt.inPreferredConfig = Bitmap.Config.RGB_565;
                shownBitmap = BitmapFactory.decodeFile(path, opt);
                shownBitmapPath = path;
                return shownBitmap;
            } catch (Throwable e) {
                recycleBitmap();
                return null;
            }
        }

        void recycleBitmap() {
            if (shownBitmap != null && !shownBitmap.isRecycled()) {
                shownBitmap.recycle();
            }
            shownBitmap = null;
            shownBitmapPath = null;
        }

        boolean next() {
            if (!ready() || currentIndex + 1 >= pages.size()) return false;
            currentIndex++;
            recycleBitmap();
            Page p = current();
            chap = p.chapter;
            invalidate();
            return true;
        }

        boolean prev() {
            if (!ready() || currentIndex <= 0) return false;
            currentIndex--;
            recycleBitmap();
            Page p = current();
            chap = p.chapter;
            invalidate();
            return true;
        }

        protected void onDetachedFromWindow() {
            recycleBitmap();
            super.onDetachedFromWindow();
        }

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
                    if (e.getX() < getWidth() / 2f) MainActivity.this.next();
                    else MainActivity.this.prev();
                } else if (dx > dy && dx > 60) {
                    if (e.getX() < downX) MainActivity.this.next();
                    else MainActivity.this.prev();
                }
                return true;
            }
            return true;
        }
    }
}
