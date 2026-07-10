package com.amz.engine.ml;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * ML 需求预测结果。
 */
@Data
@AllArgsConstructor
public class MlPrediction {

    /**
     * 预测 14 天需求量。
     */
    private double predictedDemand;

    /**
     * 置信度（0.0-1.0）。
     */
    private double confidence;

    /**
     * 模型版本标识。
     */
    private String modelVersion;
}
