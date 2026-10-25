package ljl.bilibili.chat.constant;

public class Constant {
    public static final String WEBSOCKET_CONNECT_PATH = "/ljl/bilibili/chat";
    public static final String MESSAGE_TYPE = "type";
    public static final String MESSAGE_TYPE_BIGMODEL = "bigmodel";
    public static final String MESSAGE_TYPE_MESSAGE = "message";
    public static final String MESSAGE_TYPE_REMOVE_SESSION = "removeSession";
    public static final String MESSAGE_TYPE_INIT = "init";
    public static final String MESSAGE_CONTENT = "content";
    public static final String MESSAGE_TYPE_SESSIONID = "sessionId";
    public static final String USER_IDENTITY = "userId";
    public static final String MESSAGE_TYPE_BIGMODEL_QUESTION = "question";
    public static final String RECEIVER_IDENTITY = "receiverId";
    public static final String MESSAGE_STATUS = "status";
    // ⚠️ 2026-09-23：原 hostUrl / appId / apiSecret / apiKey 四个常量已移除。
    //    它们是 static final String + 字面量 = JLS 常量变量，javac 会把字面量内联进每个
    //    调用点的 .class 文件里，导致"在配置文件里改凭据完全不生效"。
    //    星火凭据现统一由 config/SparkProperties 从外置配置（docker/.env → compose → yml）读取。
    public static final String ASSISTANT_ROLE = "assistant";
    public static final String USER_ROLE = "user";
    public static final String MODEL_APP_ID = "app_id";
    public static final String SESSION_UID = "uid";
    public static final String MODEL_DOMAIN = "domain";
    // MODEL_DOMAIN_VALUE 一并移除：域随能力不同（对话 generalv3.5 / TTI 为应用专属域名），
    // 已迁至 spark.chat.domain 与 spark.tti.domain。
    public static final String MODEL_TEMPERATURE = "temperature";
    public static final String MODEL_MAX_TOKENS = "max_tokens";
    public static final String MODEL_PARAMETER = "chat";
    public static final String MESSAGE_TEXT = "text";
    public static final String PAYLOAD_MESSAGE = "message";
    public static final String REQUEST_HEADER = "header";
    public static final String REQUEST_PARAMTER = "parameter";
    public static final String REQUEST_PAYLOAD = "payload";
}
