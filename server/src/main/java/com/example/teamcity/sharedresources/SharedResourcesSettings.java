package com.example.teamcity.sharedresources;

import jetbrains.buildServer.serverSide.ServerPaths;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.Properties;

/**
 * Persists plugin settings in a .properties file under TC's plugin-data directory.
 * All getters return safe defaults when no value has been saved yet.
 */
public class SharedResourcesSettings {

    private static final Logger LOG = Logger.getLogger(SharedResourcesSettings.class);

    static final String KEY_LOCK_TIMEOUT    = "lockTimeoutSeconds";
    static final String KEY_RETRY_LOCK      = "retryAfterLockSeconds";
    static final String KEY_RETRY_PERSIST   = "retryAfterPersistSeconds";

    static final int DEFAULT_LOCK_TIMEOUT   = 30;
    static final int DEFAULT_RETRY_LOCK     = 5;
    static final int DEFAULT_RETRY_PERSIST  = 10;

    private final File settingsFile;
    private final Properties props = new Properties();

    public SharedResourcesSettings(@NotNull ServerPaths serverPaths) {
        this.settingsFile = new File(serverPaths.getPluginDataDirectory(),
                "shared-resources-api/settings.properties");
        load();
    }

    public int getLockTimeoutSeconds() {
        return getInt(KEY_LOCK_TIMEOUT, DEFAULT_LOCK_TIMEOUT);
    }

    public int getRetryAfterLockSeconds() {
        return getInt(KEY_RETRY_LOCK, DEFAULT_RETRY_LOCK);
    }

    public int getRetryAfterPersistSeconds() {
        return getInt(KEY_RETRY_PERSIST, DEFAULT_RETRY_PERSIST);
    }

    public synchronized void update(int lockTimeoutSeconds,
                                    int retryAfterLockSeconds,
                                    int retryAfterPersistSeconds) {
        props.setProperty(KEY_LOCK_TIMEOUT,  String.valueOf(lockTimeoutSeconds));
        props.setProperty(KEY_RETRY_LOCK,    String.valueOf(retryAfterLockSeconds));
        props.setProperty(KEY_RETRY_PERSIST, String.valueOf(retryAfterPersistSeconds));
        save();
    }

    private int getInt(String key, int defaultValue) {
        String v = props.getProperty(key);
        if (v == null) return defaultValue;
        try {
            int parsed = Integer.parseInt(v.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void load() {
        if (!settingsFile.exists()) return;
        try (InputStream in = new FileInputStream(settingsFile)) {
            props.load(in);
        } catch (IOException e) {
            LOG.warn("Could not load shared-resources-api settings: " + e.getMessage());
        }
    }

    private void save() {
        try {
            File dir = settingsFile.getParentFile();
            if (!dir.exists()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(settingsFile)) {
                props.store(out, "Shared Resources API settings");
            }
        } catch (IOException e) {
            LOG.error("Could not save shared-resources-api settings: " + e.getMessage());
        }
    }
}
