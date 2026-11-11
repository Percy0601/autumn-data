<?xml version="1.0" encoding="UTF-8"?>
<!-- generated-by: autumn-data-meta-jdbc -->
<!-- fingerprint: ${fingerprint} -->
<mapper namespace="${namespace}">
    <select id="findList"><![CDATA[
SELECT ${r"${columnsSql}"} FROM ${r"${table}"}${r"${whereSql}"}${r"${orderSql}"}
]]></select>
    <select id="count"><![CDATA[
SELECT COUNT(*) FROM ${r"${table}"}${r"${whereSql}"}
]]></select>
    <insert id="insert"><![CDATA[
INSERT INTO ${r"${table}"} (${r"${insertColumnsSql}"}) VALUES (${r"${insertValuesSql}"})
]]></insert>
    <update id="update"><![CDATA[
UPDATE ${r"${table}"} SET ${r"${setSql}"} WHERE ${r"${keyWhereSql}"}
]]></update>
</mapper>
