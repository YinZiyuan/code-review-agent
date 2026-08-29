# Code Review Agent · 自托管 GitHub App 生产化升级设计

**日期**：2026-08-29
**状态**：Accepted design → Pending implementation plan
**目标形态**：单组织、自托管、模块化单体 GitHub App

---

## 1. 背景与目标

当前项目已经具备确定性 `DiffAnalyzer → ToolFindingsProducer → LlmReviewer → Summarizer` pipeline、结构化 `ReviewResult`、真实新文件行号、Hybrid RAG 引用以及隔离的重复运行评测。下一阶段不以增加 Reviewer 数量为优先，而是把现有评审引擎接入真实 Pull Request 工作流，并证明它在外部失败、重复事件、并发 commit、部分发布和开发者反馈下仍然可信。

本设计同时服务两个目标：

1. **求职作品竞争力**：提供可现场演示的 GitHub PR 闭环、真实 PR benchmark、指标与架构说明。
2. **接近生产架构**：实现最小权限、Webhook 验签、幂等、持久任务、崩溃恢复、状态机、可观测性和误报治理。

## 2. 范围

### 2.1 包含

- 单组织、自托管 GitHub App 注册与安装。
- PR `opened`、`reopened`、`synchronize` 事件触发评审。
- Webhook 验签、delivery 去重和快速 `202` 响应。
- PostgreSQL 持久化 `ReviewRun`、attempt、finding、feedback、job 和 outbox。
- 后台 Worker 执行现有 `CodeReviewAgent` pipeline。
- Check Run 摘要和高置信度 inline review comments。
- 新 commit 使旧运行失去发布权。
- 有界重试、lease 恢复和部分发布对账。
- 通过后台任务对账 review-comment reactions，形成最小反馈闭环。
- Actuator/Micrometer 指标、结构化日志和真实 PR 独立 benchmark。

### 2.2 不包含

- 多租户 SaaS、计费、组织级控制台和用户登录。
- 自动批准、自动请求修改或自动合并。
- 默认阻断分支保护。
- Kafka、Redis、微服务或 Kubernetes。
- 在线学习、自动 prompt 修改或由生产反馈直接更新阈值。
- 第一版 Check Run 手动 rerequest。
- 多 Reviewer / 多 Agent 编排。

## 3. 架构原则

1. GitHub、模型、Git、数据库和任务执行都是外部机制；它们不能拥有评审生命周期或发布政策。
2. `ReviewRun` 负责业务状态和发布授权；Application 负责编排；Infrastructure 负责适配。
3. Webhook 接收与耗时评审彻底分离。
4. 所有外部事件和副作用都可能重放、超时或部分成功，必须用幂等键和对账恢复。
5. Evaluation 只消费脱敏的结果事实，不能向线上运行注入 benchmark 答案或隐式策略变化。
6. 第一版保持模块化单体和一个 PostgreSQL，只有度量证明瓶颈后才拆分部署单元。

架构标准结论：核心规则属于 Domain-facing capability；review/job 编排属于 Application；Webhook、GitHub API、JDBC、调度、Git 和 LLM 属于 Infrastructure。Domain 不依赖 Spring、JDBC、GitHub SDK 或 LangChain4j。

## 4. 系统拓扑

```text
GitHub Pull Request
        │ signed webhook
        ▼
Webhook Adapter ── transaction ──► Delivery Dedup + ReviewRun + Durable Job
                                             │
                                             ▼
                                      PostgreSQL Worker
                                             │
                                             ▼
                                   Existing CodeReviewAgent
                                             │
                                             ▼
                                  FindingPublicationPolicy
                                             │
                              head-SHA guard + idempotent reconcile
                                             │
                                             ▼
                               GitHub Check + Inline Comments
                                             │
                                  scheduled reaction reconcile
                                             ▼
                                      FindingFeedback
                                             │ published facts
                                             ▼
                                          Evaluation
```

GitHub Webhook/App、server、worker、CLI 和 Evaluation 位于同一代码库；第一版 server 与 worker 在同一个 JVM 中运行，但模块边界允许将 worker 单独启动。

## 5. 运行模式与模块

同一个 fat jar 暴露两类启动模式：

```bash
# 保持现有 CLI 行为
java -jar target/code-review-agent-1.0.0.jar review . HEAD~1
java -jar target/code-review-agent-1.0.0.jar eval --suite smoke --version local

# GitHub App server + worker
java -jar target/code-review-agent-1.0.0.jar serve
```

