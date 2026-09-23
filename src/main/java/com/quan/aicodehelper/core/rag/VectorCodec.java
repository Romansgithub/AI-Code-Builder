package com.quan.aicodehelper.core.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量的二进制编解码与相似度计算。float32 紧凑存储代替 JSON；余弦的零向量分支集中处理，避免 NaN 污染排序。
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    /** 向量 → 字节，小端序存储。 */
    public static byte[] encode(float[] vector) {
        if (vector == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    /** 字节 → 向量；长度非 4 的整数倍视为数据损坏，直接抛异常。 */
    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("向量字节长度非法：" + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /** 余弦相似度，取值 [-1, 1]。空向量、维度不一致或零向量返回 0：检索中「算不出」等同「不相关」，不值得中断整次检索。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0D;
        }
        double dot = 0D;
        double normA = 0D;
        double normB = 0D;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA <= 0D || normB <= 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 把余弦映射到 [0,1]，与关键词命中率统一量纲后才能线性加权。 */
    public static double toUnitRange(double cosine) {
        if (Double.isNaN(cosine)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, (cosine + 1D) / 2D));
    }
}
