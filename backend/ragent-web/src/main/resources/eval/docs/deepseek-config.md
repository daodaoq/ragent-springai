# Spring AI 接入 DeepSeek 指南

## 获取 API Key
在 DeepSeek 开放平台（platform.deepseek.com）注册账号并创建 API Key，Key 的格式以 sk- 开头。

## 依赖配置
在 pom.xml 引入 Spring AI 的 OpenAI starter 即可（DeepSeek 与 OpenAI 协议完全兼容），版本由 Spring AI BOM 统一管理，无需单独指定。

## application.yml 配置
spring:
  ai:
    model:
      chat: openai
    openai:
      api-key: 你的DeepSeek Key
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-v4-flash
          temperature: 0.7

## 关键点
base-url 必须指向 DeepSeek 而不是 OpenAI 官方；模型名使用 deepseek-v4-flash；
API Key 属于敏感信息，应放到 gitignored 的本地配置文件中，不要提交到 git 仓库。
