# DailyLanguage

AI Language Tutor 的 V1 工程目前处于 `M0-S1` application skeleton 阶段。

## Prerequisites

- Java 25
- Node.js 24

## Backend

```bash
cd server
./mvnw test
./mvnw spring-boot:run
```

## Frontend

```bash
cd client
npm ci
npm run build
npm run dev
```

`M0-S1` 只提供可启动的 Spring Boot 与 Vue application skeleton，不包含 Domain、database、Security 或 AI 行为。
