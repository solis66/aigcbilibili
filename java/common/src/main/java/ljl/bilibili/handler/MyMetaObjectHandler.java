package ljl.bilibili.handler;
 
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;
/**
 *自动填充处理器
 */

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 新用户默认头像 URL（FIX-13，2026-09-23 本机实测新增）。
     * 原为字面量 "https://labilibili.com/user-cover/default.png" —— 本地部署时该域名虽能解析，
     * 但对应路径实测 **404**（那是作者线上环境的存储，与本地 minio 无关）→ 新注册用户头像破图。
     * 参数化后**默认值保持不变**（宿主直跑零配置），容器侧在 docker/config/gateway/application.yml
     * 里覆盖成本地入口 http://localhost:2023/user-cover/default.png（经前端 nginx 反代到 minio）。
     * ⚠️ 本类位于 java/common，改动后需重建**全部**后端镜像（与 FIX-11 同类约束）。
     */
    @Value("${bilibili.user.default-cover:https://labilibili.com/user-cover/default.png}")
    private String defaultUserCover;
 
 
    @Override
    public void insertFill(MetaObject metaObject) {
        this.setFieldValByName("nickname", "新用户"+ UUID.randomUUID().toString().substring(0,10), metaObject);
        // FIX-14（2026-09-23 本机实测新增）：只在**未显式指定**头像时才填默认值。
        //   原实现无条件 setFieldValByName("cover", ...)，而 Video 实体也有 cover 字段、
        //   且其 createTime 带 @TableField(fill = FieldFill.INSERT) → videoMapper.insert(video)
        //   同样会触发本方法 → 把投稿时刚刚 setCover 上去的视频封面**覆盖成默认头像 URL**。
        //   实测（投稿一条视频后回读库）：
        //     video.cover = http://localhost:2023/user-cover/default.png
        //     而 minio 的 video-cover 桶里**确实**躺着上传的封面对象 → 只有 DB 值被改坏。
        //   语义上「默认值」本就该仅在缺省时生效，故改为 null 判定；注册流程传入的显式头像不受影响。
        //   hasGetter 判定是为了兼容「有 setter 无 getter」的实体（getValue 会抛 ReflectionException）。
        if (metaObject.hasSetter("cover")
                && (!metaObject.hasGetter("cover") || metaObject.getValue("cover") == null)) {
            this.setFieldValByName("cover", defaultUserCover, metaObject);
        }
        this.setFieldValByName("createTime", LocalDateTime.now(), metaObject);
        this.setFieldValByName("danmakuCount", 0, metaObject);
        this.setFieldValByName("playCount",0, metaObject);
        this.setFieldValByName("likeCount",0, metaObject);
        this.setFieldValByName("collectCount",0, metaObject);
        this.setFieldValByName("commentCount",0, metaObject);
        this.setFieldValByName("status",0,metaObject);
        this.setFieldValByName("collectGroup",0,metaObject);
        this.setFieldValByName("remotelyLike",0,metaObject);
        this.setFieldValByName("fansList",0,metaObject);
        this.setFieldValByName("idolList",0,metaObject);
    }
 
    @Override
    public void updateFill(MetaObject metaObject) {
        this.setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
    }
}