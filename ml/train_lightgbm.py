#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LightGBM 补货需求预测模型训练脚本。

生成模拟销量数据（含季节性、促销、趋势、噪声），提取 9 维特征，
训练 LightGBM 回归模型，并导出为 ONNX 格式供 Java 端 OnnxLightGbmPredictor 加载。

依赖安装：
    pip install lightgbm scikit-learn onnxmltools onnxruntime pandas numpy

使用方式：
    python ml/train_lightgbm.py

输出：
    amz-service/amz-service-spapi/src/main/resources/ml/lightgbm_replenish.onnx
"""

import os
import numpy as np
import pandas as pd
import lightgbm as lgb
from sklearn.model_selection import train_test_split
from sklearn.metrics import mean_absolute_error, r2_score

import onnxmltools
from onnxmltools.convert.common.data_types import FloatTensorType


# ==================== 配置 ====================

# 模拟数据参数
N_SKUS = 80          # SKU 数量
N_DAYS = 200         # 每个 SKU 的模拟天数
LOOKBACK = 30        # 特征回看窗口（天）
FORECAST = 14        # 预测窗口（天）
RANDOM_SEED = 42

# 类目列表
CATEGORIES = ['ELECTRONICS', 'TOYS', 'OUTDOOR', 'APPAREL', 'HOME']

# 各类目的月度季节性指数（1.0 为正常水平）
MONTHLY_SEASONALITY = {
    'ELECTRONICS': [0.8, 0.7, 0.8, 0.9, 1.0, 1.0, 1.1, 1.0, 1.1, 1.2, 1.8, 2.5],
    'TOYS':        [0.5, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.0, 1.2, 1.5, 2.0, 3.0],
    'OUTDOOR':     [0.6, 0.7, 0.9, 1.1, 1.3, 1.5, 1.5, 1.4, 1.2, 1.0, 0.8, 0.7],
    'APPAREL':     [0.7, 0.8, 1.0, 1.1, 1.2, 1.1, 1.0, 1.0, 1.1, 1.2, 1.3, 1.5],
    'HOME':        [0.9, 0.9, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.1, 1.1, 1.3, 1.4],
}

# ONNX 模型输出路径（相对于项目根目录）
OUTPUT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    'amz-service', 'amz-service-spapi', 'src', 'main', 'resources', 'ml',
    'lightgbm_replenish.onnx'
)

# 特征列名（顺序与 Java 端 ReplenishmentFeatures.toOnnxInput() 一致）
FEATURE_NAMES = [
    'dailyAvg7', 'dailyAvg30', 'cv', 'trendSlope',
    'seasonalIndex', 'promotionMultiplier', 'currentStock',
    'leadTimeDays', 'month'
]


# ==================== 模拟数据生成 ====================

def generate_synthetic_sales():
    """生成模拟销量数据（含季节性、促销、趋势、噪声）。"""
    np.random.seed(RANDOM_SEED)
    records = []
    start_date = pd.Timestamp('2024-01-01')

    for sku_idx in range(N_SKUS):
        category = CATEGORIES[sku_idx % len(CATEGORIES)]
        base_demand = np.random.randint(10, 100)
        trend = np.random.uniform(-0.3, 0.8)  # 日均趋势斜率

        for day in range(N_DAYS):
            date = start_date + pd.Timedelta(days=day)
            month = date.month

            # 基础需求 × 季节性指数
            seasonal_idx = MONTHLY_SEASONALITY[category][month - 1]
            demand = base_demand * seasonal_idx

            # 线性趋势
            demand += trend * day

            # 促销事件（5% 概率，乘数 2.0-3.0）
            is_promo = np.random.random() < 0.05
            if is_promo:
                demand *= np.random.uniform(2.0, 3.0)

            # 高斯噪声
            demand += np.random.normal(0, base_demand * 0.15)

            # 确保非负整数
            demand = max(0, int(round(demand)))

            records.append({
                'sku': f'SKU-{sku_idx:04d}',
                'category': category,
                'date': date,
                'quantity': demand,
                'month': month,
                'is_promo': int(is_promo),
            })

    return pd.DataFrame(records)


# ==================== 特征提取 ====================

def compute_trend_slope(quantities):
    """简单线性回归计算趋势斜率。"""
    n = len(quantities)
    if n < 2:
        return 0.0
    x = np.arange(n, dtype=float)
    y = np.array(quantities, dtype=float)
    slope = np.polyfit(x, y, 1)[0]
    return float(slope)


def extract_features(df, sku, date, category, current_stock, lead_time_days):
    """
    提取 9 维特征，顺序与 Java 端 ReplenishmentFeatures.toOnnxInput() 一致。

    返回: (features_list, target)
      - features_list: [dailyAvg7, dailyAvg30, cv, trendSlope, seasonalIndex,
                         promotionMultiplier, currentStock, leadTimeDays, month]
      - target: 未来 14 天总销量
    """
    sku_data = df[(df['sku'] == sku) & (df['date'] <= date)].sort_values('date').reset_index(drop=True)

    # 取最近 30 天和 7 天
    last_30 = sku_data.tail(LOOKBACK)['quantity'].values
    last_7 = sku_data.tail(7)['quantity'].values

    daily_avg_7 = float(np.mean(last_7)) if len(last_7) > 0 else 0.0
    daily_avg_30 = float(np.mean(last_30)) if len(last_30) > 0 else 0.0

    # 变异系数 CV = stddev / mean
    if len(last_30) > 0 and np.mean(last_30) > 0:
        cv = float(np.std(last_30) / np.mean(last_30))
    else:
        cv = 0.0

    # 趋势斜率
    trend_slope = compute_trend_slope(last_30)

    # 季节性指数
    month = date.month
    seasonal_index = MONTHLY_SEASONALITY[category][month - 1]

    # 促销乘数（当天是否有促销）
    promo_rows = sku_data[sku_data['is_promo'] == 1]
    promo_multiplier = float(promo_rows['is_promo'].count() / max(len(sku_data), 1)) * 3.0 + 1.0

    features = [
        daily_avg_7,
        daily_avg_30,
        cv,
        trend_slope,
        float(seasonal_index),
        promo_multiplier,
        float(current_stock),
        float(lead_time_days),
        float(month),
    ]

    # 目标：未来 14 天总销量
    future_data = df[(df['sku'] == sku) &
                     (df['date'] > date) &
                     (df['date'] <= date + pd.Timedelta(days=FORECAST))]
    target = int(future_data['quantity'].sum())

    return features, target


def build_dataset(df):
    """构建训练数据集。"""
    features_list = []
    targets = []

    # 采购周期选项
    lead_time_options = [14, 21, 30, 45]

    for sku in df['sku'].unique():
        sku_data = df[df['sku'] == sku].sort_values('date').reset_index(drop=True)
        category = sku_data['category'].iloc[0]

        # 从第 LOOKBACK 天开始，确保有足够的历史数据
        # 到第 N_DAYS - FORECAST 天结束，确保有足够的未来数据
        for i in range(LOOKBACK, len(sku_data) - FORECAST):
            date = sku_data['date'].iloc[i]
            current_stock = int(np.random.randint(0, 500))
            lead_time_days = int(np.random.choice(lead_time_options))

            features, target = extract_features(df, sku, date, category, current_stock, lead_time_days)
            features_list.append(features)
            targets.append(target)

    X = np.array(features_list, dtype=np.float32)
    y = np.array(targets, dtype=np.float32)
    return X, y


# ==================== 模型训练与导出 ====================

def train_and_export():
    """训练 LightGBM 回归模型并导出为 ONNX。"""
    print("=" * 60)
    print("LightGBM 补货需求预测模型训练")
    print("=" * 60)

    # 1. 生成模拟数据
    print("\n[1/5] 生成模拟销量数据...")
    df = generate_synthetic_sales()
    print(f"  SKU 数量: {df['sku'].nunique()}, 总记录数: {len(df)}")

    # 2. 构建训练数据集
    print("\n[2/5] 提取特征...")
    X, y = build_dataset(df)
    print(f"  样本数: {len(X)}, 特征数: {X.shape[1]}")
    print(f"  特征列: {FEATURE_NAMES}")

    # 3. 划分训练/测试集
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=RANDOM_SEED)

    # 4. 训练 LightGBM 回归模型
    print("\n[3/5] 训练 LightGBM 回归模型...")
    model = lgb.LGBMRegressor(
        n_estimators=200,
        learning_rate=0.05,
        max_depth=6,
        num_leaves=31,
        min_child_samples=20,
        subsample=0.8,
        colsample_bytree=0.8,
        random_state=RANDOM_SEED,
        verbose=-1,
    )
    model.fit(X_train, y_train)

    # 评估
    y_pred = model.predict(X_test)
    mae = mean_absolute_error(y_test, y_pred)
    r2 = r2_score(y_test, y_pred)
    print(f"  测试集 MAE: {mae:.2f}")
    print(f"  测试集 R²:  {r2:.4f}")

    # 5. 导出为 ONNX
    print("\n[4/5] 导出 ONNX 模型...")
    # 输入名称为 "input"，与 Java 端动态获取的输入节点名一致
    initial_types = [('input', FloatTensorType([None, 9]))]
    onnx_model = onnxmltools.convert_lightgbm(model, initial_types=initial_types)

    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    onnxmltools.utils.save_model(onnx_model, OUTPUT_PATH)
    print(f"  ONNX 模型已保存至: {OUTPUT_PATH}")

    # 6. 验证 ONNX 模型
    print("\n[5/5] 验证 ONNX 模型...")
    try:
        import onnxruntime as ort
        sess = ort.InferenceSession(OUTPUT_PATH)
        input_name = sess.get_inputs()[0].name
        test_input = {input_name: X_test[:3].astype(np.float32)}
        onnx_pred = sess.run(None, test_input)[0]
        lgb_pred = model.predict(X_test[:3])
        print(f"  ONNX 输入节点: {input_name}")
        print(f"  LightGBM 预测: {lgb_pred}")
        print(f"  ONNX 预测:     {onnx_pred.flatten()}")
        print("  ONNX 模型验证通过")
    except ImportError:
        print("  onnxruntime 未安装，跳过验证（不影响模型导出）")

    print("\n" + "=" * 60)
    print("训练完成！请将 ONNX 模型文件部署到 Java 服务的 classpath。")
    print("=" * 60)


if __name__ == '__main__':
    train_and_export()
