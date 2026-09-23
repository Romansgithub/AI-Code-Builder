# AI 输出可评测性

把「AI 生成效果好不好的」从主观感受变成可量化、可对比的数字。

入口 `/api/eval/set`、`/api/eval/run`、`/api/eval/report`，均需管理员登录态。
设计与取舍见 `design-notes.md` 第一节。

## 指标

| 指标 | 含义 | 怎么读 |
|---|---|---|
| 一次通过率 | `firstBuildPassed` 占比 | AI 生成质量的基线，不依赖自愈 |
| 最终通过率 | `finalPassed` 占比 | 用户实际拿到可用项目的比例 |
| 自愈成功率 | 自愈后成功 / 触发自愈 | 自愈到底有没有用的直接证据 |
| 失败类型分布 | 各 `failureType` 计数 | 下一步该优化什么 |

另记录生成耗时与构建耗时（评估性能与成本）、产物文件数与字节数（评估代码量）。

两处容易读错的地方：

- **自愈成功率 0% 与 `healingUsedCount = 0` 不是一回事。** 前者是触发了自愈但全失败，后者是压根没有用例触发自愈。接口在后者时返回 `null` 而不是 `0`，避免混淆。
- **通过率的分母是「用例总数 − 故障注入用例数」。** 当前评测集 9 条里有 1 条注入用例，分母就是 8，所以 `8/8` 不是写错了。故障注入用例只进自愈成功率的分母。

## 使用步骤

### 1. 建表（首次必须执行）

```bash
mysql -u<user> -p <db> < docs/sql/code_gen_eval_record.sql
```

采集是旁路逻辑，表不存在时不影响代码生成，只是日志里会出现「记录生成评测指标失败」的警告。

### 2. 开启跑批

默认关闭。在 `application.yml` 或环境变量中打开：

```yaml
app:
  eval:
    enabled: true
```

或 `APP_EVAL_ENABLED=true`。

### 3. 跑一轮评测

```bash
# 先看当前评测集，确认用例没写错
curl -b "SESSION=<admin-cookie>" http://localhost:8080/api/eval/set

# 触发跑批。耗时以十分钟计，接口同步返回，务必用长超时
curl -b "SESSION=<admin-cookie>" -X POST http://localhost:8080/api/eval/run \
     --max-time 1800
```

跑批结果会同时打进日志，形如（下面是格式示例，数字是编的，不要当成真实跑批结果）：

```
评测集：代码生成基础回归集 v1.0
批次：eval-a1b2c3d4
用例：8 条，总耗时 412.3 秒

── 汇总指标 ──
一次通过率：62.5%（5/8）
最终通过率：100.0%（8/8）
自愈成功率：100.0%（3/3 条触发自愈）
平均生成耗时：38.2 秒，平均构建耗时：19.7 秒
```

### 4. 查询历史报告

```bash
# 最近 30 天
curl -b "SESSION=<admin-cookie>" "http://localhost:8080/api/eval/report?sinceDays=30"

# 指定批次
curl -b "SESSION=<admin-cookie>" "http://localhost:8080/api/eval/report?batchId=eval-a1b2c3d4"
```

## 评测集

`src/main/resources/eval/codegen-eval-set.json`，9 条用例覆盖三种生成类型（HTML 3 条 / 多文件 2 条 / Vue 工程 4 条，其中 1 条为故障注入用例）。

用例增删改之后必须升 `version`。`EvalSetLoader` 强制校验它非空，不升版本就是在拿两套不同的考卷比分数。

### 用例字段

```json
{
  "id": "vue-todo",              // 唯一，只能含字母数字下划线短横线（会用作目录名）
  "description": "Vue 工程待办应用",
  "codeGenType": "vue_project",  // 写 value 或常量名都行（vue_project / VUE_PROJECT）
  "prompt": "..."
}
```

加载时校验：`version` 非空、用例非空、id 唯一且安全、类型可识别、prompt 非空。任一不满足直接抛错并指出是哪条用例。错误指标比没有指标更危险，因为它看起来可信。

## 关键实现位置

| 文件 | 职责 |
|---|---|
| `core/eval/GenerationMetric.java` | 单次生成的指标数据 |
| `core/eval/CodeGenEvalRecorder.java` | 落库（永不抛异常）+ 产物规模统计 |
| `core/eval/EvalSetLoader.java` | 评测集加载与严格校验 |
| `core/eval/EvalRunner.java` | 跑批执行 |
| `core/eval/EvalRunResult.java` | 汇总计算与可读报告 |
| `service/impl/EvalReportService.java` | 报告组装（比率计算） |
| `controller/EvalController.java` | `/eval/set`、`/eval/run`、`/eval/report` |
| `docs/sql/code_gen_eval_record.sql` | 建表 DDL |

埋点位置在 `AiCodeGeneratorFacade`，隔离性设计与这个位置的选择理由见 `design-notes.md` 第一节。

## 已知限制

- **跑批是同步接口。** 一轮十分钟起，调用方需自备长超时。做成异步任务会更友好，但它属于手动触发的运维动作，不在用户交互路径上，暂未任务化。
- **单次跑批串行。** 9 条用例按顺序跑，换取耗时数据可信与日志可读。
- **超时只能做到「判失败并继续」，无法真正杀掉卡住的任务。** 用例跑在虚拟线程上，主线程限时 `join`，超时后 `interrupt` 并继续下一条。若卡在不可中断的 IO（如 npm 子进程）上，那个虚拟线程仍会残留。命令级超时由 `VueProjectBuilder` 兜底（npm install 300s / build 180s）。
