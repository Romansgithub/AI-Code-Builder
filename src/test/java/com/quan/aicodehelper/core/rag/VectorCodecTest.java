package com.quan.aicodehelper.core.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量编解码与相似度计算测试。零向量、维度不一致这些「算不出相似度」的情形必须返回 0，
 * 而 {@code NaN} 参与排序不报错、只让结果顺序无从解释，这类静默错误比崩溃更难查。
 */
@DisplayName("向量编解码与相似度")
class VectorCodecTest {

    @Nested
    @DisplayName("编解码")
    class EncodeDecodeCase {

        @Test
        @DisplayName("往返编解码应逐位还原")
        void roundTrip() {
            float[] original = {0.5f, -1.25f, 3f, 0f, 1e-7f};
            float[] restored = VectorCodec.decode(VectorCodec.encode(original));

            assertThat(restored).containsExactly(original);
        }

        @Test
        @DisplayName("1024 维向量应占用 4096 字节 —— 紧凑存储是这么省下来的")
        void sizeIsCompact() {
            float[] vector = new float[1024];
            assertThat(VectorCodec.encode(vector)).hasSize(1024 * Float.BYTES);
        }

        @Test
        @DisplayName("null 与空数组应原样返回 null，不应造出长度为 0 的向量")
        void nullAndEmpty() {
            assertThat(VectorCodec.encode(null)).isNull();
            assertThat(VectorCodec.decode(null)).isNull();
            assertThat(VectorCodec.decode(new byte[0])).isNull();
        }

        @Test
        @DisplayName("字节长度不是 4 的倍数必须报错，而不是解出长度对不上的向量")
        void malformedBytesShouldFail() {
            assertThatThrownBy(() -> VectorCodec.decode(new byte[7]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("向量字节长度非法");
        }
    }

    @Nested
    @DisplayName("余弦相似度")
    class CosineCase {

        @Test
        @DisplayName("同一向量相似度为 1")
        void identical() {
            float[] v = {1f, 2f, 3f};
            assertThat(VectorCodec.cosine(v, v)).isCloseTo(1D, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("正交向量相似度为 0")
        void orthogonal() {
            assertThat(VectorCodec.cosine(new float[]{1f, 0f}, new float[]{0f, 1f}))
                    .isCloseTo(0D, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("反向向量相似度为 -1")
        void opposite() {
            assertThat(VectorCodec.cosine(new float[]{1f, 2f}, new float[]{-1f, -2f}))
                    .isCloseTo(-1D, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("零向量返回 0 而不是 NaN —— NaN 会让排序结果无从解释")
        void zeroVectorIsNotNaN() {
            double result = VectorCodec.cosine(new float[]{0f, 0f}, new float[]{1f, 1f});

            assertThat(Double.isNaN(result)).isFalse();
            assertThat(result).isEqualTo(0D);
        }

        @Test
        @DisplayName("维度不一致视为不相关，而不是抛异常中断整次检索")
        void dimensionMismatchIsZero() {
            assertThat(VectorCodec.cosine(new float[]{1f, 2f}, new float[]{1f, 2f, 3f}))
                    .isEqualTo(0D);
        }

        @Test
        @DisplayName("任一侧为 null 时返回 0")
        void nullIsZero() {
            assertThat(VectorCodec.cosine(null, new float[]{1f})).isEqualTo(0D);
            assertThat(VectorCodec.cosine(new float[]{1f}, null)).isEqualTo(0D);
        }
    }

    @Nested
    @DisplayName("归一化到 0~1")
    class UnitRangeCase {

        @Test
        @DisplayName("余弦 -1 / 0 / 1 应分别映射为 0 / 0.5 / 1")
        void mapping() {
            assertThat(VectorCodec.toUnitRange(-1D)).isEqualTo(0D);
            assertThat(VectorCodec.toUnitRange(0D)).isEqualTo(0.5D);
            assertThat(VectorCodec.toUnitRange(1D)).isEqualTo(1D);
        }

        @Test
        @DisplayName("超出范围的值应被夹紧，否则权重不再是「各占多少话语权」")
        void clamped() {
            assertThat(VectorCodec.toUnitRange(-5D)).isEqualTo(0D);
            assertThat(VectorCodec.toUnitRange(5D)).isEqualTo(1D);
        }

        @Test
        @DisplayName("NaN 映射为 0，避免污染加权求和")
        void nanBecomesZero() {
            assertThat(VectorCodec.toUnitRange(Double.NaN)).isEqualTo(0D);
        }
    }
}
