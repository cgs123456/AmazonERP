# LightGBM 补货需求预测模型

## 概述

本目录包含 LightGBM 补货需求预测模型的训练脚本。模型训练完成后导出为 ONNX 格式，供 Java 端 `OnnxLightGbmPredictor` 加载推理。

## 依赖安装

```bash
pip install lightgbm scikit-learn onnxmltools onnxruntime pandas numpy
```

## 训练流程

```bash
python ml/train_lightgbm.py
```

脚本执行步骤：

1. **生成模拟销量数据** — 80 个 SKU × 200 天，含季节性、促销事件、线性趋势、高斯噪声
2. **提取 9 维特征** — 与 Java 端 `ReplenishmentFeatures.toOnnxInput()` 顺序一致
3. **训练 LightGBM 回归模型** — 目标为未来 14 天总销量
4. **导出 ONNX 模型** — 输出至 `amz-service/amz-service-spapi/src/main/resources/ml/lightgbm_replenish.onnx`
5. **验证 ONNX 推理** — 对比 LightGBM 原生预测与 ONNX 推理结果

## 特征说明

| 序号 | 特征名               | 说明                          |
|------|----------------------|-------------------------------|
| 0    | dailyAvg7            | 7 天日均销量                  |
| 1    | dailyAvg30           | 30 天日均销量                 |
| 2    | cv                   | 变异系数（stddev / mean）     |
| 3    | trendSlope           | 销量趋势斜率（线性回归）      |
| 4    | seasonalIndex        | 季节性指数                    |
| 5    | promotionMultiplier  | 促销乘数                      |
| 6    | currentStock         | 当前库存                      |
| 7    | leadTimeDays         | 采购周期（天）                |
| 8    | month                | 当前月份（1-12）              |

## 混合补货策略

Java 端 `HybridReplenishmentEngine` 在以下条件同时满足时启用 ML 混合：

- ONNX 模型已成功加载（`isModelLoaded() == true`）
- CV 变异系数 > 0.6（高波动异常 pattern）

混合公式：`suggestedQty = ML预测 × 0.7 + 规则建议 × 0.3`

未满足条件时回退到纯规则引擎（`blendStrategy = RULE_ONLY`）。
