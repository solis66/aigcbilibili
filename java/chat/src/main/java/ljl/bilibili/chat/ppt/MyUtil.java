package ljl.bilibili.chat.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import ljl.bilibili.chat.vo.response.ImageResponse;
import okhttp3.HttpUrl;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URL;
import java.text.SimpleDateFormat;

public class MyUtil {
    /**
     * 1.发起post请求
     */
    public static String doPostJson(String url, Map<String, String> urlParams, String json) {
        CloseableHttpClient closeableHttpClient = HttpClients.createDefault();
        CloseableHttpResponse closeableHttpResponse = null;
        try {
            // 创建Http Post请求
            String asciiUrl = URI.create(url).toASCIIString();
            RequestBuilder builder = RequestBuilder.post(asciiUrl);
            builder.setCharset(StandardCharsets.UTF_8);
            if (urlParams != null) {
                for (Map.Entry<String, String> entry : urlParams.entrySet()) {
                    builder.addParameter(entry.getKey(), entry.getValue());
                }
            }
            // 创建请求内容
            StringEntity entity = new StringEntity(json, ContentType.APPLICATION_JSON);
            builder.setEntity(entity);
            HttpUriRequest request = builder.build();
            // 执行http请求
            closeableHttpResponse = closeableHttpClient.execute(request);
            String resultString = EntityUtils.toString(closeableHttpResponse.getEntity(), StandardCharsets.UTF_8);
            ImageResponse imageResponse = new ObjectMapper().readValue(resultString, ImageResponse.class);
            // FIX-23（2026-09-23）：原实现在这里对**可能为 null** 的 imageResponse 直接取内容。
            //   上游给 text[] 新增 polishContent 后，readValue 抛 UnrecognizedPropertyException，
            //   被上面的 catch 吞掉 → imageResponse 仍是 null → 此行的 NPE 才是最终吐给前端的错
            //   （接口 600），真实原因完全被掩盖。现改为显式校验并带上下文抛出。
            if (imageResponse.getHeader() == null || imageResponse.getHeader().getCode() != 0) {
                throw new IllegalStateException("星火 TTI 返回业务失败: " + brief(resultString));
            }
            if (imageResponse.getPayload() == null
                    || imageResponse.getPayload().getChoices() == null
                    || imageResponse.getPayload().getChoices().getText() == null
                    || imageResponse.getPayload().getChoices().getText().isEmpty()) {
                throw new IllegalStateException("星火 TTI 响应缺少 payload.choices.text，无法取图: "
                        + brief(resultString));
            }
            return imageResponse.getPayload().getChoices().getText().get(0).getContent();
        } catch (Exception e) {
            // 不再 printStackTrace 后继续往下走（那会把异常变成 NPE），而是带上下文抛出
            throw new RuntimeException("调用星火 TTI 失败: " + e.getMessage(), e);
        } finally {
            try {
                if (closeableHttpResponse != null) {
                    closeableHttpResponse.close();
                }
                if (closeableHttpClient != null) {
                    closeableHttpClient.close();
                }
            } catch (Exception ignored) {
                // 关闭失败不影响主流程
            }
        }
    }

    /**
     * 截断响应体用于异常消息 —— 成功响应里的 content 是约 2MB 的 Base64，
     * 直接拼进日志会污染文件。
     */
    private static String brief(String body) {
        if (body == null) {
            return "(空)";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...(共 " + body.length() + " 字符)";
    }
    public static String getAuthUrl(String hostUrl, String apiKey, String apiSecret) throws Exception {
        URL url = new URL(hostUrl);
        // 时间
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = format.format(new Date());
        // date="Thu, 12 Oct 2023 03:05:28 GMT";
        // 拼接
        String preStr = "host: " + url.getHost() + "\n" + "date: " + date + "\n" + "POST " + url.getPath() + " HTTP/1.1";
        // System.err.println(preStr);
        // SHA256加密
        Mac mac = Mac.getInstance("hmacsha256");
        SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "hmacsha256");
        mac.init(spec);

        byte[] hexDigits = mac.doFinal(preStr.getBytes(StandardCharsets.UTF_8));
        // Base64加密
        String sha = Base64.getEncoder().encodeToString(hexDigits);
        // System.err.println(sha);
        // 拼接
        String authorization = String.format("api_key=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"", apiKey, "hmac-sha256", "host date request-line", sha);
        // 拼接地址
        HttpUrl httpUrl = Objects.requireNonNull(HttpUrl.parse("https://" + url.getHost() + url.getPath())).newBuilder().//
                addQueryParameter("authorization", Base64.getEncoder().encodeToString(authorization.getBytes(StandardCharsets.UTF_8))).//
                addQueryParameter("date", date).//
                addQueryParameter("host", url.getHost()).//
                build();

        // System.err.println(httpUrl.toString());
        return httpUrl.toString();
    }
}
