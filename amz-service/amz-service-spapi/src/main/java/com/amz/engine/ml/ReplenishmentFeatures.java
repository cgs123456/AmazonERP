package com.amz.engine.ml;

import lombok.Builder;
import lombok.Data;

/**
 * 补货需求预测的 9 维特征工程。
 * 特征顺序必须与 Python 训练脚本 ml/train_lightgbm.py 一致。
 */
@Data
@Builder
public class ReplenishmentFeatures {

    /** 近 7 天日均销量 */
    private double dailyAvg7;

    /** 近 30 天日均销量 */
    private double dailyAvg30;

    /** 变异系数（标准差 / 均值） */
    private double cv;

    /** 线性回归斜率 */
    private double trendSlope;

    /** 季节性指数 */
    private double seasonalIndex;

    /** 促销乘数 */
    private double promotionMultiplier;

    /** 当前总库存 */
    private int currentStock;

    /** 采购周期（天） */
    private int leadTimeDays;

    /** 当前月份（1-12） */
    private int month;

    /**
     * 将特征按固定顺序转为 ONNX 模型输入的 float 数组。
     * 顺序必须与训练脚本一致。
     *
     * @return 9 维 float 数组
     */
    public float[] toOnnxInput() {
        return new float[]{
                (float) dailyAvg7,
                (float) dailyAvg30,
                (float) cv,
                (float) trendSlope,
                (float) seasonalIndex,
                (float) promotionMultiplier,
                (float) currentStock,
                (float) leadTimeDays,
                (float) month
        };
    }
}