package kot.bootstarter.kotmybatis.service.impl;

import kot.bootstarter.kotmybatis.common.CT;
import kot.bootstarter.kotmybatis.common.Page;
import kot.bootstarter.kotmybatis.config.KotTableInfo;
import kot.bootstarter.kotmybatis.enums.ConditionEnum;
import kot.bootstarter.kotmybatis.mapper.BaseMapper;
import kot.bootstarter.kotmybatis.properties.KotMybatisProperties;
import kot.bootstarter.kotmybatis.service.MapperService;
import kot.bootstarter.kotmybatis.utils.KotBeanUtils;
import kot.bootstarter.kotmybatis.utils.KotStringUtils;
import kot.bootstarter.kotmybatis.utils.MapUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.lang.reflect.Field;
import java.util.*;

/**
 * @author YangYu
 * 通用实现
 */
@Slf4j
public class MapperServiceImpl<T> implements MapperService<T> {

    private Page<T> page;
    private T entity;
    private T setEntity;
    private MethodEnum methodEnum;
    private boolean setNull;
    private List<T> batchList;

    private BaseMapper<T> baseMapper;

    private KotMybatisProperties properties;

    MapperServiceImpl(BaseMapper<T> baseMapper, KotMybatisProperties properties) {
        this.baseMapper = baseMapper;
        this.properties = properties;
    }

    /**
     * ======================
     * 公共方法
     * ======================
     */
    @Override
    public int insert(T entity) {
        this.methodEnum = MethodEnum.INSERT;
        this.entity = entity;
        return (int) execute();
    }

    @Override
    public int batchInsert(List<T> batchList) {
        this.methodEnum = MethodEnum.BATCH_INSERT;
        this.batchList = batchList;
        return (int) execute();
    }

    @Override
    public int save(T entity) {
        final String primaryKey = KotTableInfo.primaryKey(entity);
        final Object id = KotBeanUtils.fieldVal(primaryKey, entity);
        if (id == null) {
            return insert(entity);
        }
        return updateById(entity, true);
    }

    @Override
    public T findOne(T entity) {
        this.methodEnum = MethodEnum.FIND_ONE;
        this.entity = entity;
        return (T) execute();
    }

    @Override
    public List<T> list(T entity) {
        this.methodEnum = MethodEnum.LIST;
        this.entity = entity;
        return (List<T>) execute();
    }

    @Override
    public Integer count(T entity) {
        this.methodEnum = MethodEnum.COUNT;
        this.entity = entity;
        return (Integer) execute();
    }

    @Override
    public Page<T> selectPage(Page<T> page, T entity) {
        this.entity = entity;
        this.page = page;
        boolean containsOrderBy = false;
        // count 不拼接 order by
        Object orderBy = conditionMap.get(CT.ORDER_BY);
        if (conditionMap.containsKey(CT.ORDER_BY)) {
            containsOrderBy = true;
            conditionMap.remove(CT.ORDER_BY);
        }
        final int count = count(entity);
        if (count <= 0) {
            return page;
        }
        if (containsOrderBy) {
            conditionMap.put(CT.ORDER_BY, orderBy);
        }
        this.methodEnum = MethodEnum.SELECT_PAGE;
        final List<T> list = (List<T>) execute();
        page.setData(list);
        page.setTotal(count);
        return page;
    }

    @Override
    public int delete(T entity) {
        this.methodEnum = MethodEnum.DELETE;
        return (int) execute();
    }

    @Override
    public int logicDelete(T whereEntity) {
        if (!properties.isLogicDelete()) {
            throw new RuntimeException("未启用逻辑删除功能,如果想启用,添加配置:[kot.mybatis.logicDelete=true]");
        }
        try {
            T setEntity = (T) whereEntity.getClass().newInstance();
            KotBeanUtils.logicFiled(setEntity, true);
            return update(setEntity, whereEntity);
        } catch (Exception e) {
            throw new RuntimeException("", e);
        }

    }

    @Override
    public int updateById(T entity) {
        return updateById(entity, false);
    }

    @Override
    public int updateById(T entity, boolean setNull) {
        this.methodEnum = MethodEnum.UPDATE_BY_ID;
        this.entity = entity;
        this.setNull = setNull;
        return (int) execute();
    }

    @Override
    public int update(T setEntity, T whereEntity) {
        return update(setEntity, whereEntity, false);
    }

    @Override
    public int update(T setEntity, T whereEntity, boolean setNull) {
        this.methodEnum = MethodEnum.UPDATE;
        this.entity = whereEntity;
        this.setEntity = setEntity;
        this.setNull = setNull;
        return (int) execute();
    }

