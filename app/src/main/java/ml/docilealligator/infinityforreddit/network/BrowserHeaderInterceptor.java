package ml.docilealligator.infinityforreddit.network;

import java.io.IOException;
import java.util.Map;

import androidx.annotation.NonNull;

import ml.docilealligator.infinityforreddit.utils.APIUtils;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Attaches browser-like headers (User-Agent/Accept/Referer/Origin) to requests hitting Reddit's web host,
 * so traffic that doesn't already build a header map via {@link APIUtils#getOAuthHeader} (e.g. the plain
 * anonymous GET endpoints in RedditAPI) still looks like it came from a browser rather than an API client.
 */
public class BrowserHeaderInterceptor implements Interceptor {
    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        String host = request.url().host();
        if (!host.endsWith("reddit.com")) {
            return chain.proceed(request);
        }

        Request.Builder builder = request.newBuilder();
        for (Map.Entry<String, String> header : APIUtils.getBrowserHeaders().entrySet()) {
            if (request.header(header.getKey()) == null) {
                builder.header(header.getKey(), header.getValue());
            }
        }

        return chain.proceed(builder.build());
    }
}
