package ljl.bilibili.chat.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import ljl.bilibili.chat.config.SparkProperties;
import ljl.bilibili.chat.entity.PPTDetail;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.web.socket.TextMessage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

import static ljl.bilibili.chat.constant.Constant.*;

/**
 *AIPPT 章节扩写用的星火对话 handler。
 *
 * FIX-24（2026-09-23）：本类原先有 3 处会直接拖垮 chat 服务或挂死请求线程的缺陷，
 *   因 AIPPT 一个需求要为**每个子章节**各建一条 WS 连接（实测一次 8~10 条），
 *   缺陷概率被放大 N 倍，故一并修复。详见 docker/README.md §11.1 FIX-24。
 */
@Slf4j
public class PPTHandler extends WebSocketListener {
    public  List<RoleContent> historyList = new ArrayList<>();

    public  String totalAnswer = "";

    public String NewQuestion="";
    public static final Gson gson = new Gson();
    public PPTDetail pptDetail=new PPTDetail();
    /**
     * 星火对话凭据（由 ChatServiceImpl 注入）。
     * ⚠️ 不可命名为 chat：run() 里有局部变量 JSONObject chat，会遮蔽本字段。
     */
    private final SparkProperties.Group sparkChat;

    /**
     * 本次生成是否已终止（正常收尾 / 失败 / 连接被关闭）。
     * ChatServiceImpl 依赖它做**有上限**的等待 —— 原实现用 `while (!getGenerateEnding())` 死等，
     * 星火一旦不返回 status==2 就会永久占住 Tomcat 请求线程。
     */
    private volatile boolean finished = false;

    /** 失败原因；null 表示未出错。调用方据此判断是「降级」还是「完整成功」。 */
    private volatile String errorMsg = null;

    public PPTHandler(String text, SparkProperties.Group sparkChat) {
        pptDetail.setThemeName(text);
        pptDetail.setGenerateEnding(false);
        pptDetail.setText("");
        this.sparkChat = sparkChat;
    }

    /** 生成是否已终止（无论成功、失败还是超时）。 */
    public boolean isFinished() {
        return finished;
    }

    /** 失败原因；null 表示未发生错误。 */
    public String getErrorMsg() {
        return errorMsg;
    }

    /**
     * 标记本次生成为「失败终止」。
     * 同时置 generateEnding=true 以兼容旧字段语义。
     */
    private void markFailed(String reason) {
        this.errorMsg = reason;
        this.pptDetail.setGenerateEnding(true);
        this.finished = true;
    }

    /**
     *向大模型提问
     */
    public  void  send(String text) throws Exception {
                System.out.print("我：");
                NewQuestion=text+",限定在150字以内";
                String authUrl = getAuthUrl(sparkChat.getHostUrl(), sparkChat.getApiKey(), sparkChat.getApiSecret());
                // FIX-24：OkHttpClient 改为**每实例独立**并限定超时。
                //   原为 new OkHttpClient.Builder().build()（无任何超时）—— 服务端不响应时会一直挂着。
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                String url = authUrl.replace("http://", "ws://").replace("https://", "wss://");
                Request request = new Request.Builder().url(url).build();
                    totalAnswer = "";
                    WebSocket webSocket = client.newWebSocket(request, this);
                    run(webSocket);
    }
    /**
     *若会话记录过长则删除前面一部分。
     *
     * FIX-24：原实现无条件 `remove(0..4)`，在 historyList 不足 5 条时抛
     *   IndexOutOfBoundsException；且调用方拿到 false 后**又** remove(0) 一次，属双重删除。
     *   现改为「从最老的开始裁剪，保留至少 1 条」，返回是否已降到上限内；调用方只负责 add。
     */
    public  boolean canAddHistory() {
        int historyLength = 0;
        for (RoleContent temp : historyList) {
            historyLength += temp.content == null ? 0 : temp.content.length();
        }
        while (historyLength > 12000 && historyList.size() > 1) {
            RoleContent removed = historyList.remove(0);
            historyLength -= removed.content == null ? 0 : removed.content.length();
        }
        return historyLength <= 12000;
    }


