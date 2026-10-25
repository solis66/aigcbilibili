const { defineConfig } = require('@vue/cli-service') // NOTE 本文件不是JS模块，不能直接使用ES6的import

// const SkeletonWebpackPlugin = require('vue-skeleton-webpack-plugin')
const path = require('path')
let resolve = path.resolve

// 本地联调开关（环境变量驱动）：
//   不设 VUE_APP_API_TARGET / VUE_APP_WS_TARGET → 行为与原来完全一致（代理线上、无 pathRewrite）
//   设了（Docker dev-front 变体 docker-compose.dev-front.yml 会注入 http://gateway:8200）→ 切到容器内网关
const API_TARGET = process.env.VUE_APP_API_TARGET
const WS_TARGET = process.env.VUE_APP_WS_TARGET

module.exports = defineConfig({
  transpileDependencies: true,
  lintOnSave: false, // 关闭eslint校验
  css: {
    extract: true, // 拆分ExtractTextPlugin插件，默认true - 骨架屏需要为true
    loaderOptions: {
      sass: {
        implementation: require('sass')
      }
    }
  },
  assetsDir: './',

  devServer: {
    host: 'localhost',
    port: 2023,
    // client:{
    //   webSocketURL: 'ws://127.0.0.12:2023/ws'
    // },
    headers: {
      'Access-Control-Allow-Origin': '*',
    },
    open: true, //自动打开项目
    // NOTE 跨域配置
    proxy: {
      '/api': {
        // 未设环境变量：与原来完全一致（代理线上、无 pathRewrite）
        // 设了 VUE_APP_API_TARGET：代理本机网关 8200，并去掉 /api 前缀（网关只认 /login、/play 等）
        target: API_TARGET || "https://www.labilibili.com",
        // 允许跨域：如果不开的话，会拦截响应
        changeOrigin: true,
        ...(API_TARGET ? { pathRewrite: { '^/api': '' } } : {})
      },
      '/video-test': {
        target: 'http://clips.vorwaerts-gmbh.de/big_buck_bunny.mp4',
        changeOrigin: true,
        pathRewrite: {
          '^/video-show': ''
        }
      },
      '/wschat': {
        // 未设 VUE_APP_WS_TARGET：保持线上；设了：切本机 chat ws://localhost:1688
        target: WS_TARGET || "https://www.labilibili.com",
        ws: true,
        changeOrigin: true,
      }

    }
  },
  chainWebpack(config) {
    // 配置 svg-sprite-loader
    config.module
      .rule('svg')
      .exclude.add(resolve('src/assets/svg'))
      .end()
    config.module
      .rule('icons')
      .test(/.svg$/)
      .include.add(resolve('src/assets/svg'))
      .end()
      .use('svg-sprite-loader')
      .loader('svg-sprite-loader')
      .options({
        symbolId: 'icon-[name]'
      })
      .end()
  }


})