    private Object execute() {
        // 开启逻辑删除
        if (properties.isLogicDelete()) {
            // 包含逻辑删除注解
            final KotBeanUtils.KV kv = KotBeanUtils.logicFiled(this.entity, false);
            if (kv != null) {
                this.neq(KotStringUtils.camel2Underline(kv.getFiled()), kv.getVal());
            }
        }
        conditionSql = KotStringUtils.isBlank(conditionSql) ? conditionSql() : conditionSql;
        switch (this.methodEnum) {
            case INSERT:
                return baseMapper.insert(this.entity);
            case BATCH_INSERT:
                return baseMapper.batchInsert(this.batchList);
            case FIND_ONE:
                return baseMapper.findOne(columns, conditionSql, conditionMap, this.entity);
            case LIST:
                return baseMapper.list(columns, conditionSql, conditionMap, this.entity);
            case SELECT_PAGE:
                return baseMapper.selectPage(columns, conditionSql, this.page, conditionMap, this.entity);
            case COUNT:
                return baseMapper.count(conditionSql, conditionMap, this.entity);
            case UPDATE:
                return baseMapper.update(columns, conditionSql, conditionMap, this.entity, this.setEntity, this.setNull);
            case UPDATE_BY_ID:
                return baseMapper.updateById(this.entity, this.setNull);
            case DELETE:
                return baseMapper.delete(conditionSql, conditionMap, entity);
            default:
                throw new RuntimeException("not find method: " + this.methodEnum);
        }

    }

    private Map<String, Object> map(Map<String, Object> conditionMap) {
        return (conditionMap == null ? new HashMap<>() : conditionMap);
    }

    /**
     * ======================
     * 各种条件集合
     * ======================
     */
    private Set<String> columns = new HashSet<>();
    private Map<String, Object> eqMap = null;
    private Map<String, Object> neqMap = null;
    private Map<String, Object> inMap = null;
    private Map<String, Object> ninMap = null;
    private Map<String, Object> ltMap = null;
    private Map<String, Object> gtMap = null;
    private Map<String, Object> lteMap = null;
    private Map<String, Object> gteMap = null;
    private Map<String, Object> orMap = null;
    private Map<String, Object> likeMap = null;
    private Map<String, Object> nullMap = null;
    private Map<String, Object> conditionMap = new HashMap<>();
    private String conditionSql = "";
    private StringBuilder sqlBuilder = new StringBuilder();

    @Override
    public MapperService<T> fields(String field) {
        Assert.notNull(field, "field is null");
        columns.add(field);
        return this;
    }

    @Override
    public MapperService<T> fields(List<String> fields) {
        columns.addAll(fields);
        return this;
    }

    @Override
    public MapperService<T> orderBy(String orderBy) {
        conditionMap.put("orderBy", orderBy);
        return this;
    }

    @Override
    public MapperService<T> eq(String key, Object value) {
        (eqMap = map(eqMap)).put(key, value);
        return this;
    }

    @Override
    public MapperService<T> neq(String key, Object value) {
        (neqMap = map(neqMap)).put(key, value);
        return this;
    }

    @Override
    public MapperService<T> in(String key, String values) {
        Assert.notNull(values, "in values must be not null");
        in(key, values.split(CT.SPILT));
        return this;
    }

    @Override
    public MapperService<T> in(String key, Object[] values) {
        (inMap = map(inMap)).put(key, Arrays.asList(values));
        return this;
    }

    @Override
    public MapperService<T> in(String key, Collection<?> values) {
        (inMap = map(inMap)).put(key, values);
        return this;
    }

    @Override
    public MapperService<T> nin(String key, Object[] values) {
        (ninMap = map(ninMap)).put(key, Arrays.asList(values));
        return this;
    }

    @Override
    public MapperService<T> nin(String key, Collection<?> values) {
        (ninMap = map(ninMap)).put(key, values);
        return this;
    }

    @Override
    public MapperService<T> lt(String key, Object value) {
        (ltMap = map(ltMap)).put(key, value);
        return this;
    }

    @Override
    public MapperService<T> gt(String key, Object value) {
        (gtMap = map(gtMap)).put(key, value);
        return this;
    }


    @Override
    public MapperService<T> lte(String key, Object value) {
        (lteMap = map(lteMap)).put(key, value);
        return this;
    }

    @Override
    public MapperService<T> gte(String key, Object value) {
        (gteMap = map(gteMap)).put(key, value);
        return this;
    }

    @Override
    public MapperService<T> or(String key, Object value) {
        (orMap = map(orMap)).put(key, value);
        return this;
    }

    @Override
    public MapperService<T> like(String key, Object value) {
        (likeMap = map(likeMap)).put(key, value);
        return this;
    }

    @Override
    public MapperService<T> between(String key, Object left, Object right) {
        (gteMap = map(gteMap)).put(key, left);
        (lteMap = map(lteMap)).put(key, right);
        return this;
    }

    @Override
    public MapperService<T> isNull(String key) {
        (nullMap = map(nullMap)).put(key, null);
        return this;
    }