启动器在创建 Spring context 前选择模式：CLI 使用 `WebApplicationType.NONE` 并在 picocli 完成后退出；server 使用 Servlet Web Application 并禁用 `CliRunner`。选择 Spring MVC 而不是 WebFlux，因为 Git、SpotBugs、JDBC 和模型调用均为阻塞式工作。

目标包边界：

```text
dev.langchain4j.example.codereview/
├── reviewops/
│   ├── domain/           ReviewRun, ReviewAttempt, ReviewFinding,
│   │                     FindingFeedback, FindingPublicationPolicy
│   ├── application/      observe, execute, decide, publish, feedback use cases
│   └── infrastructure/
│       ├── persistence/  PostgreSQL repositories and mappings
│       ├── jobs/         durable job leasing and workers
│       └── github/       app auth, source checkout, checks, comments, reactions
├── server/               mode bootstrap, webhook and health boundary
├── agents/pipeline/      existing deterministic review engine
├── cli/                  existing review/eval/sample commands
└── eval/                 supporting Evaluation context
```

依赖方向：外层 adapter → Application → Domain；Application 通过稳定的 `CodeReviewAgent` façade 使用现有 pipeline。第一阶段不进行无收益的大规模包移动。

## 6. Strategic and Tactical Model

权威当前模型：

- [`docs/ddd-expert/context-map.md`](../../ddd-expert/context-map.md)
- [`docs/ddd-expert/context/review-operations/model.md`](../../ddd-expert/context/review-operations/model.md)
- [`docs/ddd-expert/context/review-operations/domain-objects.md`](../../ddd-expert/context/review-operations/domain-objects.md)
- [`docs/ddd-expert/context/evaluation/model.md`](../../ddd-expert/context/evaluation/model.md)

### 6.1 ReviewRun identity

业务幂等身份由以下事实构成：

```text
installation_id
+ repository_id
+ pull_request_number
+ head_sha
+ pipeline_version
+ configuration_version
```

数据库可使用独立 UUID 作为技术主键，但必须对业务幂等身份建立唯一约束。技术重试是同一个 `ReviewRun` 下的新 `ReviewAttempt`，不能创建新的业务运行。

### 6.2 ReviewRun lifecycle

```text
REQUESTED → RUNNING
RUNNING   → REQUESTED       transient failure with retry allowance
RUNNING   → COMPLETED       accepted immutable ReviewResult
RUNNING   → FAILED          deterministic or exhausted failure
COMPLETED → PUBLISHING      current authoritative head SHA
COMPLETED → SUPERSEDED      stale authoritative head SHA
PUBLISHING → PUBLISHED      external artifacts reconciled
PUBLISHING → FAILED         terminal publication failure

REQUESTED / RUNNING / COMPLETED / PUBLISHING
  → SUPERSEDED              newer pull-request revision observed
```

`PUBLISHED`、`FAILED`、`SUPERSEDED` 是终态。历史 `PUBLISHED` run 在新 commit 后仍保留已发布事实，但不具备新的发布行为。

### 6.3 Finding identity and publication

`ReviewFinding` 是 `ReviewRun` 内部 Entity，使用 `FindingFingerprint` 进行评论幂等和 feedback 关联。Fingerprint v1 使用：

```text
SHA-256(
  normalized_file_path + "\n" +
  category + "\n" +
  normalized_title + "\n" +
  normalized_evidence_signature
)
```

行号不进入 fingerprint；它是定位事实，代码移动或输出措辞的轻微变化不应单独制造重复身份。Fingerprint 只在同一 repository/PR/head SHA 范围内解释。

`FindingPublicationPolicy` v1 按以下顺序分类：

1. 文件、post-change line 或 diff membership 无效：`RETAIN_ONLY`。
2. `source` 为 `regex` 或 `spotbugs`、severity 为 `CRITICAL|WARNING` 且 evidence 非空：inline 候选。
3. LLM finding 为 `CRITICAL|WARNING`、evidence 非空且至少有一个合法 retrieved citation：inline 候选。
4. 其余定位有效的 finding：`CHECK_SUMMARY`。
5. 每个 run 最多发布 5 条 inline comments；超出的 inline 候选按 severity、确定性来源、文件和行号稳定排序后降级为 `CHECK_SUMMARY`。

