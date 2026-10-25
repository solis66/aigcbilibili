package ljl.bilibili.chat.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 讯飞星火配置（凭据外置化）。
 *
 * <p>凭据链路：docker/.env → docker-compose.yml 的 chat.environment → 容器环境变量
 * → application.yml 的 ${SPARK_*} 占位符 → 本类。
 * 源码中不再出现任何 appId / apiKey / apiSecret 字面量 ——
 * 原先它们写在 {@code Constant} 里，而 {@code static final String} + 字面量属于 JLS 常量变量，
 * javac 会把字面量内联进每个调用点的 .class，导致运行时改配置完全不生效。
 *
 * <p>三组能力拆开是因为它们的 appId 很可能不同：对话（v3.5）、TTI 文生图、AIPPT 生成 PPT
 * 在讯飞是相互独立的服务与计费。tti / aippt 未单独配置时，在 yml 里回落复用 chat 的凭据，
 * 保证改造前后的行为一致。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "spark")
public class SparkProperties {

    private Group chat = new Group();
    private Group tti = new Group();
    private Group aippt = new Group();

    /**
     * 启动期把装载结果打进日志 —— 这是"配置到底有没有被读到"的唯一可观测证据。
     * 一律脱敏，密钥不落日志。
     */
    @PostConstruct
    public void report() {
        log.info("[星火配置] chat  domain={} host={} appId={} apiKey={} apiSecret={}",
                chat.getDomain(), chat.getHostUrl(), chat.getAppId(),
                mask(chat.getApiKey()), mask(chat.getApiSecret()));
        log.info("[星火配置] tti   domain={} appId={} apiKey={}",
                tti.getDomain(), tti.getAppId(), mask(tti.getApiKey()));
        log.info("[星火配置] aippt host={} appId={}", aippt.getHostUrl(), aippt.getAppId());
        if (blank(chat.getAppId()) || blank(chat.getApiKey()) || blank(chat.getApiSecret())) {
            log.warn("[星火配置] chat 凭据不完整 → AI 对话必然鉴权失败。请在 docker/.env 填 "
                    + "SPARK_CHAT_APP_ID / SPARK_CHAT_API_KEY / SPARK_CHAT_API_SECRET，"
                    + "然后 docker compose up -d chat");
        }
    }

    /**
     * 只保留首尾各 4 位，既能核对配置是否正确装载，又不会把密钥写进日志。
     */
    private static String mask(String value) {
        if (blank(value)) {
            return "(空)";
        }
        if (value.length() <= 8) {
            return "****[len=" + value.length() + "]";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4)
                + "[len=" + value.length() + "]";
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 一组凭据。对话 / 文生图 / PPT 三组字段含义相同，仅取值来源不同。
     */
    @Data
    public static class Group {
        /** 服务端点，如 wss://spark-api.xf-yun.com/v3.5/chat */
        private String hostUrl;
        /** 模型域，如 generalv3.5；TTI 为应用专属域名 */
        private String domain;
        private String appId;
        private String apiKey;
        private String apiSecret;
    }
}