    /**
     *将与大模型的连接传递给发送消息方法
     */
    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        super.onOpen(webSocket, response);
        System.out.print("大模型：");
    }
    /**
     *监听大模型响应并发布事件与记录对应用户会话内容
     */
    @Override
    public void onMessage(WebSocket webSocket, String text) {
        try {
            JsonParse myJsonParse = gson.fromJson(text, JsonParse.class);
            if (myJsonParse == null || myJsonParse.header == null) {
                return;
            }
            if (myJsonParse.header.code != 0) {
                // FIX-24：原先只 println 后 close，onFailure/等待方无从得知；现登记失败原因。
                log.warn("AIPPT 章节[{}] 星火返回错误码 code={} sid={}",
                        pptDetail.getThemeName(), myJsonParse.header.code, myJsonParse.header.sid);
                markFailed("星火返回错误码 " + myJsonParse.header.code);
                webSocket.close(1000, "");
                return;
            }
            if(myJsonParse.payload!=null){
                if(myJsonParse.payload.choices!=null){
                    if (myJsonParse.payload.choices.text!=null&&myJsonParse.payload.choices.text.size()>0){
                        List<Text> textList = myJsonParse.payload.choices.text;
                        for (Text temp : textList) {
                            pptDetail.setText(pptDetail.getText()+temp.content);
                        }
                    }
                }
            }
            if (myJsonParse.header.status == 2) {
                // 可以关闭连接，释放资源
                pptDetail.setGenerateEnding(true);
                if (canAddHistory()) {
                    RoleContent roleContent = new RoleContent();
                    roleContent.setRole(ASSISTANT_ROLE);
                    roleContent.setContent(totalAnswer);

                    historyList.add(roleContent);
                }
                totalAnswer="";
                // FIX-24：由真正的「流式收尾点」关闭连接，而不是发送后盲等 200ms 就关。
                finished = true;
                webSocket.close(1000, "done");
            }
        } catch (Exception e) {
            // FIX-24：解析异常不再只 printStackTrace —— 登记失败原因，让等待方及时退出。
            log.warn("AIPPT 章节[{}] 响应解析失败：{}", pptDetail.getThemeName(), e.toString());
            markFailed("响应解析失败");
        }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        super.onFailure(webSocket, t, response);
        // FIX-24（关键）：原实现在此 `System.exit(0)` —— 星火一次抖动就会**杀掉整个 chat JVM**。
        //   AIPPT 一次生成要建 N 条 WS 连接，等于把「整个服务被一个章节拖死」的概率放大 N 倍。
        //   现改为：只标记本次失败并释放连接，JVM 不受影响，上层按「已收到的内容」降级。
        int code = response != null ? response.code() : -1;
        String bodyBrief = "-";
        try {
            if (response != null && response.body() != null) {
                String b = response.body().string();
                bodyBrief = b.length() <= 200 ? b : b.substring(0, 200) + "...";
            }
        } catch (Exception ignore) {
            // body 不可读时忽略，不影响主流程
        }
        log.warn("AIPPT 章节[{}] 星火连接失败 code={} err={} body={}",
                pptDetail.getThemeName(), code, t == null ? "-" : t.getMessage(), bodyBrief);
        markFailed("星火连接失败 code=" + code);
        try {
            webSocket.close(1000, "");
        } catch (Exception ignore) {
            // 连接可能已断开，忽略
        }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        super.onClosed(webSocket, code, reason);
        // FIX-24：对端在 status==2 之前就关闭时兜底，避免调用方死等。
        if (!finished) {
            log.warn("AIPPT 章节[{}] 星火连接提前关闭 code={} reason={}，已累积 {} 字",
                    pptDetail.getThemeName(), code, reason, pptDetail.getText().length());
            markFailed("连接提前关闭 code=" + code);
        }
    }


    /**
     *鉴权
     */
    public static String getAuthUrl(String hostUrl, String apiKey, String apiSecret) throws Exception {
        URI uri = new URI(hostUrl);
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = format.format(new Date());
        String preStr = "host: " + uri.getHost() + "\n" +
                "date: " + date + "\n" +
                "GET " + uri.getPath() + " HTTP/1.1";
        String encryptType="hmacsha256";
        Mac mac = Mac.getInstance(encryptType);
        SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), encryptType);
        mac.init(spec);

        byte[] hexDigits = mac.doFinal(preStr.getBytes(StandardCharsets.UTF_8));
        String sha = Base64.getEncoder().encodeToString(hexDigits);
        String authorization = String.format("api_key=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"", apiKey, "hmac-sha256", "host date request-line", sha);
        String query = String.format("authorization=%s&date=%s&host=%s",
                URLEncoder.encode(Base64.getEncoder().encodeToString(authorization.getBytes(StandardCharsets.UTF_8)), "UTF-8"),
                URLEncoder.encode(date, "UTF-8"),
                URLEncoder.encode(uri.getHost(), "UTF-8"));

        return "wss://" + uri.getHost() + uri.getPath() + "?" + query;
    }

    class JsonParse {
        Header header;
        Payload payload;
    }

    class Header {
        int code;
        int status;
        String sid;
        public int getStatus(){
            return status;
        }
    }

    class Payload {
        Choices choices;
    }

    class Choices {
        List<Text> text;
    }

    class Text {
        String role;
        String content;
    }

    class RoleContent {
        String role;
        String content;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
    /**
     *封装请求json发送给大模型
     */
    public void run(WebSocket webSocket) {
        try {
            JSONObject requestJson = new JSONObject();

            JSONObject header = new JSONObject();
            header.put(MODEL_APP_ID, sparkChat.getAppId());
            header.put(SESSION_UID, UUID.randomUUID().toString().substring(0, 10));

            JSONObject parameter = new JSONObject();
            JSONObject chat = new JSONObject();
            chat.put(MODEL_DOMAIN, sparkChat.getDomain());
            chat.put(MODEL_TEMPERATURE, 1.0);
            chat.put(MODEL_MAX_TOKENS, 8000);
            parameter.put(MODEL_PARAMETER, chat);

            JSONObject payload = new JSONObject();
            JSONObject message = new JSONObject();
            JSONArray text = new JSONArray();

            if (historyList.size() > 0) {
                for (RoleContent tempRoleContent : historyList) {
                    text.add(JSON.toJSON(tempRoleContent));
                }
            }

            RoleContent roleContent = new RoleContent();
            roleContent.role = USER_ROLE;
            roleContent.content = NewQuestion;
            log.info(NewQuestion);
            text.add(JSON.toJSON(roleContent));
            historyList.add(roleContent);
            message.put(MESSAGE_TEXT, text);
            payload.put(PAYLOAD_MESSAGE, message);
            requestJson.put(REQUEST_HEADER, header);
            requestJson.put(REQUEST_PARAMTER, parameter);
            requestJson.put(REQUEST_PAYLOAD, payload);
            webSocket.send(requestJson.toString());

            // FIX-24：删除原先「sleep 200ms 后无条件 close(1000,"")」。
            //   星火 v3.5 是**流式**返回，200ms 内必然收不完；提前 close 会与 onMessage 形成竞态，
            //   可能丢掉尾包（status==2）→ 调用方死等。现统一由 onMessage(status==2)
            //   或 onFailure / onClosed 负责收尾关连接。
        } catch (Exception e) {
            log.warn("AIPPT 章节[{}] 请求发送失败：{}", pptDetail.getThemeName(), e.toString());
            markFailed("请求发送失败");
        }
    }

}
