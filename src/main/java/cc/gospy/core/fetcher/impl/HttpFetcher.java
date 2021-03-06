/*
 * Copyright 2017 ZhangJiupeng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.gospy.core.fetcher.impl;

import cc.gospy.core.entity.Page;
import cc.gospy.core.entity.Task;
import cc.gospy.core.fetcher.FetchException;
import cc.gospy.core.fetcher.Fetcher;
import cc.gospy.core.fetcher.UserAgent;
import org.apache.http.*;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class HttpFetcher implements Fetcher, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(HttpFetcher.class);
    private static int _TIMEOUT = 3000;

    private int maxConnCount = 200;
    private int maxConnPerRoute = 20;
    private int cleanPeriodSeconds = 30;
    private int connExpireSeconds = 10;
    private boolean autoKeepAlive = true;
    private boolean useProxy = false;
    private InetSocketAddress proxyAddress = new InetSocketAddress("localhost", 1080);
    private String userAgent = UserAgent.Default;

    public static void setTimeout(int timeout) {
        HttpFetcher._TIMEOUT = timeout;
    }

    private HttpFetcher() {
        this(
                request -> request.setConfig(RequestConfig.custom()
                        .setRedirectsEnabled(true)
                        .setRelativeRedirectsAllowed(true)
                        .setCircularRedirectsAllowed(true)
                        .setConnectionRequestTimeout(_TIMEOUT)
                        .setConnectTimeout(_TIMEOUT)
                        .setSocketTimeout(_TIMEOUT).build())
                , response -> {
                    Page page = new Page();
                    ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
                    page.setStatusCode(response.getStatusLine().getStatusCode());
                    HttpEntity entity = response.getEntity();
                    String contentType;
                    if (entity.getContentType() != null && !(contentType = entity.getContentType().getValue()).equals("")) {
                        page.setContentType(contentType.indexOf(';') != -1 ? contentType.substring(0, contentType.indexOf(';')) : contentType);
                    }
                    entity.writeTo(responseBody);
                    page.setContent(responseBody.toByteArray());
                    Map<String, Object> responseHeader = new HashMap<>();
                    for (Header header : response.getAllHeaders()) {
                        responseHeader.put(header.getName(), header.getValue());
                    }
                    page.getExtra().put("responseHeader", responseHeader);
                    return page;
                }
        );
    }

    private HttpFetcher(BeforeFetch configurator, AfterFetch responseHandler) {
        this.requestHandler = configurator;
        this.responseHandler = responseHandler;
    }

    private void init() throws KeyManagementException, NoSuchAlgorithmException {
        if (useProxy) {
            connectionManager = new PoolingHttpClientConnectionManager(RegistryBuilder
                    .<ConnectionSocketFactory>create()
                    .register("http", new ProxyPlainConnectionSocketFactory())
                    .register("https", new ProxySSLConnectionSocketFactory(getWeakenedSSLContextInstance()))
                    .build()
            );
        } else {
            connectionManager = new PoolingHttpClientConnectionManager(RegistryBuilder
                    .<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.INSTANCE)
                    .register("https", new SSLConnectionSocketFactory(getWeakenedSSLContextInstance()))
                    .build()
            );
        }
        if (autoKeepAlive) {
            ((PoolingHttpClientConnectionManager) connectionManager).setMaxTotal(maxConnCount);
            ((PoolingHttpClientConnectionManager) connectionManager).setDefaultMaxPerRoute(maxConnPerRoute);
            client = getHttpClientInstance();
            cleanerThread = new PoolingHttpClientConnectionCleaner(connectionManager, connExpireSeconds);
            cleanerThread.setDaemon(true);
            cleanerThread.start();
        }
    }

    public static Builder custom() {
        return new Builder();
    }

    public static HttpFetcher getDefault() {
        HttpFetcher fetcher = new HttpFetcher();
        try {
            fetcher.init();
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return fetcher;
    }

    protected class ProxyPlainConnectionSocketFactory extends PlainConnectionSocketFactory {

        @Override
        public Socket createSocket(HttpContext httpContext) throws IOException {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, proxyAddress);
            return new Socket(proxy);
        }

        @Override
        public Socket connectSocket(
                final int connectTimeout,
                final Socket socket, final HttpHost host,
                final InetSocketAddress remoteAddress,
                final InetSocketAddress localAddress,
                final HttpContext context
        ) throws IOException {
            Socket socket0 = socket != null ? socket : createSocket(context);
            if (localAddress != null) {
                socket0.bind(localAddress);
            }
            try {
                socket0.connect(remoteAddress, connectTimeout);
            } catch (SocketTimeoutException e) {
                throw new ConnectTimeoutException(e, host, remoteAddress.getAddress());
            }
            return socket0;
        }
    }

    protected class ProxySSLConnectionSocketFactory extends SSLConnectionSocketFactory {

        private ProxySSLConnectionSocketFactory(SSLContext sslContext) {
            super(sslContext);
        }

        @Override
        public Socket createSocket(HttpContext httpContext) throws IOException {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, proxyAddress);
            return new Socket(proxy);
        }
    }

    private CloseableHttpClient getHttpClientInstance() {
        HttpRequestRetryHandler handler = (e, i, httpContext) -> i < 2 && e instanceof NoHttpResponseException;
        return HttpClients.custom().setConnectionManager(connectionManager).setRetryHandler(handler).build();
    }

    private SSLContext getWeakenedSSLContextInstance() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = SSLContext.getInstance("SSLv3");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(
                    java.security.cert.X509Certificate[] paramArrayOfX509Certificate,
                    String paramString) throws CertificateException {
            }

            public void checkServerTrusted(
                    java.security.cert.X509Certificate[] paramArrayOfX509Certificate,
                    String paramString) throws CertificateException {
            }

            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        }}, null);
        return context;
    }

    private BeforeFetch requestHandler;
    private AfterFetch responseHandler;

    private CloseableHttpClient client;

    private CloseableHttpResponse doGet(String url, String cookie, Map<String, String> header) throws IOException {
        HttpGet request = new HttpGet(url);
        requestHandler.handle(request);
        request.setHeader("User-Agent", userAgent);
        if (cookie != null) {
            request.setHeader("Cookie", cookie);
        }
        setRequestHeader(request, header);
        return client.execute(request);
    }

    private CloseableHttpResponse doPost(String url, String cookie, Map<String, String> header, Map<String, String> attributes) throws IOException {
        HttpPost request = new HttpPost(url);
        requestHandler.handle(request);
        request.setHeader("User-Agent", userAgent);
        if (cookie != null) {
            request.setHeader("Cookie", cookie);
        }
        setRequestHeader(request, header);
        List<NameValuePair> pairs = new ArrayList<>();
        attributes.keySet().forEach(key -> pairs.add(new BasicNameValuePair(key, attributes.get(key))));
        request.setEntity(new UrlEncodedFormEntity(pairs));
        return client.execute(request);
    }

    private void setRequestHeader(HttpRequestBase request, Map<String, String> header) {
        if (header != null) {
            for (Map.Entry<String, String> entry : header.entrySet()) {
                request.setHeader(entry.getKey(), entry.getValue());
            }
        }
    }

    private String getCookieString(Map cookies) {
        if (cookies != null) {
            StringBuilder builder = new StringBuilder();
            Iterator<Map.Entry<String, String>> iterator = cookies.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, String> entry = iterator.next();
                builder.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
            }
            if (builder.length() > 2) {
                return builder.substring(0, builder.length() - 2);
            } else {
                logger.warn("Found cookie field in task.extra, but its content is null.");
            }
        }
        return null;
    }

    @FunctionalInterface
    public interface BeforeFetch {
        void handle(HttpRequestBase request);
    }

    @FunctionalInterface
    public interface AfterFetch {
        Page handle(CloseableHttpResponse response) throws Throwable;
    }

    @Override
    public Page fetch(Task task) throws FetchException {
        try {
            if (!autoKeepAlive) {
                this.init();
                client = getHttpClientInstance();
            }
            CloseableHttpResponse response;
            Map<String, Object> extra = task.getExtra();

            // send request
            long timer = System.currentTimeMillis();
            String url = task.getUrl();
            Object obj;
            String cookies = null;
            if ((obj = extra.get("cookies")) != null && obj instanceof Map) {
                cookies = getCookieString((Map) obj);
            } else if ((obj = extra.get("cookie")) != null && obj instanceof String) {
                cookies = obj.toString();
            }
            Map<String, String> headers = null;
            if ((obj = extra.get("headers")) != null && obj instanceof Map) {
                headers = (Map) obj;
            }
            response = (extra.get("post") != null) ?
                    doPost(url, cookies, headers, (Map) extra.get("post")) :
                    doGet(url, cookies, headers);
            timer = System.currentTimeMillis() - timer;

            // load page
            Page page = responseHandler.handle(response);
            if (page != null) {
                page.setResponseTime(timer);
                task.addVisitCount();
                page.setTask(task);
            }
            response.close();

            if (!autoKeepAlive) {
                client.close();
            }
            return page;
        } catch (Throwable throwable) {
            throw new FetchException(throwable.getMessage(), throwable);
        }
    }

    @Override
    public String[] getAcceptedProtocols() {
        return new String[]{null, "http", "https"};
    }

    @Override
    public String getUserAgent() {
        return userAgent;
    }

    private HttpClientConnectionManager connectionManager;
    private PoolingHttpClientConnectionCleaner cleanerThread;

    protected class PoolingHttpClientConnectionCleaner extends Thread {
        private final HttpClientConnectionManager connectionManager;
        private volatile boolean running;
        private int expireSeconds;

        private PoolingHttpClientConnectionCleaner(HttpClientConnectionManager manager, int expireSeconds) {
            this.connectionManager = manager;
            this.expireSeconds = expireSeconds;
            this.running = true;
        }

        @Override
        public void run() {
            while (running) {
                synchronized (this) {
                    try {
                        wait(TimeUnit.SECONDS.toMillis(cleanPeriodSeconds));
                        connectionManager.closeExpiredConnections();
                        connectionManager.closeIdleConnections(expireSeconds, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        private void shutdown() {
            boolean flag = running;
            running = false;
            synchronized (this) {
                notifyAll();
            }
            if (flag && !running) {
                logger.info("Connection cleaner stopped.");
            }
        }

    }

    @Override
    public void close() {
        if (autoKeepAlive) {
            cleanerThread.shutdown();
        }
    }

    public static class Builder {
        private HttpFetcher fetcher;

        private Builder() {
            fetcher = new HttpFetcher();
        }

        public Builder before(BeforeFetch requestHandler) {
            fetcher.requestHandler = requestHandler;
            return this;
        }

        public Builder after(AfterFetch responseHandler) {
            fetcher.responseHandler = responseHandler;
            return this;
        }

        public Builder setMaxConnCount(int maxConnCount) {
            fetcher.maxConnCount = maxConnCount;
            return this;
        }

        public Builder setMaxConnPerRoute(int maxConnPerRoute) {
            fetcher.maxConnPerRoute = maxConnPerRoute;
            return this;
        }

        public Builder setCleanPeriodSeconds(int cleanPeriodSeconds) {
            fetcher.cleanPeriodSeconds = cleanPeriodSeconds;
            return this;
        }

        public Builder setConnExpireSeconds(int connExpireSeconds) {
            fetcher.connExpireSeconds = connExpireSeconds;
            return this;
        }

        public Builder setAutoKeepAlive(boolean autoKeepAlive) {
            fetcher.autoKeepAlive = autoKeepAlive;
            return this;
        }

        public Builder setSocks5ProxyAddress(InetSocketAddress proxyAddress) {
            fetcher.proxyAddress = proxyAddress;
            fetcher.useProxy = true;
            return this;
        }

        public Builder setUserAgent(String userAgent) {
            fetcher.userAgent = userAgent;
            return this;
        }

        public HttpFetcher build() {
            try {
                fetcher.init();
            } catch (KeyManagementException | NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
            return fetcher;
        }
    }
}
