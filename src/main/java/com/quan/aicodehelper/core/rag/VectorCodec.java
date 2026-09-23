package com.quan.aicodehelper.core.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量的二进制编解码与相似度计算。
 *
 * <p><b>为什么不把向量存成 JSON 数组或逗号分隔字符串</b>：
 * 1024 维的 float 用 JSON 大约 12 KB，用 float32 紧凑存储只要 4 KB，
 * 而且不用解析 —— 拉出来就能直接参与计算。库里存的是派生数据，
 * 可读性换不来什么，体积与解析开销却是每次检索都要付的。</p>
 *
 * <p><b>为什么把相似度计算收在这里而不是用现成工具</b>：
 * 余弦的零向量分支很容易被漏掉 —— 分母为 0 时结果是 {@code NaN}，
 * 而 {@code NaN} 参与排序不会抛异常，只会让排序结果变成一堆无从解释的顺序。
 * 这类错误静默且难查，所以集中一处处理并单独测。</p>
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    /**
     * 向量 → 字节。采用小端序，与主流 x86 原生字节序一致。
     */
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

    /**
     * 字节 → 向量。长度不是 4 的整数倍时抛异常 —— 说明存进去的东西本身坏了，
     * 此时应当立刻暴露，而不是解出一个长度对不上的向量继续算。
     */
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

    /**
     * 余弦相似度，取值范围 [-1, 1]。
     *
     * <p>任一向量为空、维度不一致或存在零向量时返回 0（而非抛异常）：
     * 检索链路上「算不出相似度」与「相似度很低」的处理方式完全相同 ——
     * 都当作不相关。为零向量而中断整次检索，代价远大于收益。</p>
     */
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

    /**
     * 把余弦值映射到 [0, 1]，便于与同样落在 [0, 1] 的关键词命中率线性加权。
     *
     * <p>不映射也能用，但那样得分为负数时会让「向量部分为负 + 关键词部分为正」
     * 出现抵消，最终排序不再可解释。统一到同一量纲后，
     * 两个权重才真正表示「各占多少话语权」。</p>
     */
    public static double toUnitRange(double cosine) {
        if (Double.isNaN(cosine)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, (cosine + 1D) / 2D));
    }
}
