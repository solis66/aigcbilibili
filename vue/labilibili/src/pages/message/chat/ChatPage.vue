<!--和别人私聊-->
<template>
    <div class="chat-panel">
        <!--左侧用户列表-->
        <div class="chat-userList">
            <chatListVue @handleChange="handleChange" ref="chatList" />
        </div>
        <!--纵轴隔-->
        <div class="message-vertical-divided here-vertical-divided"></div>
        <!--右侧聊天框-->
        <div class="chat-content flex-column-left-max-container">
            <!--
                头栏：左侧是聊天对象名，右侧是大模型会话的类型切换（文本/图片/PPT）。
                NOTE 这里必须是普通流式子元素，不能用 position:absolute —— 原先写死 top:10px，
                而该行只有约 27px 高、tab 组却有 56px，导致 tab 的下半截（约 25px）扎进下方对话框内部，
                且那一段的点击会被对话框吃掉。改成与 .chat-box 同宽同边距的 flex 行后，
                结构上不可能再重叠，右侧也自动与对话框右边缘对齐。
            -->
            <div class="chat-content-head">
                <!-- <div class="main-title chat-content-item" style="height: 50px;">{{ currentPerson.upName }}</div> -->
                <div class="chat-content-item">{{ currentPerson.upName }}</div>
                <div class="chat-type-tabs" v-show="currentPerson.upId === 0">
                    <el-tabs :tab-position="'tops'" type="card" @tab-change="changeType" v-model="type">
                        <el-tab-pane label="文本" name="0"></el-tab-pane>
                        <el-tab-pane label="图片" name="1"></el-tab-pane>
                        <el-tab-pane label="PPT" name="2"></el-tab-pane>
                    </el-tabs>
                </div>
            </div>
            <chatContentVue :type="type" :upId="currentPerson.upId" :type2="currentPerson.type" @getList="getLIst" />
        </div>
    </div>
</template>

<script setup>
import { ref, watch, onMounted, provide, defineAsyncComponent } from "vue"
import { fetchUserInfo } from '@/api/user'
import { useChat } from "@/store/chat"
import { useUserInfo } from "@/store/userInfo"
import { useRoute } from 'vue-router'

const userInfo = useUserInfo() // 使用登录信息 
const userId = userInfo.getId() // 登录用户的id
const chatSession = useChat() // 使用聊天信息
const chatList = ref()
const chatListVue = defineAsyncComponent(() =>
    import("./ChatObj.vue")
)
const chatContentVue = defineAsyncComponent(() =>
    import("./ChatContent.vue")
)
const defaultBigModel = { // 默认是大模型的id
    upId: 0, // 聊天对象的id
    upName: "星火API", // 聊天对象的name
    intro: "星火大模型伴你同行",
    avatar: require("@/assets/img/avater.png"), // 聊天对象的头像
    leastMessage: "星火大模型伴你同行",
    leastMessageFrom: 2, // 0是不存在，1是自己，2是对方
    updatedUnreadFlag: true, // 是否有新的未读消息
    unreadNum: 1, // 未读新消息的条数 
    type: 0
}
const currentPerson = ref(defaultBigModel)

const getLIst = () => {
    chatList.value.getData()
}



let type = ref('0')
const changeType = (val) => {
    console.log(val);
    type.value = val
}



let route = useRoute()
// 接收消息时的处理
onMounted(() => {

})
const handleChange = (obj) => {
    console.log(obj);
    currentPerson.value = Object.assign(currentPerson.value, obj)
    console.log('6666', currentPerson.value);
    type.value = '0'
}

</script>

<style lang="scss" scoped>
@import "@/assets/css/messagePage.scss";

.chat-panel {
    display: flex;
    flex-flow: row nowrap;
    height: 99%;
}

.chat-userList {
    flex: 0.1 0.1 15rem;
}

.message-vertical-divided {
    margin: 0.5rem 0.5rem;
    height: 99%;
}

.chat-content {
    flex: 0.9 0.9 8rem;

    /*
     * 头栏与下方 ChatContent 的 .chat-box 用同一套盒模型（同样的左右边距 + 同样的宽度），
     * 这样右侧的类型切换会自动与对话框右边缘对齐（实测偏差 0px）。
     * 它是流式子元素，高度撑到 tab 组的高度，因此不可能再压进对话框里。
     */
    .chat-content-head {
        display: flex;
        flex-flow: row nowrap;
        align-items: center;
        justify-content: space-between;
        width: 85%;
        margin: 0.4rem 1rem 0 1rem;
        min-height: 2.2rem;

        .chat-content-item {
            margin: 0;
        }

        .chat-type-tabs {
            flex: 0 0 auto;

            // el-tabs 头部默认 margin-bottom: 15px，会把头栏撑高、把对话框整体往下推
            ::v-deep .el-tabs__header {
                margin-bottom: 0;
            }
        }
    }
}

@media screen and (min-width: 1020px) {
    .chat-content .chat-content-head {
        width: 96%;
    }
}
</style>