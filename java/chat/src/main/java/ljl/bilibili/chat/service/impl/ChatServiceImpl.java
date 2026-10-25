package ljl.bilibili.chat.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ljl.bilibili.chat.config.SparkProperties;
import ljl.bilibili.chat.entity.NoticeCount;
import ljl.bilibili.chat.entity.PPTWord;
import ljl.bilibili.chat.ppt.*;
import ljl.bilibili.chat.handler.PPTHandler;
import ljl.bilibili.chat.mapper.ChatServiceMapper;
import ljl.bilibili.chat.service.ChatService;
import ljl.bilibili.chat.vo.response.HistoryChatResponse;
import ljl.bilibili.chat.vo.request.ChatSessionRequest;
import ljl.bilibili.chat.vo.request.ChangeChatStatusRequest;
import ljl.bilibili.chat.vo.response.ChatSessionResponse;
import ljl.bilibili.chat.vo.response.PPTResponse;
import ljl.bilibili.chat.vo.response.TempSessionResponse;
import ljl.bilibili.entity.chat.Chat;
import ljl.bilibili.entity.chat.ChatSession;
import ljl.bilibili.entity.user_center.user_info.User;
import ljl.bilibili.mapper.chat.ChatMapper;
import ljl.bilibili.mapper.chat.ChatSessionMapper;
import ljl.bilibili.mapper.user_center.user_info.UserMapper;
import ljl.bilibili.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 *聊天service
 */
@Service
@Slf4j
public class ChatServiceImpl implements ChatService {
    @Resource
    ChatSessionMapper chatSessionMapper;
    /**
     * 星火三组凭据（对话 / TTI 文生图 / AIPPT），全部来自外置配置，源码内零凭据字面量。
     */
    @Resource
    SparkProperties sparkProperties;
    @Resource
    ChatMapper chatMapper;
    @Resource
    UserMapper userMapper;
    @Resource
    ChatServiceMapper chatServiceMapper;

    /**
     * AIPPT 单章节扩写的最长等待（毫秒）。超时后按已收到的内容降级，不整体失败。
     * FIX-24：原实现是无上限 while 死等，星火不返回 status==2 会永久占住 Tomcat 请求线程。
     */
    private static final long PPT_CHAPTER_TIMEOUT_MS = 30_000L;
    /** AIPPT 轮询 pptx 生成进度的上限（毫秒）。 */
    private static final long PPT_RENDER_TIMEOUT_MS = 180_000L;
    /** AIPPT 进度轮询间隔（毫秒）。 */
    private static final long PPT_POLL_INTERVAL_MS = 5_000L;
    /** 日志里截断上游响应体的长度，避免把整段大纲/大 Base64 灌进日志。 */
    private static final int LOG_BRIEF_LEN = 300;

