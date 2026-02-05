package com.winss.wbutils.network;

import com.winss.wbutils.WBUtilsClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class NetworkManager {
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, CachedResponse> cache = new ConcurrentHashMap<>();
    
    private static final Map<String, RateLimitTracker> rateLimits = new ConcurrentHashMap<>();

    private static final long DEFAULT_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_REQUESTS_PER_SECOND = 10;

    public record NetworkResponse(int statusCode, String body) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    public static CompletableFuture<NetworkResponse> get(String url, boolean useCache) {
        return get(url, useCache, null);
    }

    public static CompletableFuture<NetworkResponse> get(String url, boolean useCache, String authToken) {
        if (useCache) {
            CachedResponse cached = cache.get(url);
            if (cached != null && !cached.isExpired()) {
                return CompletableFuture.completedFuture(new NetworkResponse(200, cached.data));
            }
        }

        if (isRateLimited(url)) {
            WBUtilsClient.LOGGER.warn("[NetworkManager] Rate limited GET request to: {}", url);
            return CompletableFuture.failedFuture(new RuntimeException("Rate limited - please slow down"));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("User-Agent", "WBUtils-Mod/" + WBUtilsClient.getVersion());

        if (authToken != null && !authToken.isBlank()) {
            builder.header("Authorization", "Bearer " + authToken);
        }

        HttpRequest request = builder.build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    String body = response.body();
                    if (response.statusCode() >= 200 && response.statusCode() < 300 && useCache) {
                        cache.put(url, new CachedResponse(body));
                    }
                    return new NetworkResponse(response.statusCode(), body);
                });
    }

    public static CompletableFuture<NetworkResponse> post(String url, String json) {
        return post(url, json, null);
    }

    public static CompletableFuture<NetworkResponse> post(String url, String json, String authToken) {
        if (isRateLimited(url)) {
            WBUtilsClient.LOGGER.warn("[NetworkManager] Rate limited POST request to: {}", url);
            return CompletableFuture.failedFuture(new RuntimeException("Rate limited - please slow down"));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("User-Agent", "WBUtils-Mod/" + WBUtilsClient.getVersion())
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (authToken != null && !authToken.isBlank()) {
            builder.header("Authorization", "Bearer " + authToken);
        }

        HttpRequest request = builder.build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new NetworkResponse(response.statusCode(), response.body()));
    }

    private static boolean isRateLimited(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null) host = "unknown";
            if (path == null) path = "/";
            
            String endpointKey = host + path;
            
            RateLimitTracker tracker = rateLimits.computeIfAbsent(endpointKey, k -> new RateLimitTracker());
            return !tracker.allowRequest();
        } catch (Exception e) {
            return false;
        }
    }

    public static void clearCache() {
        cache.clear();
    }

    private static class CachedResponse {
        final String data;
        final long timestamp;

        CachedResponse(String data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > DEFAULT_CACHE_TTL_MS;
        }
    }

    private static class RateLimitTracker {
        private int requestsInLastSecond = 0;
        private long lastSecondStart = 0;

        synchronized boolean allowRequest() {
            long now = System.currentTimeMillis();
            if (now - lastSecondStart > 1000) {
                lastSecondStart = now;
                requestsInLastSecond = 0;
            }

            if (requestsInLastSecond < MAX_REQUESTS_PER_SECOND) {
                requestsInLastSecond++;
                return true;
            }
            return false;
        }
    }
}
