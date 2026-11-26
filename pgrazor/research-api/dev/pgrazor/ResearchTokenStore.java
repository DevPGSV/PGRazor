package dev.pgrazor;

import android.content.Context;
import android.provider.Settings;
import android.util.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

final class ResearchTokenStore implements ResearchApiSession.Store {
    private final AtomicFile file;

    ResearchTokenStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "pgrazor-research-api-v2"));
    }

    static long boot(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    @Override public ResearchApiSession.Saved load() throws IOException {
        try (DataInputStream in = new DataInputStream(file.openRead())) {
            if (in.available() > 128) throw new IOException("Invalid research token record");
            int format = in.readInt();
            if (format != 1 && format != 2) throw new IOException("Invalid research token record");
            ResearchApiSession.Saved saved = new ResearchApiSession.Saved(in.readUTF(), in.readLong(), in.readLong(), in.readLong());
            if ((format == 1 && saved.boot < -1) || in.read() != -1) throw new IOException("Invalid research token record");
            return saved;
        } catch (FileNotFoundException missing) {
            if (file.getBaseFile().exists()) throw missing;
            return null;
        }
    }

    @Override public void save(ResearchApiSession.Saved saved) throws IOException {
        // A tombstone replaces the previous credential atomically.
        FileOutputStream stream = file.startWrite();
        try {
            DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(2);
            out.writeUTF(saved == null ? "" : saved.token);
            out.writeLong(saved == null ? -1 : saved.boot);
            out.writeLong(saved == null ? 0 : saved.issued);
            out.writeLong(saved == null ? 0 : saved.deadline);
            out.flush();
            file.finishWrite(stream);
        } catch (IOException | RuntimeException failure) {
            file.failWrite(stream);
            throw failure;
        }
        ResearchApiSession.Saved result = load();
        if (result == null || (saved == null ? !result.token.isEmpty()
                : !saved.token.equals(result.token) || saved.boot != result.boot
                    || saved.issued != result.issued || saved.deadline != result.deadline)) {
            throw new IOException("Research token write could not be verified");
        }
    }

    @Override public void clearAfterFailure() {
        file.delete();
    }
}
