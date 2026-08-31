package goodysgui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Load and save UTF-8 text the same way console v2 did, but for a JTextArea.
 *
 * WORKING: JTextArea always uses '\n' in memory. On disk we write a List of
 * lines so Files.write can apply the platform newline (LF on Unix, CRLF on
 * Windows) — that is the Java 7 NIO.2 behaviour v2 already relied on.
 */
final class TextFileIO {

    private TextFileIO() {
    }

    static String read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        // WORKING (Java 8): String.join is Java 8. This rebuilds the buffer
        // with '\n' so JTextArea.setText does not see stray '\r' characters.
        return String.join("\n", lines);
    }

    static void write(Path path, String text) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        // WORKING: limit -1 keeps a trailing empty line ("hello\n" stays two parts).
        String[] parts = text.split("\n", -1);
        Files.write(path, Arrays.asList(parts), StandardCharsets.UTF_8);
    }
}
