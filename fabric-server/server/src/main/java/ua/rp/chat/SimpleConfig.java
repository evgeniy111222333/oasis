package ua.rp.chat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class SimpleConfig {
    private final Map<String, String> values = new HashMap<>();
    private final File file;

    public SimpleConfig(File file) {
        this.file = file;
        if (!file.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            Stack<String> sections = new Stack<>();
            Stack<Integer> indents = new Stack<>();
            indents.push(-1);

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                // Count leading spaces
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') {
                    indent++;
                }

                while (!indents.isEmpty() && indent <= indents.peek()) {
                    indents.pop();
                    if (!sections.isEmpty()) {
                        sections.pop();
                    }
                }

                int colonIdx = line.indexOf(':');
                if (colonIdx == -1) {
                    continue;
                }

                String key = line.substring(indent, colonIdx).trim();
                String val = line.substring(colonIdx + 1).trim();

                if (val.isEmpty()) {
                    // It's a section
                    sections.push(key);
                    indents.push(indent);
                } else {
                    // Strip quotes if present
                    if (val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                    } else if (val.startsWith("'") && val.endsWith("'")) {
                        val = val.substring(1, val.length() - 1);
                    }

                    // Build key path
                    StringBuilder fullKey = new StringBuilder();
                    for (String section : sections) {
                        fullKey.append(section).append(".");
                    }
                    fullKey.append(key);
                    values.put(fullKey.toString(), val);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getString(String path, String def) {
        return values.getOrDefault(path, def);
    }

    public int getInt(String path, int def) {
        String val = values.get(path);
        if (val == null) return def;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBoolean(String path, boolean def) {
        String val = values.get(path);
        if (val == null) return def;
        return Boolean.parseBoolean(val);
    }

    /** Persists a top-level integer without destroying comments or unrelated YAML. */
    public synchronized void setInt(String path, int value) {
        if (path == null || path.isBlank() || path.contains(".")) {
            throw new IllegalArgumentException("Only top-level scalar keys can be updated");
        }
        values.put(path, Integer.toString(value));
        try {
            List<String> lines = file.exists()
                    ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
                    : new java.util.ArrayList<>();
            String prefix = path + ":";
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                        && line.trim().startsWith(prefix)) {
                    lines.set(i, path + ": " + value);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) lines.add(path + ": " + value);
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            java.nio.file.Path temp = file.toPath().resolveSibling(file.getName() + ".tmp");
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist " + path + " to " + file, e);
        }
    }
}