    /**
     *修改私聊消息状态
     */
    @Override
    public Result<Boolean> changeChatStatus(ChangeChatStatusRequest changeChatStatusRequest) {
        LambdaUpdateWrapper<Chat> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(Chat::getStatus, 1);
        wrapper.eq(Chat::getSenderId, changeChatStatusRequest.getReceiverId());
        wrapper.eq(Chat::getReceiverId, changeChatStatusRequest.getUserId());
        chatMapper.update(null, wrapper);
        return Result.success(true);
    }
    /**
     *创建临时会话
     */
    @Override
    public Result<TempSessionResponse> createTempSession(Integer receiverId){
        User u=userMapper.selectById(receiverId);
        return Result.data(new TempSessionResponse().setCover(u.getCover()).setNickName(u.getNickname()));
    }
    /**
     *修改回话的最后聊天时间和内容
     */
    @Override
    public Result<Boolean> changeChatSessionTime(ChatSessionRequest chatSessionRequest) {
        LambdaUpdateWrapper<ChatSession> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatSession::getSenderId, chatSessionRequest.getSenderId());
        wrapper.eq(ChatSession::getReceiverId, chatSessionRequest.getReceiverId());
        wrapper.set(ChatSession::getUpdateTime, LocalDateTime.now());
        wrapper.set(ChatSession::getUpdateContent, chatSessionRequest.getUpdateContent());
        chatSessionMapper.update(null, wrapper);
        return Result.success(true);
    }
    /**
     *获取历史会话列表
     */
    @Override
    public Result<List<ChatSessionResponse>> getHistoryChatSession(Integer userId) {
        List<ChatSessionResponse> selfResponses= chatServiceMapper.getSelfSession(userId);
        List<ChatSessionResponse> otherResponses= chatServiceMapper.getOtherSession(userId);
        selfResponses.addAll(otherResponses);
        List<Integer> idList=new ArrayList<>();
        //获取会话集合的id集合
        for(ChatSessionResponse sessionResponse : selfResponses){
            idList.add(sessionResponse.getSessionId());
        }
        if(idList.size()>0){
            //获取每个会话的未读消息数和设置每个会话的未读状态
            List<NoticeCount> noticeCounts= chatServiceMapper.getNoticeCounts(idList,userId);
            for(ChatSessionResponse sessionResponse : selfResponses){
                for(NoticeCount noticeCount : noticeCounts){
                    if(noticeCount.getSessionId().equals(sessionResponse.getSessionId())){
                        if(noticeCount.getNoticeCount()>0){
                            sessionResponse.setCount(noticeCount.getNoticeCount());
                            sessionResponse.setStatus(false);
                            break;
                        }
                    }
                }
            }
        }
        //按创建时间倒序排
        if(selfResponses.size()>0){
            selfResponses = selfResponses.stream()
                    .sorted(Comparator.comparing(ChatSessionResponse::getUpdateTime).reversed())
                    .collect(Collectors.toList());
        }
        return Result.data(selfResponses);
    }
    /**
     *新增聊天会话和内容
     */
    @Override
    public Result<Boolean> addChatSessionAndContent(ChatSessionRequest chatSessionRequest) {
        //查询之前是否存在自己向他人或他人向自己发起的会话
        LambdaQueryWrapper<ChatSession> wrapper1 = new LambdaQueryWrapper<>();
        wrapper1.eq(ChatSession::getSenderId, chatSessionRequest.getSenderId());
        wrapper1.eq(ChatSession::getReceiverId, chatSessionRequest.getReceiverId());
        LambdaQueryWrapper<ChatSession> wrapper2 = new LambdaQueryWrapper<>();
        wrapper2.eq(ChatSession::getSenderId, chatSessionRequest.getReceiverId());
        wrapper2.eq(ChatSession::getReceiverId, chatSessionRequest.getSenderId());
        ChatSession c1=chatSessionMapper.selectOne(wrapper1);
        ChatSession c2=chatSessionMapper.selectOne(wrapper2);
        ChatSession chatSession=chatSessionRequest.toSessionEntity();
        chatSession.setUpdateTime(LocalDateTime.now());
        //如果查询过后发现该会话之前并未存在
        if ( c1== null && c2 == null) {
            chatSessionMapper.insert(chatSession);
        }else {
            chatServiceMapper.updateChatSession(chatSessionRequest.getUpdateContent(), LocalDateTime.now(),chatSessionRequest.getSenderId(),chatSessionRequest.getReceiverId());
        }
        chatMapper.insert(chatSessionRequest.toChatEntity().setContent(chatSession.getUpdateContent()));
        return Result.success(true);
    }
    /**
     *获取某会话历史聊天内容
     */
    @Override
    public Result<List<HistoryChatResponse>> getHistoryChat(Integer userId, Integer receiverId) {
        LambdaQueryWrapper<Chat> wrapper1 = new LambdaQueryWrapper<>();
        wrapper1.eq(Chat::getSenderId, userId);
        wrapper1.eq(Chat::getReceiverId, receiverId);
        LambdaQueryWrapper<Chat> wrapper2 = new LambdaQueryWrapper<>();
        wrapper2.eq(Chat::getReceiverId, userId);
        wrapper2.eq(Chat::getSenderId, receiverId);
        List<Chat> list1 = chatMapper.selectList(wrapper1);
        List<Chat> list2 = chatMapper.selectList(wrapper2);
        list1.addAll(list2);
        List<HistoryChatResponse> responses = new ArrayList<>();
        for (Chat chat : list1) {
            responses.add(new HistoryChatResponse(chat));
        }
        responses = responses.stream()
                .sorted(Comparator.comparing(HistoryChatResponse::getCreateTime).reversed())
                .collect(Collectors.toList());
        return Result.data(responses);
    }
    /**
     * 生成 PPT：大纲 → 逐章节星火扩写 → 生成可下载的 .pptx。
     *
     * FIX-24（2026-09-23）改造点：
     *   ① 原实现**从不调用** createByOutline / progress，用户只能拿到大纲与正文、拿不到 .pptx 文件；
     *      现补齐最后两步并返回 pptId / pptUrl。
     *      ⚠️ 实测上游 createByOutline 的 outline 参数必须是 **JSON 字符串**（传对象会返回
     *      code:-1「请求体body有误」），故此处沿用 getOutline() 的 String 值，不要"顺手改成对象"。
     *   ② 逐章节等待由「无上限死等」改为 {@link #PPT_CHAPTER_TIMEOUT_MS} 上限 + 单章失败降级，
     *      任一章节失败不再让整个请求 600（原实现是 throw RuntimeException 直接整体失败）。
     *   ③ 去掉每 200ms 一条的「还没打印完」日志与正文全文 log.info，改为关键节点单条 WARN，
     *      避免一次生成把容器日志撑大数 MB。
     */
    public PPTResponse getPPT(String describe) throws IOException {
        long timestamp = System.currentTimeMillis() / 1000;
        String ts = String.valueOf(timestamp);
        // 获得鉴权信息（AIPPT 是独立服务，凭据取 spark.aippt.*）
        SparkProperties.Group aippt = sparkProperties.getAippt();
        ApiAuthAlgorithm auth = new ApiAuthAlgorithm();
        String signature = auth.getSignature(aippt.getAppId(), aippt.getApiSecret(), timestamp);
        ApiClient client = new ApiClient(aippt.getHostUrl());

        // 1) 大纲生成
        String outlineResp = client.createOutline(aippt.getAppId(), ts, signature, describe);
        CreateResponse outlineResponse = JSON.parseObject(outlineResp, CreateResponse.class);
        if (outlineResponse == null || outlineResponse.getData() == null
                || outlineResponse.getData().getOutline() == null) {
            // 原实现此处必然 NPE，600 会掩盖真因；现在把上游原文带进异常。
            throw new IllegalStateException("AIPPT 大纲生成失败：" + brief(outlineResp));
        }
        ObjectMapper objectMapper = new ObjectMapper();
        OutlineVo outlineVo = objectMapper.readValue(outlineResponse.getData().getOutline(), OutlineVo.class);
        List<OutlineVo.Chapter> chapters = outlineVo.getChapters() == null
                ? Collections.emptyList() : outlineVo.getChapters();

        List<String> chapterList = new ArrayList<>();
        Map<String, Object> chapterMap = new HashMap<>();
        int i = 1, j = 1;
        String prefix = "主题是" + outlineVo.getTitle() + ",副主题是" + outlineVo.getSubTitle() + ",大纲是";
        for (OutlineVo.Chapter chapter : chapters) {
            prefix = prefix + chapter.chapterTitle + ",";
            if (chapter.chapterContents != null) {
                for (OutlineVo.Chapter chapter1 : chapter.chapterContents) {
                    prefix = prefix + chapter1.chapterTitle + ",";
                }
            }
        }

        // 2) 对每个大纲内部调用讯飞星火的提问 api 进行扩展详情（带上限等待 + 单章降级）
        for (OutlineVo.Chapter chapter : chapters) {
            chapterMap.put(String.valueOf(i), chapter.chapterTitle);
            chapterList.add(String.valueOf(i));
            if (chapter.chapterContents == null) {
                i++;
                continue;
            }
            for (OutlineVo.Chapter chapter1 : chapter.chapterContents) {
                String key = i + "." + j;
                chapterList.add(key);
                PPTHandler pptHandler = new PPTHandler(chapter1.chapterTitle, sparkProperties.getChat());
                chapterMap.put(key, pptHandler);
                j++;
                try {
                    pptHandler.send(prefix + "现在的详细解说内容是:" + chapter1.chapterTitle);
                    long deadline = System.currentTimeMillis() + PPT_CHAPTER_TIMEOUT_MS;
                    while (!pptHandler.isFinished() && System.currentTimeMillis() < deadline) {
                        if (!sleepQuietly(200)) {
                            break;
                        }
                    }
                    if (!pptHandler.isFinished()) {
                        log.warn("AIPPT 章节[{}] 扩写超时 {}ms，按已收到 {} 字降级",
                                key, PPT_CHAPTER_TIMEOUT_MS, pptHandler.pptDetail.getText().length());
                    } else if (pptHandler.getErrorMsg() != null) {
                        log.warn("AIPPT 章节[{}] 扩写失败（{}），按已收到 {} 字降级",
                                key, pptHandler.getErrorMsg(), pptHandler.pptDetail.getText().length());
                    }
                } catch (Exception e) {
                    log.warn("AIPPT 章节[{}] 扩写异常，按已收到内容降级：{}", key, e.toString());
                }
            }
            i++;
            j = 1;
        }

        // 3) 将扩展后的ppt大纲和大纲扩展后的详情统一封装到集合对象里
        List<PPTWord> pptWords = new ArrayList<>();
        Pattern pattern = Pattern.compile("^[1-9]$");
        for (String index : chapterList) {
            Object value = chapterMap.get(index);
            if (pattern.matcher(index).matches() && value instanceof String) {
                pptWords.add(new PPTWord().setIndex(index).setThemeName((String) value));
            } else if (value instanceof PPTHandler) {
                PPTHandler pptHandler = (PPTHandler) value;
                pptWords.add(new PPTWord().setIndex(index)
                        .setThemeName(pptHandler.pptDetail.getThemeName())
                        .setText(pptHandler.pptDetail.getText()));
            }
        }

        // 4) 生成可下载的 .pptx —— FIX-24 新增，原先完全缺失
        String pptId = null;
        String pptUrl = null;
        String outlineJson = outlineResponse.getData().getOutline();
        try {
            String pptResp = client.createPptByOutline(aippt.getAppId(), ts, signature, describe, outlineJson);
            CreateResponse pptCreated = JSON.parseObject(pptResp, CreateResponse.class);
            if (pptCreated == null || pptCreated.getData() == null || pptCreated.getData().getSid() == null) {
                log.warn("AIPPT 文件创建未返回 sid：{}", brief(pptResp));
            } else {
                String sid = pptCreated.getData().getSid();
                long deadline = System.currentTimeMillis() + PPT_RENDER_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    String progressResult = client.checkProgress(aippt.getAppId(), ts, signature, sid);
                    ProgressResponse progress = JSON.parseObject(progressResult, ProgressResponse.class);
                    if (progress != null && progress.getData() != null) {
                        String url = progress.getData().getPptUrl();
                        if (url != null && !url.isEmpty()) {
                            pptId = progress.getData().getPptId();
                            pptUrl = url;
                            break;
                        }
                    }
                    if (!sleepQuietly(PPT_POLL_INTERVAL_MS)) {
                        break;
                    }
                }
                if (pptUrl == null) {
                    log.warn("AIPPT 文件生成超时 {}ms（sid={}），本次仅返回大纲与正文",
                            PPT_RENDER_TIMEOUT_MS, sid);
                }
            }
        } catch (Exception e) {
            // 文件生成失败不影响大纲/正文可用性，只是少一个下载链接
            log.warn("AIPPT 文件生成异常，本次仅返回大纲与正文：{}", e.toString());
        }
        log.info("AIPPT 生成完成：{} 个词条、{} 个一级章节，pptx={}",
                pptWords.size(), chapters.size(), pptUrl == null ? "未生成" : "已生成");

        return new PPTResponse()
                .setPptWordList(pptWords)
                .setCoverImgSrc(outlineResponse.getData().getCoverImgSrc())
                .setPptId(pptId)
                .setPptUrl(pptUrl);
    }

    /**
     * 休眠指定毫秒。被中断时恢复中断标志并返回 false，调用方据此终止轮询（不再死等）。
     */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 截断上游响应用于日志，避免把整段大纲或大体积 Base64 写进日志文件。
     */
    private static String brief(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.length() <= LOG_BRIEF_LEN
                ? raw
                : raw.substring(0, LOG_BRIEF_LEN) + "...(总长 " + raw.length() + ")";
    }
    /**
     *获取讯飞星火图片响应
     */
    public String getImage(String text) throws Exception {
        //绑定图片响应地址（TTI 文生图是独立服务，凭据取 spark.tti.*）
        SparkProperties.Group tti = sparkProperties.getTti();
        String url= tti.getHostUrl();

        String authUrl= MyUtil.getAuthUrl(url,tti.getApiKey(),tti.getApiSecret());
        //特定json格式发送请求到讯飞星火
        String json = "{\n" +
                "  \"header\": {\n" +
                "    \"app_id\": \"" + tti.getAppId() + "\",\n" +
                "    \"uid\": \"" + UUID.randomUUID().toString().substring(0, 15) + "\"\n" +
                "  },\n" +
                "  \"parameter\": {\n" +
                "    \"chat\": {\n" +
                "      \"domain\": \"" + tti.getDomain() + "\",\n" +
                "      \"temperature\": 0.5,\n" +
                "      \"max_tokens\": 4096,\n" +
                "      \"width\": 1024,\n" +
                "      \"height\": 1024\n" +
                "    }\n" +
                "  },\n" +
                "  \"payload\": {\n" +
                "    \"message\": {\n" +
                "      \"text\": [\n" +
                "        {\n" +
                "          \"role\": \"user\",\n" +
                "           \"content\": \"" + text + "\"\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  }\n" +
                "}";
        //获取响应的Base64编码
        String res = MyUtil.doPostJson(authUrl, null, json);
        return res;
    }
}
