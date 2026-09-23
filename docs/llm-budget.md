# LLM 调用预算

限制进程内的 LLM 调用次数，防止开发联调或评测跑批把额度烧光。

设计与取舍见 `design-notes.md` 第二节。

## 配置

```yaml
app:
  budget:
    enabled: ${APP_BUDGET_ENABLED:true}          # 总开关，false = 不限制
    max-calls: ${APP_BUDGET_MAX_CALLS:200}       # 进程内调用次数上限
    warn-ratio: ${APP_BUDGET_WARN_RATIO:0.8}     # 用量到 80% 时打一次警告
    reject-when-exhausted: ${APP_BUDGET_REJECT:true}  # 触顶后拒绝（false = 只告警）
```

默认开启。

### 环境变量速查

| 场景 | 设置 |
|---|---|
| 想完全关掉限制 | `APP_BUDGET_ENABLED=false` |
| 想跑一轮评测 | `APP_BUDGET_MAX_CALLS=500`（或临时关掉） |
| 只想观测不拦截 | `APP_BUDGET_REJECT=false` |

## 哪四条路径会被计数

| 路径 | 入口 | 一次消耗 |
|---|---|---|
| 代码生成 | `POST /app/chat/gen/code` | 1 次 |
| 类型路由 | `POST /app/add`（创建应用时判定类型） | 1 次 |
| 构建自愈 | 生成后构建失败自动触发 | 最多 `max-attempts` 次（默认 2） |
| 评测跑批 | `POST /eval/run` | 每用例 1 次 + 各自的自愈次数 |

计数点装在 `AiCodeGeneratorFacade.generateCodeStream`、`BuildSelfHealingService.askAiToFix`、`AppServiceImpl.createApp` 三处。为什么必须是这三处，见 `design-notes.md` 第二节。

## 使用

### 查看用量

```bash
GET /api/budget/usage        # 需管理员登录
```

返回已用、剩余、被拦截次数、已用百分比。

### 重置计数（不重启服务）

```bash
POST /api/budget/reset       # 需管理员登录
```

重置是全局的，作用于整个进程的计数，不只是当前用户。

## 已知限制

- **计数在进程内，重启即清零。** 开发场景合理，重启本就意味着重新开始一轮，但它不是配额系统。要跨实例、跨重启地限流，需把计数器换成 Redis。
- **按次不按 token。** 一次生成长度差异很大，次数的成本估算精度有限，所以配置里同时收紧了 `max-tokens`。
- **`assertAvailable` 与 `acquire` 之间有理论竞态。** 检查通过、落账时已被抢光。此时 `acquire` 仍会正常抛异常，只是准备工作白做一次，相比账目虚高一倍，这个代价更可接受。
