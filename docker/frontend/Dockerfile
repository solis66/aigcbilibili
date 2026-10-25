# =============================================================================
# 前端两阶段构建：Node 构建 → nginx 运行（Q2 裁定：生产静态构建 + nginx）
# 构建上下文 = 仓库根目录（compose 中 context: ..）
# 约束落实：
#   - Node 16（D-05）：webpack 4 在 Node 16 无需 --openssl-legacy-provider
#   - 锁文件（D-05）：优先 yarn --frozen-lockfile；失败降级 npm install --legacy-peer-deps
#     （仓库同存 yarn.lock 与 pnpm-lock.yaml，无 package-lock.json）
#   - 不改 vue/**（D-01）
#   - vue.config.js 设 assetsDir: './' → 产物平铺在 dist/ 根，直接拷到 nginx html
# =============================================================================

# ---------- builder ----------
FROM node:16.20.2-alpine AS build
WORKDIR /src
COPY vue/labilibili/package.json vue/labilibili/yarn.lock vue/labilibili/pnpm-lock.yaml ./
# -----------------------------------------------------------------------------
# FIX-2（2026-09-22 本机实测新增，修正 README §7「前端构建风险」的处置口径）
#
# 现象：yarn build 阶段直接崩，报
#   ERROR  Error: Cannot find module 'webpack/lib/rules/BasicEffectRulePlugin'
#   Require stack:
#     - /src/node_modules/vue-loader/dist/pluginWebpack5.js   ← 从**根级** node_modules 解析 webpack
#     - /src/node_modules/@vue/cli-service/node_modules/webpack/lib/webpack.js  ← 真正跑构建的是嵌套 webpack 5
#
# 根因：上游 vue/labilibili/package.json 把 "webpack" 硬锁为 "4.0.0"（且误放在 dependencies 里），
#       而 @vue/cli-service@~5.0.0 + Vue3 的 vue-loader@17 走 webpack 5 API，webpack 4 无该模块。
#       → 根级 webpack4 与 cli-service 内嵌 webpack5 **版本错配**。
#       注：与 package manager 无关（pnpm/yarn 同样受该精确 pin 约束），故 README 里
#       「改试 pnpm」这条路对本故障无效，在此修正。
#
# 处置：仅在**镜像内**把该 pin 改写为 ^5.89.0，使 npm 把**单一 webpack 5** 提升到根级。
#       仓库内 vue/** 源码保持不动，符合 D-01「不改 vue 源码」。
# -----------------------------------------------------------------------------
RUN node -e "const fs=require('fs');const p=JSON.parse(fs.readFileSync('package.json','utf8'));p.dependencies.webpack='^5.89.0';fs.writeFileSync('package.json',JSON.stringify(p,null,2));"
# 降级链（D-05 内置）：yarn --frozen-lockfile 失败 → 清空残留 node_modules → 回退 npm --legacy-peer-deps
#   实测 yarn 报 "Your lockfile needs to be updated, but yarn was run with --frozen-lockfile"
#   （yarn.lock 与已被增补过的 package.json 不同步，属仓库既有状态）
RUN yarn install --frozen-lockfile || (rm -rf node_modules && npm install --legacy-peer-deps)
COPY vue/labilibili/ ./
# build = vue-cli-service build
RUN yarn build

# ---------- runtime ----------
FROM nginx:1.25.4-alpine AS runtime
COPY docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/ /usr/share/nginx/html/
EXPOSE 80
