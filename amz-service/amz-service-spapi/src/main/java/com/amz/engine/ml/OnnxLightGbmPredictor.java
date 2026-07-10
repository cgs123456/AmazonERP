package com.amz.engine.ml;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 ONNX Runtime 的 LightGBM 需求预测器。
 * <p>
 * 在构造函数中尝试从 classpath 加载 {@code ml/lightgbm_replenish.onnx} 模型文件。
 * 加载失败时仅记录 warning，不抛异常，{@link #isModelLoaded()} 返回 false，
 * 调用方可据此回退到纯规则引擎，实现优雅降级。
 * <p>
 * 输入：9 维 float 特征数组（见 {@link ReplenishmentFeatures#toOnnxInput()}）。
 * 输出：1 维 float（预测 14 天需求量）。
 */
@Slf4j
@Component
public class OnnxLightGbmPredictor implements MlDemandPredictor {

    /**
     * ONNX 模型在 classpath 中的路径。
     */
    private static final String MODEL_PATH = "ml/lightgbm_replenish.onnx";

    /**
     * 模型版本标识。
     */
    private static final String MODEL_VERSION = "lightgbm-onnx-1.0";

    /**
     * 模型加载成功时的默认置信度（回归模型无原生置信度，使用固定值）。
     */
    private static final double DEFAULT_CONFIDENCE = 0.8;

    private final OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private volatile boolean modelLoaded;

    /**
     * 构造函数中尝试加载 ONNX 模型，加载失败只 log warning 不抛异常。
     */
    public OnnxLightGbmPredictor() {
        env = OrtEnvironment.getEnvironment();
        try {
            loadModel();
            modelLoaded = true;
            log.info("ONNX LightGBM 模型加载成功: path={} inputName={}", MODEL_PATH, inputName);
        } catch (Exception e) {
            modelLoaded = false;
            log.warn("ONNX LightGBM 模型加载失败，将使用规则引擎兜底: {} - {}", MODEL_PATH, e.getMessage());
        }
    }

    /**
     * 从 classpath 加载 ONNX 模型并创建推理 session。
     *
     * @throws Exception 模型文件不存在或加载失败时抛出
     */
    private void loadModel() throws Exception {
        ClassPathResource resource = new ClassPathResource(MODEL_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("模型文件不存在: " + MODEL_PATH);
        }
        try (InputStream is = resource.getInputStream()) {
            byte[] modelBytes = is.readAllBytes();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            session = env.createSession(modelBytes, options);
            options.close();
            // 动态获取模型输入节点名称（onnxmltools 导出的 LightGBM 默认为 "input"）
            Map<String, NodeInfo> inputInfo = session.getInputInfo();
            inputName = inputInfo.keySet().iterator().next();
        }
    }

    @Override
    public MlPrediction predict(ReplenishmentFeatures features) {
        if (!modelLoaded || session == null) {
            return defaultPrediction();
        }
        try {
            float[][] input = {features.toOnnxInput()};
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, input)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put(inputName, inputTensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    for (Map.Entry<String, OnnxValue> entry : result) {
                        OnnxValue value = entry.getValue();
                        double predictedDemand = extractDemand(value.getValue());
                        return new MlPrediction(predictedDemand, DEFAULT_CONFIDENCE, MODEL_VERSION);
                    }
                }
            }
            log.warn("ONNX 推理无有效输出，返回默认预测");
            return defaultPrediction();
        } catch (OrtException e) {
            log.warn("ONNX 推理失败: {}", e.getMessage());
            return defaultPrediction();
        }
    }

    /**
     * 从 ONNX 输出值中提取预测需求量。
     * <p>
     * LightGBM 回归模型的输出可能是 float[1][1]、float[1] 或 Float，此处统一处理。
     *
     * @param value ONNX 输出值
     * @return 预测需求量
     */
    private double extractDemand(Object value) {
        if (value instanceof float[][]) {
            float[][] arr = (float[][]) value;
            if (arr.length > 0 && arr[0].length > 0) {
                return arr[0][0];
            }
        } else if (value instanceof float[]) {
            float[] arr = (float[]) value;
            if (arr.length > 0) {
                return arr[0];
            }
        } else if (value instanceof Float) {
            return (Float) value;
        }
        return 0.0;
    }

    /**
     * 模型未加载或推理失败时的默认预测（confidence=0）。
     */
    private MlPrediction defaultPrediction() {
        return new MlPrediction(0.0, 0.0, MODEL_VERSION);
    }

    @Override
    public boolean isModelLoaded() {
        return modelLoaded;
    }

    /**
     * Bean 销毁时关闭 ONNX session 释放原生资源。
     */
    @PreDestroy
    public void destroy() {
        if (session != null) {
            try {
                session.close();
                log.info("ONNX session 已关闭");
            } catch (Exception e) {
                log.warn("关闭 ONNX session 失败: {}", e.getMessage());
            }
        }
    }
}
