package ljl.bilibili.chat.vo.response;

import ljl.bilibili.chat.entity.PPTWord;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class PPTResponse {
    private String coverImgSrc;
    private List<PPTWord> pptWordList;
    /**
     * FIX-24（2026-09-23）新增：AIPPT 生成的 pptx 标识与下载地址。
     * 原先接口只返回大纲与正文，用户拿不到真正的 PPT 文件；这两个字段由
     * createByOutline + progress 轮询补齐。上游生成的临时文件有有效期，
     * 前端应即时下载，不要持久化该链接。
     */
    private String pptId;
    private String pptUrl;
}
