package com.winss.wbutils.network;

import com.winss.wbutils.WBUtilsClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

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

    /**
     * Normalizes a URL string to ensure it has a proper scheme.
     * If no scheme is provided, defaults to https://
     * 
     * @param url The URL to normalize
     * @return A normalized URL with a proper scheme
     * @throws IllegalArgumentException if the URL is invalid
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL cannot be null or empty");
        }
        
        url = url.trim();
        
        // Check if URL already has a scheme
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        
        // Default to https:// for security
        return "https://" + url;
    }

    /**
     * Validates if a URL string is valid (can be parsed as a URI).
     * 
     * @param url The URL to validate
     * @return true if the URL is valid, false otherwise
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        
        try {
            String normalizedUrl = normalizeUrl(url);
            new URI(normalizedUrl);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Safely creates a URI from a URL string, normalizing it first.
     * 
     * @param url The URL string
     * @return A valid URI
     * @throws IllegalArgumentException if the URL is invalid
     */
    private static URI createUri(String url) {
        String normalizedUrl = normalizeUrl(url);
        try {
            return new URI(normalizedUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + url + " - " + e.getMessage(), e);
        }
    }

    public static CompletableFuture<NetworkResponse> get(String url, boolean useCache) {
        return get(url, useCache, null);
    }

    public static CompletableFuture<NetworkResponse> get(String url, boolean useCache, String authToken) {
        // Normalize the URL for cache key
        String normalizedUrl;
        try {
            normalizedUrl = normalizeUrl(url);
        } catch (IllegalArgumentException e) {
            WBUtilsClient.LOGGER.error("[NetworkManager] Invalid URL: {}", url);
            return CompletableFuture.failedFuture(e);
        }

        if (useCache) {
            CachedResponse cached = cache.get(normalizedUrl);
            if (cached != null && !cached.isExpired()) {
                return CompletableFuture.completedFuture(new NetworkResponse(200, cached.data));
            }
        }

        if (isRateLimited(normalizedUrl)) {
            WBUtilsClient.LOGGER.warn("[NetworkManager] Rate limited GET request to: {}", normalizedUrl);
            return CompletableFuture.failedFuture(new RuntimeException("Rate limited - please slow down"));
        }

        return executeGetRequest(normalizedUrl, useCache, authToken, false);
    }

    /**
     * Internal method to execute GET request with optional HTTP fallback for SSL errors.
     */
    private static CompletableFuture<NetworkResponse> executeGetRequest(String normalizedUrl, boolean useCache, String authToken, boolean isRetry) {
        URI uri;
        try {
            uri = createUri(normalizedUrl);
        } catch (IllegalArgumentException e) {
            WBUtilsClient.LOGGER.error("[NetworkManager] Failed to create URI from: {}", normalizedUrl);
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
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
                        cache.put(normalizedUrl, new CachedResponse(body));
                    }
                    return new NetworkResponse(response.statusCode(), body);
                })
                .exceptionally(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    
                    // If SSL error and we haven't retried yet, try falling back to HTTP
                    if (cause instanceof SSLException && !isRetry && normalizedUrl.startsWith("https://")) {
                        String httpUrl = "http://" + normalizedUrl.substring(8); // Replace https:// with http://
                        WBUtilsClient.LOGGER.warn("[NetworkManager] SSL error with HTTPS, attempting HTTP fallback to: {}", httpUrl);
                        return executeGetRequest(httpUrl, useCache, authToken, true).join();
                    }
                    
                    if (cause instanceof SSLException) {
                        WBUtilsClient.LOGGER.error("[NetworkManager] SSL error connecting to {}: {}. " +
                            "This may indicate the server doesn't support HTTPS or there's a certificate issue.", 
                            normalizedUrl, cause.getMessage());
                    } else {
                        WBUtilsClient.LOGGER.error("[NetworkManager] Request failed for {}: {}", 
                            normalizedUrl, cause.getMessage());
                    }
                    throw new RuntimeException("Network request failed: " + cause.getMessage(), cause);
                });
    }

    public static CompletableFuture<NetworkResponse> post(String url, String json) {
        return post(url, json, null);
    }

    public static CompletableFuture<NetworkResponse> post(String url, String json, String authToken) {
        // Normalize the URL
        String normalizedUrl;
        try {
            normalizedUrl = normalizeUrl(url);
        } catch (IllegalArgumentException e) {
            WBUtilsClient.LOGGER.error("[NetworkManager] Invalid URL: {}", url);
            return CompletableFuture.failedFuture(e);
        }

        if (isRateLimited(normalizedUrl)) {
            WBUtilsClient.LOGGER.warn("[NetworkManager] Rate limited POST request to: {}", normalizedUrl);
            return CompletableFuture.failedFuture(new RuntimeException("Rate limited - please slow down"));
        }

        return executePostRequest(normalizedUrl, json, authToken, false);
    }

    /**
     * Internal method to execute POST request with optional HTTP fallback for SSL errors.
     */
    private static CompletableFuture<NetworkResponse> executePostRequest(String normalizedUrl, String json, String authToken, boolean isRetry) {
        URI uri;
        try {
            uri = createUri(normalizedUrl);
        } catch (IllegalArgumentException e) {
            WBUtilsClient.LOGGER.error("[NetworkManager] Failed to create URI from: {}", normalizedUrl);
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("User-Agent", "WBUtils-Mod/" + WBUtilsClient.getVersion())
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (authToken != null && !authToken.isBlank()) {
            builder.header("Authorization", "Bearer " + authToken);
        }

        HttpRequest request = builder.build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new NetworkResponse(response.statusCode(), response.body()))
                .exceptionally(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    
                    // If SSL error and we haven't retried yet, try falling back to HTTP
                    if (cause instanceof SSLException && !isRetry && normalizedUrl.startsWith("https://")) {
                        String httpUrl = "http://" + normalizedUrl.substring(8); // Replace https:// with http://
                        WBUtilsClient.LOGGER.warn("[NetworkManager] SSL error with HTTPS, attempting HTTP fallback to: {}", httpUrl);
                        return executePostRequest(httpUrl, json, authToken, true).join();
                    }
                    
                    if (cause instanceof SSLException) {
                        WBUtilsClient.LOGGER.error("[NetworkManager] SSL error connecting to {}: {}. " +
                            "This may indicate the server doesn't support HTTPS or there's a certificate issue.", 
                            normalizedUrl, cause.getMessage());
                    } else {
                        WBUtilsClient.LOGGER.error("[NetworkManager] Request failed for {}: {}", 
                            normalizedUrl, cause.getMessage());
                    }
                    throw new RuntimeException("Network request failed: " + cause.getMessage(), cause);
                });
    }

    private static boolean isRateLimited(String url) {
        try {
            // URL should already be normalized at this point
            URI uri = new URI(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null) host = "unknown";
            if (path == null) path = "/";
            
            String endpointKey = host + path;
            
            RateLimitTracker tracker = rateLimits.computeIfAbsent(endpointKey, k -> new RateLimitTracker());
            return !tracker.allowRequest();
        } catch (Exception e) {
            WBUtilsClient.LOGGER.debug("[NetworkManager] Failed to parse URL for rate limiting: {}", url);
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
