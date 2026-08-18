package com.pocketrealm.importer;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic read-only SAF tree included only in debug builds. */
public final class ImportFixtureProvider extends DocumentsProvider {
    private static final String VALID = "valid";
    private static final String WRONG = "wrong";
    private static final String HOST = "host";
    private static final List<String> MPQS = Arrays.asList(
        "base.MPQ", "dbc.MPQ", "fonts.MPQ", "interface.MPQ", "misc.MPQ", "model.MPQ",
        "sound.MPQ", "speech.MPQ", "terrain.MPQ", "texture.MPQ", "wmo.MPQ");
    private static final String[] ROOT_COLUMNS = {
        DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS};
    private static final String[] DOC_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS};

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        cursor.newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID, VALID)
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, VALID)
            .add(DocumentsContract.Root.COLUMN_TITLE, "O11 fixture")
            .add(DocumentsContract.Root.COLUMN_FLAGS, 0);
        File host = hostRoot();
        if (host.isDirectory()) {
            cursor.newRow().add(DocumentsContract.Root.COLUMN_ROOT_ID, HOST)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, HOST)
                .add(DocumentsContract.Root.COLUMN_TITLE, "O11 host client staging")
                .add(DocumentsContract.Root.COLUMN_FLAGS, 0);
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOC_COLUMNS);
        addDocument(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOC_COLUMNS);
        for (String child : children(parent)) addDocument(cursor, child);
        return cursor;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
        throws java.io.FileNotFoundException {
        if (!"r".equals(mode)) throw new java.io.FileNotFoundException("fixture is read-only");
        if (isHost(id)) {
            File file = hostFile(id);
            if (!file.isFile()) throw new java.io.FileNotFoundException(id);
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        byte[] bytes = content(id);
        File cache = new File(getContext().getCacheDir(), "o11-provider");
        cache.mkdirs();
        File file = new File(cache, id.replaceAll("[^A-Za-z0-9._-]", "_"));
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(bytes); stream.getFD().sync();
        } catch (java.io.IOException error) {
            throw new java.io.FileNotFoundException(error.toString());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getDocumentType(String id) { return mime(id); }
    @Override public boolean isChildDocument(String parent, String id) {
        return id.startsWith(parent + ":");
    }

    private List<String> children(String parent) {
        List<String> values = new ArrayList<>();
        if (isHost(parent)) {
            File directory = hostFile(parent);
            File[] files = directory.listFiles();
            if (files != null) {
                Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
                for (File file : files) values.add(parent + ":" + file.getName());
            }
            return values;
        }
        if (VALID.equals(parent) || WRONG.equals(parent)) {
            values.add(parent + ":wow"); values.add(parent + ":data");
            for (int index = 0; index < 12; index++) values.add(parent + ":note" + index);
        } else if ((VALID + ":data").equals(parent) || (WRONG + ":data").equals(parent)) {
            for (String mpq : MPQS) values.add(parent + ":" + mpq);
        }
        return values;
    }

    private void addDocument(MatrixCursor cursor, String id) {
        if (isHost(id)) {
            File file = hostFile(id);
            cursor.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
                .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    HOST.equals(id) ? "WoW 1.12.1 host staging" : file.getName())
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE, file.isDirectory()
                    ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream")
                .add(DocumentsContract.Document.COLUMN_SIZE, file.isFile() ? file.length() : 0L)
                .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
                .add(DocumentsContract.Document.COLUMN_FLAGS, 0);
            return;
        }
        boolean directory = VALID.equals(id) || WRONG.equals(id) || id.endsWith(":data");
        byte[] bytes = directory ? new byte[0] : content(id);
        cursor.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName(id))
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime(id))
            .add(DocumentsContract.Document.COLUMN_SIZE, bytes.length)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1700000000000L)
            .add(DocumentsContract.Document.COLUMN_FLAGS, 0);
    }

    private String displayName(String id) {
        if (VALID.equals(id) || WRONG.equals(id)) return "fixture";
        if (id.endsWith(":wow")) return "WoW.exe";
        if (id.endsWith(":data")) return "Data";
        if (id.contains(":note")) return "Readme-" + id.substring(id.lastIndexOf("note") + 4) + ".txt";
        return id.substring(id.lastIndexOf(':') + 1);
    }

    private String mime(String id) {
        if (isHost(id)) return hostFile(id).isDirectory()
            ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
        return VALID.equals(id) || WRONG.equals(id) || id.endsWith(":data")
            ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
    }

    private static byte[] content(String id) {
        if (id.endsWith(":wow")) return syntheticPe(id.startsWith(WRONG) ? 6005 : 5875);
        if (id.contains(":data:")) return new byte[] {0x4d, 0x50, 0x51, 0x1a, 1, 2, 3, 4};
        if (id.contains(":note")) return ("fixture-" + id.substring(id.lastIndexOf("note") + 4)).getBytes();
        throw new IllegalArgumentException("not a document: " + id);
    }

    private static boolean isHost(String id) {
        return HOST.equals(id) || id.startsWith(HOST + ":");
    }

    private File hostRoot() {
        return new File(getContext().getExternalFilesDir(null), "wow");
    }

    private File hostFile(String id) {
        try {
            File root = hostRoot().getCanonicalFile();
            if (HOST.equals(id)) return root;
            String relative = id.substring((HOST + ":").length()).replace(':', File.separatorChar);
            File file = new File(root, relative).getCanonicalFile();
            if (!file.toPath().startsWith(root.toPath())) throw new SecurityException("host document escaped root");
            return file;
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] syntheticPe(int build) {
        byte[] bytes = new byte[4096]; bytes[0] = 'M'; bytes[1] = 'Z';
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x3c, 0x100); bytes[0x100] = 0x50; bytes[0x101] = 0x45;
        buffer.putShort(0x104, (short) 0x14c); buffer.putShort(0x118, (short) 0x10b);
        buffer.putInt(0x300, 0xfeef04bd); buffer.putInt(0x308, (1 << 16) | 12);
        buffer.putInt(0x30c, (1 << 16) | build);
        return bytes;
    }
}
