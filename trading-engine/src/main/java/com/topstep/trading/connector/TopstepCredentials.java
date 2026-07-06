package com.topstep.trading.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Resolves Topstep API credentials from, in priority order:
 *
 *   1. JVM system properties: topstep.apiUrl / topstep.username /
 *      topstep.apiKey / topstep.accountId
 *   2. Properties file {user.home}/.topstep/credentials.properties
 *      (keys: apiUrl, username, apiKey, accountId)
 *   3. Environment variables: TOPSTEP_API_URL / TOPSTEP_USERNAME /
 *      TOPSTEP_API_KEY / TOPSTEP_ACCOUNT_ID
 *
 * The file/property paths exist because environment variables set in the
 * launching shell do not reliably reach a JVM forked by the Gradle daemon —
 * the daemon captures its environment at daemon start, so `$env:` changes in
 * a newer shell are silently ignored and the engine can come up bound to a
 * stale account. A runtime file read cannot go stale that way.
 *
 * The resolved source and account id are logged (never the API key).
 */
public final class TopstepCredentials {

    private static final Logger logger = LoggerFactory.getLogger(TopstepCredentials.class);

    public final String apiUrl;
    public final String username;
    public final String apiKey;
    public final String accountId;
    public final String source;

    private TopstepCredentials(String apiUrl, String username, String apiKey,
                               String accountId, String source) {
        this.apiUrl = apiUrl;
        this.username = username;
        this.apiKey = apiKey;
        this.accountId = accountId;
        this.source = source;
    }

    public static TopstepCredentials load() {
        // 1. System properties
        String apiUrl = System.getProperty("topstep.apiUrl");
        String username = System.getProperty("topstep.username");
        String apiKey = System.getProperty("topstep.apiKey");
        String accountId = System.getProperty("topstep.accountId");
        if (isComplete(apiUrl, username, apiKey, accountId)) {
            return logged(new TopstepCredentials(apiUrl, username, apiKey, accountId, "system properties"));
        }

        // 2. Credentials file
        Path credFile = Paths.get(System.getProperty("user.home"), ".topstep", "credentials.properties");
        if (Files.isReadable(credFile)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(credFile)) {
                props.load(in);
                String fApiUrl = props.getProperty("apiUrl");
                String fUsername = props.getProperty("username");
                String fApiKey = props.getProperty("apiKey");
                String fAccountId = props.getProperty("accountId");
                if (isComplete(fApiUrl, fUsername, fApiKey, fAccountId)) {
                    return logged(new TopstepCredentials(fApiUrl.trim(), fUsername.trim(),
                        fApiKey.trim(), fAccountId.trim(), credFile.toString()));
                }
                logger.warn("Credentials file {} exists but is incomplete; falling back to environment", credFile);
            } catch (IOException e) {
                logger.warn("Failed to read credentials file {}: {}", credFile, e.getMessage());
            }
        }

        // 3. Environment variables
        String eApiUrl = System.getenv("TOPSTEP_API_URL");
        String eUsername = System.getenv("TOPSTEP_USERNAME");
        String eApiKey = System.getenv("TOPSTEP_API_KEY");
        String eAccountId = System.getenv("TOPSTEP_ACCOUNT_ID");
        if (isComplete(eApiUrl, eUsername, eApiKey, eAccountId)) {
            return logged(new TopstepCredentials(eApiUrl, eUsername, eApiKey, eAccountId,
                "environment variables (WARNING: may be stale under the Gradle daemon)"));
        }

        throw new IllegalStateException(
            "Missing Topstep credentials. Provide one of:\n" +
            "  - system properties: -Dtopstep.apiUrl -Dtopstep.username -Dtopstep.apiKey -Dtopstep.accountId\n" +
            "  - file: " + credFile + " (keys: apiUrl, username, apiKey, accountId)\n" +
            "  - environment: TOPSTEP_API_URL, TOPSTEP_USERNAME, TOPSTEP_API_KEY, TOPSTEP_ACCOUNT_ID");
    }

    private static TopstepCredentials logged(TopstepCredentials creds) {
        logger.info("Topstep credentials loaded from {} — account={}, username={}",
            creds.source, creds.accountId, creds.username);
        return creds;
    }

    private static boolean isComplete(String... values) {
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
