package ai.agentican.blocks.examples;

import java.io.IOException;
import java.util.Properties;

public final class Keys {

    private static final Properties PROPS = load();

    private Keys() {}

    public static String get(String name) {

        var fromFile = PROPS.getProperty(name);

        if (fromFile != null && !fromFile.isBlank()) return fromFile;

        return System.getenv(name);
    }

    public static String require(String name) {

        var value = get(name);

        if (value == null || value.isBlank())
            throw new IllegalStateException(
                    "Missing " + name + ". Set it as an environment variable, "
                            + "or add it to src/examples/resources/keys.properties.");

        return value;
    }

    private static Properties load() {

        var props = new Properties();

        try (var in = Keys.class.getResourceAsStream("/keys.properties")) {

            if (in != null) props.load(in);
        }
        catch (IOException ignored) {
            // File present but unreadable — fall back to env vars only.
        }

        return props;
    }
}
