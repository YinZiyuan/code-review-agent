# W4 样本分布

## 基线：20 个 reverse 样本

| 类别 | 难度 | 数量 |
| --- | --- | --- |
| concurrency | hard | 1 |
| concurrency | medium | 3 |
| performance | easy | 1 |
| performance | medium | 3 |
| security | easy | 3 |
| security | medium | 1 |
| stability | easy | 3 |
| stability | medium | 2 |
| test | easy | 2 |
| test | medium | 1 |

## W4 扩充目标

现有样本的类别覆盖尚可，但只有一个 hard 样本。W4 新增样本需要：

- 补充 concurrency、performance、stability 和 security 的 hard 场景。
- 增加 test 类别，其中至少包含一个较难的测试设计缺陷。
- 加入 synthetic true negative 和 near miss，不只对 recall 施压，也要检验 precision。

## 最终结果：40 个 release 样本

| 类别 | 难度 | 数量 |
| --- | --- | --- |
| concurrency | hard | 4 |
| concurrency | medium | 4 |
| performance | easy | 2 |
| performance | hard | 2 |
| performance | medium | 5 |
| security | easy | 3 |
| security | hard | 2 |
| security | medium | 3 |
| stability | easy | 3 |
| stability | hard | 2 |
| stability | medium | 3 |
| style | easy | 1 |
| test | easy | 2 |
| test | hard | 2 |
| test | medium | 2 |

新增集合由 10 个 reverse-style 缺陷引入样本和 10 个 synthetic 边界样本组成：

- True negative：`synthetic-001..003`
- Near miss：`synthetic-004..006`
- 行号、跨文件等复杂场景：`synthetic-007..010`

> 这份分布用于项目内部回归与版本比较。样本全部为手工构造，不代表真实 PR 的自然缺陷分布。
