#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""aigcbilibili 端到端冒烟脚本（v2）

用法（栈已起来后）：
    python tools/smoke_test.py
    python tools/smoke_test.py --gateway http://localhost:8200

覆盖：首页 / 视频详情 / 评论 / 弹幕 / 合集 / 个人主页 / 用户信息 / 搜索（总数、列表、补全）
     / 注册 / 登录（含图形码）/ 带 token 写操作（发弹幕、发评论）/ WebSocket 握手（经网关）

退出码 0 = 全通过；1 = 有失败项。
"""
import argparse
import http.cookiejar
import json
import random
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

FAILS = []
OKS = []


def make_opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)), cj


def call(opener, base, method, path, body=None, headers=None, timeout=25):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        resp = opener.open(req, timeout=timeout)
        return resp.status, resp.read().decode("utf-8", "replace"), resp.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), e.headers
    except Exception as e:  # noqa: BLE001
        return None, str(e), {}


def check(name, cond, detail=""):
    if cond:
        OKS.append(name)
        print("  [OK  ] %s" % name)
    else:
        FAILS.append((name, detail))
        print("  [FAIL] %s  %s" % (name, detail))


def redis_get(key):
    """从 redis 容器读图形码（仅本机开发环境使用）。"""
    try:
        out = subprocess.run(
            ["docker", "exec", "redis", "redis-cli", "get", key],
            capture_output=True, text=True, timeout=15,
        )
        return out.stdout.strip()
    except Exception:  # noqa: BLE001
        return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gateway", default="http://localhost:8200")
    args = ap.parse_args()
    base = args.gateway.rstrip("/")
    opener, cj = make_opener()

    print("\n== 1. 只读接口 ==")
    reads = [
        ("首页", "/play/getFirstPageVideo/1"),
        ("视频详情", "/play/getDetailVideo/5/6/0"),
        ("推荐视频", "/play/getCommendVideo/5"),
        ("评论列表", "/comment/getComment/5/6/0"),
        ("弹幕列表", "/danmaku/getDanmaku/5"),
        ("视频合集", "/ensemble/getAllVideoInEnsemble/6/5"),
        ("个人主页内容", "/selfCenter/getPersonalCenterContent/6/6"),
        ("用户信息", "/userInfo/getUserInfo/6/6"),
        ("用户权限", "/selfCenter/getUserPrivilege/6"),
    ]
    for name, path in reads:
        status, body, _ = call(opener, base, "GET", path)
        check(name, status == 200 and '"code":200' in body, "status=%s body=%s" % (status, body[:80]))

    print("\n== 2. 搜索（ES 链路）==")
    for kw in ("顺德", "solis"):
        q = urllib.parse.quote(kw)
        status, body, _ = call(opener, base, "GET", "/search/totalKeywordSearch/" + q)
        check("搜索总数 %s" % kw, status == 200 and '"code":200' in body, body[:100])
        status, body, _ = call(opener, base, "GET", "/search/videoKeywordSearch/%s/1/0" % q)
        check("搜索列表 %s" % kw, status == 200 and '"code":200' in body, body[:100])
    status, body, _ = call(opener, base, "GET", "/search/likelyKeyWordSearch/" + urllib.parse.quote("顺"))
    check("关键字补全", status == 200 and '"code":200' in body, body[:100])

    print("\n== 3. 注册 / 登录 ==")
    user = "smoke_%d" % random.randint(100000, 999999)
    status, body, _ = call(opener, base, "POST", "/register/passwordRegister",
                           {"userName": user, "password": "Test123456", "nickName": "冒烟用户"})
    check("注册", status == 200 and '"code":200' in body, body[:120])

    call(opener, base, "GET", "/login/getCaptcha")
    sid = [c.value for c in cj if c.name == "sessionId"]
    captcha = redis_get(sid[0]) if sid else ""
    check("图形码写入 redis", bool(captcha), "sessionId=%s" % (sid[:1],))

    status, body, headers = call(opener, base, "POST", "/login/passwordLogin",
                                 {"userName": user, "password": "Test123456", "captcha": captcha})
    check("密码登录", status == 200 and '"status":true' in body, body[:120])
    token = headers.get("shortAuthorization", "")

    print("\n== 4. 带 token 的写操作 ==")
    if token:
        hdr = {"shortAuthorization": token, "aigcBiliBiliHeader": "smoke"}
        status, body, _ = call(opener, base, "POST", "/danmaku/addDanmaku",
                               {"videoId": 5, "content": "冒烟弹幕", "time": 1.0,
                                "userId": 6, "color": "#ffffff", "type": 0}, hdr)
        check("发弹幕", status == 200 and '"code":200' in body, body[:120])
        status, body, _ = call(opener, base, "POST", "/comment/comment",
                               {"videoId": 5, "userId": 6, "content": "冒烟评论", "parentId": None}, hdr)
        check("发评论", status == 200 and '"code":200' in body, body[:120])
    else:
        check("拿到 shortAuthorization", False, "登录未返回 token 头")

    print("\n== 5. WebSocket 握手 ==")
    try:
        ws = subprocess.run(
            ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "8",
             "-H", "Connection: Upgrade", "-H", "Upgrade: websocket",
             "-H", "Sec-WebSocket-Version: 13",
             "-H", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
             base + "/ljl/chat"],
            capture_output=True, text=True, timeout=20,
        )
        check("私聊 WebSocket 101", ws.stdout.strip() == "101", "got=%r" % ws.stdout.strip())
    except Exception as e:  # noqa: BLE001
        check("私聊 WebSocket 101", False, str(e))

    print("\n---- 结果：%d 通过 / %d 失败 ----" % (len(OKS), len(FAILS)))
    for name, detail in FAILS:
        print("  FAIL %s: %s" % (name, detail))
    return 0 if not FAILS else 1


if __name__ == "__main__":
    sys.exit(main())
