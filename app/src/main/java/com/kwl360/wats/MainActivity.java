package com.kwl360.wats;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Movie;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.WindowInsets;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoder;
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoderOptions;
import com.aureusapps.android.webpandroid.encoder.WebPConfig;
import com.aureusapps.android.webpandroid.encoder.WebPMuxAnimParams;
import com.aureusapps.android.webpandroid.encoder.WebPPreset;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String AUTH = "com.kwl360.wats.stickercontentprovider";
    static final String ID = "kwl_demo";
    private static final String PREFS = "kwl360_sticker_pack";
    private static final String KEY_STICKERS = "stickers";
    private static final String KEY_PACK_NAME = "pack_name";
    private static final String KEY_PUBLISHER = "publisher";
    private static final int PICK_MEDIA = 21;
    private static final int PICK_ZIP = 23;
    private static final int CANVAS_SIZE = 512;
    private static final int MAX_STICKERS = 30;
    private static final long MAX_ANIMATED_BYTES = 500L * 1024L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText packName;
    private EditText publisher;
    private LinearLayout stickerGrid;
    private Button addButton;
    private boolean busy;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(15, 23, 42));
        getWindow().setNavigationBarColor(Color.rgb(15, 23, 42));
        ensurePackFiles();
        buildScreen();
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            View content = findViewById(android.R.id.content);
            content.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
            content.requestApplyInsets();
        }
        loadFields();
        refreshStickers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveFields();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(248, 250, 252));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);
        setContentView(scroll);

        TextView brand = new TextView(this);
        brand.setText("KWL360  •  WATS");
        brand.setTextColor(Color.rgb(22, 102, 106));
        brand.setTextSize(14);
        brand.setGravity(Gravity.CENTER);
        root.addView(brand, matchWrap());

        TextView title = new TextView(this);
        title.setText("صانع الملصقات المتحركة");
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(4));
        root.addView(title, matchWrap());

        TextView intro = new TextView(this);
        intro.setText("حوّل صورة أو GIF أو مقطعًا قصيرًا إلى ملصق متحرك، ثم أضف الحزمة إلى واتساب.");
        intro.setTextColor(Color.rgb(71, 85, 105));
        intro.setTextSize(15);
        intro.setGravity(Gravity.CENTER);
        intro.setPadding(dp(4), 0, dp(4), dp(16));
        root.addView(intro, matchWrap());

        packName = new EditText(this);
        packName.setSingleLine(true);
        packName.setHint("اسم حزمة الملصقات");
        packName.setTextSize(16);
        root.addView(packName, matchWrap());

        publisher = new EditText(this);
        publisher.setSingleLine(true);
        publisher.setHint("اسم الناشر");
        publisher.setTextSize(16);
        root.addView(publisher, matchWrap());

        TextView createLabel = sectionTitle("إنشاء ملصقات");
        root.addView(createLabel, matchWrap());

        Button choose = makeButton("＋  اختر صورة أو GIF أو فيديو");
        choose.setOnClickListener(v -> chooseMedia());
        LinearLayout.LayoutParams chooseParams = matchWrap();
        chooseParams.topMargin = dp(8);
        root.addView(choose, chooseParams);

        Button importZip = makeButton("استيراد حزمة ملصقات ZIP");
        importZip.setOnClickListener(v -> chooseZip());
        LinearLayout.LayoutParams zipParams = matchWrap();
        zipParams.topMargin = dp(8);
        root.addView(importZip, zipParams);

        TextView zipHint = new TextView(this);
        zipHint.setText("اختر ملف ZIP يحتوي على صور WebP. سيُضاف ما يصل إلى 30 ملصقًا.");
        zipHint.setTextColor(Color.rgb(71, 85, 105));
        zipHint.setTextSize(13);
        zipHint.setGravity(Gravity.CENTER);
        zipHint.setPadding(0, dp(4), 0, dp(10));
        root.addView(zipHint, matchWrap());

        addButton = makeButton("أضف الحزمة إلى واتساب");
        addButton.setOnClickListener(v -> addPackToWhatsApp());
        LinearLayout.LayoutParams addParams = matchWrap();
        addParams.topMargin = dp(8);
        root.addView(addButton, addParams);

        status = new TextView(this);
        status.setTextColor(Color.rgb(30, 41, 59));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(14), 0, dp(12));
        root.addView(status, matchWrap());

        TextView section = sectionTitle("محتوى الحزمة");
        root.addView(section, matchWrap());

        stickerGrid = new LinearLayout(this);
        stickerGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridParams = matchWrap();
        gridParams.topMargin = dp(8);
        root.addView(stickerGrid, gridParams);

        TextView note = new TextView(this);
        note.setText("تحتاج الحزمة إلى 3 ملصقات على الأقل، وتقبل حتى 30. تُحفظ ملفاتك على الهاتف داخل التطبيق.");
        note.setTextColor(Color.rgb(100, 116, 139));
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note, matchWrap());
    }

    private void chooseZip() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_ZIP);
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر فتح ملفات الهاتف: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importZip(Uri uri) {
        if (busy) return;
        busy = true;
        status.setText("جارٍ فحص ملف ZIP واستيراد ملصقات WebP…");
        addButton.setEnabled(false);
        worker.execute(() -> {
            List<String> imported = new ArrayList<>();
            String error = null;
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ZipInputStream zip = new ZipInputStream(input)) {
                if (input == null) throw new IOException("تعذّرت قراءة ملف ZIP.");
                int available = MAX_STICKERS - stickerNames().size();
                if (available <= 0) throw new IOException("الحزمة ممتلئة؛ احذف ملصقًا أولًا.");
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String lower = entry.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!lower.endsWith(".webp") || lower.contains("/__macosx/")) continue;
                    String base = lower.substring(lower.lastIndexOf('/') + 1);
                    if (base.startsWith("tray") || base.startsWith("icon") || base.startsWith("cover")) continue;
                    if (imported.size() >= available) break;
                    File destination = nextStickerFile();
                    try (FileOutputStream output = new FileOutputStream(destination)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        long total = 0;
                        while ((count = zip.read(buffer)) != -1) {
                            total += count;
                            if (total > MAX_ANIMATED_BYTES) throw new IOException("أحد الملصقات أكبر من 500 كيلوبايت.");
                            output.write(buffer, 0, count);
                        }
                    }
                    if (destination.length() < 12) {
                        destination.delete();
                        continue;
                    }
                    addStickerName(destination.getName());
                    imported.add(destination.getName());
                }
                if (imported.isEmpty()) throw new IOException("لم أجد صور WebP داخل ZIP. تأكد أن الملف يحتوي على ملصقات WebP.");
            } catch (Exception e) {
                error = e.getMessage() == null ? "ملف ZIP غير صالح أو لا يحتوي صور WebP." : e.getMessage();
                for (String name : imported) {
                    new File(folder(this), name).delete();
                    removeStickerName(name);
                }
            }
            final String message = error;
            final int count = imported.size();
            runOnUiThread(() -> {
                busy = false;
                if (message == null) {
                    status.setText("تم استيراد " + count + " ملصقًا من ملف ZIP.");
                } else {
                    status.setText("تعذّر استيراد ZIP: " + message);
                }
                refreshStickers();
            });
        });
    }

    private void chooseMedia() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_MEDIA);
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر فتح ملفات الهاتف: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_ZIP) {
            importZip(uri);
            return;
        }
        if (requestCode != PICK_MEDIA) return;
        String mime = getContentResolver().getType(uri);
        final String mediaMime = mime == null ? "" : mime;
        String displayName = queryDisplayName(uri);
        final String selectedName = displayName;
        if (!(mediaMime.startsWith("image/") || mediaMime.startsWith("video/") || selectedName.toLowerCase().endsWith(".gif"))) {
            status.setText("اختر صورة أو GIF أو فيديو.");
            return;
        }
        if (stickerNames().size() >= MAX_STICKERS) {
            status.setText("اكتملت الحزمة: الحد الأقصى 30 ملصقًا.");
            return;
        }
        busy = true;
        status.setText("جارٍ تجهيز الملصق وتحويله إلى WebP متحرك…");
        addButton.setEnabled(false);
        worker.execute(() -> {
            String error = null;
            try {
                File out = nextStickerFile();
                List<Bitmap> frames = decodeFrames(uri, mediaMime, selectedName);
                try {
                    encodeAnimatedWebp(frames, out);
                } finally {
                    recycleFrames(frames);
                }
                if (!out.isFile() || out.length() == 0) throw new IOException("لم ينتج ملف الملصق.");
                if (out.length() > MAX_ANIMATED_BYTES) {
                    out.delete();
                    throw new IOException("حجم الملصق تجاوز 500 كيلوبايت. اختر GIF أقصر أو أقل دقة.");
                }
                addStickerName(out.getName());
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String message = error;
            runOnUiThread(() -> {
                busy = false;
                addButton.setEnabled(true);
                if (message == null) {
                    status.setText("تم إنشاء الملصق المتحرك وحفظه في الحزمة.");
                    refreshStickers();
                } else {
                    status.setText("لم يكتمل التحويل: " + message);
                }
            });
        });
    }

    private List<Bitmap> decodeFrames(Uri uri, String mime, String displayName) throws Exception {
        if (mime.startsWith("video/")) return decodeVideo(uri);
        if (mime.equalsIgnoreCase("image/gif") || displayName.toLowerCase().endsWith(".gif")) {
            List<Bitmap> gif = decodeGif(uri);
            if (gif != null && !gif.isEmpty()) return gif;
        }
        Bitmap image;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("تعذّر قراءة الملف.");
            image = BitmapFactory.decodeStream(in);
        }
        if (image == null) throw new IOException("صيغة الملف غير مدعومة. جرّب صورة أو GIF أو MP4.");
        List<Bitmap> frames = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Bitmap frame = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(frame);
            float scalePulse = 0.94f + (float) (0.06 * (0.5 + 0.5 * Math.sin((i / 11.0) * Math.PI * 2)));
            drawContained(canvas, image, scalePulse);
            frames.add(frame);
        }
        image.recycle();
        return frames;
    }

    private List<Bitmap> decodeGif(Uri uri) throws IOException {
        Movie movie;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            movie = Movie.decodeStream(in);
        } catch (RuntimeException e) {
            return null;
        }
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) return null;
        int duration = Math.max(800, movie.duration());
        int sampledDuration = Math.min(duration, 2400);
        int count = Math.max(8, Math.min(24, sampledDuration / 100));
        List<Bitmap> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int time = (int) ((long) i * sampledDuration / count);
            movie.setTime(time);
            Bitmap frame = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(frame);
            canvas.drawColor(Color.TRANSPARENT);
            float scale = Math.min(448f / movie.width(), 448f / movie.height());
            float width = movie.width() * scale;
            float height = movie.height() * scale;
            movie.draw(canvas, (CANVAS_SIZE - width) / 2f, (CANVAS_SIZE - height) / 2f);
            frames.add(frame);
        }
        return frames;
    }

    private List<Bitmap> decodeVideo(Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        List<Bitmap> frames = new ArrayList<>();
        try {
            retriever.setDataSource(this, uri);
            String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationText == null ? 0 : Long.parseLong(durationText);
            if (durationMs <= 0) throw new IOException("تعذّر قراءة مدة الفيديو.");
            long sampled = Math.min(durationMs, 2400);
            int count = 12;
            for (int i = 0; i < count; i++) {
                long timeUs = (sampled * i / count) * 1000L;
                Bitmap source = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (source == null) continue;
                Bitmap frame = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
                drawContained(new Canvas(frame), source, 1f);
                source.recycle();
                frames.add(frame);
            }
            if (frames.size() < 2) throw new IOException("لم أستطع استخراج إطارات من الفيديو.");
            return frames;
        } finally {
            retriever.release();
        }
    }

    private void drawContained(Canvas canvas, Bitmap source, float pulse) {
        canvas.drawColor(Color.TRANSPARENT);
        float available = 448f * pulse;
        float scale = Math.min(available / source.getWidth(), available / source.getHeight());
        float width = source.getWidth() * scale;
        float height = source.getHeight() * scale;
        float left = (CANVAS_SIZE - width) / 2f;
        float top = (CANVAS_SIZE - height) / 2f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, new RectF(left, top, left + width, top + height), paint);
    }

    private void encodeAnimatedWebp(List<Bitmap> frames, File destination) throws Exception {
        if (frames.size() < 2) throw new IOException("يحتاج التحويل إلى إطارين على الأقل.");
        WebPAnimEncoderOptions options = new WebPAnimEncoderOptions(
                Boolean.TRUE, null, null, Boolean.FALSE, Boolean.FALSE,
                new WebPMuxAnimParams(Color.TRANSPARENT, 0));
        WebPAnimEncoder encoder = new WebPAnimEncoder(this, CANVAS_SIZE, CANVAS_SIZE, options);
        try {
            encoder.configure(null, WebPPreset.WEBP_PRESET_PICTURE);
            long timestamp = 0;
            for (Bitmap frame : frames) {
                encoder.addFrame(timestamp, frame);
                timestamp += 100;
            }
            encoder.assemble(timestamp, Uri.fromFile(destination));
        } finally {
            encoder.release();
        }
    }

    private void refreshStickers() {
        List<String> names = stickerNames();
        stickerGrid.removeAllViews();
        for (int start = 0; start < names.size(); start += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 2 && start + column < names.size(); column++) {
                String name = names.get(start + column);
                row.addView(stickerCard(name), new LinearLayout.LayoutParams(0, dp(190), 1f));
            }
            if (names.size() % 2 == 1 && start + 2 >= names.size()) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(190), 1f));
            }
            LinearLayout.LayoutParams rp = matchWrap();
            rp.bottomMargin = dp(8);
            stickerGrid.addView(row, rp);
        }
        status.setText("في الحزمة " + names.size() + " من 30 ملصقًا.");
        addButton.setEnabled(names.size() >= 3 && !busy);
        addButton.setText(names.size() < 3 ? "أضف " + (3 - names.size()) + " ملصقات أخرى أولًا" : "أضف الحزمة إلى واتساب");
    }

    private View stickerCard(String name) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8), dp(8), dp(8), dp(6));
        card.setBackgroundColor(Color.WHITE);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        File f = new File(folder(this), name);
        Bitmap preview = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (preview != null) image.setImageBitmap(preview);
        card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128)));
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(11);
        label.setTextColor(Color.rgb(71, 85, 105));
        label.setGravity(Gravity.CENTER);
        card.addView(label, matchWrap());
        Button remove = makeButton("حذف");
        remove.setTextSize(12);
        remove.setOnClickListener(v -> {
            if (stickerNames().size() <= 3) {
                status.setText("احتفظ بثلاثة ملصقات على الأقل حتى تستطيع إضافة الحزمة.");
                return;
            }
            new File(folder(this), name).delete();
            removeStickerName(name);
            refreshStickers();
        });
        card.addView(remove, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        card.setLayoutParams(params);
        return card;
    }

    private void addPackToWhatsApp() {
        List<String> names = stickerNames();
        if (names.size() < 3) {
            status.setText("أضف ثلاثة ملصقات على الأقل قبل التثبيت.");
            return;
        }
        saveFields();
        String packageName = "com.whatsapp";
        if (!isPackageInstalled(packageName)) {
            packageName = "com.whatsapp.w4b";
            if (!isPackageInstalled(packageName)) {
                status.setText("ثبّت واتساب أو واتساب للأعمال أولًا.");
                return;
            }
        }
        Intent intent = new Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK");
        intent.setPackage(packageName);
        intent.putExtra("sticker_pack_id", ID);
        intent.putExtra("sticker_pack_authority", AUTH);
        intent.putExtra("sticker_pack_name", packTitle());
        try {
            startActivityForResult(intent, 22);
        } catch (Exception e) {
            status.setText("تعذّر فتح واتساب لإضافة الحزمة: " + e.getMessage());
        }
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensurePackFiles() {
        File dir = folder(this);
        if (!dir.exists()) dir.mkdirs();
        File tray = new File(dir, "tray.png");
        try {
            if (!tray.exists()) copyAsset("stickers/tray.png", tray);
            List<String> existing = stickerNames();
            if (existing.isEmpty()) {
                for (int i = 1; i <= 3; i++) {
                    String name = "sticker-" + i + ".webp";
                    File sticker = new File(dir, name);
                    try {
                        copyAsset("stickers/" + name, sticker);
                        addStickerName(name);
                    } catch (Exception ignored) {
                        sticker.delete();
                    }
                }
            } else {
                for (File f : dir.listFiles()) {
                    if (f.isFile() && f.getName().matches("sticker-[0-9]+\\.webp") && !existing.contains(f.getName())) {
                        addStickerName(f.getName());
                    }
                }
            }
        } catch (Exception ignored) {
            // The user can still create a complete pack from their own media.
        }
    }

    private void copyAsset(String assetName, File destination) throws IOException {
        try (InputStream in = getAssets().open(assetName); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
    }

    private static File folder(android.content.Context context) {
        return new File(context.getFilesDir(), "packs/" + ID);
    }

    private List<String> stickerNames() {
        Set<String> saved = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_STICKERS, null);
        List<String> names = saved == null ? new ArrayList<>() : new ArrayList<>(saved);
        Collections.sort(names, Comparator.comparingInt(MainActivity::stickerNumber));
        return names;
    }

    private void addStickerName(String name) {
        List<String> names = stickerNames();
        if (!names.contains(name)) names.add(name);
        saveStickerNames(names);
    }

    private void removeStickerName(String name) {
        List<String> names = stickerNames();
        names.remove(name);
        saveStickerNames(names);
    }

    private void saveStickerNames(List<String> names) {
        Set<String> sorted = new TreeSet<>(names);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_STICKERS, sorted).apply();
        getContentResolver().notifyChange(Uri.parse("content://" + AUTH + "/metadata/" + ID), null);
    }

    private File nextStickerFile() throws IOException {
        List<String> names = stickerNames();
        boolean[] used = new boolean[MAX_STICKERS + 1];
        for (String name : names) {
            int n = stickerNumber(name);
            if (n >= 1 && n <= MAX_STICKERS) used[n] = true;
        }
        for (int i = 1; i <= MAX_STICKERS; i++) {
            if (!used[i]) return new File(folder(this), "sticker-" + i + ".webp");
        }
        throw new IOException("وصلت إلى الحد الأقصى للحزمة.");
    }

    private static int stickerNumber(String name) {
        try {
            return Integer.parseInt(name.replace("sticker-", "").replace(".webp", ""));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private void recycleFrames(List<Bitmap> frames) {
        for (Bitmap frame : frames) if (frame != null && !frame.isRecycled()) frame.recycle();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    private void loadFields() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        packName.setText(prefs.getString(KEY_PACK_NAME, "ملصقات حماد"));
        publisher.setText(prefs.getString(KEY_PUBLISHER, "KWL360"));
    }

    private void saveFields() {
        if (packName == null || publisher == null) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_PACK_NAME, cleanField(packName.getText().toString(), "ملصقات حماد"))
                .putString(KEY_PUBLISHER, cleanField(publisher.getText().toString(), "KWL360"))
                .apply();
    }

    private String packTitle() {
        return cleanField(packName.getText().toString(), "ملصقات حماد");
    }

    private String publisherName() {
        return cleanField(publisher.getText().toString(), "KWL360");
    }

    private String cleanField(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) result = fallback;
        return result.length() > 128 ? result.substring(0, 128) : result;
    }

    private void setBusyMessage(String value) {
        runOnUiThread(() -> status.setText(value));
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(15, 23, 42));
        view.setTextSize(18);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(22, 102, 106)));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public static class Provider extends ContentProvider {
        private static final String[] META = {
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher",
                "sticker_pack_icon", "android_play_store_link", "ios_app_download_link",
                "publisher_email", "publisher_website", "privacy_policy_website",
                "license_agreement_website", "image_data_version", "avoid_cache",
                "animated_sticker_pack"
        };
        private static final String[] STICKERS = {
                "sticker_file_name", "sticker_emoji", "sticker_accessibility_text"
        };

        @Override public boolean onCreate() { return true; }

        @Override public String getType(Uri uri) {
            return uri.toString().endsWith(".png") ? "image/png" : "image/webp";
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            List<String> path = uri.getPathSegments();
            if (path.isEmpty()) return null;
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS, MODE_PRIVATE);
            if ("metadata".equals(path.get(0)) && (path.size() == 1 || (path.size() == 2 && ID.equals(path.get(1))))) {
                MatrixCursor cursor = new MatrixCursor(META);
                String name = prefs.getString(KEY_PACK_NAME, "ملصقات حماد");
                String author = prefs.getString(KEY_PUBLISHER, "KWL360");
                String version = String.valueOf(prefs.getLong("image_data_version", 1L));
                cursor.addRow(new Object[]{ID, name, author, "tray.png", "", "", "",
                        "https://kwl360.com", "", "", version, 0, 1});
                return cursor;
            }
            if (path.size() == 2 && "stickers".equals(path.get(0)) && ID.equals(path.get(1))) {
                MatrixCursor cursor = new MatrixCursor(STICKERS);
                Set<String> names = prefs.getStringSet(KEY_STICKERS, null);
                if (names != null) {
                    List<String> ordered = new ArrayList<>(names);
                    Collections.sort(ordered, Comparator.comparingInt(MainActivity::stickerNumber));
                    for (String name : ordered) cursor.addRow(new Object[]{name, "🇵🇸", "ملصق متحرك"});
                }
                return cursor;
            }
            return null;
        }

        @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
            List<String> path = uri.getPathSegments();
            if (path.size() != 3 || !"stickers_asset".equals(path.get(0)) || !ID.equals(path.get(1))
                    || !path.get(2).matches("tray\\.png|sticker-[0-9]+\\.webp")) {
                throw new FileNotFoundException("Invalid sticker asset");
            }
            File file = new File(folder(getContext()), path.get(2));
            if (!file.isFile()) throw new FileNotFoundException("Sticker not found");
            return new AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length());
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return openAssetFile(uri, mode).getParcelFileDescriptor();
        }

        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
