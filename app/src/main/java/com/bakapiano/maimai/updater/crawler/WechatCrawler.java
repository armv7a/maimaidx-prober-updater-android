package com.bakapiano.maimai.updater.crawler;

import static com.bakapiano.maimai.updater.crawler.CrawlerCaller.writeLog;

import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionSpec;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.TlsVersion;

public class WechatCrawler {
    // Make this true for Fiddler to capture https request
    private static final boolean IGNORE_CERT = false;

    private static final String TAG = "Crawler";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType TEXT = MediaType.parse("text/plain");
    private static final SimpleCookieJar jar = new SimpleCookieJar();
    private static final Map<Integer, String> diffMap = new HashMap<>();
    private static OkHttpClient client = null;

    protected WechatCrawler() {
        diffMap.put(0, "Basic");
        diffMap.put(1, "Advance");
        diffMap.put(2, "Expert");
        diffMap.put(3, "Master");
        diffMap.put(4, "Re:Master");
        buildHttpClient(false);
    }

    private static void uploadData(int i, String data) {
        Request request = new Request.Builder().url("https://www.diving-fish.com/api/pageparser/page").addHeader("content-type", "text/plain").post(RequestBody.create(data, TEXT)).build();
        Callback callback = new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                try {
                    throw e;
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                assert response.body() != null;
                String result = response.body().string();
                Log.d(TAG, result);
                writeLog(diffMap.get(i) + " 难度数据上传完成：" + result);
//                writeLog("diff = " + i + " " + result);
            }

        };
        client.newCall(request).enqueue(callback);
    }

    private static void fetchAndUploadData(String username, String password, Set<Integer> difficulties) throws IOException {
        fetchAndUploadData(username, password, difficulties, new ArrayList<>());
    }

    private static void fetchAndUploadData(String username, String password, Set<Integer> difficulties, List<CompletableFuture<Object>> tasks) throws IOException {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Iterator<Integer> iterator = difficulties.iterator();
            if (iterator.hasNext()) {
                Integer diff = iterator.next();
                Request request = new Request.Builder().url("https://maimai.wahlap.com/maimai-mobile/record/musicGenre/search/?genre=99&diff=" + diff).build();

                writeLog("开始获取 " + diffMap.get(diff) + " 难度的数据");

                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NotNull Call call, @NotNull IOException e) {
                        throw new RuntimeException(e);
                    }

                    @Override
                    public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                        tasks.add(CompletableFuture.supplyAsync(() -> {
                            Log.d(TAG, response.request().url() + " " + response.code());
                            String data;
                            try {
                                data = Objects.requireNonNull(response.body()).string();
                                Matcher matcher = Pattern.compile("<html.*>([\\s\\S]*)</html>").matcher(data);
                                if (matcher.find()) data = Objects.requireNonNull(matcher.group(1));
                                data = Pattern.compile("\\s+").matcher(data).replaceAll(" ");

//                                writeLog("diff = " + diff + " was cached");
                                writeLog(diffMap.get(diff) + " 难度的数据已获取，正在上传至水鱼查分器");

                                // Upload data to maimai-prober
                                uploadData(diff, "<login><u>" + username + "</u><p>" + password + "</p></login>" + data);
                            } catch (IOException e) {
                                Log.d(TAG, "fetchAndUploadData: " + diff);
                                throw new RuntimeException(e);
                            }

                            return diff;
                        }));
                        difficulties.remove(diff);
                        if (!difficulties.isEmpty()) {
                            fetchAndUploadData(username, password, difficulties, tasks);
                        } else {
                            for (CompletableFuture<Object> task : tasks) {
                                task.join();
                            }
                            writeLog("maimai 数据缓存完成，请等待数据上传至水鱼查分器");
                        }
                    }
                });
            }

        } else {

            for (int diff : difficulties) {
                // Fetch data
                Request request = new Request.Builder().url("https://maimai.wahlap.com/maimai-mobile/record/musicGenre/search/?genre=99&diff=" + diff).build();

                Log.d("Cookie", "diff = " + diff + " start");

                Call call = client.newCall(request);
                try (Response response = call.execute()) {
                    Log.d(TAG, response.request().url() + " " + response.code());
                    @NonNull String data = Objects.requireNonNull(response.body()).string();

                    Matcher matcher = Pattern.compile("<html.*>([\\s\\S]*)</html>").matcher(data);
                    if (matcher.find()) data = Objects.requireNonNull(matcher.group(1));
                    data = Pattern.compile("\\s+").matcher(data).replaceAll(" ");

                    writeLog("diff = " + diff + " was cached");

                    // Upload data to maimai-prober
                    uploadData(diff, "<login><u>" + username + "</u><p>" + password + "</p></login>" + data);
                }

            }
        }
    }

    public boolean verifyProberAccount(String username, String password) throws IOException {
        String data = String.format("{\"username\" : \"%s\", \"password\" : \"%s\"}", username, password);
        RequestBody body = RequestBody.create(JSON, data);

        Request request = new Request.Builder().addHeader("Host", "www.diving-fish.com").addHeader("Origin", "https://www.diving-fish.com").addHeader("Referer", "https://www.diving-fish.com/maimaidx/prober/").url("https://www.diving-fish.com/api/maimaidxprober/login").post(body).build();

        Call call = client.newCall(request);
        Response response = call.execute();
        String responseBody = response.body().string();

        Log.d(TAG, "Verify account: " + responseBody + response);
        return !responseBody.contains("errcode");
    }

    protected String getWechatAuthUrl() throws IOException {
        this.buildHttpClient(true);

        Request request = new Request.Builder().addHeader("Host", "tgk-wcaime.wahlap.com").addHeader("Upgrade-Insecure-Requests", "1").addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12; IN2010 Build/RKQ1.211119.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/86.0.4240.99 XWEB/4317 MMWEBSDK/20220903 Mobile Safari/537.36 MMWEBID/363 MicroMessenger/8.0.28.2240(0x28001C57) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64").addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/wxpic,image/tpg,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9").addHeader("X-Requested-With", "com.tencent.mm").addHeader("Sec-Fetch-Site", "none").addHeader("Sec-Fetch-Mode", "navigate").addHeader("Sec-Fetch-User", "?1").addHeader("Sec-Fetch-Dest", "document").addHeader("Accept-Encoding", "gzip, deflate").addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7").url("https://tgk-wcaime.wahlap.com/wc_auth/oauth/authorize/maimai-dx").build();

        Call call = client.newCall(request);
        Response response = call.execute();
        String url = response.request().url().toString().replace("redirect_uri=https", "redirect_uri=http");

        Log.d(TAG, "Auth url:" + url);
        return url;
    }

    protected void fetchAndUploadData(String username, String password, Set<Integer> difficulties, String wechatAuthUrl) throws IOException {
        if (wechatAuthUrl.startsWith("http"))
            wechatAuthUrl = wechatAuthUrl.replaceFirst("http", "https");

        jar.clearCookieStroe();

        // Login wechat
        try {
            this.loginWechat(wechatAuthUrl);
            writeLog("登陆完成");
        } catch (Exception error) {
            writeLog("登陆时出现错误:\n");
            writeLog(error);
            return;
        }

        // Fetch maimai data
        try {
            this.fetchMaimaiData(username, password, difficulties);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                writeLog("maimai 数据更新完成");
            }
        } catch (Exception error) {
            writeLog("maimai 数据更新时出现错误:");
            writeLog(error);
            return;
        }

        // Fetch chuithm data
        this.fetchChunithmData(username, password);
    }

    private void loginWechat(String wechatAuthUrl) throws Exception {
        this.buildHttpClient(true);

        Log.d(TAG, wechatAuthUrl);
        writeLog("登录url:\n" + wechatAuthUrl);

        Request request = new Request.Builder().addHeader("Host", "tgk-wcaime.wahlap.com").addHeader("Upgrade-Insecure-Requests", "1").addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12; IN2010 Build/RKQ1.211119.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/86.0.4240.99 XWEB/4317 MMWEBSDK/20220903 Mobile Safari/537.36 MMWEBID/363 MicroMessenger/8.0.28.2240(0x28001C57) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64").addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/wxpic,image/tpg,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9").addHeader("X-Requested-With", "com.tencent.mm").addHeader("Sec-Fetch-Site", "none").addHeader("Sec-Fetch-Mode", "navigate").addHeader("Sec-Fetch-User", "?1").addHeader("Sec-Fetch-Dest", "document").addHeader("Accept-Encoding", "gzip, deflate").addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7").get().url(wechatAuthUrl).build();

        Call call = client.newCall(request);
        Response response = call.execute();

        try {
            String responseBody = response.body().string();
            Log.d(TAG, responseBody);
//            writeLog(responseBody);
        } catch (NullPointerException error) {
            writeLog(error);
        }

        int code = response.code();
        writeLog(String.valueOf(code));
        if (code >= 400) {
            throw new Exception("登陆时出现错误，请重试！");
        }

        // Handle redirect manually
        String location = response.headers().get("Location");
        if (response.code() >= 300 && response.code() < 400 && location != null) {
            request = new Request.Builder().url(location).get().build();
            call = client.newCall(request);
            call.execute().close();
        }
    }

    private void fetchMaimaiData(String username, String password, Set<Integer> difficulties) throws IOException {
        this.buildHttpClient(false);
        fetchAndUploadData(username, password, difficulties);
    }

    private void fetchChunithmData(String username, String password) throws IOException {
        // TODO
    }

    private void buildHttpClient(boolean followRedirect) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        if (IGNORE_CERT) ignoreCertBuilder(builder);

        builder.connectTimeout(120, TimeUnit.SECONDS);
        builder.readTimeout(120, TimeUnit.SECONDS);
        builder.writeTimeout(120, TimeUnit.SECONDS);

        builder.followRedirects(followRedirect);
        builder.followSslRedirects(followRedirect);

        builder.cookieJar(jar);

        // No cache for http request
        builder.cache(null);
        Interceptor noCacheInterceptor = chain -> {
            Request request = chain.request();
            Request.Builder builder1 = request.newBuilder().addHeader("Cache-Control", "no-cache");
            request = builder1.build();
            return chain.proceed(request);
        };
        builder.addInterceptor(noCacheInterceptor);

        // Fix SSL handle shake error
        ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS).tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0).allEnabledCipherSuites().build();
        // 兼容http接口
        ConnectionSpec spec1 = new ConnectionSpec.Builder(ConnectionSpec.CLEARTEXT).build();
        builder.connectionSpecs(Arrays.asList(spec, spec1));

        builder.pingInterval(3, TimeUnit.SECONDS);

        client = builder.build();
    }

    private void ignoreCertBuilder(OkHttpClient.Builder builder) {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[]{};
                }
            }};
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Exception ignored) {

        }
    }
}
