package com.amz.engine.ml;

/**
 * ML 需求预测器接口。
 * <p>
 * 抽象出补货需求预测能力，底层可替换为 ONNX / TensorFlow / 远程推理服务等不同实现。
 */
public interface MlDemandPredictor {

    /**
     * 基于特征工程进行需求预测。
     *
     * @param features 补货特征
     * @return ML 预测结果（预测需求量、置信度、模型版本）
     */
    MlPrediction predict(ReplenishmentFeatures features);

    /**
     * 判断 ML 模型是否已成功加载。
     * <p>
     * 模型未加载时调用方应回退到纯规则引擎。
     *
     * @return true 表示模型已加载且可用于推理
     */
    boolean isModelLoaded();
}
