package com.superzeka.kolaj;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_FOLDER = 2;
    private static final int REQ_WRITE_PERM = 3;

    private static final String[] RATIO_LABELS = {"Kare 1:1", "Dikey 4:5", "Dikey 9:16 (Story)", "Yatay 16:9", "Yatay 4:3", "A4 Dikey"};
    private static final float[] RATIOS = {1f, 4f / 5f, 9f / 16f, 16f / 9f, 4f / 3f, 1f / 1.4142f};

    private static final String[] SIZE_LABELS = {"2048 px (hızlı)", "4096 px (önerilen)", "6144 px (yüksek)", "8192 px (çok yüksek)"};
    private static final int[] SIZES = {2048, 4096, 6144, 8192};

    private static final String[] BG_LABELS = {"Beyaz", "Siyah", "Gri", "Krem"};
    private static final int[] BG_COLORS = {Color.WHITE, Color.BLACK, Color.rgb(60, 60, 60), Color.rgb(250, 243, 224)};

    private final LinkedHashSet<Uri> selected = new LinkedHashSet<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView countText;
    private TextView statusText;
    private ProgressBar progress;
    private ImageView preview;
    private Spinner ratioSpinner;
    private Spinner sizeSpinner;
    private Spinner bgSpinner;
    private SeekBar spacingBar;
    private TextView spacingLabel;
    private CheckBox shuffleBox;
    private Button createBtn;
    private Button saveBtn;
    private Button shareBtn;

    private Bitmap result;
    private Uri savedUri;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) selected.addAll(uris);
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) selected.add(uri);
        }
        updateCount();
    }

    private void buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        countText = new TextView(this);
        countText.setTextSize(20);
        countText.setGravity(Gravity.CENTER);
        root.addView(countText);

        TextView hint = new TextView(this);
        hint.setText("Seçim sınırı yok: istediğin kadar fotoğraf seç, tekrar tekrar ekleyebilirsin. "
                + "Bir klasörün tamamını da tek seferde ekleyebilirsin. "
                + "Galeriden 'Paylaş' ile de bu uygulamaya gönderebilirsin.");
        hint.setPadding(0, dp(4), 0, dp(8));
        root.addView(hint);

        LinearLayout pickRow = row();
        Button pickBtn = button("Fotoğraf Ekle");
        pickBtn.setOnClickListener(v -> pickImages());
        Button folderBtn = button("Klasör Ekle");
        folderBtn.setOnClickListener(v -> pickFolder());
        pickRow.addView(pickBtn, weight());
        pickRow.addView(folderBtn, weight());
        root.addView(pickRow);

        Button clearBtn = button("Seçimi Temizle");
        clearBtn.setOnClickListener(v -> {
            if (busy) return;
            selected.clear();
            updateCount();
        });
        root.addView(clearBtn);

        root.addView(label("Kolaj oranı"));
        ratioSpinner = spinner(RATIO_LABELS, 0);
        root.addView(ratioSpinner);

        root.addView(label("Çıktı boyutu (uzun kenar)"));
        sizeSpinner = spinner(SIZE_LABELS, 1);
        root.addView(sizeSpinner);

        root.addView(label("Arka plan rengi"));
        bgSpinner = spinner(BG_LABELS, 0);
        root.addView(bgSpinner);

        spacingLabel = label("");
        root.addView(spacingLabel);
        spacingBar = new SeekBar(this);
        spacingBar.setMax(20);
        spacingBar.setProgress(2);
        spacingBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                updateSpacingLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        root.addView(spacingBar);
        updateSpacingLabel();

        shuffleBox = new CheckBox(this);
        shuffleBox.setText("Fotoğrafları karıştır");
        root.addView(shuffleBox);

        createBtn = button("KOLAJ OLUŞTUR");
        createBtn.setOnClickListener(v -> createCollage());
        root.addView(createBtn);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        statusText = new TextView(this);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(0, dp(8), 0, dp(8));
        root.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout outRow = row();
        saveBtn = button("Galeriye Kaydet");
        saveBtn.setOnClickListener(v -> saveResult());
        shareBtn = button("Paylaş");
        shareBtn.setOnClickListener(v -> shareResult());
        outRow.addView(saveBtn, weight());
        outRow.addView(shareBtn, weight());
        root.addView(outRow);

        setContentView(scroll);
        updateCount();
        setResultButtons(false);
    }

    private void updateSpacingLabel() {
        spacingLabel.setText("Fotoğraflar arası boşluk: " + spacingBar.getProgress());
    }

    private void updateCount() {
        countText.setText("Seçilen fotoğraf: " + selected.size());
    }

    private void setResultButtons(boolean enabled) {
        saveBtn.setEnabled(enabled);
        shareBtn.setEnabled(enabled);
    }

    private void pickImages() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_PICK);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("image/*");
            fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(fallback, "Fotoğraf seç"), REQ_PICK);
        }
    }

    private void pickFolder() {
        if (busy) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER);
        } catch (Exception e) {
            toast("Klasör seçici bulunamadı");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK) {
            int before = selected.size();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) selected.add(u);
                }
            } else if (data.getData() != null) {
                selected.add(data.getData());
            }
            toast((selected.size() - before) + " fotoğraf eklendi");
            updateCount();
        } else if (requestCode == REQ_FOLDER && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            statusText.setText("Klasör taranıyor...");
            executor.execute(() -> {
                List<Uri> found = new ArrayList<>();
                scanTree(tree, DocumentsContract.getTreeDocumentId(tree), found);
                main.post(() -> {
                    int before = selected.size();
                    selected.addAll(found);
                    statusText.setText("");
                    toast((selected.size() - before) + " fotoğraf eklendi");
                    updateCount();
                });
            });
        }
    }

    /** Recursively collects every image inside the chosen folder, including subfolders. */
    private void scanTree(Uri tree, String docId, List<Uri> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String mime = c.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanTree(tree, id, out);
                } else if (mime != null && mime.startsWith("image/")) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void createCollage() {
        if (busy) return;
        if (selected.isEmpty()) {
            toast("Önce fotoğraf seç");
            return;
        }
        final List<Uri> uris = new ArrayList<>(selected);
        if (shuffleBox.isChecked()) Collections.shuffle(uris);

        float ratio = RATIOS[ratioSpinner.getSelectedItemPosition()];
        int longSide = SIZES[sizeSpinner.getSelectedItemPosition()];
        int width, height;
        if (ratio >= 1f) {
            width = longSide;
            height = Math.round(longSide / ratio);
        } else {
            height = longSide;
            width = Math.round(longSide * ratio);
        }
        // Spacing scales with the output so it looks the same at every size.
        int spacing = Math.round(spacingBar.getProgress() * longSide / 2048f);
        int bg = BG_COLORS[bgSpinner.getSelectedItemPosition()];

        if (result != null) {
            preview.setImageDrawable(null);
            result.recycle();
            result = null;
        }
        savedUri = null;
        setResultButtons(false);
        busy = true;
        createBtn.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setMax(uris.size());
        progress.setProgress(0);
        statusText.setText("Hazırlanıyor...");

        final int fw = width, fh = height;
        executor.execute(() -> {
            Bitmap out = null;
            String error = null;
            try {
                out = CollageBuilder.build(getContentResolver(), uris, fw, fh, spacing, bg,
                        (done, total) -> main.post(() -> {
                            progress.setProgress(done);
                            statusText.setText(done + " / " + total + " fotoğraf işlendi");
                        }));
            } catch (OutOfMemoryError e) {
                error = "Bellek yetmedi. Daha küçük bir çıktı boyutu seç.";
            } catch (Exception e) {
                error = "Hata: " + e.getMessage();
            }
            final Bitmap bmp = out;
            final String err = error;
            main.post(() -> {
                busy = false;
                createBtn.setEnabled(true);
                progress.setVisibility(View.GONE);
                if (bmp == null) {
                    statusText.setText(err);
                    return;
                }
                result = bmp;
                statusText.setText("Kolaj hazır: " + bmp.getWidth() + " x " + bmp.getHeight() + " px, "
                        + uris.size() + " fotoğraf");
                showPreview(bmp);
                setResultButtons(true);
            });
        });
    }

    private void showPreview(Bitmap bmp) {
        int max = 1600;
        float scale = Math.min(1f, max / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
        Bitmap small = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * scale)),
                Math.max(1, Math.round(bmp.getHeight() * scale)), true);
        preview.setImageBitmap(small);
    }

    private void saveResult() {
        if (result == null || busy) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_PERM);
            return;
        }
        saveAsync(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveAsync(null);
            } else {
                toast("Kaydetmek için depolama izni gerekli");
            }
        }
    }

    private void saveAsync(Runnable after) {
        busy = true;
        statusText.setText("Kaydediliyor...");
        final Bitmap bmp = result;
        executor.execute(() -> {
            Uri uri = null;
            String err = null;
            try {
                uri = writeToGallery(bmp);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Uri u = uri;
            final String e2 = err;
            main.post(() -> {
                busy = false;
                if (u == null) {
                    statusText.setText("Kaydedilemedi: " + e2);
                    return;
                }
                savedUri = u;
                statusText.setText("Galeriye kaydedildi (Resimler/SuperKolaj)");
                if (after != null) after.run();
            });
        });
    }

    private Uri writeToGallery(Bitmap bmp) throws Exception {
        String name = "Kolaj_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentResolver cr = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SuperKolaj");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SuperKolaj");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            values.put(MediaStore.Images.Media.DATA, new java.io.File(dir, name).getAbsolutePath());
        }
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("MediaStore kaydı oluşturulamadı");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null || !bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)) {
                throw new Exception("JPEG yazılamadı");
            }
        } catch (Exception e) {
            cr.delete(uri, null, null);
            throw e;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        }
        return uri;
    }

    private void shareResult() {
        if (result == null || busy) return;
        if (savedUri == null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_PERM);
                return;
            }
            saveAsync(this::startShare);
        } else {
            startShare();
        }
    }

    private void startShare() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/jpeg");
        share.putExtra(Intent.EXTRA_STREAM, savedUri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Kolajı paylaş"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // --- small UI helpers ---

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    private Spinner spinner(String[] items, int selection) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selection);
        return s;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
