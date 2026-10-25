package ljl.bilibili.search.handler;

import ljl.bilibili.entity.user_center.user_info.User;
import ljl.bilibili.mapper.user_center.user_info.UserMapper;
import ljl.bilibili.search.constant.Constant;
import ljl.bilibili.search.service.MysqlToEsService;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * MySQL 到 ES 的数据同步任务。
 *
 * <p>v2（2026-10-25）改造：原来由 xxl-job 的 {@code @XxlJob("mysqlToEs")} 触发，但调度中心里
 * 从来没有为 search 注册过执行器分组与任务，这条同步链路自部署起一次都没执行过 ——
 * 表现为 ES 里没有任何索引，搜索页报错、新投稿视频搜不到。
 * 现改为 Spring {@code @Scheduled}，默认每分钟跑一次，开箱即跑；
 * 用 Redis SETNX 做跨实例互斥，避免将来多副本部署时重复全量同步。
 */
@Component
@Slf4j
public class MysqlToEsHandler {
    /** 分布式互斥锁的 key 与过期时间（秒） */
    private static final String SYNC_LOCK_KEY = "bilibili:search:mysqlToEs:lock";
    private static final long SYNC_LOCK_SECONDS = 300L;
    @Resource
    RedisTemplate objectRedisTemplate;
    @Resource
    RestHighLevelClient client;
    @Resource
    MysqlToEsService mysqlToEsService;
    @Resource
    UserMapper userMapper;
    private static Boolean hasSynchronousVideo = false;
    private static Boolean hasSynchronousUser = false;
    /**
     *同步MySQL数据到ES
     */
    @Scheduled(cron = "${bilibili.search.sync-cron:0 0/1 * * * ?}")
    public void mysqlToEsHandler() {
        // 跨实例互斥：抢到锁才执行，抢不到说明另一实例正在同步
        Boolean locked = objectRedisTemplate.opsForValue()
                .setIfAbsent(SYNC_LOCK_KEY, "1", SYNC_LOCK_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("mysqlToEs 同步正在其他实例执行，本轮跳过");
            return;
        }
        try {
            doSync();
        } catch (Exception e) {
            log.error("mysqlToEs 同步失败", e);
        } finally {
            objectRedisTemplate.delete(SYNC_LOCK_KEY);
        }
    }

