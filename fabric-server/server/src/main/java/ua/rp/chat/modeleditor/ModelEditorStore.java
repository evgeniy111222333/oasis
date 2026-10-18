package ua.rp.chat.modeleditor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Consumer;

/** Atomic primary replacement; the backup always contains a previously validated document. */
public final class ModelEditorStore {
    private final Path file;
    private ModelEditorDocument lastValid;
    private Consumer<ModelEditorDocument> validator = document -> {};
    public ModelEditorStore(Path file) { this.file = file.toAbsolutePath(); }
    public synchronized ModelEditorDocument load() throws IOException {
        return load(document -> {});
    }
    public synchronized ModelEditorDocument load(Consumer<ModelEditorDocument> validator) throws IOException {
        this.validator = validator;
        Path backup = backup();
        if (!Files.exists(file) && !Files.exists(backup)) return ModelEditorDocument.empty();
        IOException primaryFailure;
        try { return lastValid = readValidated(file); }
        catch (IOException invalid) { primaryFailure = invalid; }
        try { return lastValid = readValidated(backup); }
        catch (IOException invalid) {
            primaryFailure.addSuppressed(invalid);
            throw primaryFailure;
        }
    }
    private ModelEditorDocument readValidated(Path path) throws IOException {
        ModelEditorDocument loaded = read(path);
        try { validator.accept(loaded); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid model placement items: " + path, invalid); }
        return loaded;
    }
    private ModelEditorDocument read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(ModelEditorDocument.MAX_JSON_BYTES + 1);
            if (bytes.length > ModelEditorDocument.MAX_JSON_BYTES)
                throw new IOException("Model placement file is oversized: " + path);
            try {
                // A reporting decoder rejects damaged UTF-8 instead of silently changing IDs.
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                return ModelEditorDocument.decode(text);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid model placement file: " + path, invalid);
            }
        }
    }
    public synchronized void save(ModelEditorDocument document) throws IOException {
        byte[] bytes = document.encode().getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        // Do not rotate a damaged file over the previous valid backup.
        ModelEditorDocument previous = lastValid;
        if (previous == null && Files.isRegularFile(file)) {
            try { previous = readValidated(file); }
            catch (IOException damaged) { /* Keep existing validated backup. */ }
        }
        if (previous != null) atomicWrite(backup(), previous.encode().getBytes(StandardCharsets.UTF_8));
        atomicWrite(file, bytes);
        lastValid = document;
    }
    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Fail closed if the filesystem cannot atomically replace; never truncate the live file.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private Path backup() { return file.resolveSibling(file.getFileName() + ".bak"); }
}
