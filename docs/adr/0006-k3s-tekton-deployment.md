# ADR-0006: 一期采用 K3s + Tekton 部署路线

- Status: ACCEPTED — 仅技术路线及发布门禁；详细实现合同尚未批准。
- Date: 2026-10-19
- Scope: 统一学习平台一期部署与 CI/CD 方向；本对话只做设计与文档。
- 详细设计：[部署与发布方案](../architecture/DEPLOYMENT_ARCHITECTURE_PROPOSAL.md)。

## Context

用户希望部署同时巩固云服务器、Kubernetes 和 Tekton 知识，要求免费工具方案，并明确 CI 通过后人工确认生产发布。机器与域名等购买后参数不阻塞当前设计。

## Decision

- 用户明确选择第二条路线：K3s + Tekton。
- K3s 作为一期应用运行平台；Tekton 承担构建、测试和经授权的部署步骤。
- Webhook 自动触发 CI，生产发布仍由用户确认；审批与执行权限合同另行设计。
- Coolify / Compose 不作为一期生产部署路线；GitHub Actions 不再是必需 CI 执行者。
- 不要求同时采用 Argo CD 或购买 OpenShift 商业订阅。
- 免费工具与自托管优先，不默认授权托管服务超额付费；服务器等基础设施另计。

## Consequences

一期纳入 Kubernetes 控制面、工作负载、网络、持久存储、RBAC 及构建任务的运维责任；需要控制构建与业务资源／权限隔离。K3s / Tekton 学习不等于 OpenShift 产品特有功能实践。

镜像仓库、构建器、Triggers、审批入口、执行位置、存储、精确版本与清单工具均待设计，不因路线批准自动选定。约 4 vCPU / 8 GB 是预算理解，不是容量验证结果。

## Authorization and Verification

本决定不授权安装集群、修改代码／部署配置、创建流水线或远端资源，不自动批准未来 implementation slice。统一应用技术版本 Q1 继续待决。

本轮仅文档核对；所有运行时、发布、安全与恢复验证均 NOT_RUN。