    /**
     * 同步主体：首次全量（视频 + 用户），之后按 Redis 队列里的增删改做增量。
     */
    private void doSync() throws IOException {
        //查询是否已同步过视频数据，没有的话全量同步视频数据
        if (hasSynchronousVideo == false) {
            mysqlToEsService.videoMysqlToEs();
            mysqlToEsService.updateVideoData();
            hasSynchronousVideo = true;
        }
        //查询是否已同步过用户数据，没有的话全量同步用户数据
        if (hasSynchronousUser == false) {
            mysqlToEsService.userMysqlToEs();
            mysqlToEsService.updateUserData();
            hasSynchronousUser = true;
        }
        //将redis存储的视频、用户相关增删改的操作记录下来进行增量同步
        List<HashMap<String, Object>> videoAddList = objectRedisTemplate.opsForList().range(Constant.VIDEO_ADD_KEY, 0, -1);
        List<HashMap<String, Object>> videoDeleteList = objectRedisTemplate.opsForList().range(Constant.VIDEO_DELETE_KEY, 0, -1);
        List<HashMap<String, Object>> videoUpDateList = objectRedisTemplate.opsForList().range(Constant.VIDEO_UPDATE_KEY, 0, -1);
        List<HashMap<String, Object>> userAddList = objectRedisTemplate.opsForList().range(Constant.USER_ADD_KEY, 0, -1);
        List<HashMap<String, Object>> userUpDateList = objectRedisTemplate.opsForList().range(Constant.USER_UPDATE_KEY, 0, -1);
        //先执行增删同步再进行修改同步，防止修改时该文档已删除或者还未添加
        if (videoAddList.size() > 0) {
            mysqlAddToEs(Constant.OPERATION_ADD, videoAddList, Constant.VIDEO_INDEX_NAME);
        }
        if (videoDeleteList.size() > 0) {
            // FIX-19（2026-09-23）：此处原为 videoAddList —— 传错了列表，
            // 导致「删除视频」的同步实际上拿新增列表去做 DeleteRequest：
            // 已删视频永远留在 ES 里（搜索能搜到、点进去 404），而新增视频反被删索引。
            // 判据：删掉一条视频后跑一次同步，ES 中该文档应消失。
            mysqlAddToEs(Constant.OPERATION_DELETE, videoDeleteList, Constant.VIDEO_INDEX_NAME);
        }

        if (userAddList.size() > 0) {
            mysqlAddToEs(Constant.OPERATION_ADD, userAddList, Constant.USER_INDEX_NAME);
        }
        // FIX-19（2026-09-23）：此处原有「把 ES 当前所有文档 id 灌进布隆过滤器」的逻辑，
        //   它只在下面 update 分支里用来「判定不存在就跳过」。实测这条机制是坏的：
        //   ① 过滤器只在**首次全量同步**时填充一次（在 hasSynchronousVideo 那个 if 里），
        //      此后永不刷新 → 新上传的视频必然「不在过滤器里」→ 更新被全部跳过；
        //   ② 跳过之后仍然发出**空的 BulkRequest** → ES 报
        //      ActionRequestValidationException: Validation Failed: 1: no requests added;
        //      → 任务以异常结束（v2 前表现为 xxl-job 日志里 handle_code=500）。
        //   处置：删掉这套「猜存在性」的机制，update 一律走 upsert（见 mysqlAddToEs）。
        //   判据：xxl_job_log 的 handle_code 不再出现 500。
        //最后再执行更新操作
        if (videoUpDateList.size() > 0) {
            mysqlAddToEs(Constant.OPERATION_UPDATE, videoUpDateList, Constant.VIDEO_INDEX_NAME);
        }
        if (userUpDateList.size() > 0) {
            mysqlAddToEs(Constant.OPERATION_UPDATE, userUpDateList, Constant.USER_INDEX_NAME);
        }
        //删除对应的键值对
        objectRedisTemplate.delete(Constant.VIDEO_ADD_KEY);
        objectRedisTemplate.delete(Constant.VIDEO_DELETE_KEY);
        objectRedisTemplate.delete(Constant.VIDEO_UPDATE_KEY);
        objectRedisTemplate.delete(Constant.USER_UPDATE_KEY);
        objectRedisTemplate.delete(Constant.USER_ADD_KEY);

    }/**
     *根据索引名和原始请求批量添加请求和保存原始请求
     */

    public Boolean mysqlAddToEs(String requestType, List<HashMap<String, Object>> list, String indexName) throws IOException {
        //批量添加请求
        BulkRequest bulkRequest = new BulkRequest();
        List<DocWriteRequest> docWriteRequestList = new ArrayList<>();
        if (requestType.equals("add")) {
            for (Map<String, Object> document : list) {
                normalizeVideoDocument(document, indexName);
                int intId;
                if (Constant.VIDEO_INDEX_NAME.equals(indexName)) {
                    intId = (Integer) document.get(Constant.VIDEO_INDEX_ID);
                } else {
                    intId = (Integer) document.get(Constant.INDEX_ID);
                }
                String id = String.valueOf(intId);
                IndexRequest indexRequest = new IndexRequest(indexName);
                indexRequest.id(id).source(document, XContentType.JSON);
                docWriteRequestList.add(indexRequest);
                bulkRequest.add(indexRequest);
            }

        } else if (requestType.equals("update")) {
            for (Map<String, Object> map : list) {
                normalizeVideoDocument(map, indexName);
                int intId = Constant.VIDEO_INDEX_NAME.equals(indexName)
                        ? (Integer) map.get(Constant.VIDEO_INDEX_ID)
                        : (Integer) map.get(Constant.INDEX_ID);
                // FIX-19（2026-09-23）：原实现用布隆过滤器「判定不在 ES 里就跳过」，
                //   但过滤器只在首次全量同步时填过一次，新视频必然被判为「不存在」→ 更新被丢弃；
                //   且全部被丢弃时会发出空 BulkRequest → ES 报 no requests added → 任务 500。
                //   改为 docAsUpsert：文档在就做局部更新，不在就顺手创建，无需猜存在性。
                UpdateRequest updateRequest = new UpdateRequest(indexName, String.valueOf(intId))
                        .doc(map).docAsUpsert(true);
                bulkRequest.add(updateRequest);
                docWriteRequestList.add(updateRequest);
            }
        } else {
            for (Map<String, Object> document : list) {
                // FIX-19（2026-09-23）：删除消息的 id 键名与新增/修改**不一样**。
                //   video 服务的 delete() 用 TABLE_ID（= "id"）下发 {table, type=delete, id}，
                //   而这里原来硬读 VIDEO_INDEX_ID（= "video_id"）→ 取到 null
                //   → DeleteRequest(indexName, "null") → 删的是不存在的文档，
                //   已删视频永远留在 ES（搜索得到、点进去 404）。
                //   兼容两种键名，避免再次踩「上下游键名不一致」。
                Object rawId = document.containsKey(Constant.VIDEO_INDEX_ID)
                        ? document.get(Constant.VIDEO_INDEX_ID)
                        : document.get(Constant.INDEX_ID);
                if (rawId == null) {
                    continue;
                }
                DeleteRequest deleteRequest = new DeleteRequest(indexName, String.valueOf(rawId));
                bulkRequest.add(deleteRequest);
                docWriteRequestList.add(deleteRequest);
            }
        }
        // FIX-19：空 BulkRequest 会被 ES 拒绝（Validation Failed: 1: no requests added）并让任务报 500。
        //   前置条件判断（size()>0）挡不住「列表非空但每条都被过滤掉」的情况，这里按最终请求数兜底。
        if (bulkRequest.numberOfActions() == 0) {
            return true;
        }
        //递归调用同步方法
        bulkOpreateUntilAllSucess(bulkRequest, docWriteRequestList, 10);
        return true;
    }

