<!--轮播图-->
<template>
    <div class="video-category flex-center-container">
        <button @click="prevSlide()" 
        class="video-category-controller controller-left common-based-btn font-fifth-color">上一项</button>
        <div class="video-category-item based-box common-based-btn" 
        :class="item.id-1===currentIndex?'tags-and-labels':'recommend-labels'" 
        v-for="(item, index) in visibleCategory" :key="index"
        @click="changeRecommendType(item.id-1)"><!--NOTE 虽然@v-for不报错，但不能这么用。因为v-for是指令而非事件-->
            {{item.name}}
        </div>
        <button @click="nextSlide()" 
        class="video-category-controller controller-right common-based-btn font-fifth-color">下一项</button>
    </div>
</template>

<script setup>
import { computed, defineProps, defineEmits } from "vue"
/**
 * props和emits
 */
const config = defineProps({
    currentIndex: {
        type: Number,
        required: true,
        default: 0
    },
    itemsPerPage: {
        type: Number,
        required: true,
        default: 0
    },
    recommendCategory: {
        type: Array,
        required: true,
        default: undefined
    }    
})
const recLen = config.recommendCategory.length
/**
 * NOTE 事件名必须与父组件监听的保持一致（父组件写的是 @updateIndex）。
 * 之前这里叫 editIndex 且回调里引用的 emits 根本没定义，
 * 结果点击分类/上一项/下一项时抛 ReferenceError，页面毫无反应。
 */
const emit = defineEmits(['updateIndex'])
/**
 * 可视化分类
 */
const visibleCategory = computed(() => {
    // 根据当前索引和每页显示数量，计算轮播项
    const start = config.currentIndex
    const end = start + config.itemsPerPage;
    let visibleData = config.recommendCategory.slice(start, end)
    // 如果start过大
    let i = 0
    while(visibleData.length < config.itemsPerPage) {
        visibleData.push(config.recommendCategory[i++])
    } 
    return visibleData
})
/**
 * 推荐相关的交互
 * NOTE currentIndex 是 props，不能直接赋值（改了也不生效且会告警），
 * 一律通过 emit 交给父组件更新后回传。
 */
// 切换推荐状态
const changeRecommendType = (index) => {
    emit('updateIndex', index)
}
// 推荐类型的滚动
const prevSlide = () => {
    emit('updateIndex', (config.currentIndex - 1 + Math.ceil(recLen)) % Math.ceil(recLen))
}
const nextSlide = () => {
    emit('updateIndex', (config.currentIndex + 1) % recLen)
}
</script>

<style lang="scss" scoped>
/**
 * NOTE 分类项与「上一项/下一项」都是本组件的元素，
 * 样式必须写在本组件的 scoped 里。写在父组件（VideoRecommend）
 * 的 scoped 里只会命中本组件的根元素，对内部元素一律不生效
 * —— 这正是分类挤成一坨、按钮不定位的根因。
 */
.video-category {
    /* 为下方绝对定位的控制器提供包含块 */
    position: relative;
}

.video-category-item {
    padding: 0.6rem;
    width: auto;
    max-width: 5rem;
    height: 1.3rem;
    margin-left: 0.8rem;
    margin-right: 0.8rem;
}

.recommend-labels {
    color: #052b54a9;
}

.video-category-controller {
    padding: 0.3rem;
    width: 4rem;
    height: 2.5rem;
    border: 1px solid #9ac9fb;
    background-color: #fff;
    position: absolute;
    /* 纵向居中，不依赖行高 */
    top: 50%;
    transform: translateY(-50%);
}

/* 与分类条两端对齐（不额外外扩，避免把横向滚动宽度撑大） */
.controller-left {
    left: 0;
}

.controller-right {
    right: 0;
}
</style>
