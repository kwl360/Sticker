package com.kwl360.wats;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.io.ByteArrayOutputStream;
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
    private static final String PREFS = "kwl360_sticker_packs";
    private static final String KEY_PACK_IDS = "pack_ids";
    private static final String KEY_SELECTED_PACK = "selected_pack";
    private static final String KEY_STICKERS = "stickers";
    private static final String KEY_ANIMATED = "animated_pack";
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
    private LinearLayout packList;
    private Button addButton;
    private boolean busy;
    private String currentPackId = ID;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(15, 23, 42));
        getWindow().setNavigationBarColor(Color.rgb(15, 23, 42));
        ensurePackFiles();
        currentPackId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SELECTED_PACK, ID);
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
        refreshPacks();
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
        root.setPadding(dp(16), dp(14), dp(16), dp(24));
        root.setBackgroundColor(Color.rgb(241, 245, 249));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);
        setContentView(scroll);

        TextView brand = new TextView(this);
        brand.setText("KWL360  •  WATS");
        brand.setTextColor(Color.rgb(22, 102, 106));
        brand.setTextSize(13);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, dp(4), 0, dp(4));
        root.addView(brand, matchWrap());

        TextView title = new TextView(this);
        title.setText("صانع ملصقات واتساب");
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(4), 0, dp(4));
        root.addView(title, matchWrap());

        TextView intro = new TextView(this);
        intro.setText("أضف صورك أو استورد ZIP، راجع الملصقات، ثم أرسل الحزمة إلى واتساب.");
        intro.setTextColor(Color.rgb(71, 85, 105));
        intro.setTextSize(14);
        intro.setGravity(Gravity.CENTER);
        intro.setPadding(dp(6), 0, dp(6), dp(12));
        root.addView(intro, matchWrap());

        LinearLayout details = sectionCard();
        details.addView(sectionTitle("١. بيانات الحزمة"), matchWrap());
        TextView nameLabel = fieldLabel("اسم الحزمة");
        details.addView(nameLabel, matchWrap());
        packName = new EditText(this);
        packName.setSingleLine(true);
        packName.setHint("مثال: ملصقات حماد");
        packName.setTextSize(16);
        details.addView(packName, matchWrap());

        TextView publisherLabel = fieldLabel("اسم الناشر");
        LinearLayout.LayoutParams publisherLabelParams = matchWrap();
        publisherLabelParams.topMargin = dp(8);
        details.addView(publisherLabel, publisherLabelParams);
        publisher = new EditText(this);
        publisher.setSingleLine(true);
        publisher.setHint("مثال: KWL360");
        publisher.setTextSize(16);
        details.addView(publisher, matchWrap());
        root.addView(details, cardParams());

        LinearLayout packs = sectionCard();
        packs.addView(sectionTitle("٢. حزمك المستوردة"), matchWrap());
        TextView packsHint = new TextView(this);
        packsHint.setText("استورد كل ZIP كحزمة مستقلة، ثم اختر الحزمة التي تريد إضافتها إلى واتساب.");
        packsHint.setTextColor(Color.rgb(71, 85, 105));
        packsHint.setTextSize(13);
        packs.addView(packsHint, matchWrap());
        packList = new LinearLayout(this);
        packList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams packListParams = matchWrap();
        packListParams.topMargin = dp(6);
        packs.addView(packList, packListParams);
        root.addView(packs, cardParams());

        LinearLayout actions = sectionCard();
        actions.addView(sectionTitle("٣. أضف ملصقات"), matchWrap());
        Button choose = makeButton("اختر صورة أو GIF أو فيديو");
        choose.setOnClickListener(v -> chooseMedia());
        LinearLayout.LayoutParams chooseParams = matchWrap();
        chooseParams.topMargin = dp(8);
        actions.addView(choose, chooseParams);

        Button importZip = makeButton("استيراد حزمة ملصقات ZIP");
        importZip.setOnClickListener(v -> chooseZip());
        LinearLayout.LayoutParams zipParams = matchWrap();
        zipParams.topMargin = dp(8);
        actions.addView(importZip, zipParams);

        TextView zipHint = new TextView(this);
        zipHint.setText("يجب أن يحتوي ZIP على 3–30 ملصق WebP من النوع نفسه، بأبعاد 512×512. الثابت حتى 100 كيلوبايت والمتحرك حتى 500 كيلوبايت.");
        zipHint.setTextColor(Color.rgb(71, 85, 105));
        zipHint.setTextSize(12);
        zipHint.setPadding(dp(2), dp(6), dp(2), 0);
        actions.addView(zipHint, matchWrap());

        addButton = makeButton("أضف الحزمة إلى واتساب");
        addButton.setOnClickListener(v -> addPackToWhatsApp());
        LinearLayout.LayoutParams addParams = matchWrap();
        addParams.topMargin = dp(12);
        actions.addView(addButton, addParams);
        root.addView(actions, cardParams());

        status = new TextView(this);
        status.setTextColor(Color.rgb(30, 41, 59));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setBackgroundColor(Color.rgb(232, 240, 240));
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.bottomMargin = dp(12);
        root.addView(status, statusParams);

        LinearLayout contents = sectionCard();
        contents.addView(sectionTitle("٤. راجع ملصقات الحزمة المختارة"), matchWrap());
        stickerGrid = new LinearLayout(this);
        stickerGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridParams = matchWrap();
        gridParams.topMargin = dp(6);
        contents.addView(stickerGrid, gridParams);

        TextView note = new TextView(this);
        note.setText("تحتاج الحزمة إلى 3 ملصقات على الأقل. تُحفظ ملفاتك على الهاتف داخل التطبيق.");
        note.setTextColor(Color.rgb(100, 116, 139));
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(12), 0, 0);
        contents.addView(note, matchWrap());
        root.addView(contents, cardParams());
    }

    private LinearLayout sectionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(14));
        card.setBackgroundColor(Color.WHITE);
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        return params;
    }

    private TextView fieldLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(51, 65, 85));
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        return label;
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
        final String importedPackId = "kwl_" + System.currentTimeMillis();
        String archiveName = queryDisplayName(uri);
        final String importedPackName = cleanField(archiveName.replaceFirst("(?i)\\.zip$", ""), "حزمة جديدة");
        final String importedPublisher = "KWL360";
        worker.execute(() -> {
            List<String> imported = new ArrayList<>();
            String error = null;
            File packDir = folder(this, importedPackId);
            if (!packDir.exists() && !packDir.mkdirs()) {
                error = "تعذّر إنشاء مساحة للحزمة الجديدة.";
            }
            File staging = new File(packDir, ".zip-import");
            Boolean packAnimated = null;
            try {
                if (error != null) throw new IOException(error);
                if (staging.exists()) deleteTree(staging);
                if (!staging.mkdirs()) throw new IOException("تعذّر تجهيز مساحة استيراد مؤقتة.");
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("تعذّرت قراءة ملف ZIP.");
                try (ZipInputStream zip = new ZipInputStream(input)) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.isDirectory()) continue;
                        String lower = entry.getName().toLowerCase(java.util.Locale.ROOT);
                        if (!lower.endsWith(".webp") || lower.contains("/__macosx/")) continue;
                        String base = lower.substring(lower.lastIndexOf('/') + 1);
                        if (base.startsWith("tray") || base.startsWith("icon") || base.startsWith("cover")) continue;
                        if (imported.size() >= MAX_STICKERS) throw new IOException("الملف يحتوي أكثر من 30 ملصقًا.");
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            if (bytes.size() + count > MAX_ANIMATED_BYTES) throw new IOException("أحد الملصقات يتجاوز حد 500 كيلوبايت.");
                            bytes.write(buffer, 0, count);
                        }
                        byte[] sticker = bytes.toByteArray();
                        boolean animated = validateStickerWebp(sticker);
                        if (packAnimated != null && packAnimated != animated) throw new IOException("الحزمة لا تقبل خلط الملصقات الثابتة والمتحركة. استخدم ZIP من نوع واحد.");
                        packAnimated = animated;
                        String name = "sticker-" + (imported.size() + 1) + ".webp";
                        try (FileOutputStream output = new FileOutputStream(new File(staging, name))) {
                            output.write(sticker);
                            output.getFD().sync();
                        }
                        imported.add(name);
                    }
                }
                if (imported.size() < 3) throw new IOException("تحتاج الحزمة إلى 3 ملصقات صالحة على الأقل.");
                installNewPackFiles(staging, packDir, imported);
                SharedPreferences.Editor pack = getSharedPreferences(packPrefsName(importedPackId), MODE_PRIVATE).edit()
                        .putString(KEY_PACK_NAME, importedPackName)
                        .putString(KEY_PUBLISHER, importedPublisher)
                        .putBoolean(KEY_ANIMATED, Boolean.TRUE.equals(packAnimated))
                        .putStringSet(KEY_STICKERS, new TreeSet<>(imported))
                        .putLong("image_data_version", 1L);
                if (!pack.commit()) throw new IOException("تعذّر حفظ بيانات الحزمة المستوردة.");
                File tray = new File(packDir, "tray.png");
                if (!tray.isFile()) copyAsset("stickers/tray.png", tray);
                registerPack(this, importedPackId);
            } catch (Exception e) {
                error = e.getMessage() == null ? "ملف ZIP غير صالح أو لا يحتوي صور WebP." : e.getMessage();
                if (packDir.exists()) deleteTree(packDir);
                unregisterPack(this, importedPackId);
            }
            final String message = error;
            final int count = imported.size();
            runOnUiThread(() -> {
                busy = false;
                if (message == null) {
                    currentPackId = importedPackId;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SELECTED_PACK, currentPackId).apply();
                    loadFields();
                    status.setText("تم استيراد «" + importedPackName + "» كحزمة مستقلة تضم " + count + " ملصقًا.");
                } else {
                    status.setText("تعذّر استيراد ZIP: " + message);
                }
                refreshPacks();
                refreshStickers();
            });
        });
    }

    private static boolean validateStickerWebp(byte[] bytes) throws IOException {
        if (bytes.length < 30 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') {
            throw new IOException("أحد الملفات ليس صورة WebP سليمة.");
        }
        boolean animated = isAnimatedWebp(bytes);
        int maxBytes = animated ? 500 * 1024 : 100 * 1024;
        if (bytes.length > maxBytes) throw new IOException(animated ? "الملصق المتحرك أكبر من 500 كيلوبايت." : "الملصق الثابت أكبر من 100 كيلوبايت.");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth != CANVAS_SIZE || options.outHeight != CANVAS_SIZE) throw new IOException("أبعاد كل ملصق يجب أن تكون 512×512.");
        return animated;
    }

    private static boolean isAnimatedWebp(byte[] bytes) {
        for (int offset = 12; offset + 8 <= bytes.length;) {
            String tag = new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = (bytes[offset + 4] & 255L) | ((bytes[offset + 5] & 255L) << 8)
                    | ((bytes[offset + 6] & 255L) << 16) | ((bytes[offset + 7] & 255L) << 24);
            if ("ANIM".equals(tag)) return true;
            if ("VP8X".equals(tag) && offset + 8 < bytes.length && (bytes[offset + 8] & 2) != 0) return true;
            if (size > bytes.length - offset - 8) break;
            offset += 8 + (int) size + (int) (size & 1);
        }
        return false;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private void installNewPackFiles(File staging, File packDir, List<String> newNames) throws IOException {
        for (String name : newNames) {
            if (!new File(staging, name).renameTo(new File(packDir, name))) {
                throw new IOException("تعذّر تثبيت ملفات الملصقات المستوردة.");
            }
        }
        deleteTree(staging);
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
        if (!stickerNames().isEmpty() && !getSharedPreferences(packPrefsName(currentPackId), MODE_PRIVATE).getBoolean(KEY_ANIMATED, true)) {
            status.setText("هذه الحزمة ثابتة. احذف ملصقاتها أو استورد ZIP متحركًا قبل إضافة GIF أو فيديو.");
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
        String selectedName = getSharedPreferences(packPrefsName(currentPackId), MODE_PRIVATE).getString(KEY_PACK_NAME, "الحزمة المختارة");
        status.setText("«" + selectedName + "»: فيها " + names.size() + " من 30 ملصقًا.");
        addButton.setEnabled(names.size() >= 3 && !busy);
        addButton.setText(names.size() < 3 ? "أضف " + (3 - names.size()) + " ملصقات أخرى أولًا" : "أضف الحزمة المختارة إلى واتساب");
    }

    private void refreshPacks() {
        if (packList == null) return;
        packList.removeAllViews();
        List<String> ids = packIds(this);
        for (String id : ids) {
            SharedPreferences prefs = getSharedPreferences(packPrefsName(id), MODE_PRIVATE);
            String name = prefs.getString(KEY_PACK_NAME, "حزمة ملصقات");
            int count = prefs.getStringSet(KEY_STICKERS, Collections.emptySet()).size();
            Button choice = makeButton((id.equals(currentPackId) ? "✓  " : "") + name + "  •  " + count + " ملصق");
            choice.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    id.equals(currentPackId) ? Color.rgb(15, 23, 42) : Color.rgb(22, 102, 106)));
            choice.setOnClickListener(v -> selectPack(id));
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(4);
            packList.addView(choice, params);
        }
        if (ids.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("لا توجد حزم بعد. استورد ملف ZIP لتظهر هنا.");
            empty.setTextColor(Color.rgb(71, 85, 105));
            packList.addView(empty, matchWrap());
        }
    }

    private void selectPack(String id) {
        if (busy) return;
        saveFields();
        currentPackId = id;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SELECTED_PACK, id).apply();
        loadFields();
        refreshPacks();
        refreshStickers();
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
        String[] labels = {"واتساب العادي", "واتساب للأعمال"};
        String[] packages = {"com.whatsapp", "com.whatsapp.w4b"};
        new AlertDialog.Builder(this)
                .setTitle("إلى أي تطبيق تريد إضافة «" + packTitle() + "»؟")
                .setItems(labels, (dialog, which) -> {
                    String packageName = packages[which];
                    if (!isPackageInstalled(packageName)) {
                        status.setText(which == 0 ? "واتساب العادي غير مثبت على الهاتف." : "واتساب للأعمال غير مثبت على الهاتف.");
                        return;
                    }
                    sendPackToWhatsApp(packageName);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void sendPackToWhatsApp(String packageName) {
        Intent intent = new Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK");
        intent.setPackage(packageName);
        intent.putExtra("sticker_pack_id", currentPackId);
        intent.putExtra("sticker_pack_authority", AUTH);
        intent.putExtra("sticker_pack_name", packTitle());
        try {
            startActivityForResult(intent, 22);
        } catch (Exception e) {
            status.setText("تعذّر فتح التطبيق المختار لإضافة الحزمة: " + e.getMessage());
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
        SharedPreferences index = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> registered = new TreeSet<>(index.getStringSet(KEY_PACK_IDS, Collections.emptySet()));
        SharedPreferences legacy = getSharedPreferences("kwl360_sticker_pack", MODE_PRIVATE);
        SharedPreferences demoPrefs = getSharedPreferences(packPrefsName(ID), MODE_PRIVATE);
        if (!registered.contains(ID) && legacy.contains("stickers")) {
            Set<String> oldNames = legacy.getStringSet("stickers", Collections.emptySet());
            demoPrefs.edit()
                    .putString(KEY_PACK_NAME, legacy.getString("pack_name", "ملصقات حماد"))
                    .putString(KEY_PUBLISHER, legacy.getString("publisher", "KWL360"))
                    .putBoolean(KEY_ANIMATED, legacy.getBoolean("animated_pack", true))
                    .putStringSet(KEY_STICKERS, new TreeSet<>(oldNames))
                    .putLong("image_data_version", legacy.getLong("image_data_version", 1L))
                    .commit();
        }
        registerPack(this, ID);
        if (!index.contains(KEY_SELECTED_PACK)) index.edit().putString(KEY_SELECTED_PACK, ID).apply();
        File dir = folder(this, ID);
        if (!dir.exists()) dir.mkdirs();
        File tray = new File(dir, "tray.png");
        try {
            if (!tray.exists()) copyAsset("stickers/tray.png", tray);
            SharedPreferences prefs = getSharedPreferences(packPrefsName(ID), MODE_PRIVATE);
            Set<String> saved = prefs.getStringSet(KEY_STICKERS, null);
            List<String> existing = saved == null ? new ArrayList<>() : new ArrayList<>(saved);
            if (existing.isEmpty()) {
                for (int i = 1; i <= 3; i++) {
                    String name = "sticker-" + i + ".webp";
                    File sticker = new File(dir, name);
                    try {
                        copyAsset("stickers/" + name, sticker);
                        existing.add(name);
                    } catch (Exception ignored) {
                        sticker.delete();
                    }
                }
                prefs.edit().putString(KEY_PACK_NAME, prefs.getString(KEY_PACK_NAME, "ملصقات تجريبية"))
                        .putString(KEY_PUBLISHER, prefs.getString(KEY_PUBLISHER, "KWL360"))
                        .putBoolean(KEY_ANIMATED, prefs.getBoolean(KEY_ANIMATED, true))
                        .putStringSet(KEY_STICKERS, new TreeSet<>(existing)).putLong("image_data_version", 1L).apply();
            } else {
                for (File f : dir.listFiles()) {
                    if (f.isFile() && f.getName().matches("sticker-[0-9]+\\.webp") && !existing.contains(f.getName())) {
                        existing.add(f.getName());
                    }
                }
                prefs.edit().putStringSet(KEY_STICKERS, new TreeSet<>(existing)).apply();
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

    private static File folder(android.content.Context context, String id) {
        return new File(context.getFilesDir(), "packs/" + id);
    }

    private File folder(android.content.Context context) { return folder(context, currentPackId); }

    private static String packPrefsName(String id) { return "kwl360_pack_" + id; }

    private static List<String> packIds(android.content.Context context) {
        Set<String> saved = context.getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_PACK_IDS, Collections.emptySet());
        ArrayList<String> ids = new ArrayList<>(saved);
        Collections.sort(ids);
        return ids;
    }

    private static void registerPack(android.content.Context context, String id) {
        Set<String> ids = new TreeSet<>(context.getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_PACK_IDS, Collections.emptySet()));
        ids.add(id);
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_PACK_IDS, ids).commit();
    }

    private static void unregisterPack(android.content.Context context, String id) {
        Set<String> ids = new TreeSet<>(context.getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(KEY_PACK_IDS, Collections.emptySet()));
        ids.remove(id);
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(KEY_PACK_IDS, ids).commit();
    }

    private List<String> stickerNames() {
        Set<String> saved = getSharedPreferences(packPrefsName(currentPackId), MODE_PRIVATE).getStringSet(KEY_STICKERS, null);
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
        SharedPreferences prefs = getSharedPreferences(packPrefsName(currentPackId), MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit()
                .putStringSet(KEY_STICKERS, sorted)
                .putLong("image_data_version", prefs.getLong("image_data_version", 0L) + 1L);
        if (names.isEmpty()) editor.putBoolean(KEY_ANIMATED, true);
        editor.apply();
        getContentResolver().notifyChange(Uri.parse("content://" + AUTH + "/metadata/" + currentPackId), null);
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
        SharedPreferences prefs = getSharedPreferences(packPrefsName(currentPackId), MODE_PRIVATE);
        packName.setText(prefs.getString(KEY_PACK_NAME, "حزمة ملصقات"));
        publisher.setText(prefs.getString(KEY_PUBLISHER, "KWL360"));
    }

    private void saveFields() {
        if (packName == null || publisher == null) return;
        getSharedPreferences(packPrefsName(currentPackId), MODE_PRIVATE).edit()
                .putString(KEY_PACK_NAME, cleanField(packName.getText().toString(), "حزمة ملصقات"))
                .putString(KEY_PUBLISHER, cleanField(publisher.getText().toString(), "KWL360"))
                .apply();
        getContentResolver().notifyChange(Uri.parse("content://" + AUTH + "/metadata/" + currentPackId), null);
        refreshPacks();
    }

    private String packTitle() {
        return cleanField(packName.getText().toString(), "حزمة ملصقات");
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
                "sticker_pack_publisher_email", "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website", "image_data_version", "whatsapp_will_not_cache_stickers",
                "animated_sticker_pack"
        };
        private static final String[] STICKERS = {
                "sticker_file_name", "sticker_emoji", "sticker_accessibility_text"
        };

        @Override public boolean onCreate() { return true; }

        @Override public String getType(Uri uri) {
            List<String> path = uri.getPathSegments();
            if (path.size() == 1 && "metadata".equals(path.get(0))) return "vnd.android.cursor.dir/vnd." + AUTH + ".metadata";
            if (path.size() == 2 && "metadata".equals(path.get(0))) return "vnd.android.cursor.item/vnd." + AUTH + ".metadata";
            if (path.size() == 2 && "stickers".equals(path.get(0))) return "vnd.android.cursor.dir/vnd." + AUTH + ".stickers";
            if (path.size() == 3 && "stickers_asset".equals(path.get(0))) {
                return path.get(2).endsWith(".png") ? "image/png" : "image/webp";
            }
            return null;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            List<String> path = uri.getPathSegments();
            if (path.isEmpty()) return null;
            if ("metadata".equals(path.get(0)) && (path.size() == 1 || path.size() == 2)) {
                MatrixCursor cursor = new MatrixCursor(META);
                List<String> ids = path.size() == 2 ? Collections.singletonList(path.get(1)) : packIds(getContext());
                for (String id : ids) {
                    if (!id.matches("[a-zA-Z0-9_-]{1,60}")) continue;
                    SharedPreferences prefs = getContext().getSharedPreferences(packPrefsName(id), MODE_PRIVATE);
                    Set<String> saved = prefs.getStringSet(KEY_STICKERS, Collections.emptySet());
                    if (saved.size() < 3) continue;
                    String name = prefs.getString(KEY_PACK_NAME, "حزمة ملصقات");
                    String author = prefs.getString(KEY_PUBLISHER, "KWL360");
                    String version = String.valueOf(prefs.getLong("image_data_version", 1L));
                    int animated = prefs.getBoolean(KEY_ANIMATED, false) ? 1 : 0;
                    cursor.addRow(new Object[]{id, name, author, "tray.png", "", "", "",
                            "https://kwl360.com", "", "", version, 0, animated});
                }
                return cursor;
            }
            if (path.size() == 2 && "stickers".equals(path.get(0)) && path.get(1).matches("[a-zA-Z0-9_-]{1,60}")) {
                MatrixCursor cursor = new MatrixCursor(STICKERS);
                SharedPreferences prefs = getContext().getSharedPreferences(packPrefsName(path.get(1)), MODE_PRIVATE);
                Set<String> names = prefs.getStringSet(KEY_STICKERS, null);
                if (names != null) {
                    List<String> ordered = new ArrayList<>(names);
                    Collections.sort(ordered, Comparator.comparingInt(MainActivity::stickerNumber));
                    boolean animated = prefs.getBoolean(KEY_ANIMATED, false);
                    for (String name : ordered) cursor.addRow(new Object[]{name, "🇵🇸", animated ? "ملصق متحرك" : "ملصق فلسطيني"});
                }
                return cursor;
            }
            return null;
        }

        @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
            List<String> path = uri.getPathSegments();
            if (path.size() != 3 || !"stickers_asset".equals(path.get(0)) || !path.get(1).matches("[a-zA-Z0-9_-]{1,60}")
                    || !path.get(2).matches("tray\\.png|sticker-[0-9]+\\.webp")) {
                throw new FileNotFoundException("Invalid sticker asset");
            }
            if (!packIds(getContext()).contains(path.get(1))) throw new FileNotFoundException("Unknown sticker pack");
            File file = new File(folder(getContext(), path.get(1)), path.get(2));
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
