package soka;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Pembaca config.properties dengan pesan error yang jelas kalau key hilang. */
public final class Config {
    private final Properties props = new Properties();

    public Config() throws IOException {
        try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                throw new IOException("config.properties tidak ditemukan di classpath");
            }
            props.load(in);
        }
    }

    public String getString(String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("Key tidak ada di config.properties: " + key);
        }
        return value.trim();
    }

    public int getInt(String key) {
        return Integer.parseInt(getString(key));
    }

    public long getLong(String key) {
        return Long.parseLong(getString(key));
    }

    public double getDouble(String key) {
        return Double.parseDouble(getString(key));
    }
}