    /**
     * 实际查询条件
     */
    private String conditionSql() {

        // 实体条件
        this.entityCondition();

        // 链式条件
        if (eqMap != null) {
            eqMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.EQ, k, v));
            MapUtils.aliasKey(eqMap, newKey(ConditionEnum.EQ));
            conditionMap.putAll(eqMap);
        }
        if (neqMap != null) {
            neqMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.NEQ, k, v));
            MapUtils.aliasKey(neqMap, newKey(ConditionEnum.NEQ));
            conditionMap.putAll(neqMap);
        }
        if (inMap != null) {
            inMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.IN, k, v));
            MapUtils.aliasKey(inMap, newKey(ConditionEnum.IN));
            conditionMap.putAll(inMap);
        }
        if (ninMap != null) {
            ninMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.NIN, k, v));
            MapUtils.aliasKey(ninMap, newKey(ConditionEnum.NIN));
            conditionMap.putAll(ninMap);
        }
        if (ltMap != null) {
            ltMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.LT, k, v));
            MapUtils.aliasKey(ltMap, newKey(ConditionEnum.LT));
            conditionMap.putAll(ltMap);
        }
        if (gtMap != null) {
            gtMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.GT, k, v));
            MapUtils.aliasKey(gtMap, newKey(ConditionEnum.GT));
            conditionMap.putAll(gtMap);
        }
        if (lteMap != null) {
            lteMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.LTE, k, v));
            MapUtils.aliasKey(lteMap, newKey(ConditionEnum.LTE));
            conditionMap.putAll(lteMap);
        }
        if (gteMap != null) {
            gteMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.GTE, k, v));
            MapUtils.aliasKey(gteMap, newKey(ConditionEnum.GTE));
            conditionMap.putAll(gteMap);
        }
        if (likeMap != null) {
            likeMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.LIKE, k, v));
            MapUtils.aliasKey(likeMap, newKey(ConditionEnum.LIKE));
            conditionMap.putAll(likeMap);
        }
        if (nullMap != null) {
            nullMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.NULL, k, v));
            MapUtils.aliasKey(nullMap, newKey(ConditionEnum.NULL));
            conditionMap.putAll(nullMap);
        }
        // 放在最后，否则拼接sql会有问题
        if (orMap != null) {
            orMap.forEach((k, v) -> sqlBuilder(sqlBuilder, ConditionEnum.OR, k, v));
            MapUtils.aliasKey(orMap, newKey(ConditionEnum.OR));
            conditionMap.putAll(orMap);
        }
        conditionSql = sqlBuilder.toString();
        return conditionSql;
    }

    private void entityCondition() {
        if (this.entity != null) {
            final KotTableInfo.TableInfo tableInfo = KotTableInfo.get(entity);
            tableInfo.getFields().forEach(fieldWrapper -> {
                final Field field = fieldWrapper.getField();
                field.setAccessible(true);
                try {
                    final Object val = field.get(entity);
                    if (val != null) {
                        (eqMap = map(eqMap)).put(tableInfo.getFieldColumnMap().get(field.getName()), val);
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * 构建SQL语句
     */
    private void sqlBuilder(StringBuilder sqlBuilder, ConditionEnum conditionEnum, String k, Object v) {
        if (conditionEnum == ConditionEnum.OR) {
            sqlBuilder.append(CT.OR);
        } else {
            sqlBuilder.append(CT.AND);
        }
        sqlBuilder.append(k).append(conditionEnum.oper);
        k = conditionEnum.name() + "_" + k;
        // in 查询拼接SQL语法
        if (conditionEnum == ConditionEnum.IN || conditionEnum == ConditionEnum.NIN) {
            Collection collection = (Collection) v;
            StringBuilder inBuilder = new StringBuilder(CT.OPEN);
            for (int i = 0; i < collection.size(); i++) {
                inBuilder.append("#{").append(CT.ALIAS_CONDITION).append(k).append("[").append(i).append("]").append("}").append(CT.SPILT);
            }
            inBuilder.deleteCharAt(inBuilder.lastIndexOf(CT.SPILT));
            inBuilder.append(CT.CLOSE);
            sqlBuilder.append(inBuilder.toString());
        } else if (conditionEnum == ConditionEnum.LIKE) {
            // like 查询拼接SQL语法
            sqlBuilder.append("CONCAT").append("('%',").append("#{").append(CT.ALIAS_CONDITION).append(CT.DOT).append(k).append("},").append("'%')");
        } else if (conditionEnum == ConditionEnum.NULL) {
            // nothing
        } else {
            // 默认查询拼接SQL语法
            sqlBuilder.append("#{").append(CT.ALIAS_CONDITION).append(CT.DOT).append(k).append("}");
        }
    }

    /**
     * key 别名
     */
    private String newKey(ConditionEnum conditionEnum) {
        return conditionEnum.name() + "_%s";
    }

    enum MethodEnum {
        /**
         * 调用函数
         */
        INSERT, BATCH_INSERT, FIND_ONE, LIST, COUNT, SELECT_PAGE, UPDATE, UPDATE_BY_ID, DELETE
    }

}