这是一条可版本化、可离线评测的保守初始政策，不采用 LLM 自报置信度。

### 6.4 FindingFeedback

Feedback 身份为 `ReviewRunID + FindingFingerprint + GitHubActor`，状态为 `HELPFUL`、`FALSE_POSITIVE` 或 `WITHDRAWN`。没有 Aggregate 表示从未反馈；撤销 reaction 不删除审计记录。

GitHub 当前提供 review-comment reaction 查询 API，但不把相应 reaction 变化作为本设计可依赖的 GitHub App Webhook。因此 `REACTION_RECONCILE` job 在 PR 活跃期低频读取已发布评论的 reactions，按 reaction ID 和 actor 幂等对账。默认间隔 15 分钟；PR 关闭或评论发布满 30 天后停止自动轮询。管理员仍可手动运行一次对账命令用于演示或修复。

## 7. GitHub Integration Contract

### 7.1 最小权限

GitHub App 请求以下 repository permissions：

- **Contents: read** — 获取 diff 所需源码/commit。
- **Pull requests: write** — 读取 PR/reactions 并创建 inline review comments。
- **Checks: write** — 创建和更新 Check Runs。
- **Metadata: read** — GitHub App 的基础 repository 元数据权限。

订阅 `pull_request` Webhook；第一版处理 `opened`、`reopened`、`synchronize`。不订阅与当前能力无关的 Issues、Push、Deployments 等事件。

### 7.2 Webhook endpoint

`POST /webhooks/github`：

1. 读取原始 UTF-8 bytes。
2. 使用 Webhook secret 对原始 payload 计算 HMAC-SHA256。
3. 以 constant-time comparison 校验 `X-Hub-Signature-256`，签名必须以 `sha256=` 开头。
4. 读取 `X-GitHub-Delivery` 作为 delivery 幂等键，读取 `X-GitHub-Event` 进行路由。
5. 在一个数据库事务中记录 delivery、创建或复用新的 `ReviewRun`，并插入执行与 supersede durable jobs；不能在该事务中直接批量修改多个旧 Aggregate。
6. 事务提交后返回 `202`；不等待 clone、SpotBugs 或 LLM。

签名失败返回 `401` 且不保存 payload；格式错误返回 `400`；已验证但不关心或重复的事件返回 `202`。

### 7.3 Authentication and secrets

- GitHub App 私钥只用于短期 JWT；installation token 按安装获取并仅在内存缓存到过期前。
- Webhook secret、App private key、数据库凭据和模型 API key 仅从环境变量或外部 secret 注入。
- 日志、metrics 标签、数据库和异常信息不得包含 token、私钥、Webhook secret 或完整原始 payload。
- Repository 名、PR number、head SHA、delivery ID 和 GitHub artifact ID 可以作为审计事实。

### 7.4 Publication reconciliation

- Check Run 始终绑定 `ReviewRun.headSha`，使用固定 check name 和 ReviewRun external ID 对账。
- Inline comment 使用 `FindingFingerprint` 的不可见 marker 或稳定外部映射避免重复。
- 每次发布批次前重新读取 GitHub authoritative head SHA；每条 comment 绑定明确 commit SHA。
- 已有 artifact 优先 update/reconcile，只有不存在时才 create。
- 部分成功时立即保存已确认 artifact ID；重试只处理未确认项。
- 最终 review-system failure 更新 Check 为 `neutral` 并给出脱敏原因，不发布代码评论，也不阻断合并。

官方约束依据：