    /**
     * FIX-19（2026-09-23）：把增量文档的字段名对齐到全量同步（MysqlToEsServiceImpl.videoMysqlToEs）定义的 ES 结构。
     *   增量消息由 video 服务发出（UploadAndEditServiceImpl 用 SNAKE_CASE 序列化 Video 实体），
     *   落进 redis 的是 name / user_id / video_length；而全量同步写的是
     *   video_name / author_id / author_name / length。
     *   两者不一致的后果（本机实测）：新投稿的视频文档在 ES 里**确实存在**，但只有 name 字段，
     *   而 totalKeywordSearch / videoPageKeywordSearch 只查 video_name / author_name / intro
     *   → 新视频永远搜不到，直到 search 服务重启触发一次全量同步。
     *   判据：投稿后等一个调度周期，videoKeywordSearch 应能搜到新视频。
     */
    private void normalizeVideoDocument(Map<String, Object> document, String indexName) {
        // redis 反序列化会带上类型标记；notice 侧的更新消息还会残留 table 字段
        document.remove("@type");
        document.remove("table");
        if (!Constant.VIDEO_INDEX_NAME.equals(indexName)) {
            return;
        }
        Object name = document.remove("name");
        if (name != null) {
            document.put(Constant.MULTI_QUERY_VIDEO_NAME, name);
        }
        Object authorId = document.remove("user_id");
        if (authorId != null) {
            document.put("author_id", authorId);
            // 增量消息里没有作者昵称，但搜索按 author_name 匹配，缺了它「按作者搜」就搜不到新视频
            if (!document.containsKey(Constant.MULTI_QUERY_AUTHOR_NAME) && authorId instanceof Integer) {
                User author = userMapper.selectById((Integer) authorId);
                if (author != null && author.getNickname() != null) {
                    document.put(Constant.MULTI_QUERY_AUTHOR_NAME, author.getNickname());
                }
            }
        }
        Object videoLength = document.remove("video_length");
        if (videoLength != null) {
            document.put("length", videoLength);
        }
    }
    /**
     *递归批量同步操作
     */
    public Boolean bulkOpreateUntilAllSucess(BulkRequest bulkRequest, List<DocWriteRequest> list, int maxRetry) throws IOException {
        BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
        //递归将收集到的失败操作统一递归再调用自己方法最大程度减少失败请求次数，同时设定次数为10防止递归过多堆栈溢出
        if (bulkResponse.hasFailures() && maxRetry > 0) {
            BulkRequest bulkRequest1 = new BulkRequest();
            for (int i = 0; i < bulkResponse.getItems().length; i++) {
                BulkItemResponse itemResponse = bulkResponse.getItems()[i];
                if (itemResponse.isFailed()) {
                    bulkRequest1.add(list.get(i));
                }
            }
            // FIX-19：原为 bulkRequest —— 重试时把**整个原始请求**又发了一遍（而不是只发失败项），
            //   等于白重试且会放大写入量；改为只重发失败项。
            return bulkOpreateUntilAllSucess(bulkRequest1, list, maxRetry - 1);
        } else {
            return true;
        }
    }
}
