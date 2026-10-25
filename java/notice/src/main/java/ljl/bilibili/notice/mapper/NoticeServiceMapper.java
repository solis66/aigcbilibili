package ljl.bilibili.notice.mapper;
import ljl.bilibili.notice.vo.response.get_notice.CommentNoticeResponse;
import ljl.bilibili.notice.vo.response.get_notice.DynamicVideoResponse;
import ljl.bilibili.notice.vo.response.get_notice.LikeNoticeResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NoticeServiceMapper {
    @Select("select u.nickname as nickName,u.id as userId,u.cover as userCover,v.cover as videoCover,v.id as videoId,ln.create_time as createTime,ln.status from video v inner join like_notice ln on ln.video_id = v.id inner join user u on u.id = ln.sender_id  where v.user_id = #{userId} and ln.comment_id is null order by ln.create_time desc")
     List<LikeNoticeResponse> getVideoLikeNotice(Integer userId);
    /**
     * 对「评论」的点赞通知。
     *
     * FIX-17（2026-09-23 本机实测修正）：
     *   原 SQL 的 WHERE 写的是 `t.user_id`，但整条语句**没有任何别名 t**（FROM 用的是 c1/c2/u/ln）
     *   → MySQL 抛 `Unknown column 't.user_id' in 'where clause'`，接口返回非标准 600，
     *   前端 axios 走 error 分支 → 「我的消息」页弹「获得点赞消息失败」。
     *
     * 三处语义修正（依据数据模型，非猜测）：
     *   1) WHERE `t.user_id` → `c1.user_id`。收件人 = **被点赞评论的作者**。
     *      对齐同文件 getVideoLikeNotice 的 `v.user_id = #{userId}`（视频作者），
     *      也对齐 GetNoticeServiceImpl.getNoticeCount 里 commentLikeCount 的
     *      `eq(Comment::getUserId, userId)` + `leftJoin(LikeNotice ON commentId = comment.id)`。
     *   2) 点赞人改为直接 join：去掉 `INNER JOIN comment c2 ON (c2.id = ln.sender_id)`，
     *      改为 `INNER JOIN user u ON (u.id = ln.sender_id)`。
     *      依据 video/vo/.../like/LikeRequest.toAddOrDeleteNotice() 的 `setSenderId(userId)` 与
     *      同文件 getVideoLikeNotice 的 `u.id = ln.sender_id`：`like_notice.sender_id` 是**用户 id**，
     *      把它当 comment.id 去 join 既取错人，又会让「没有同 id 评论」的点赞被 INNER JOIN 静默丢弃。
     *      且 LikeNoticeResponse 的字段注释明确 `nickName` =「点赞评论的人的昵称」。
     *   3) 补 `ln.create_time AS createTime` + `ORDER BY ln.create_time DESC`，与 getVideoLikeNotice 一致
     *      —— VO 有 createTime 字段、ThumbsUpMe.vue 也在渲染它，原来恒为空。
     */
    @Select("select u.nickname as nickName,u.id as userId,u.cover as userCover,c1.video_id as videoId,ln.comment_id as commentId,ln.status as status,c1.content,ln.create_time as createTime FROM comment c1 INNER JOIN like_notice ln ON (ln.comment_id = c1.id) INNER JOIN user u ON (u.id = ln.sender_id) WHERE c1.user_id = #{userId} ORDER BY ln.create_time DESC")
    List<LikeNoticeResponse> getCommentLikeNotice(Integer userId);
    @Select("SELECT     cn.status,v.cover AS videoCover,v.id AS videoId,u.nickname AS nickName,u.cover AS userCover,c.content AS replyContent,c.id AS replyId,c.create_time AS createTime   FROM video  v    INNER JOIN comment c ON (c.video_id = v.id) INNER JOIN user u ON (u.id = c.user_id) INNER JOIN comment_notice cn ON (cn.sender_id = c.id)     WHERE   (v.user_id =#{userId}  AND c.parent_id IS NULL) ORDER BY cn.create_time DESC")
    List<CommentNoticeResponse> getCommentToVideoNotice(Integer userId);
    @Select("SELECT     t1.status,t3.cover AS userCover,t3.id AS userId,t.content AS replyToContent,comment.content AS replyContent,t1.sender_id AS replyId   FROM comment  t    INNER JOIN comment_notice t1 ON (t1.receiver_id = t.id) INNER JOIN comment comment ON (comment.id = t1.sender_id) INNER JOIN user t3 ON (t3.id = comment.user_id)     WHERE   (t.user_id = #{userId})")
    List<CommentNoticeResponse> getCommentToCommentNotice(Integer userId);
    @Select("SELECT     t.id,t.status,d.video_id,d.video_cover,d.video_name,d.create_time,u.cover AS authorCover,u.nickname AS authorName,u.id AS authorId   FROM dynamic_to_user  t    INNER JOIN dynamic d ON (d.id = t.dynamic_id) INNER JOIN user u ON (u.id = d.author_id)     WHERE   (t.user_id = #{userId}) ORDER BY d.create_time DESC")
    List<DynamicVideoResponse> getDynamicVideoNotice(Integer userId);
}
