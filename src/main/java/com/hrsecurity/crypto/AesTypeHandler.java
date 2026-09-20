package com.hrsecurity.crypto;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * MyBatis-Plus 字段加解密 TypeHandler：业务侧只看见明文，落库自动变密文。
 *
 * 用法：实体字段上 {@code @TableField(typeHandler = AesTypeHandler.class)}，
 * 之后 insert/update/select（含 Wrapper 查询、分页、selectBatchIds）都会自动走这里。
 *
 * 为什么用 TypeHandler 而不是在 Service 里手动加解密（面试点）：
 * 1. 不遗漏：只要走这个实体的读写都会被处理，不依赖开发者记得调用加解密；
 * 2. 不重复：加解密规则只有一处，改密钥/改算法不用翻遍 Service；
 * 3. 语义清晰：实体字段就是"明文字段"，数据库列是"密文列"，加密是存储细节。
 * 代价：无法对密文列做 SQL 层面的模糊查询（所以另有 id_card_hash，见 FieldHashUtil）。
 */
@MappedTypes(String.class)
public class AesTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        // 写：明文 → 密文（每次加密都用新随机 IV，同一明文两次结果不同）
        ps.setString(i, CryptoHolder.cipher().encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decrypt(cs.getString(columnIndex));
    }

    private String decrypt(String stored) {
        if (Objects.isNull(stored)) {
            return null;
        }
        // 读：密文 → 明文；密文损坏/密钥不对时抛异常并让请求失败，
        // 绝不"降级返回密文"，否则前端会拿到一长串 Base64 而无人察觉
        return CryptoHolder.cipher().decrypt(stored);
    }
}
