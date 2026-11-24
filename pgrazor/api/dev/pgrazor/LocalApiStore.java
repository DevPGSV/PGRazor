package dev.pgrazor;

import android.content.Context;
import android.util.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

final class LocalApiStore implements LocalApiController.Store {
    private static final int FORMAT = 1;
    private final Context context;

    LocalApiStore(Context context) {
        this.context = context;
    }

    private AtomicFile file() {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "pgrazor-local-api-v1"));
    }

    @Override public LocalApiController.Settings load() throws IOException {
        AtomicFile file = file();
        try (DataInputStream input = new DataInputStream(file.openRead())) {
            if (input.available() > 128 || input.readInt() != FORMAT) throw new IOException("Invalid local API settings");
            LocalApiConfig config = new LocalApiConfig(input.readUTF(), input.readInt());
            String token = input.readUTF();
            if (!token.matches("[A-Za-z0-9_-]{43}") || input.read() != -1) {
                throw new IOException("Invalid local API settings");
            }
            return new LocalApiController.Settings(config, token);
        } catch (FileNotFoundException error) {
            if (file.getBaseFile().exists()) throw error;
            return null;
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid local API settings", malformed);
        }
    }

    @Override public void save(LocalApiController.Settings settings) throws IOException {
        AtomicFile file = file();
        FileOutputStream stream = file.startWrite();
        try {
            DataOutputStream output = new DataOutputStream(stream);
            output.writeInt(FORMAT);
            output.writeUTF(settings.config.host);
            output.writeInt(settings.config.port);
            output.writeUTF(settings.token);
            output.flush();
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            file.failWrite(stream);
            throw error;
        }
        LocalApiController.Settings verified = load();
        if (verified == null || !verified.token.equals(settings.token)
                || !verified.config.host.equals(settings.config.host) || verified.config.port != settings.config.port) {
            throw new IOException("Could not persist local API settings");
        }
    }
}
