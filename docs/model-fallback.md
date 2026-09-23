# 多模型降级

主模型调用失败时自动切换候选模型或候选 key。默认不启用。

设计与取舍见 `design-notes.md` 第三节。

## 配置

```yaml
app:
  model-fallback:
    enabled: ${APP_MODEL_FALLBACK_ENABLED:true}
    attempts-per-candidate: ${APP_MODEL_FALLBACK_ATTEMPTS:1}
    notify-user: ${APP_MODEL_FALLBACK_NOTIFY:true}
    candidates: []
```

默认 `candidates: []`（空），行为与不启用时完全一致。降级是要显式配置候选的能力。

### 候选字段

全部可选，未填的字段沿用主模型配置：

| 字段 | 说明 |
|---|---|
| `model-name` | **必填**。为空则该候选被跳过 |
| `base-url` | 覆盖 base-url，用于跨服务商 |
| `api-key` | 覆盖 key，用于跨账号 |
| `max-tokens` | 覆盖输出上限 |
| `label` | 日志与前端提示用的可读名 |

### 两种典型配法

**换模型档位**（同名不同档，成本更低）：

```yaml
candidates:
  - model-name: qwen-turbo
    label: 通义千问（经济档）
  - model-name: qwen-plus
    api-key: ${LLM_API_KEY_BACKUP:}
    label: 通义千问（备用账号）
```

**换 key**（应对单 key 额度耗尽，开发者最常遇到的场景）：

```yaml
candidates:
  - model-name: qwen-plus
    api-key: ${LLM_API_KEY_BACKUP}
    label: 备用账号
```

## 哪些失败才降级

只对「换个模型或换个 key 可能就好了」的失败降级。

| 降级 | 不降级 |
|---|---|
| 限流 429 | 请求参数非法 |
| 5xx 服务端错误 | 上下文超长 |
| 超时 | 内容被安全策略拦截 |
| 鉴权失败 401/403（额度耗尽） | 其它未识别异常 |
| 模型不存在 404 | |
| 网络不可达 / DNS 失败 | |

分类由 `FallbackFailureClassifier` 自己实现，没有复用 LangChain4j 的 `RetriableException` / `NonRetriableException`，两者的语义不同。差异见 `design-notes.md` 第三节。

## 两条行为约束

**一旦开始输出就不能再降级。** 流式响应不可回滚。主模型已经吐了 500 个 token 之后才失败，再切备用模型会让用户拿到「前 500 token + 备用模型的完整输出」，比直接报错更糟。规则是只在尚未收到任何 partial response 时才能降级，一旦有 token 流出，后续失败一律如实上报。

**同步抛异常也要接住。** 有些实现会同步 `throw` 而不是回调 `onError`，两条路径都要接住，否则降级在这种实现上会静默失效。

## 用户可见性

降级发生时通过 SSE 进度事件告知用户：

```
模型服务异常（模型服务限流），已自动切换到备用模型 通义千问（经济档）
```

备用模型往往档位更低，静默降级会让用户困惑于「为什么这次生成得不一样」。配置 `notify-user: false` 可关闭。

## 实现

| 类 | 职责 |
|---|---|
| `FallbackFailureClassifier` | 判断该不该降级（纯函数，易测） |
| `FallbackStreamingChatModel` | 装饰器，按链逐个尝试 |
| `FallbackModelProvider` | 按配置装配候选链 |

装配点在 `AiCodeGeneratorServiceFactory.createAiCodeGeneratorService`。主模型排在候选队列第 0 位，与其他候选逻辑完全一致。未配置候选时返回原模型对象，不做包装。

## 已知限制

- **降级不解决「两边都没额度」。** 候选链全部失败时如实上报，不会无限尝试。
- **只覆盖流式生成模型。** 路由用的同步 `ChatModel` 未接入降级，它的失败会让创建应用失败，但额度消耗极小。
- **候选链在启动时确定**，运行时不可动态增减。
- **不做熔断。** 某一个候选持续失败时，每个请求都会先撞上它再降级，多花一次调用。真正的生产系统需要加熔断器（如 Resilience4j），这里刻意不做：本项目是单实例开发规模，加熔断会让代码复杂度远超收益。这是已知的、有意接受的简化。
- **降级会额外消耗调用预算**，每次尝试都算一次调用。`attempts-per-candidate` 设大时尤其明显，真正故障时等待时间也会翻倍。
