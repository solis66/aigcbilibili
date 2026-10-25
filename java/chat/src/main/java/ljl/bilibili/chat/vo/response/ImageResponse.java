package ljl.bilibili.chat.vo.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 讯飞星火 TTI（文生图）响应体。
 *
 * <p>FIX-23（2026-09-23 本机实测）：{@code text[]} 新增 {@code polishContent} 字段，
 * 并在每一级打开 {@code ignoreUnknown = true}。
 *
 * <p>起因：上游在 {@code payload.choices.text[]} 里增加了 {@code polishContent}
 * （讯飞自动润色后的提示词），而本类原先只声明 content / index / role。
 * Jackson 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=true} → 抛 UnrecognizedPropertyException
 * → 被 {@code MyUtil.doPostJson} 的 catch 吞掉 → imageResponse 为 null
 * → 方法末尾对 null 取 payload 触发 NPE → 接口恒返回 600，前端只看到"生成失败"。
 * 注意鉴权与配额都是正常的（同一凭据直接调用讯飞返回 code=0），故障只在反序列化这一层。
 *
 * <p>两处设计：声明 {@code polishContent} 是为了不丢弃上游给出的可用信息；
 * 打开 {@code ignoreUnknown} 是防御 —— 上游再加字段时不会再打穿。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageResponse {
    public Header header;
    public Payload payload;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Header {
        public int code;
        public String message;
        public String sid;
        public int status;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {
        public Choices choices;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Choices {
            public int status;
            public int seq;
            public List<Text> text;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Text {
                /** PNG 图片的 Base64（前端拼成 data:image/png;base64,... 使用） */
                public String content;
                public int index;
                public String role;
                /** 讯飞自动润色后的提示词（上游新增字段，可为空） */
                public String polishContent;
            }
        }
    }
}