- [Validating webhook deliveries](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)
- [Using webhooks with GitHub Apps](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/using-webhooks-with-github-apps)
- [REST API endpoints for check runs](https://docs.github.com/en/rest/checks/runs)
- [REST API endpoints for pull request review comments](https://docs.github.com/en/rest/pulls/comments)
- [REST API endpoints for reactions](https://docs.github.com/en/rest/reactions/reactions)
- [Choosing permissions for a GitHub App](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/choosing-permissions-for-a-github-app)

## 8. PostgreSQL Persistence and Durable Jobs

Flyway 是 schema 的唯一变更入口。Domain 对象通过 Repository adapter 映射，不使用 persistence annotation 污染 Domain。

逻辑表及关键约束：

| Table | Purpose | Required invariant |
| --- | --- | --- |
| `github_deliveries` | verified Webhook delivery ledger | unique delivery ID |
| `review_runs` | ReviewRun root state and snapshots | unique business id; optimistic version |
| `review_attempts` | ordered execution attempts | unique `(review_run_id, attempt_number)` |
| `review_findings` | identified immutable finding plus publication facts | unique `(review_run_id, fingerprint)` |
| `finding_feedback` | current actor feedback and audit | unique `(review_run_id, fingerprint, actor_id)` |
| `durable_jobs` | leased executable intents | unique idempotency key |
| `outbox_events` | cross-context published facts | unique event ID; unpublished index |

`durable_jobs` 至少表达 job type、payload reference、state、attempt count、next attempt time、lease owner、lease expiry、last failure class 和 idempotency key。Worker 使用 PostgreSQL row locking 原子领取到期 job：

```text
READY → LEASED → SUCCEEDED
             ├→ READY     retryable failure with future next-attempt
             ├→ DEAD      deterministic/exhausted failure
             └→ READY     expired lease recovery
```

所有 job payload 使用内部 ID 引用 Aggregate，不复制 GitHub secret 或完整源码。`ReviewRun` 保存与后续 job/outbox 插入必须处于同一事务。Outbox 发布失败不回滚已经成立的 Domain 事实，由后续轮询继续投递。

## 9. Application Use Cases

### ObservePullRequestRevision

验证 adapter 已提供的 GitHub 事实，幂等创建新 `ReviewRun`，并提交 `EXECUTE_REVIEW` 与 `SUPERSEDE_OBSOLETE_RUNS` jobs。

### SupersedeObsoleteReviewRuns

按旧 run ID 分别加载并在独立事务中执行 `supersede obsolete work`。在状态收敛前，`PublishReviewOutcome` 的 GitHub authoritative head-SHA guard 已经即时阻止旧结果发布，因此不需要跨 Aggregate 事务。

### ExecuteReviewRun

领取 job、启动 attempt、准备隔离 checkout/source root、调用现有 `CodeReviewAgent`、规范化 finding fingerprints，并完成或失败 `ReviewRun`。完成时记录 `ReviewRunCompleted`。

### DecideReviewPublication

响应已持久化的 `ReviewRunCompleted`，运行纯 `FindingPublicationPolicy`，保存 decisions，并提交 `PUBLISH_REVIEW` job。

### PublishReviewOutcome

读取 GitHub authoritative revision，调用 `ReviewRun.authorizePublication`，对账 Check/comment artifacts，并确认 `PUBLISHED` 或记录最终失败。它不能在 adapter 内重算 publication decisions。

### ReconcileFindingFeedback

读取活跃 published comments 的 `+1`/`-1` reactions，幂等创建、修改、撤回 `FindingFeedback`，并写入 `FindingFeedbackRecorded` outbox fact。

## 10. Failure Semantics

| Failure class | Examples | Action |
| --- | --- | --- |
| Transient | model timeout, GitHub 5xx, connection reset | exponential backoff with jitter, max 2 execution retries |
| Rate limited | GitHub primary/secondary rate limit | respect server reset/retry guidance; no tight retry loop |
| Deterministic input | invalid diff, missing head, unsupported repository state | no retry; terminal non-blocking failure |
| Authorization | installation removed, permission revoked | no automatic retry until a new external event; terminal visible failure |
| Output format | one format-only repair fails again | terminal review failure; no fabricated findings |
| Worker crash | process dies after lease | lease expiry returns job to READY |
| Stale revision | head SHA no longer current | `SUPERSEDED`; no publication |
| Partial publication | some comments confirmed | persist confirmed IDs and resume only missing artifacts |

Retry count belongs to `ReviewAttempt`/job evidence; retry policy version belongs to the immutable run configuration snapshot.

## 11. Observability

所有结构化日志携带可用的 `delivery_id`、`review_run_id`、repository、PR number、head SHA、job ID 和 pipeline/config version。不得把 repository、actor 或错误正文作为无界高基数 metrics 标签。

Micrometer/Actuator 至少提供：

- Webhook received、signature failure、duplicate delivery；
- queue depth、lease recovery、retry 和 dead job；
- ReviewRun 状态计数、端到端耗时和各 stage latency；
- superseded run 和 prevented stale publication；
- finding 的 publication tier 分布和每 PR comment 数；
- GitHub、LLM、SpotBugs 成功率和延迟；
- input/output token 数；
- helpful、false-positive、withdrawn feedback；
- reaction reconciliation latency 和 API failure。

第一版提供 JSON logs、metrics 和 health/readiness，不引入分布式 tracing。Health 只说明进程存活；readiness 需要数据库可用且 server 已能接受 Webhook。模型或 GitHub 暂时不可用不应使 Webhook endpoint 退出 readiness，而应反映在 dependency metrics 与 job retry 中。

## 12. Test and Evaluation Strategy

### Domain unit tests

- ReviewRun 合法/非法转换表。
- transient retry、exhaustion、supersession 和 stale publication guard。
- ReviewAttempt 终态与顺序身份。
- Finding fingerprint normalization 与稳定性。
- FindingPublicationPolicy 的证据组合、边界和 top-5 降级。
- FindingFeedback 创建、修改、撤回、恢复和重复 observation。

### Application tests

- 重复 delivery 只产生一个 run/job。
- Aggregate 保存与 job/outbox 原子提交。
- 新 revision 使旧活动 run 失效。
- Application 不复制 Domain 状态判断。
- Feedback 不能修改原始 ReviewFinding。

### PostgreSQL integration tests

使用 Testcontainers PostgreSQL 验证 migration、Repository round trip、唯一约束、乐观锁、多 Worker 排他领取、lease 恢复和事务 rollback。禁止用 H2 代替 PostgreSQL 锁与事务测试。

### GitHub adapter contract tests

使用本地 HTTP stub 和签名 fixtures 验证 HMAC、delivery replay、installation token、Checks API、inline comments、reaction pagination、限流、部分成功恢复以及 secret redaction。

### End-to-end test

启动应用、PostgreSQL、fake GitHub 和 deterministic fake reviewer，跑通 signed Webhook → durable job → review → policy → Check/comments → reaction reconciliation。

### Architecture tests

使用 ArchUnit 强制：

- `reviewops.domain` 不依赖 Spring、JDBC、GitHub、LangChain4j 或 infrastructure。
- infrastructure 只能通过 Application/Domain 暴露的契约进入系统。
- production Review Operations 不依赖 evaluator-only annotation/sample loaders。

### AI quality evaluation

现有 synthetic/reverse release suite 继续作为回归集。真实公开 PR benchmark 单独存储和报告，不与当前 40-sample 数字混算。生产试运行只使用 non-blocking 模式，并报告 precision、comments/PR、helpful rate、false-positive rate、no-comment PR ratio、latency 和 token usage。

## 13. Acceptance Criteria

功能与可靠性红线：

1. 同一个 signed delivery 重放不会创建第二个 ReviewRun 或 job。
2. 新 head SHA 到达后，旧活动 run 无法创建或更新 Check/comment。
3. Worker 在领取任务后崩溃，lease 到期可由另一个 Worker 恢复。
4. 发布中途失败后重试不会重复已经确认的 comments。
5. 最终系统失败产生 neutral Check，不阻断合并且不发布 finding comments。
6. 👍、👎 和移除 reaction 经对账后分别形成 `HELPFUL`、`FALSE_POSITIVE`、`WITHDRAWN`。
7. Domain package 的架构依赖测试通过。
8. 原有 `mvn test`、CLI review/eval 行为和 sample isolation 不回归。
9. 新真实 PR benchmark 与原 synthetic/reverse report 明确分区。
10. 日志、数据库和测试快照中不存在私钥、installation token、Webhook secret 或模型 API key。

## 14. Delivery Slices

每个 slice 都保持可演示、可测试，并在完成后根据证据调整下一 slice，不绑定固定日历周期。

1. **Domain foundation** — ReviewRun/Attempt/Finding/Feedback、publication policy、状态与架构测试。
2. **Persistence foundation** — PostgreSQL、Flyway、repositories、durable jobs/outbox、Testcontainers。
3. **GitHub intake** — dual-mode launcher、MVC Webhook、HMAC、delivery dedup、PR revision admission。
4. **Review execution** — isolated checkout、existing pipeline adapter、attempt/retry、completed event handling。
5. **Publication** — head guard、Check Run、inline comment reconciliation、neutral failure behavior。
6. **Feedback** — reaction reconciliation、FindingFeedback 和 offline metrics export。
7. **Portfolio proof** — real public PR benchmark、metrics dashboard/document、Docker Compose demo and README walkthrough。

多 Agent、阻断门禁或微服务拆分只有在真实数据指出明确瓶颈或质量收益时才进入新的设计周期。
