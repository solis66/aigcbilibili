package ljl.bilibili.client.video;

import ljl.bilibili.client.pojo.UploadVideo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Component
// url 用占位符：默认值 http://localhost:10201 适配宿主直跑；容器内由外置配置覆盖为 http://video:10201
@FeignClient(name = "video",url = "${service.video.url:http://localhost:10201}")
public interface VideoClient {
    @PostMapping(value = "/videoEncode/uploadVideo",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    void uploadVideo(@RequestPart("multipartFile") MultipartFile multipartFile);
    @PostMapping("/videoEncode/getVideoInputStream")
    ResponseEntity<Resource> getVideo(@RequestBody UploadVideo uploadVideo);
    @PostMapping(value = "/videoEncode/uploadVideoCover",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    void uploadVideoCover(@RequestPart("multipartFile") MultipartFile multipartFile);
}
