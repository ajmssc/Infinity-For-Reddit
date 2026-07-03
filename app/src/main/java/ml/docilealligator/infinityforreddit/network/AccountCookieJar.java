package ml.docilealligator.infinityforreddit.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * Persists reddit.com browser session cookies per account (in Room, via {@link RedditDataRoomDatabase#accountDao()}),
 * replacing OAuth bearer tokens as the auth mechanism. Which account's cookies are used is decided by the injected
 * {@link AccountUsernameSupplier}, so the same class serves the anonymous client, the "current account" client, and
 * clients pinned to one specific (possibly non-current) account.
 */
public class AccountCookieJar implements CookieJar {
    public interface AccountUsernameSupplier {
        @Nullable
        String getUsername();
    }

    private final RedditDataRoomDatabase redditDataRoomDatabase;
    private final AccountUsernameSupplier usernameSupplier;
    private final Map<String, List<Cookie>> cache = new ConcurrentHashMap<>();

    public AccountCookieJar(RedditDataRoomDatabase redditDataRoomDatabase, AccountUsernameSupplier usernameSupplier) {
        this.redditDataRoomDatabase = redditDataRoomDatabase;
        this.usernameSupplier = usernameSupplier;
    }

    @NonNull
    @Override
    public List<Cookie> loadForRequest(@NonNull HttpUrl url) {
        String username = usernameSupplier.getUsername();
        if (username == null) {
            return Collections.emptyList();
        }
        return getOrLoadCookies(username);
    }

    @Override
    public void saveFromResponse(@NonNull HttpUrl url, @NonNull List<Cookie> cookies) {
        if (cookies.isEmpty()) {
            return;
        }
        String username = usernameSupplier.getUsername();
        if (username == null) {
            return;
        }

        List<Cookie> merged = mergeCookies(getOrLoadCookies(username), cookies);
        cache.put(username, merged);
        persist(username, merged);
    }

    private List<Cookie> getOrLoadCookies(String username) {
        List<Cookie> cached = cache.get(username);
        if (cached != null) {
            return cached;
        }

        List<Cookie> loaded = deserialize(redditDataRoomDatabase.accountDao().getSessionCookies(username));
        cache.put(username, loaded);
        return loaded;
    }

    /** Call after a fresh login to make the harvested cookies immediately visible without a DB round-trip. */
    public void setCookies(String username, List<Cookie> cookies) {
        cache.put(username, cookies);
        persist(username, cookies);
    }

    private void persist(String username, List<Cookie> cookies) {
        redditDataRoomDatabase.accountDao().updateSessionCookies(username, serialize(cookies));
    }

    private static List<Cookie> mergeCookies(List<Cookie> existing, List<Cookie> incoming) {
        Map<String, Cookie> byKey = new LinkedHashMap<>();
        for (Cookie cookie : existing) {
            byKey.put(cookie.name() + "|" + cookie.domain() + "|" + cookie.path(), cookie);
        }
        for (Cookie cookie : incoming) {
            byKey.put(cookie.name() + "|" + cookie.domain() + "|" + cookie.path(), cookie);
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Parses a `Cookie:` request-header-style string (as returned by Android's
     * {@code android.webkit.CookieManager#getCookie}, i.e. "name1=value1; name2=value2") into
     * {@link Cookie} objects. Unlike a Set-Cookie response header this format carries no domain/path/
     * expiry metadata, so reasonable defaults are used.
     */
    public static List<Cookie> parseCookieHeader(@Nullable String cookieHeader, String url) {
        List<Cookie> cookies = new ArrayList<>();
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return cookies;
        }

        HttpUrl httpUrl = HttpUrl.parse(url);
        String domain = httpUrl != null ? httpUrl.host() : "www.reddit.com";
        long farFutureExpiry = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000;

        for (String pair : cookieHeader.split(";\\s*")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.isEmpty()) {
                continue;
            }

            cookies.add(new Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(domain)
                    .path("/")
                    .expiresAt(farFutureExpiry)
                    .secure()
                    .build());
        }

        return cookies;
    }

    public static String serialize(List<Cookie> cookies) {
        JSONArray array = new JSONArray();
        for (Cookie cookie : cookies) {
            JSONObject object = new JSONObject();
            try {
                object.put("name", cookie.name());
                object.put("value", cookie.value());
                object.put("domain", cookie.domain());
                object.put("path", cookie.path());
                object.put("expiresAt", cookie.expiresAt());
                object.put("secure", cookie.secure());
                object.put("httpOnly", cookie.httpOnly());
                object.put("hostOnly", cookie.hostOnly());
                array.put(object);
            } catch (JSONException ignored) {
            }
        }
        return array.toString();
    }

    public static List<Cookie> deserialize(@Nullable String json) {
        List<Cookie> cookies = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return cookies;
        }

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Cookie.Builder builder = new Cookie.Builder()
                        .name(object.getString("name"))
                        .value(object.getString("value"))
                        .path(object.optString("path", "/"))
                        .expiresAt(object.optLong("expiresAt", Long.MAX_VALUE))
                        .domain(object.getString("domain"));
                if (object.optBoolean("secure", false)) {
                    builder.secure();
                }
                if (object.optBoolean("httpOnly", false)) {
                    builder.httpOnly();
                }

                cookies.add(builder.build());
            }
        } catch (JSONException ignored) {
        }

        return cookies;
    }
}
