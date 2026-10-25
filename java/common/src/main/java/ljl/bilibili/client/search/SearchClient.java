package ljl.bilibili.client.search;

import ljl.bilibili.client.pojo.RecommendVideo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
@Component
// url 用占位符：默认值 http://localhost:8201 适配宿主直跑；容器内由外置配置覆盖为 http://search:8201
@FeignClient(name = "search",url = "${service.search.url:http://localhost:8201}")
public interface SearchClient {
    @GetMapping("/search/likelyVideoRecommend/{videoId}")
    List<RecommendVideo> getRecommendVideo(@PathVariable String videoId);
}
